package com.zzkingcc.domainplane.infrastructure.redis.checkpoint;

import com.zzkingcc.domainplane.common.exception.CheckpointCorruptedException;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.serializer.std.CheckpointListSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.*;
import java.time.Duration;
import java.util.*;

/**
 * 基于 Redis 的 graph 检查点持久化
 * @author zzkingcc
 */
public class RedisCheckpointSaver implements BaseCheckpointSaver {

    private static final Logger log = LoggerFactory.getLogger(RedisCheckpointSaver.class);

    /** Key 前缀 */
    private static final String KEY_PREFIX = "domainplane:graph:checkpoint:";

    /** SCAN 每批拉取的键数（大批量扫描时的单次往返规模） */
    private static final int SCAN_BATCH = 500;

    /** 检查点默认保留时长:中断后未 resume 的会话不会主动清理,靠 TTL 兜底 */
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * 单会话保留的检查点上限（只留最近的）。
     *
     * 每次 put 都要把整份列表读出来、再整份写回去，列表无上限时单键体积与写入成本
     * 都随轮次线性增长，一次长会话足以把这条键撑成"大 key"，且写放大是 O(n²)。
     * resume 只用到最近的那个断点，历史断点仅用于 {@code getStateHistory} 这类排障场景，
     * 留最近 200 个足够。
     */
    private static final int MAX_CHECKPOINTS = 200;

    /**
     * 分片写锁：同会话的"读改写"必须串行，不同会话之间不要互相排队。
     * 固定长度数组，键再多也不会把锁对象越攒越多。
     */
    private static final Object[] WRITE_LOCKS = new Object[64];

    static {
        for (int i = 0; i < WRITE_LOCKS.length; i++) {
            WRITE_LOCKS[i] = new Object();
        }
    }

    private static Object lockFor(String key) {
        return WRITE_LOCKS[Math.floorMod(key.hashCode(), WRITE_LOCKS.length)];
    }

    private final StringRedisTemplate redisTemplate;
    private final CheckpointListSerializer checkpointListSerializer;
    private final Duration ttl;

    public RedisCheckpointSaver(StringRedisTemplate redisTemplate, StateSerializer<?> stateSerializer) {
        this(redisTemplate, stateSerializer, DEFAULT_TTL);
    }

