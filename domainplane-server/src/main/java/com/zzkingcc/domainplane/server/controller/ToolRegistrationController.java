package com.zzkingcc.domainplane.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzkingcc.domainplane.runtime.tool.InstanceRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.tool.RemoteToolExecutor;
import com.zzkingcc.domainplane.server.tool.ToolManifest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具实例注册入口 —— 外部工具实例的唯一接入点
 * @author zzkingcc
 */
@RestController
@RequestMapping("/api/agent/tools")
public class ToolRegistrationController {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistrationController.class);

    private final InstanceRegistry instances;
    private final ToolRegistry tools;
    private final RemoteToolExecutor remoteExecutor;

    /** 已告警的实例 → 告警时的回流地址；心跳周期很短，同一组合只提示一次 */
    private final Map<String, String> warnedEndpoints = new ConcurrentHashMap<>();

    public ToolRegistrationController(InstanceRegistry instances,
                                      ToolRegistry tools,
                                      RemoteToolExecutor remoteExecutor) {
        this.instances = instances;
        this.tools = tools;
        this.remoteExecutor = remoteExecutor;
    }

    /**
     * 整包注册 / 心跳
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) JsonNode body,
                                                        HttpServletRequest request) {
        ToolManifest.Parsed parsed;
        try {
            parsed = ToolManifest.parse(body, remoteExecutor);
        } catch (IllegalArgumentException e) {
            log.warn("[工具注册] 拒绝一次不合法的注册请求: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "reason", e.getMessage(),
                    "hint", "see docs/API.md §2.6"));
        }

        // "mcp:" 是 MCP 连接的保留 instanceId 前缀：外部工具实例不得占用，
        // 否则会在 ToolRegistry 的 byInstance 上与 MCP 连接撞键、互相覆盖工具。
        // MCP 自身盖章走的是 McpToolBridge → ToolRegistry.replaceInstanceTools，不经此入口，故不受影响。
        if (parsed.instanceId() != null && parsed.instanceId().startsWith("mcp:")) {
            log.warn("[工具注册] 拒绝占用 MCP 保留前缀的注册: {}", parsed.instanceId());
            return ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "reason", "instanceId 以 'mcp:' 开头是 MCP 连接的保留前缀，外部工具实例不可使用",
                    "hint", "see docs/API.md §2.6"));
        }

        warnIfEndpointUnreachable(parsed.instanceId(), parsed.endpoint(), request.getRemoteAddr());

        boolean accepted;
        try {
            accepted = instances.onHeartbeat(
                    parsed.instanceId(),
                    parsed.endpoint(),
                    parsed.digest(),
                    parsed.toolNames(),
                    () -> tools.replaceInstanceTools(parsed.instanceId(), parsed.endpoint(), parsed.tools()));
        } catch (IllegalArgumentException e) {
            // 例如与本地 Bean 工具同名：属实例侧 manifest 的问题，重发同样会被拒，因此回 400 而非 500
            log.warn("[工具注册] 实例 {} 的 manifest 被拒绝: {}", parsed.instanceId(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "reason", e.getMessage(),
                    "hint", "工具名与服务端本地工具冲突，请改名后重新心跳"));
        }

        if (!accepted) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "accepted", false,
                    "reason", "force_offline"));
        }
        return ResponseEntity.ok(Map.of(
                "accepted", true,
                "toolNames", List.copyOf(parsed.toolNames())));
    }

    /**
     * 提示"回流地址在服务端侧指向服务端自己"：注册是实例主动发起的，服务端不会立刻回调，
     * 所以地址写错不会在注册时报错，而是等到模型真的调用该工具时全部失败。
     * 这里用注册来源 IP 做一次一致性判断，把这个静默故障提前暴露出来。
     */
    private void warnIfEndpointUnreachable(String instanceId, String endpoint, String remoteAddr) {
        if (!isLoopbackHost(endpoint) || isLoopbackAddress(remoteAddr)) {
            warnedEndpoints.remove(instanceId);
            return;
        }
        if (endpoint.equals(warnedEndpoints.put(instanceId, endpoint))) {
            return;
        }
        log.warn("[工具注册] 实例 {} 上报的回流地址是 {}，但注册请求来自 {}："
                        + "该地址在服务端侧指向服务端自己，工具调用会全部失败。"
                        + "请把 domainplane.tool-instance.endpoint 改成服务端可达的地址"
                        + "（形如 http://10.0.0.5:8081/domainplane/invoke）",
                instanceId, endpoint, remoteAddr);
    }

    /**
     * 回流地址的主机是否是回环地址。只认字面量，不做 DNS 解析，
     * 这个判断在每次心跳的请求线程里执行，不能引入阻塞的网络调用。
     */
    private static boolean isLoopbackHost(String endpoint) {
        String host;
        try {
            host = URI.create(endpoint).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "localhost".equals(normalized) || "::1".equals(normalized) || normalized.startsWith("127.");
    }

    /** 注册请求的来源地址是否是回环地址（Servlet 容器给的一律是字面 IP） */
    private static boolean isLoopbackAddress(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        String normalized = remoteAddr.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("127.") || "::1".equals(normalized) || "0:0:0:0:0:0:0:1".equals(normalized);
    }
}
