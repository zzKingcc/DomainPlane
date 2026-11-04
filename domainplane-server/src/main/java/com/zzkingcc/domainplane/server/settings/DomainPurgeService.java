package com.zzkingcc.domainplane.server.settings;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import com.zzkingcc.domainplane.infrastructure.redis.checkpoint.RedisCheckpointSaver;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisConversationArchive;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisConversationSummary;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisHistorySearch;
import com.zzkingcc.domainplane.infrastructure.redis.memory.RedisChatMemoryStore;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.mcp.McpConnectionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 域硬删：既有级联清理 + 停在飞会话，宽限期到头（或运维强制）时由本服务执行物理删除。
 *
 * 从 AdminDomainController 抽出，是因为调用方从一个变成两个：管控台的立即删/force 删，
 * 与宽限期到点的定时删（{@code DomainPurgeScheduler}）。两条路径必须走同一套顺序——
 * 删域的失败方式是不报错的半完成状态，各写一份必然漂移。
 *
 * 顺序是硬要求，且调整过两次：Redis 记忆与检查点 → <b>知识体只解绑、不删任何数据</b> →
 * 域属性 → 删域。原因：每一步失败都是"抛异常中止、域未删除"，所以中止点必须落在还没有
 * 任何东西被删的位置。
 *
 * <p>v2 起删域<b>不再触碰知识库存储</b>：知识体是数据实体（原文 + 索引），域只是消费者。
 * 删索引既会销毁可能被别的域共用、或准备再绑给新域的数据，又让删域依赖后端可达性——
 * 后端一时抽风就删不掉域，这不合理。要真的销毁知识，走知识库页知识体卡片上的
 * 「删除知识体」（唯一会毁数据的入口，需输名确认）。</p>
 * 原先 Redis 清理排在索引之后，一旦它失败，索引已经删掉、域却还在：留下一个比直接失败
 * 更难收拾的半完成状态。
 *
 * @author zzkingcc
 */
@Slf4j
@Service
public class DomainPurgeService {

    /**
     * 删域前等待在飞会话停止的上限。取 5 秒：正常一轮在下一个检查点就会看到停止标志，
     * 只有卡在长耗时工具调用里才会耗到头。
     *
     * 仅 {@code awaitStop=true}（宽限期置 0 的旧语义路径）使用；软删宽限期到点的硬删
     * 走强杀路径，不再等待——宽限期已给足，死结由此解开。
     */
    private static final Duration RUNNING_STOP_TIMEOUT = Duration.ofSeconds(5);

    private final DomainRegistry domainRegistry;
    private final DomainStore domainStore;
    private final DomainSettingsStore domainSettingsStore;
    private final ModelProfileRegistry modelProfileRegistry;
    private final McpConnectionStore mcpConnectionStore;
    private final ToolRegistry toolRegistry;
    private final RedisChatMemoryStore chatMemoryStore;
    private final RedisCheckpointSaver checkpointSaver;
    private final RedisConversationArchive conversationArchive;
    private final RedisHistorySearch historySearch;
    private final RedisConversationSummary conversationSummary;
    private final CancellationRegistry cancellationRegistry;

    /** 正在硬删的域（防调度器与并发管理请求对同一域双跑；也是撤销删除的竞态判据） */
    private final Set<String> purging = ConcurrentHashMap.newKeySet();

    public DomainPurgeService(DomainRegistry domainRegistry,
                              DomainStore domainStore,
                              DomainSettingsStore domainSettingsStore,
                              ModelProfileRegistry modelProfileRegistry,
                              McpConnectionStore mcpConnectionStore,
                              ToolRegistry toolRegistry,
                              RedisChatMemoryStore chatMemoryStore,
                              RedisCheckpointSaver checkpointSaver,
                              RedisConversationArchive conversationArchive,
                              RedisHistorySearch historySearch,
                              RedisConversationSummary conversationSummary,
                              CancellationRegistry cancellationRegistry) {
        this.domainRegistry = domainRegistry;
        this.domainStore = domainStore;
        this.domainSettingsStore = domainSettingsStore;
        this.modelProfileRegistry = modelProfileRegistry;
        this.mcpConnectionStore = mcpConnectionStore;
        this.toolRegistry = toolRegistry;
        this.chatMemoryStore = chatMemoryStore;
        this.checkpointSaver = checkpointSaver;
        this.conversationArchive = conversationArchive;
        this.historySearch = historySearch;
        this.conversationSummary = conversationSummary;
        this.cancellationRegistry = cancellationRegistry;
    }

