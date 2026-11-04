package com.zzkingcc.domainplane.server.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.runtime.storage.BlobStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.storage.BlobStoreRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 切片原文档案：知识库的权威源，索引是它重建出来的派生数据。
 *
 * <p>为什么需要它：换向量模型 / 换检索后端都要求"能取回原文"。纯向量库存不下原文，
 * 而 ES 存得下——于是"保原文重灌"这件事在过去是 ES 的隐性福利，换后端即失效。
 * 把原文收进 {@link BlobStore} 之后，重灌从"数据迁移"降级为"重新生成索引"。</p>
 *
 * <p><b>落点由知识体决定</b>（v2 P4）：同一个索引的档案放在哪，取决于该体的
 * {@code raw} 字段——{@code local}（内置盘）或某条 s3 连接。所以读写都先经
 * {@link #storeOf(String)} 把索引翻成 store，本类不认识磁盘或 S3。</p>
 *
 * <p>两种产物，前缀分开：</p>
 * <ul>
 *   <li>{@code kb/chunks/{index}/{docId}.json} —— 切片 JSON（<b>剥掉向量</b>），重灌的输入；</li>
 *   <li>{@code kb/files/{index}/{docId}} —— 原始文件字节，换解析器/改切片策略时从它重切。
 *       键不带扩展名：扩展名要靠文件名推断，而为了推断去 LIST 一次桶不值得；
 *       下载时文件名由文档表里的 {@code fileName} 提供。</li>
 * </ul>
 *
 * <p><b>列举要跨全部落点</b>：体换过原文落点后，旧桶里仍有历史档案。对账只看当前落点
 * 会把它们判成"不存在"，进而把索引里的文档当"无档案"去补写——补写用的是索引里的切片，
 * 内容没错，但会平白多出一份副本。所以 {@link #inventory()} / {@link #docIdsOf(String)}
 * 合并所有可用落点的清单。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class ChunkArchive {

    /** 切片档案键前缀（对账与整域清除都按它列举） */
    public static final String PREFIX = "kb/chunks/";

    /** 原始文件字节键前缀 */
    public static final String FILES_PREFIX = "kb/files/";

    private static final String SUFFIX = ".json";

    /** 生产装配：按知识体的原文落点路由 */
    private final BlobStoreRegistry registry;
    private final KnowledgeBodyResolver bodies;
    /** 单 store 模式（测试 / 无体上下文的工具）：与 registry 二选一 */
    private final BlobStore single;
    private final ObjectMapper objectMapper;

    /**
     * 生产构造器：按索引所属知识体的 {@code raw} 落点取 store。
     *
     * @param registry 落点 → blob 存储
     * @param bodies   索引 → 知识体（再取体的原文落点）
     */
    @Autowired
    public ChunkArchive(BlobStoreRegistry registry, KnowledgeBodyResolver bodies,
                        ObjectMapper objectMapper) {
        this.registry = registry;
        this.bodies = bodies;
        this.single = null;
        this.objectMapper = objectMapper;
    }

    /**
     * 兼容构造器：所有档案都落同一个 store（测试与一次性工具用）。
     *
     * <p>保留它是因为大量单测直接 {@code new ChunkArchive(store, mapper)} 构造——
     * 逼它们去拼 registry 只会让测试更脆，而"单 store"在语义上完全成立。</p>
     */
    public ChunkArchive(BlobStore blobStore, ObjectMapper objectMapper) {
        this.registry = null;
        this.bodies = null;
        this.single = blobStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 索引 → 该体当前原文落点的 store；落点不可用时抛（调用方按存储故障处置）。
     *
     * <p>公开是为了迁移：换原文落点要<b>显式</b>指定"从哪个 store 读、往哪个 store 写"，
     * 不能靠这个按当前配置猜。</p>
     */
    public BlobStore storeOf(String index) {
        if (registry == null) {
            return single;
        }
        String bodyId = bodies.bodyIdOfIndex(index);
        InfraSettings.KnowledgeBody body = bodyId == null ? null : bodies.body(bodyId);
        String location = body == null ? InfraSettings.RAW_LOCAL : body.rawLocation();
        return registry.of(location);
    }

    /** 全部可用落点的 store（对账 / 列举用）；单 store 模式只有一个 */
    public Map<String, BlobStore> stores() {
        if (registry == null) {
            return Map.of(InfraSettings.RAW_LOCAL, single);
        }
        return registry.all();
    }

    /**
     * 指定落点的 store（迁移用：换原文落点时要<b>显式</b>指出源与目标）。
     *
     * @throws IllegalStateException 该落点不可用
     */
    public BlobStore storeAt(String location) {
        return registry == null ? single : registry.of(location);
    }

    /**
     * 写一个文档的全部切片（覆盖语义：同 docId 重传先写后删，天然幂等）。
     *
     * **向量在此剥离**：archive 是原文权威源，向量是派生数据（换模型即废）。
     * 带上向量会让每个 JSON 大出维度倍（768 维 ≈ 6KB/条），而重灌反正要重新
     * embed——存它就是存一份注定作废的复印件。
     *
     * 失败只告警不抛：上传主流程已经成功（索引里可检索），此时抛异常会让
     * 一次成功的上传对外报错；而缺档有对账兜底，代价可承受。
     */
    public void write(String index, String docId, List<KbChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String key = keyOf(index, docId);
        try {
            List<KbChunk> textOnly = new ArrayList<>(chunks.size());
            for (KbChunk chunk : chunks) {
                textOnly.add(chunk.vector() == null
                        ? chunk
                        : new KbChunk(chunk.id(), chunk.text(), chunk.metadata(), null));
            }
            byte[] json = objectMapper.writeValueAsBytes(textOnly);
            storeOf(index).put(key, new ByteArrayInputStream(json), json.length);
            MetricsRegistry.kbArchiveBytes(json.length);
            log.info("[原文档案] 已归档 docId={}（索引 {}）：{} 条切片，{} 字节",
                    docId, index, chunks.size(), json.length);
        } catch (Exception e) {
            // 告警 + 指标：缺档由对账补写，但这里必须留痕——
            // 静默失败会让"换模型重灌"在很久以后才丢文档，且毫无线索
            log.warn("[原文档案] 归档失败（docId={}，索引 {}），对账将补写: {}",
                    docId, index, e.getMessage());
            MetricsRegistry.kbArchiveMissing();
        }
    }

    /**
     * 读一个文档的全部切片。
     *
     * @return 切片列表；档案不存在时为空。读取或解析失败抛异常——调用方是
     *         重灌与对账，"读不全就往下走"会静默丢文档
     */
    public Optional<List<KbChunk>> read(String index, String docId) {
        String key = keyOf(index, docId);
        Optional<InputStream> in;
        try {
            in = storeOf(index).open(key);
        } catch (Exception e) {
            throw new IllegalStateException("读取原文档案[" + key + "]失败：" + e.getMessage(), e);
        }
        if (in.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream stream = in.get()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            stream.transferTo(out);
            List<KbChunk> chunks = objectMapper.readValue(
                    out.toByteArray(), new TypeReference<List<KbChunk>>() {});
            return Optional.of(chunks);
        } catch (Exception e) {
            throw new IllegalStateException("解析原文档案[" + key + "]失败：" + e.getMessage(), e);
        }
    }

    // ==================== 原始文件字节（P4） ====================

    /**
     * 归档原始文件字节（默认开）。
     *
     * <p>价值不只是"能下载原文"：换解析器或改切片策略时能从原始文件重切，
     * 而切片 JSON 的粒度已经是切完之后的，重切只能得到"同样的切法"。</p>
     *
     * <p>与切片档案同一条失败姿态：只告警不抛——上传已经成功，缺原始文件不影响检索。</p>
     */
    public void writeFile(String index, String docId, byte[] content) {
        if (content == null || content.length == 0) {
            return;
        }
        String key = fileKeyOf(index, docId);
        try {
            storeOf(index).put(key, new ByteArrayInputStream(content), content.length);
            log.info("[原文档案] 已归档原始文件 docId={}（索引 {}）：{} 字节", docId, index, content.length);
        } catch (Exception e) {
            log.warn("[原文档案] 原始文件归档失败（docId={}，索引 {}）: {}", docId, index, e.getMessage());
            MetricsRegistry.kbArchiveMissing();
        }
    }

    /** 读原始文件字节；不存在时为空。读取失败抛（调用方是下载接口，要如实回报） */
    public Optional<InputStream> openFile(String index, String docId) {
        try {
            return storeOf(index).open(fileKeyOf(index, docId));
        } catch (Exception e) {
            throw new IllegalStateException("读取原始文件[" + index + "/" + docId + "]失败："
                    + e.getMessage(), e);
        }
    }

    /** 该文档有没有归档原始文件（文档表上的三态打勾用） */
    public boolean hasFile(String index, String docId) {
        try {
            return storeOf(index).exists(fileKeyOf(index, docId));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 该索引下已归档原始文件的 docId 集合（全部落点合并） */
    public Set<String> fileDocIdsOf(String index) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : fileInventory().entrySet()) {
            if (index.equals(entry.getKey())) {
                out.addAll(entry.getValue());
            }
        }
        return out;
    }

    // ==================== 删除 ====================

    /** 删一个文档的档案与原始文件（幂等；删不动只告警——孤儿留给对账回收） */
    public void delete(String index, String docId) {
        try {
            storeOf(index).delete(keyOf(index, docId));
            log.info("[原文档案] 已删除 docId={}（索引 {}）", docId, index);
        } catch (Exception e) {
            log.warn("[原文档案] 删除失败（docId={}，索引 {}），孤儿留给对账: {}",
                    docId, index, e.getMessage());
        }
        try {
            storeOf(index).delete(fileKeyOf(index, docId));
        } catch (Exception e) {
            log.warn("[原文档案] 删除原始文件失败（docId={}，索引 {}），孤儿留给对账: {}",
                    docId, index, e.getMessage());
        }
    }

    /**
     * 删一个 collection 的全部档案与原始文件（删体 / purgeAll 用）。
     *
     * <p><b>遍历全部落点</b>：体换过原文落点后，旧桶里还有同一索引的历史档案；
     * 只清当前落点会留下一份"永远没人看得见、也不会被回收"的副本。
     * 原文键带索引前缀，不可能属于别的体，所以这里可以放心全删。</p>
     *
     * <p>单个键删不动不中断其余：一个坏对象不该挡住整域清除；漏下的由对账当孤儿回收。</p>
     */
    public int deleteCollection(String index) {
        int deleted = 0;
        for (BlobStore store : stores().values()) {
            deleted += deletePrefix(store, PREFIX + index + "/");
            deleted += deletePrefix(store, FILES_PREFIX + index + "/");
        }
        if (deleted > 0) {
            log.info("[原文档案] 已清除索引 {} 的 {} 个档案 / 原始文件", index, deleted);
        }
        return deleted;
    }

    private int deletePrefix(BlobStore store, String prefix) {
        List<String> keys;
        try {
            keys = store.listKeys(prefix);
        } catch (Exception e) {
            log.warn("[原文档案] 列举 {} 下的对象失败（跳过，孤儿留给对账）: {}", prefix, e.getMessage());
            return 0;
        }
        int deleted = 0;
        for (String key : keys) {
            try {
                store.delete(key);
                deleted++;
            } catch (Exception e) {
                log.warn("[原文档案] 删除档案 {} 失败（下轮对账回收）: {}", key, e.getMessage());
            }
        }
        return deleted;
    }

    // ==================== 列举 / 对账 ====================

    /**
     * 全部切片档案的清单：索引名 → 已归档的 docId 集合（对账用）。
     *
     * <p>解析规则 = 键布局的逆运算：{@code kb/chunks/{index}/{docId}.json}。
     * 不符合布局的键跳过不猜——猜错会把活档案当孤儿删掉。</p>
     *
     * <p>合并全部可用落点：换过落点的体，旧桶里的档案也是活的。</p>
     */
    public Map<String, Set<String>> inventory() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (BlobStore store : stores().values()) {
            for (String key : listQuietly(store, PREFIX)) {
                if (!key.startsWith(PREFIX) || !key.endsWith(SUFFIX)) {
                    continue;
                }
                String body = key.substring(PREFIX.length(), key.length() - SUFFIX.length());
                int slash = body.indexOf('/');
                if (slash <= 0 || slash == body.length() - 1) {
                    continue;
                }
                out.computeIfAbsent(body.substring(0, slash), k -> new LinkedHashSet<>())
                        .add(body.substring(slash + 1));
            }
        }
        return out;
    }

    /** 全部原始文件的清单：索引名 → 已归档的 docId 集合 */
    public Map<String, Set<String>> fileInventory() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (BlobStore store : stores().values()) {
            for (String key : listQuietly(store, FILES_PREFIX)) {
                if (!key.startsWith(FILES_PREFIX)) {
                    continue;
                }
                String body = key.substring(FILES_PREFIX.length());
                int slash = body.indexOf('/');
                if (slash <= 0 || slash == body.length() - 1) {
                    continue;
                }
                out.computeIfAbsent(body.substring(0, slash), k -> new LinkedHashSet<>())
                        .add(body.substring(slash + 1));
            }
        }
        return out;
    }

    private static List<String> listQuietly(BlobStore store, String prefix) {
        try {
            return store.listKeys(prefix);
        } catch (Exception e) {
            // 列举失败按"这台没有"处理：对账宁可少删，也不能把一个坏后端
            // 演成"全部档案都是孤儿"
            log.warn("[原文档案] 列举 {} 失败（按空处理）: {}", prefix, e.getMessage());
            return List.of();
        }
    }

    /**
     * 档案键的年龄护栏（对账的竞态防护，与 MediaBlobSweeper 同口径）。
     *
     * <p>写入序是"索引成功 → 写 archive"，正常不会撞窗口；护栏防的是将来的
     * 写入序变化与"补写刚写完、登记表还没刷新"的瞬时态。取不到时间不跳过：
     * 年龄是护栏不是判据。</p>
     */
    public boolean isYoung(String key, Duration minAge) {
        if (minAge == null || minAge.isZero()) {
            return false;
        }
        for (BlobStore store : stores().values()) {
            Optional<Instant> time = store.lastModified(key);
            if (time.isPresent()) {
                return Duration.between(time.get(), Instant.now()).compareTo(minAge) < 0;
            }
        }
        return false;
    }

    /** 该索引下已归档的 docId 集合（缺档判定的"archive 侧"输入） */
    public Set<String> docIdsOf(String index) {
        Set<String> ids = inventory().get(index);
        return ids == null ? Set.of() : ids;
    }

    static String keyOf(String index, String docId) {
        return PREFIX + index + "/" + docId + SUFFIX;
    }

    static String fileKeyOf(String index, String docId) {
        return FILES_PREFIX + index + "/" + docId;
    }

    /** 仅供测试与对账复用：列出全部档案键（全部落点合并） */
    List<String> allKeys() {
        List<String> keys = new ArrayList<>();
        for (BlobStore store : stores().values()) {
            keys.addAll(listQuietly(store, PREFIX));
        }
        return keys;
    }
}
