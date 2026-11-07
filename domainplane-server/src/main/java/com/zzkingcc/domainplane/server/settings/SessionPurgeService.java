package com.zzkingcc.domainplane.server.settings;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.conversation.AssemblyReportStore;
import com.zzkingcc.domainplane.api.conversation.ConversationArchive;
import com.zzkingcc.domainplane.api.conversation.ConversationSummaryStore;
import com.zzkingcc.domainplane.api.conversation.HistorySearch;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.infrastructure.redis.checkpoint.RedisCheckpointSaver;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisSessionStorage;
import com.zzkingcc.domainplane.infrastructure.redis.memory.RedisChatMemoryStore;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * 单会话硬删：把该会话散在各处的记忆痕迹一次性清干净。
 *
 * <p>与 {@link DomainPurgeService} 是同一套账，只是维度从"域"换成"会话"：
 * 记忆（模型上下文）、检查点（可能存着待执行的工具调用）、归档（全量轮记录）、
 * 倒排+向量索引、滚动摘要、装配清单，六路全清。少清任何一路，旧内容都会从另一路
 * 被勾回来 —— 尤其摘要是有损压缩产物，归档删了它也复原不出来。</p>
 *
 * <p><b>边界</b>：只做人工、单会话、二次确认后的清除。不按体量排行、不批量、不定时、
 * 不做自动瘦身。"原始记忆只增不减"这条约束禁的是无人值守的淘汰，不是运维手里
 * 一个指名道姓的删除按钮。</p>
 *
 * <p><b>失败语义</b>：跨键删除没有事务，中途抛异常会留下"前半段已清"的状态。
 * 每一步都是幂等的，所以正确的恢复动作就是重跑一次；错误文案必须把这句话带上，
 * 不能让运维以为整体回滚了、从而不敢重试。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Service
public class SessionPurgeService {

    /**
     * 清理动作集合：把六个 Redis 具体类收敛成函数，单测就不必引 Mockito
     * （本项目测试不引），也不必为测一段编排去造 StringRedisTemplate 桩。
     * 字段顺序即清理顺序，见 {@link #clear(String)}。
     */
    record Targets(Supplier<Set<String>> runningKeys,
                   Function<String, RedisSessionStorage.Snapshot> snapshot,
                   Consumer<String> clearMemory,
                   ToIntFunction<String> clearCheckpoints,
                   Consumer<String> clearArchive,
                   ToIntFunction<String> clearIndexes,
                   ToIntFunction<String> clearSummaries,
                   ToIntFunction<String> clearAssemblies) {
    }

    private final Targets targets;

    /** 两个构造器时 Spring 不猜：不标 @Autowired 会回落无参构造，启动直接失败 */
    @Autowired
    public SessionPurgeService(RedisSessionStorage sessionStorage,
                               CancellationRegistry cancellationRegistry,
                               RedisChatMemoryStore chatMemoryStore,
                               RedisCheckpointSaver checkpointSaver,
                               ConversationArchive conversationArchive,
                               HistorySearch historySearch,
                               ConversationSummaryStore conversationSummary,
                               AssemblyReportStore assemblyReportStore) {
        this(new Targets(cancellationRegistry::runningKeys,
                sessionStorage::snapshotOf,
                chatMemoryStore::deleteMessages,
                checkpointSaver::deleteBySession,
                conversationArchive::clearSession,
                historySearch::deleteBySession,
                conversationSummary::deleteBySession,
                assemblyReportStore::deleteBySession));
    }

    /** 包可见：单测注入动作桩用 */
    SessionPurgeService(Targets targets) {
        this.targets = targets;
    }

    /**
     * 清除结果：记忆条数 / 归档轮数取自删除前的 O(1) 快照，其余是各路删掉的键数。
     * 归档是逐轮 list，没有"键数"可数，给轮数才是运维关心的量。
     */
    public record ClearResult(String sessionKey, long memoryMessages, long archiveTurns,
                              int checkpointKeys, int indexKeys, int summaryKeys,
                              int assemblyKeys) {
    }

    /**
     * 清空一个会话。
     *
     * @param sessionKey 会话键（{@code 域|sessionId}），由调用方解析好并校验非空
     * @throws BaseException 会话正在运行，或任一路清理失败
     */
    public ClearResult clear(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "会话键为空，已拒绝执行清除");
        }
        // 在飞那一轮不受删除影响：它结束时照样会把记忆、归档、断点写回刚清空的键。
        // 不自动 requestStop（停不掉时更尴尬），直接拒绝，让运维先停再清。
        if (targets.runningKeys().get().contains(sessionKey)) {
            throw new BaseException(ErrorCode.ORCHESTRATION_FAILED,
                    "会话 " + sessionKey + " 正在运行，已拒绝清除：先停止这一轮再清，"
                            + "否则它结束时会把记忆与归档写回刚清空的键");
        }

        // 快照只读 O(1) 的 LLEN/HLEN，且放在任何删除之前：
        // 读失败时一点东西都还没删，中止点落在最干净的位置。
        RedisSessionStorage.Snapshot snap = targets.snapshot().apply(sessionKey);

        try {
            targets.clearMemory().accept(sessionKey);
            int checkpoints = targets.clearCheckpoints().applyAsInt(sessionKey);
            targets.clearArchive().accept(sessionKey);
            int indexes = targets.clearIndexes().applyAsInt(sessionKey);
            int summaries = targets.clearSummaries().applyAsInt(sessionKey);
            int assemblies = targets.clearAssemblies().applyAsInt(sessionKey);
            log.info("[会话管理] 已清空会话 {}：记忆 {} 条、归档 {} 轮、断点 {} 键、索引 {} 键、"
                            + "摘要 {} 键、装配清单 {} 键",
                    sessionKey, snap.memoryMessages(), snap.archiveTurns(), checkpoints,
                    indexes, summaries, assemblies);
            return new ClearResult(sessionKey, snap.memoryMessages(), snap.archiveTurns(),
                    checkpoints, indexes, summaries, assemblies);
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("[会话管理] 清空会话 {} 中途失败：{}", sessionKey, e.getMessage(), e);
            throw new BaseException(ErrorCode.CHAT_MEMORY_DELETE_ERROR,
                    "清空会话 " + sessionKey + " 中途失败：" + e.getMessage()
                            + "。各步删除都是幂等的，请重新执行一次清除（已清掉的部分不会回来）", e);
        }
    }
}
