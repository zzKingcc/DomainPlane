package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.redis.checkpoint.RedisCheckpointSaver;
import com.zzkingcc.domainplane.infrastructure.redis.memory.RedisChatMemoryStore;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.knowledge.DomainChannelProvider;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理面：域的创建、可调用性切换与删除。
 *
 * 域有两种角色，由 {@code callable} 显式区分：
 *
 *   • 可调用单元（{@code callable=true}）：能作为入口被调用，是"这一个 AI 切片"的入口；
 *   • 装配节点（{@code callable=false}）：只负责把工具 / 提示词 / 模型绑定 / 知识传给后代，不能直接调。
 *
 *
 * 为什么必须显式：只有显式声明，才能把"父域不能当入口"写成一条不受结构变化影响的规则；
 * 而"有没有子域"是随时会变的派生事实 —— 拿它当判据会在新增子域时静默改变调用方的可用性。
 *
 * 删除的边界：只有根域不可删。删除是递归的 —— 该域的全部子孙一并带走，
 * 不向上提升层级；删除前会先删知识库索引，删不干净就拒绝删域。
 *
 * 路径在 {@code /admin/**} 之下，鉴权由凭证拦截器统一处理。
 *
 * @author zzkingcc
 */
@RestController
public class AdminDomainController {

    private static final Logger log = LoggerFactory.getLogger(AdminDomainController.class);

    /**
     * 删域前等待在飞会话停止的上限。取 5 秒：正常一轮在下一个检查点就会看到停止标志，
     * 只有卡在长耗时工具调用里才会耗到头 —— 那属于"还在用已撤销的授权干活"，必须让调用方看到。
     */
    private static final Duration RUNNING_STOP_TIMEOUT = Duration.ofSeconds(5);

    private final DomainRegistry domainRegistry;
    private final DomainStore domainStore;
    private final KnowledgeBaseService knowledgeBase;
    private final DomainSettingsStore domainSettingsStore;
    private final ModelProfileRegistry modelProfileRegistry;
    private final ToolRegistry toolRegistry;
    private final DomainChannelProvider domainChannelProvider;
    private final RedisChatMemoryStore chatMemoryStore;
    private final RedisCheckpointSaver checkpointSaver;
    private final CancellationRegistry cancellationRegistry;

    public AdminDomainController(DomainRegistry domainRegistry,
                                 DomainStore domainStore,
                                 KnowledgeBaseService knowledgeBase,
                                 DomainSettingsStore domainSettingsStore,
                                 ModelProfileRegistry modelProfileRegistry,
                                 ToolRegistry toolRegistry,
                                 DomainChannelProvider domainChannelProvider,
                                 RedisChatMemoryStore chatMemoryStore,
                                 RedisCheckpointSaver checkpointSaver,
                                 CancellationRegistry cancellationRegistry) {
        this.domainRegistry = domainRegistry;
        this.domainStore = domainStore;
        this.knowledgeBase = knowledgeBase;
        this.domainSettingsStore = domainSettingsStore;
        this.modelProfileRegistry = modelProfileRegistry;
        this.toolRegistry = toolRegistry;
        this.domainChannelProvider = domainChannelProvider;
        this.chatMemoryStore = chatMemoryStore;
        this.checkpointSaver = checkpointSaver;
        this.cancellationRegistry = cancellationRegistry;
    }

    /**
     * 创建域（完整路径；链上缺失的祖先一并建出，祖先一律是装配节点）。
     *
     * @param body {@code id} 域标识，须为从 {@code default} 出发的完整路径，
     *             单段只允许字母 / 数字 / 下划线 / 连字符，自身链上不得重复段；
     *             {@code callable} 省略时按"可调用单元"创建
     */
    @PostMapping("/admin/domains")
    public Map<String, Object> create(@RequestBody CreateBody body) {
        if (body == null || body.getId() == null || body.getId().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域标识不能为空");
        }
        boolean callable = body.getCallable() == null || body.getCallable();
        DomainRegistry.CreateResult created = domainRegistry.create(body.getId(), callable);
        if (!created.created()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, created.reason());
        }
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        log.info("[域管理] 已创建域 {}（来源 MANUAL，可调用={}）", body.getId().trim(), callable);
        return view("created", body.getId().trim(), List.of());
    }

    /**
     * 切换某个域的可调用性。
     *
     * 只影响这一个域，不向下传播；切回可调用后，以它为入口的调用立即恢复。
     *
     * @param body {@code callable} 目标值
     */
    @PutMapping("/admin/domains/{id}/callable")
    public Map<String, Object> setCallable(@PathVariable("id") String id,
                                           @RequestBody(required = false) CallableBody body) {
        if (body == null || body.getCallable() == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "缺少 callable（true=可调用单元，false=装配节点）");
        }
        String normalized = Domains.normalize(id);
        // 根域同样服从叶子规则（有子域时它也只是装配节点），因此这里不需要根域特例 ——
        // 唯一的硬约束是"只有叶子域可以作为可调用单元"。
        DomainRegistry.CallableResult result = domainRegistry.setCallable(normalized, body.getCallable());
        if (!result.changed()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, result.reason());
        }
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        return view("callable", normalized, List.of());
    }

    /**
     * 删除域及其全部子孙（递归）。只有根域拒绝删除。
     *
     * 顺序是硬要求，且调整过一次：现在的顺序是
     * Redis 记忆与检查点 → 知识库索引与切片预览 → 域属性 → 删域。
     *
     * 原因：每一步失败都是"抛异常中止、域未删除"，所以中止点必须落在
     * 还没有任何东西被删的位置。原先 Redis 清理排在索引之后，一旦它失败，
     * 索引已经删掉、域却还在 —— 留下一个比直接失败更难收拾的半完成状态。
     *
     * 为什么这几步都必须成功才继续：
     *
     *   • 索引：一域一索引，索引是该域知识的唯一载体；域先没了那些索引就再没人认领
     *       （检索不到、上传也无从指定），而索引名是域路径的哈希，
     *       同路径域重建时名字相同 —— 这份本该删掉的知识内容会原样复活。
     *   • 会话记忆：保留期默认永久，不清则同路径域重建时对话历史原样复活。
     *   • 检查点：里面存着"即将执行、尚未执行"的工具调用，不清则 24 小时内重建域
     *       就能 {@code resume(approved=true)} 把当初被拦下的破坏性动作补执行掉 ——
     *       删域没有撤销授权。
     *   • 域属性（提示词 / 模型绑定 / 工具声明派生记录 / 检索器缓存）：
     *       否则同路径域重建时静默复活旧配置。
     *
     */
    @DeleteMapping("/admin/domains/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        // 1) 先确认这个域存在且可删，避免白删一轮索引
        String normalized = Domains.normalize(id);
        if (!domainRegistry.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域不存在：" + normalized);
        }
        if (domainRegistry.isBuiltin(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "根域不可删除：" + normalized + "（它是整棵域树的起点）");
        }

        // 2) 本次会牵连的域 = 自身 + 全部子孙，索引按这个集合删
        List<String> affected = new ArrayList<>(domainRegistry.descendantsOf(normalized));
        affected.add(normalized);

        // 2.5) 先停掉这些域链上**正在跑**的会话，并等它们真的停下来。
        //     删域的语义是"撤销这个域的一切"，但撤销对**在飞的那一轮**不生效：
        //     那一轮仍会继续调工具（已剥离域声明、等于授权已撤销），并把记忆与断点
        //     **写回**下面刚清过的 Redis 键。这里先 requestStop 再等 running 标记释放，
        //     确保"没有东西会在清理之后又写回来"。停不下来（正卡在长耗时工具）就中止，
        //     不默默往下删 —— 与 I-03/I-04 同一 fail-closed 判据。
        Set<String> affectedSet = Set.copyOf(affected);
        List<String> inflight = cancellationRegistry.runningKeys().stream()
                .filter(key -> affectedSet.contains(SessionKeys.domainOf(key)))
                .toList();
        if (!inflight.isEmpty()) {
            boolean stopped = cancellationRegistry.requestStopAndAwait(
                    inflight, RUNNING_STOP_TIMEOUT);
            if (!stopped) {
                throw new BaseException(ErrorCode.ORCHESTRATION_FAILED,
                        "域 " + normalized + " 仍有会话在运行（" + inflight.size() + " 个）未能在 "
                                + RUNNING_STOP_TIMEOUT.toSeconds() + " 秒内停止，已中止、域未删除："
                                + "那几轮正卡在长耗时工具调用里，先停止它们再删域");
            }
            log.info("[域管理] 删除域 {} 前已停掉在飞会话 {}", normalized, inflight);
        }

        // 3) Redis 记忆与检查点排在所有破坏性动作之前（有意为之），且失败即抛（原先只 warn）。
        //    中止必须落在"还没删任何东西"的位置：否则 Redis 挂了才中止，索引已删、域还在，
        //    半完成状态比直接失败更难收拾。不清记忆/检查点，同路径域重建会复活旧对话历史与待执行的破坏性调用。
        int purgedMemories = 0;
        int purgedCheckpoints = 0;
        try {
            for (String domain : affected) {
                purgedMemories += chatMemoryStore.deleteByDomain(domain);
                purgedCheckpoints += checkpointSaver.deleteByDomain(domain);
            }
        } catch (ChatMemoryException e) {
            throw new BaseException(ErrorCode.CHAT_MEMORY_DELETE_ERROR,
                    "删除域 " + normalized + " 前清理会话记忆/检查点失败，已中止、域未删除："
                            + e.getMessage());
        }

        // 4) 知识库索引（含切片预览文件）
        List<String> removedIndices;
        try {
            // 删索引前会先把这些域的切片预览文件（含文档全文）一并清掉
            removedIndices = knowledgeBase.deleteIndices(affected);
        } catch (KnowledgeBaseException e) {
            throw new BaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "删除域 " + normalized + " 前清理知识库失败，已中止、域未删除：" + e.getMessage());
        }
        // 索引检索器缓存一并失效：删除的索引还在缓存里会被旧检索器继续命中
        List<String> affectedIndices = affected.stream().map(KbIndexes::nameOf).toList();
        domainChannelProvider.evictIndices(affectedIndices);

        // 5) 域属性一并清理：提示词 / 模型绑定 / 工具声明派生记录。
        //    不清理的话，同路径域将来重建时会"静默复活"旧配置。
        domainSettingsStore.removePrompts(affected);
        modelProfileRegistry.unbindDomains(affected);
        toolRegistry.forgetDomains(affected);

        // 6) 以上都成功才动域
        DomainRegistry.DomainDeleteResult deleted = domainRegistry.delete(normalized);
        if (!deleted.deleted()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, deleted.reason());
        }
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        log.info("[域管理] 已删除域 {} 及其子孙 {}；连带删除知识库索引 {} 个（域 {}），"
                        + "会话记忆 {} 条、断点 {} 条",
                normalized, deleted.removed(), removedIndices.size(), removedIndices,
                purgedMemories, purgedCheckpoints);
        return view("deleted", normalized, deleted.removed(), removedIndices);
    }

    /**
     * 统一返回体：动作 + 当前域全貌，便于管控台一次刷新。
     */
    private Map<String, Object> view(String action, String domainId, List<String> removed,
                                     List<String> removedIndices) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("action", action);
        result.put("domain", domainId);
        result.put("removed", removed);
        result.put("removedIndices", removedIndices);
        result.put("manualDomains", domainRegistry.manualIds());
        result.put("callableDomains", domainRegistry.callableIds());
        result.put("settingsFile", domainStore.filePath());
        return result;
    }

    private Map<String, Object> view(String action, String domainId, List<String> removed) {
        return view(action, domainId, removed, List.of());
    }

    /** 创建请求体 */
    @Data
    public static class CreateBody {
        /** 域标识 */
        private String id;

        /** 是否建为可调用单元；省略 = true */
        private Boolean callable;
    }

    /** 可调用性切换请求体 */
    @Data
    public static class CallableBody {
        /** true=可调用单元，false=装配节点 */
        private Boolean callable;
    }
}
