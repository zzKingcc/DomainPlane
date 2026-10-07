package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.runtime.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.runtime.usage.TokenUsageRecorder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 工具路由器 —— Agent 调用能力的唯一入口
 * @author zzkingcc
 */
public class ToolRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

    private final ToolRegistry registry;
    /** 域注册表：内置与人工创建的域在这里；工具声明派生的域在 registry 里 */
    private final DomainRegistry domainRegistry;
    /** 本地工具的执行隔离层（超时 + 有界池）；远程工具走 HTTP 自带超时，不经它*/
    private final ToolExecutorService execution;

    /** 只接工具注册表时，域注册表用缺省实例（仅含根域）—— 供单元测试与最小装配使用 */
    public ToolRouter(ToolRegistry registry) {
        this(registry, new DomainRegistry());
    }

    public ToolRouter(ToolRegistry registry, DomainRegistry domainRegistry) {
        this(registry, domainRegistry, ToolExecutorService.sharedDefault());
    }

    public ToolRouter(ToolRegistry registry, DomainRegistry domainRegistry, ToolExecutorService execution) {
        this.registry = registry;
        this.domainRegistry = domainRegistry == null ? new DomainRegistry() : domainRegistry;
        this.execution = execution == null ? ToolExecutorService.sharedDefault() : execution;
        log.info("[工具路由] 初始化完成，注册表内共 {} 个工具，其中 {} 个需人工授权: {}",
                registry.size(), registry.toolsRequiringApproval().size(),
                registry.toolsRequiringApproval());
        if (registry.isEmpty()) {
            log.warn("[工具路由] 当前没有任何工具被注册：请确认工具方法上标注了 @Tool，且所在 Bean 已被 Spring 扫描到");
        }
    }

    /**
     * 获取所有工具规格（全量；无权限校验场景使用）
     */
    public List<ToolSpecification> toolSpecifications() {
        return registry.toolSpecifications();
    }

    /**
     * 按本轮域过滤后的工具规格
     *
     * @param domainId 本轮所处的域
     */
    public List<ToolSpecification> toolSpecifications(String domainId) {
        return registry.toolSpecifications(domainId);
    }

    /**
     * 获取需授权工具名集合（全量；管理页 / 无域场景使用）
     */
    public Set<String> toolsRequiringApproval() {
        return registry.toolsRequiringApproval();
    }

    /**
     * 按本轮域过滤后的"需授权工具名"集合（供条件路由决定是否走 review 中断）。
     */
    public Set<String> toolsRequiringApproval(String domainId) {
        return registry.toolsRequiringApproval(domainId);
    }

    /**
     * 域全集 —— 注册表登记的域 ∪ 工具声明派生过的域（含"被声明过、此刻已无工具"的域）。
     *
     * 口径是"出现过"，因此它只适合展示与排障（管控台域空间、提示词页、启动自检）；
     * 判断"能不能被调用"必须用 {@link #isCallableDomain(String)}。
     */
    public Set<String> knownDomains() {
        Set<String> all = new LinkedHashSet<>(domainRegistry.ids());
        all.addAll(registry.knownDomains());
        return Set.copyOf(all);
    }

    /**
     * 域可调用集 —— 入口唯一认可的域集合（显式标记为可调用单元的域）。
     *
     * 不含"仅被工具声明过"的域，也不含装配节点：父域可以只做装配，由叶子当入口。
     */
    public Set<String> callableDomains() {
        return domainRegistry.callableIds();
    }

    /**
     * 该域是否是可调用单元（入口判据）。
     *
     * 只认注册表里显式声明的可调用性；根域恒为可调用。
     */
    public boolean isCallableDomain(String domainId) {
        return domainRegistry.isCallable(domainId);
    }

    /**
     * 该域是否已知（任一处存在即为真）。
     */
    public boolean containsDomain(String domainId) {
        return domainRegistry.contains(domainId) || registry.containsDomain(domainId);
    }

        /**
     * 判断某工具对指定域是否可见（工具执行前的兜底校验）。
     */
    public boolean isToolVisible(String toolName, String domainId) {
        return registry.find(toolName)
                .map(r -> r.descriptor().visibleIn(domainId))
                .orElse(false);
    }

    /**
     * 工具当前是否存在于注册表（用于区分"域外"与"已下线/不存在"两种拒绝原因）。
     */
    public boolean exists(String toolName) {
        return registry.find(toolName).isPresent();
    }

    /**
     * 获取全部工具描述符（供管理页 / 审计使用；不含任何 Class 引用）
     */
    public List<ToolDescriptor> toolDescriptors() {
        return registry.descriptors();
    }

    /**
     * 某工具的敏感参数名（供工具调用事件与审批 payload 做值掩码）。
     */
    public Set<String> sensitiveParams(String toolName) {
        return registry.sensitiveParams(toolName);
    }

    /**
     * 执行工具调用（统一入口）
     *
     * @param request LLM 生成的工具调用请求
     * @return 工具执行结果；工具不存在或执行失败时返回给模型看的失败描述
     *（回喂模型而不是抛异常是刻意的：模型需要知道"这个工具失败了"才能改口或换路；
     * 让整轮对话因一次工具失败而崩掉，对用户更糟。详见 {@link #describeFailure}）
     */
    public String execute(ToolExecutionRequest request) {
        Optional<ToolRegistry.Registered> found = registry.find(request.name());
        if (found.isEmpty()) {
            // 文案取自 ErrorCode,与 toolsNode 复核层拒绝时的文案同源——同一个事实只该有一种说法
            String message = ErrorCode.TOOL_NOT_FOUND.getMessage() + ": " + request.name();
            log.warn("[工具路由] {}", message);
            MetricsRegistry.toolCall(false);
            return message;
        }

        ToolRegistry.Registered registered = found.get();
        ToolExecutor executor = registered.executor();
        String arguments = request.arguments() == null ? "" : request.arguments();

        log.info("[工具路由] 执行工具: {}（sideEffect={}, 需授权={}）",
                request.name(), registered.descriptor().sideEffect(),
                registered.descriptor().requiresApproval());

        String result;
        boolean success = true;
        try {
            // memoryId 恒为 null：当前工具均为无状态能力，不依赖会话记忆
            result = isLocal(registered)
                    ? execution.call(request.name(), () -> executor.execute(request, null))
                    : executor.execute(request, null);
            if (result == null) {
                result = "";
            }
        } catch (ToolExecutorService.ToolTimeoutException e) {
            // 超时与"工具内部报错"对模型是完全不同的信号：前者模型改参数也没用，
            // 只能如实告知用户"这一步没在预期时间内完成"。所以单列文案，不进 describeFailure
            success = false;
            log.error("[工具路由] 工具[{}] {}；隔离池不会被这次调用继续占用（已发中断）", request.name(), e.getMessage());
            result = describeUnavailable(request.name(), "执行超时（超过 " + e.timeoutMs() + "ms 仍未返回）");
        } catch (ToolExecutorService.ToolRejectedException e) {
            success = false;
            log.error("[工具路由] 工具[{}] {}。这通常意味着有工具调用卡死占满了隔离池，"
                    + "请到日志里找超时的那几个工具", request.name(), e.getMessage());
            result = describeUnavailable(request.name(), "系统繁忙，暂时无法执行");
        } catch (Exception e) {
            success = false;
            // 完整异常（含堆栈、SQL、内网地址、文件路径）只进日志，不回喂模型
            log.error("[工具路由] 工具[{}] 执行失败: {}", request.name(), e.getMessage(), e);
            result = describeFailure(request.name(), e);
        }
        // 成功失败都计：失败率是运维最需要的那个数。"工具不存在"也算失败——它对模型同样是失败信号
        MetricsRegistry.toolCall(success);

        // 统一记录 Token 用量（唯一统计点）
        TokenUsageRecorder.recordToolCall(request.name(), arguments, result);
        return result;
    }

    /**
     * 是否本地工具（不经 HTTP 回流，执行器直接调 Bean 方法）。
     *
     * 只有本地工具走隔离层。远程工具的耗时已经被
     * {@code HttpRequest.timeout(...)} 卡住，而它是纯 IO 等待 —— 塞进池子只会占着池里的
     * 线程空等，还会让本该给本地工具兜底的容量被远程调用吃掉。
     */
    private static boolean isLocal(ToolRegistry.Registered registered) {
        return registered.endpoints().stream().anyMatch(InstanceEndpoint::isLocal);
    }

    /**
     * 超时 / 池满这类"没能开始或没能完成"的回喂文案。
     *
     * 与 {@link #describeFailure} 分开是因为模型该采取的动作不同：工具内部报错时
     * 换参数重试有意义，超时或系统繁忙时再原样重试只会再耗一轮 —— 该做的是告诉用户
     * 「这一步没成」，让用户决定要不要继续。
     */
    private static String describeUnavailable(String toolName, String reason) {
        return "工具 " + toolName + " " + reason
                + "。请如实告知用户该步骤未能完成，不要编造结果，也不要立刻用相同参数重试。";
    }

    /**
     * 生成回喂给模型的失败描述：够模型改口，不泄内部细节。
     *
     * 原先直接拼 {@code e.getMessage()}。那段文本常常含 SQL 语句、文件路径、
     * 内网主机名甚至凭据片段 —— 模型会把它原样转述给终端用户，等于把内部结构
     * 通过对话泄漏出去。而 {@code message} 本身对模型决策几乎没有增量价值：
     * 它无法据此修好工具，只能如实告诉用户"这一步没成"。
     *
     * 要定位具体原因看服务端日志（有 traceId），不该从用户对话里捞。
     */
    private static String describeFailure(String toolName, Exception e) {
        String type = e.getClass().getSimpleName();
        String detail = sanitize(e.getMessage());
        return "工具 " + toolName + " 执行失败（" + type + (detail.isEmpty() ? "" : "：" + detail)
                + "）。请如实告知用户该步骤未能完成，不要编造结果；需要重试可在条件允许时再试一次。";
    }

    /**
     * 异常消息里的高危片段一律抹掉，只留下能帮助模型判断"是不是参数问题"的部分。
     *
     * 这里只做兜底，不做完整脱敏 —— 完整的敏感项脱敏由工具作者在
     * {@code @ToolAdvanced.sensitive} 里声明。这一层挡的是"作者没声明、但异常消息
     * 顺带带出来"的情况（框架级异常、底层驱动异常）。
     */
    private static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String out = message;
        // 连接串与凭据
        out = out.replaceAll("(?i)(password|passwd|pwd|token|secret|apikey|api_key)\\s*[=:]\\s*\\S+",
                "$1=***");
        // JDBC 连接串（含账号与主机）
        out = out.replaceAll("jdbc:[^\\s,;)]+", "jdbc:***");
        out = out.replaceAll("(?i)\\b(?:https?|redis|rediss|mongodb)://[^\\s]+", "<内部地址>");
        // 绝对路径与 Windows 盘符路径
        out = out.replaceAll("(?:[A-Za-z]:)?[/\\\\][\\w.\\-]+(?:[/\\\\][\\w.\\-]+)+", "<路径>");
        // 折行与超长文本：异常消息可能整段堆栈被塞进 getMessage
        out = out.replaceAll("\\s+", " ").trim();
        if (out.length() > 120) {
            out = out.substring(0, 120) + "…";
        }
        return out;
    }
}