    /**
     * 硬删结果（供调用方组装管控台返回体 / 记日志）。
     *
     * @param retainedBodies 这些域绑定过、<b>但一个字节都没删</b>的知识体 id
     *                       （删域只解绑：原文与索引都留着，可以再绑给新域）
     */
    public record PurgeResult(List<String> removed, List<String> retainedBodies,
                              int purgedMemories, int purgedCheckpoints, int purgedArchives,
                              int purgedIndexes, int purgedSummaries) {
    }

    /** 该域是否正在被硬删（撤销删除的竞态判据：清理进行中不可逆） */
    public boolean isPurging(String domainId) {
        return purging.contains(domainId);
    }

    /**
     * 硬删一个域及其全部子孙。
     *
     * @param awaitStop 在飞会话处置：
     *                  {@code true} = 旧语义，请求停止并等待标记释放，停不掉抛
     *                  {@link ErrorCode#ORCHESTRATION_FAILED} 拒绝删除（宽限期置 0 的兼容路径）；
     *                  {@code false} = 强杀合法化，尽力请求停止后不再等待，容忍卡死线程
     *                  最终返回时向已删域写孤儿键（域已出注册表，键无消费方，检查点 TTL 兜底回收）。
     * @throws BaseException 任一步清理失败：中止且域未删除（见类注释的顺序约束）
     */
    public PurgeResult purge(String domainId, boolean awaitStop) {
        String normalized = com.zzkingcc.domainplane.api.agent.Domains.normalize(domainId);
        purging.add(normalized);
        try {
            return doPurge(normalized, awaitStop);
        } finally {
            purging.remove(normalized);
        }
    }

