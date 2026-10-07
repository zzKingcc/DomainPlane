package com.zzkingcc.domainplane.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzkingcc.domainplane.api.support.TraceId;
import com.zzkingcc.domainplane.runtime.tool.InstanceEndpoint;
import com.zzkingcc.domainplane.runtime.tool.ToolInvocationContext;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 远程工具执行器 —— 把一次工具调用推到工具实例上执行
 * @author zzkingcc
 */
public class RemoteToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(RemoteToolExecutor.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /** 单次调用超时（毫秒） */
    private final int timeoutMillis;

    /** 单次工具调用最多尝试几个不同副本 */
    private final int maxAttempts;

    public RemoteToolExecutor(ToolRegistry registry, int timeoutMillis, int maxAttempts) {
        this.registry = registry;
        this.timeoutMillis = timeoutMillis;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String toolName = request.name();
        List<InstanceEndpoint> replicas = new ArrayList<>(registry.endpoints(toolName).stream()
                .filter(endpoint -> !endpoint.isLocal())
                .toList());
        if (replicas.isEmpty()) {
            // 走到这里说明工具条目刚被摘除（复核层与执行之间有一瞬窗口），或实例声明的是本地工具
            String message = "工具 " + toolName + " 当前没有可用的远程副本（提供它的实例均已下线）";
            log.warn("[远程工具] {}", message);
            return message;
        }

        Collections.shuffle(replicas);
        int attempts = Math.min(replicas.size(), maxAttempts);
        String lastFailure = null;
        for (int i = 0; i < attempts; i++) {
            InstanceEndpoint replica = replicas.get(i);
            try {
                return invoke(toolName, replica, request);
            } catch (TransportFailure e) {
                lastFailure = e.getMessage();
                log.warn("[远程工具] {} 调用实例 {}（{}）失败，{}",
                        toolName, replica.instanceId(), replica.endpoint(),
                        i + 1 < attempts ? "换副本重试" : "已无副本可试");
            }
        }
        return "工具 " + toolName + " 调用失败（已尝试 " + attempts + " 个副本）: " + lastFailure;
    }

    /**
     * 调用一个副本。返回工具结果文本；传输层失败抛 {@link TransportFailure} 让上层换副本。
     */
    private String invoke(String toolName, InstanceEndpoint replica, ToolExecutionRequest request)
            throws TransportFailure {
        String requestId = UUID.randomUUID().toString();
        String endpoint = replica.endpoint();

        ObjectNode body = mapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("toolName", toolName);
        body.set("arguments", parseArguments(request.arguments()));
        // traceId 让实例侧日志与服务端这次调用对齐；未绑定时退回当前 MDC 值
        String traceId = ToolInvocationContext.traceId() == null
                ? TraceId.current() : ToolInvocationContext.traceId();
        putIfPresent(body, "traceId", traceId);

        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new TransportFailure("调用地址非法（" + endpoint + "）：" + e.getMessage(), e);
        }

        HttpResponse<String> response;
        long startedAt = System.currentTimeMillis();
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportFailure("调用被中断", e);
        } catch (Exception e) {
            throw new TransportFailure("请求失败：" + e.getMessage(), e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new TransportFailure("实例返回 HTTP " + response.statusCode()
                    + "：" + abbreviate(response.body()));
        }

        JsonNode parsed;
        try {
            parsed = mapper.readTree(response.body());
        } catch (Exception e) {
            throw new TransportFailure("实例响应不是合法 JSON：" + abbreviate(response.body()), e);
        }

        log.info("[远程工具] {} ← 实例 {}{}，耗时 {}ms", toolName, replica.instanceId(),
                parsed.path("requestId").asText("").isBlank() ? "" : "（requestId=" + parsed.path("requestId").asText() + "）",
                System.currentTimeMillis() - startedAt);

        if (parsed.path("success").asBoolean(false)) {
            return renderResult(parsed.get("result"));
        }
        // 业务失败：不换副本，把原因回喂模型（模型据此改写参数或改述给用户）
        String error = parsed.path("error").asText("");
        log.warn("[远程工具] {} 在实例 {} 上执行失败: {}", toolName, replica.instanceId(),
                error.isBlank() ? "(未给出原因)" : error);
        return "工具 " + toolName + " 执行失败: " + (error.isBlank() ? "实例未给出原因" : error);
    }

    /**
     * 工具参数 → JSON 对象。
     */
    private JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(arguments);
            return node.isObject() ? node : mapper.createObjectNode();
        } catch (Exception e) {
            log.warn("[远程工具] 工具参数不是合法 JSON，按空参数发送: {}", abbreviate(arguments));
            return mapper.createObjectNode();
        }
    }

    /** 工具返回值 → 回喂模型的文本：文本原样，结构化数据序列化成 JSON 串 */
    private static String renderResult(JsonNode result) {
        if (result == null || result.isNull()) {
            return "";
        }
        return result.isTextual() ? result.asText() : result.toString();
    }

    private static void putIfPresent(ObjectNode body, String field, String value) {
        if (value != null && !value.isBlank()) {
            body.put(field, value);
        }
    }

    private static String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "(空)";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /** 传输层失败：换副本重试的信号（与"业务失败"区分开） */
    private static final class TransportFailure extends Exception {
        TransportFailure(String message) {
            super(message);
        }

        TransportFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