    public RedisCheckpointSaver(StringRedisTemplate redisTemplate, StateSerializer<?> stateSerializer,
                                Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.checkpointListSerializer = new CheckpointListSerializer(stateSerializer);
        this.ttl = ttl;
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        String key = buildKey(config);
        try {
            LinkedList<Checkpoint> all = readAll(key);
            // 反转:最新在前,符合 BaseCheckpointSaver 的约定(getStateHistory 取第一个为最新)
            Collections.reverse(all);
            return all;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[检查点] list 会话[{}]失败: {}", threadId(config), e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHECKPOINT_ERROR, "读取检查点列表失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String key = buildKey(config);
        try {
            LinkedList<Checkpoint> all = readAll(key);
            if (all.isEmpty()) {
                log.debug("[检查点] get 会话[{}]: 无检查点", threadId(config));
                return Optional.empty();
            }
            // 优先按 checkpointId 精确匹配（config.checkPointId() 是框架方法名，不改）
            String checkpointId = config.checkPointId().orElse(null);
            if (checkpointId != null) {
                for (int i = all.size() - 1; i >= 0; i--) {
                    if (checkpointId.equals(all.get(i).getId())) {
                        return Optional.of(all.get(i));
                    }
                }
            }
            // 否则取最新
            Checkpoint latest = all.get(all.size() - 1);
            log.debug("[检查点] get 会话[{}]: 命中 checkpoint={}", threadId(config), latest.getId());
            return Optional.of(latest);
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[检查点] get 会话[{}]失败: {}", threadId(config), e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHECKPOINT_ERROR, "读取检查点失败: " + e.getMessage(), e);
        }
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        String key = buildKey(config);
        try {
            LinkedList<Checkpoint> all;
            // 同会话的 put 必须串行：readAll → add → writeAll 是读改写，
            // 并发 put 各自读到同一份旧列表，后写的那次把先写的检查点整条丢掉，
            // 表现为"走完一步之后 resume 找不到断点"。分片锁而不是单锁：
            // 不同会话之间不该互相排队。
            synchronized (lockFor(key)) {
                all = readAll(key);
                all.add(checkpoint);
                trim(all);
                writeAll(key, all);
            }
            log.info("[检查点] put 会话[{}]: 写入 checkpoint={}, node={}, next={}, 累计={}",
                    threadId(config), checkpoint.getId(), checkpoint.getNodeId(),
                    checkpoint.getNextNodeId(), all.size());
            // 返回带新 checkPointId 的 config,resume 时据此定位
            return RunnableConfig.builder(config)
                    .checkPointId(checkpoint.getId())
                    .nextNode(checkpoint.getNextNodeId())
                    .build();
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[检查点] put 会话[{}]失败: {}", threadId(config), e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHECKPOINT_ERROR, "写入检查点失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Tag release(RunnableConfig config) throws Exception {
        String key = buildKey(config);
        // release 的语义是"把这个 threadId 的断点整个删掉"，它只需要读出一份 tag 作为返回值，
        // 并不依赖读到的内容是否可用，因此这里不能因为数据损坏就放弃删除：
        // 否则一个损坏的键会永远卡在那里，既读不出、也清不掉（而 deleteByDomain 也只能删整个域）。
        LinkedList<Checkpoint> all;
        try {
            all = readAll(key);
        } catch (ChatMemoryException e) {
            log.warn("[检查点] release 会话[{}]：数据损坏，仍按语义强制删除该键", threadId(config));
            all = new LinkedList<>();
        }
        try {
            Tag tag = new Tag(threadId(config), all);
            redisTemplate.delete(key);
            log.info("[检查点] release 会话[{}]: 释放 {} 个检查点", threadId(config), all.size());
            return tag;
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            log.error("[检查点] release 会话[{}]失败: {}", threadId(config), e.getMessage(), e);
            throw new ChatMemoryException(ErrorCode.CHECKPOINT_ERROR, "释放检查点失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除某个域下的全部检查点（删域级联清理用）。
     *
     * 断点里存着"即将执行但还没执行"的工具调用。域被删后若不清，24 小时内同路径域重建
     * 就能 {@code resume(approved=true)} 把当初那个破坏性动作补执行掉，域的删除没有撤销授权。
     * 因此这里失败直接抛：静默跳过等于"删除成功"却把授权留在了原地。
     *
     * @return 删除的键数
     * @throws ChatMemoryException 清理失败或域为空
     */
    public int deleteByDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim();
        // 守卫必须先于 pattern 拼接：空域会让 pattern 匹配到全部域的断点
        if (normalized.isEmpty()) {
            throw new ChatMemoryException(ErrorCode.INVALID_PARAMETER,
                    "deleteByDomain 收到空域，已拒绝执行（否则会清空全部域的检查点）");
        }
        String pattern = KEY_PREFIX + normalized + SessionKeys.SEPARATOR + "*";
        try {
            List<String> keys = new ArrayList<>();
            try (Cursor<String> cursor = redisTemplate.scan(
                    ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH).build())) {
                cursor.forEachRemaining(keys::add);
            }
            if (keys.isEmpty()) {
                return 0;
            }
            Long deleted = redisTemplate.delete(keys);
            log.info("[检查点] 已按域清理 {} 个键（模式 {}）", deleted, pattern);
            return deleted == null ? 0 : deleted.intValue();
        } catch (ChatMemoryException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatMemoryException(ErrorCode.CHECKPOINT_ERROR,
                    "按域清理检查点失败（模式 " + pattern + "）：" + e.getMessage()
                            + "。相关断点可能残留 —— 其中可能存有待执行的工具调用，"
                            + "需修复后重新执行删除", e);
        }
    }

    private String buildKey(RunnableConfig config) {
        return KEY_PREFIX + threadId(config);
    }

    /** 从键里还原 threadId，仅用于日志（键形如 {@code domainplane:graph:checkpoint:{域}|{sessionId}}） */
    private static String threadIdFromKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX) ? key.substring(KEY_PREFIX.length()) : String.valueOf(key);
    }

    /**
     * 读取 threadId 下全部 checkpoint,保持写入顺序(旧→新)
     */
    private LinkedList<Checkpoint> readAll(String key) {
        String base64 = redisTemplate.opsForValue().get(key);
        if (base64 == null || base64.isBlank()) {
            return new LinkedList<>();
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw corrupt(key, e);
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return checkpointListSerializer.read(ois);
        } catch (Exception e) {
            throw corrupt(key, e);
        }
    }

    /**
     * 断点读不出来时必须报错，不能当空列表。
     *
     * 当成空的代价是灾难性的且不可逆：{@link #put} 走的是"读全部 → 加一条 → 写回整份"，
     * 它读到一个空列表就会把原有整段会话历史覆盖成一条，且全程无任何异常。
     * 紧接着 {@code get/list} 会报"无检查点"，用户收到的是"会话不存在，请新建会话"：
     * 一个与真实原因（数据损坏）毫无关系的错误信息。
     *
     * fail-closed 与记忆侧一致：{@code RedisChatMemoryStore.getMessages} 读失败同样抛异常。
     * 数据存疑时宁可不提供，也不能提供一份被清空过的"正确答案"。
     */
    private CheckpointCorruptedException corrupt(String key, Exception cause) {
        log.error("[检查点] 反序列化失败（键 {}），按损坏处理并中止本轮读写：{}", key, cause.getMessage(), cause);
        // 抛子类而不是父类：调用方要能把它与"Redis 连不上"分开，
        // 前者重试无效、必须清键自愈，后者重试可能就好。
        return new CheckpointCorruptedException(
                "检查点数据损坏，已中止本轮读写以免覆盖历史数据: " + cause.getMessage(), cause);
    }

    /**
     * 写入 threadId 下全部 checkpoint
     */
    private void writeAll(String key, LinkedList<Checkpoint> all) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            checkpointListSerializer.write(all, oos);
        }
        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        // 写入与续期必须是同一条命令：先 SET 再 EXPIRE 的话，崩在两步之间就留下永不过期的键；
        // 反过来若上一次的 EXPIRE 先到，刚写的断点会被立即删除。带过期时间的 SET 是原子的。
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            redisTemplate.opsForValue().set(key, base64, ttl);
            return;
        }
        redisTemplate.opsForValue().set(key, base64);
        if (ttl != null && ttl.isNegative()) {
            // 负数 TTL 落进 Redis 的 EXPIRE 会被服务端当作「立即删除」：
            // 断点刚写进去就没了，挂起的审批从此永远无法 resume，而 get 会报
            // "会话不存在"，把问题指向完全错误的方向。负数一律按永久处理并告警。
            log.warn("[检查点] 会话[{}] 的 checkpoint-ttl 配置为负数（{}），已按永久处理。"
                            + "负数 TTL 会让 Redis 立即删除该键（挂起的审批将无法恢复）。请改为正数",
                    threadIdFromKey(key), ttl);
        }
    }

    /** 只保留最近的 {@link #MAX_CHECKPOINTS} 个断点（列表按旧→新排列，砍头留尾） */
    private static void trim(LinkedList<Checkpoint> all) {
        while (all.size() > MAX_CHECKPOINTS) {
            all.removeFirst();
        }
    }
}