    private PurgeResult doPurge(String normalized, boolean awaitStop) {
        // 1) 本次会牵连的域 = 自身 + 全部子孙，索引按这个集合删
        List<String> affected = new ArrayList<>(domainRegistry.descendantsOf(normalized));
        affected.add(normalized);

        // 2) 先停掉这些域链上正在跑的会话。删域的语义是"撤销这个域的一切"，
        //    但撤销对在飞的那一轮不生效：那一轮仍会继续调工具，并把记忆与断点写回
        //    下面刚清过的 Redis 键。所以要么等标记真的释放（awaitStop），
        //    要么按强杀路径只置标志不等待（宽限期已给足，不再无限等待）。
        Set<String> affectedSet = Set.copyOf(affected);
        List<String> inflight = cancellationRegistry.runningKeys().stream()
                .filter(key -> affectedSet.contains(SessionKeys.domainOf(key)))
                .toList();
        if (!inflight.isEmpty()) {
            if (awaitStop) {
                boolean stopped = cancellationRegistry.requestStopAndAwait(
                        inflight, RUNNING_STOP_TIMEOUT);
                if (!stopped) {
                    throw new BaseException(ErrorCode.ORCHESTRATION_FAILED,
                            "域 " + normalized + " 仍有会话在运行（" + inflight.size() + " 个）未能在 "
                                    + RUNNING_STOP_TIMEOUT.toSeconds() + " 秒内停止，已中止、域未删除："
                                    + "那几轮正卡在长耗时工具调用里，先停止它们再删域");
                }
                log.info("[域管理] 删除域 {} 前已停掉在飞会话 {}", normalized, inflight);
            } else {
                inflight.forEach(cancellationRegistry::requestStop);
                log.warn("[域管理] 硬删域 {}：{} 个在飞会话未等待其停止（宽限期已给足或运维强制）。"
                                + "若某轮最终卡死返回，可能向已删域写入孤儿键"
                                + "（域已出注册表，键无消费方，检查点 TTL 兜底回收）",
                        normalized, inflight.size());
            }
        }

        // 3) Redis 记忆、会话归档与检查点排在所有破坏性动作之前（有意为之），且失败即抛。
        //    不清记忆/检查点，同路径域重建会复活旧对话历史与待执行的破坏性调用；
        //    不清归档同理：归档里是全量对话，漏掉它等于"删域成功"之后一句旧话还能勾回整段历史。
        int purgedMemories = 0;
        int purgedCheckpoints = 0;
        int purgedArchives = 0;
        int purgedIndexes = 0;
        int purgedSummaries = 0;
        try {
            for (String domain : affected) {
                purgedMemories += chatMemoryStore.deleteByDomain(domain);
                purgedCheckpoints += checkpointSaver.deleteByDomain(domain);
                purgedArchives += conversationArchive.deleteByDomain(domain);
                purgedIndexes += historySearch.deleteByDomain(domain);
                // 摘要与归档同族：它是有损压缩的产物，重放归档也复原不出同一份措辞，
                // 所以按档案级对待：删域时必须一起清
                purgedSummaries += conversationSummary.deleteByDomain(domain);
            }
        } catch (ChatMemoryException e) {
            throw new BaseException(ErrorCode.CHAT_MEMORY_DELETE_ERROR,
                    "删除域 " + normalized + " 前清理会话记忆/归档/检索索引/滚动摘要/检查点失败，已中止、域未删除："
                            + e.getMessage());
        }

        /* 4) 知识库：<b>只解绑，不动数据</b>。
           知识体是数据实体，域只是消费者。删域时删索引 = 顺手销毁一份可能被别的域共用、
           或者用户准备再绑给新域的原文与向量；而且删索引要连后端，后端一时不可达就会
           让整个删域失败——删域本身不该被知识库的可达性绑住。
           绑定本身随第 6 步的 registry 删除 + saveDeclarations 一起消失，这里只需要
           先把"保留了哪些体"记下来给管控台回话。 */
        List<String> retainedBodies = new ArrayList<>();
        for (String domain : affected) {
            String bodyId = domainStore.bodyOf(domain);
            if (bodyId != null && !bodyId.isBlank() && !retainedBodies.contains(bodyId)) {
                retainedBodies.add(bodyId);
            }
        }

        // 5) 域属性一并清理：提示词 / 工具执行信息回写开关 / 模型绑定 / 工具声明派生记录。
        //    不清理的话，同路径域将来重建时会"静默复活"旧配置。
        domainSettingsStore.removeDomains(affected);
        modelProfileRegistry.unbindDomains(affected);
        // MCP 连接绑定按"域维度"存储，删域级联摘掉受影响域的绑定（连接本身不删，与模型绑定同口径）；
        // 摘完后由 McpCoordinator 的 reconcile 把已无绑定的连接工具从注册表摘除。
        mcpConnectionStore.unbindDomains(Set.copyOf(affected));
        toolRegistry.forgetDomains(affected);

        // 6) 以上都成功才动域
        DomainRegistry.DomainDeleteResult deleted = domainRegistry.delete(normalized);
        if (!deleted.deleted()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, deleted.reason());
        }
        // 软删标记随硬删一起消失：留着的话同路径域重建后会莫名拒新会话
        //（标记在 domains.json 里，重启照样恢复进注册表）
        deleted.removed().forEach(domainStore::clearDeleted);
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        log.info("[域管理] 已删除域 {} 及其子孙 {}；知识体 {} 保留未动（删域只解绑），"
                        + "会话记忆 {} 条、会话归档 {} 个、检索索引 {} 个、滚动摘要 {} 个、断点 {} 条",
                normalized, deleted.removed(), retainedBodies,
                purgedMemories, purgedArchives, purgedIndexes, purgedSummaries, purgedCheckpoints);
        return new PurgeResult(deleted.removed(), retainedBodies, purgedMemories, purgedCheckpoints,
                purgedArchives, purgedIndexes, purgedSummaries);
    }
}
