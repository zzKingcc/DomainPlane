package com.zzkingcc.domainplane.toolprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工具实例客户端 —— 客户侧的核心组件（SDK 入口）
 *
 * @author zzkingcc
 */
public class ToolInstanceClient implements ToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ToolInstanceClient.class);

    /** 与服务端约定的凭证请求头 */
    public static final String CREDENTIAL_HEADER = "X-DomainPlane-Credential";

    private final ToolInstanceConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 本实例声明的工具：名字 → (声明, 执行体)。心跳时按此生成 manifest */
    private final Map<String, ToolEntry> tools = new ConcurrentHashMap<>();

    /** 缓存的凭证（签名式、不设有效期，正常路径下整个生命周期只登录一次） */
    private final AtomicReference<String> credential = new AtomicReference<>();

    /** 心跳调度器 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "domainplane-tool-provider-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    private volatile boolean stopped;
    private volatile boolean forcedOffline;
    private volatile Set<String> lastAccepted = Set.of();
    private volatile String lastError;

    public ToolInstanceClient(ToolInstanceConfig config) {
        this.config = config;
    }

    public ToolInstanceConfig config() {
        return config;
    }

    // ==================== 声明工具 ====================

    /**
     * 声明一个工具（可链式调用多次）
     *
     * 启动后再调也生效：下一次心跳会把新工具带上去（注册表随之 diff 出新副本）。
     */
    @Override
    public ToolInstanceClient register(ToolSpec spec, ToolHandler handler) {
        if (spec == null || handler == null) {
            throw new IllegalArgumentException("工具声明与执行体都不能为空");
        }
        ToolEntry previous = tools.put(spec.name(), new ToolEntry(spec, handler));
        if (previous != null) {
            log.warn("[工具实例] 工具 {} 被重复声明，已用后一次覆盖", spec.name());
        }
        return this;
    }

    /** 当前声明的工具名（有序） */
    public Set<String> toolNames() {
        return new TreeSet<>(tools.keySet());
    }

    // ==================== 生命周期 ====================

    /**
     * 启动心跳：立即发一次，之后按周期发。
     */
    public void start() {
        if (tools.isEmpty()) {
            log.warn("[工具实例] {} 未声明任何工具，心跳会注册一份空 manifest —— 若这不是本意，"
                    + "请先调用 register(ToolSpec, ToolHandler)", config.instanceId());
        }
        log.info("[工具实例] {} 启动：{} 个工具 {}，心跳周期 {}s，注册地址 {}，回流地址 {}",
                config.instanceId(), tools.size(), toolNames(),
                config.heartbeatIntervalSeconds(), config.registerUrl(), config.endpoint());
        scheduleNext(0L);
    }

    /** 停止心跳（客户应用关闭时调用；停止后不可再 start——调度器已关闭） */
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        scheduler.shutdownNow();
        log.info("[工具实例] {} 已停止心跳", config.instanceId());
    }

    private void scheduleNext(long delayMillis) {
        if (stopped) {
            return;
        }
        try {
            scheduler.schedule(this::tick, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 调度器已关闭：正常关闭竞态，不是错误
        }
    }

    /**
     * 一次心跳。成功即回到正常周期；失败进入指数退避；被强制下线则终止。
     */
    private void tick() {
        if (stopped) {
            return;
        }
        try {
            sendHeartbeat();
            consecutiveFailures.set(0);
            lastError = null;
            scheduleNext(config.heartbeatIntervalSeconds() * 1000L);
        } catch (ForcedOfflineException e) {
            forcedOffline = true;
            lastError = "已被服务端强制下线（410）";
            log.error("[工具实例] {} 已被服务端强制下线，停止心跳。本实例注册的工具副本已被摘除；"
                    + "如仍需提供服务，请确认该实例身份（instanceId={}）是否应归属于本程序",
                    config.instanceId(), config.instanceId());
            stop();
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            long delay = backoffMillis(failures);
            lastError = e.getMessage();
            log.warn("[工具实例] {} 第 {} 次心跳失败（{}s 后重试）：{}",
                    config.instanceId(), failures, delay / 1000, e.getMessage());
            scheduleNext(delay);
        }
    }

    /**
     * 指数退避
     */
    private long backoffMillis(int failures) {
        long interval = config.heartbeatIntervalSeconds() * 1000L;
        long max = config.maxBackoffSeconds() * 1000L;
        int shift = Math.min(failures - 1, 6);           // 上限 64 倍，防移位溢出
        long delay = interval << shift;
        return Math.min(delay, max);
    }

    // ==================== 心跳 ====================

    private void sendHeartbeat() throws Exception {
        String currentCredential = credential();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", config.instanceId());
        body.put("endpoint", config.endpoint());
        List<Map<String, Object>> manifest = tools.values().stream()
                .map(entry -> entry.spec().toManifest())
                .toList();
        body.put("manifest", manifest);

        HttpResponse<String> response = post(config.registerUrl(), body, currentCredential);
        int status = response.statusCode();

        if (status == 410) {
            throw new ForcedOfflineException();
        }
        if (status == 401 || status == 403) {
            // 凭证失效：服务端可能改过密码（签名密钥由密码哈希派生）。丢弃缓存，下次自动重新登录。
            credential.set(null);
            throw new IllegalStateException("服务端拒绝凭证（HTTP " + status + "）：已丢弃本地凭证，下次心跳将重新登录。"
                    + describeLoginStatus(status));
        }
        if (status / 100 != 2) {
            throw new IllegalStateException("注册失败（HTTP " + status + "）：" + abbreviate(response.body()));
        }

        JsonNode parsed = mapper.readTree(response.body());
        if (!parsed.path("accepted").asBoolean(false)) {
            // 400 已在上面的 4xx 分支拦掉，走到这里说明报文格式异常
            throw new IllegalStateException("服务端未受理本次注册：" + abbreviate(response.body()));
        }

        Set<String> accepted = new TreeSet<>();
        parsed.path("toolNames").forEach(node -> accepted.add(node.asText("")));
        if (!accepted.equals(lastAccepted)) {
            // 只在"受理结果变化"时打 INFO：心跳每 5 秒一次，每次都打会把日志刷成噪音
            log.info("[工具实例] {} 注册结果：服务端受理 {} 个工具 {}", config.instanceId(), accepted.size(), accepted);
            lastAccepted = accepted;
        } else {
            log.debug("[工具实例] {} 心跳正常（{} 个工具）", config.instanceId(), accepted.size());
        }
    }

    /**
     * 取凭证，没有就登录一次（并发下只登一次）。
     */
    private String credential() throws Exception {
        String cached = credential.get();
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = credential.get();
            if (cached != null) {
                return cached;
            }
            Map<String, Object> body = Map.of(
                    "username", config.username(),
                    "password", config.password());
            HttpResponse<String> response = post(config.loginUrl(), body, null);
            int status = response.statusCode();
            if (status / 100 != 2) {
                throw new IllegalStateException("登录失败（HTTP " + status + "）：" + describeLoginStatus(status));
            }
            String value = mapper.readTree(response.body()).path("credential").asText("");
            if (value.isBlank()) {
                throw new IllegalStateException("服务端未返回凭证：请确认 " + config.serverUrl()
                        + " 是 DomainPlane 服务端（该端点返回的 JSON 里应有 credential 字段）");
            }
            credential.set(value);
            // 只记账号，凭证是凭据，明文不进日志
            log.info("[工具实例] 已用账号 [{}] 换取访问凭证", config.username());
            return value;
        }
    }

    /**
     * 登录失败的可操作提示。
     */
    private String describeLoginStatus(int status) {
        return switch (status) {
            case 401 -> "账号或密码不对。若最近在管控台改过密码，请同步更新工具实例的密码配置";
            case 403 -> "服务端拒绝了本次登录（403），请检查网络侧访问控制";
            case 409 -> "服务端账号尚未初始化：请先打开 " + config.serverUrl()
                    + "/console/login.html 完成首次初始化";
            default -> "请检查 " + config.serverUrl() + " 是否为 DomainPlane 服务端且已启动";
        };
    }

    // ==================== 服务端 → 实例：执行工具 ====================

    /**
     * 处理一次工具调用（服务端 POST 到本实例 endpoint 时由 Web 层转发进来）。
     *
     * @param request 服务端请求体：{@code {requestId, toolName, arguments, traceId}}
     * @return 协议响应：{@code {requestId, success, result}} 或 {@code {requestId, success:false, error, retryable}}
     */
    public JsonNode invoke(JsonNode request) {
        String requestId = request == null ? "" : request.path("requestId").asText("");
        String toolName = request == null ? "" : request.path("toolName").asText("");
        ObjectNode response = mapper.createObjectNode();
        response.put("requestId", requestId);

        ToolEntry entry = tools.get(toolName);
        if (entry == null) {
            // 常见于"实例刚升级、工具已下线，但服务端注册表还没收到新 manifest"的那一小段窗口
            log.warn("[工具实例] 收到未注册工具的调用: {}（本实例声明的工具: {}）", toolName, toolNames());
            response.put("success", false);
            response.put("error", "工具未在本实例注册: " + toolName);
            response.put("retryable", false);
            return response;
        }

        // request 允许为 null（controller 是 @RequestBody(required = false)），
        // 上面已按空处理过 requestId/toolName，这里也必须同样处理，
        // 否则一个没有 body 的 POST 会直接 NPE → 500 + 堆栈外泄。
        JsonNode arguments = request == null ? null : request.path("arguments");
        String argumentsJson = arguments == null || arguments.isMissingNode() || arguments.isNull()
                ? "{}" : arguments.toString();
        String traceId = request == null ? "" : request.path("traceId").asText("");

        long startedAt = System.currentTimeMillis();
        try {
            String value = entry.handler().handle(argumentsJson);
            log.info("[工具实例] {} 执行完成（traceId={}, 耗时 {}ms）",
                    toolName, traceId, System.currentTimeMillis() - startedAt);
            response.put("success", true);
            response.put("result", value == null ? "" : value);
        } catch (Exception e) {
            // 业务失败：如实回给服务端（它会作为工具结果回喂模型），不在实例侧重试
            log.error("[工具实例] {} 执行失败（traceId={}）: {}", toolName, traceId, e.getMessage(), e);
            response.put("success", false);
            response.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            response.put("retryable", entry.spec().idempotent());
        }
        return response;
    }

    // ==================== 状态（供客户应用自检 / 健康检查） ====================

    /** 是否已被服务端强制下线（此后不再心跳，需要人工介入） */
    public boolean isForcedOffline() {
        return forcedOffline;
    }

    /** 最近一次心跳是否成功过（用于客户应用的健康检查端点） */
    public boolean isRegistered() {
        return !lastAccepted.isEmpty() || (consecutiveFailures.get() == 0 && lastError == null && !stopped);
    }

    /** 最近一次失败原因（成功后清空） */
    public String lastError() {
        return lastError;
    }

    // ==================== HTTP ====================

    private HttpResponse<String> post(String url, Map<String, Object> body, String credential) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(config.requestTimeoutMillis()))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (credential != null) {
            builder.header(CREDENTIAL_HEADER, credential);
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断", e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "(空响应)";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /** 本实例的一个工具：声明 + 执行体 */
    private record ToolEntry(ToolSpec spec, ToolHandler handler) {
    }

    /** 被服务端强制下线（410）：不是可重试的失败，必须终止心跳 */
    private static final class ForcedOfflineException extends Exception {
    }
}
