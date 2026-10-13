package com.zzkingcc.domainplane.toolprovider.spring;

import com.zzkingcc.domainplane.toolprovider.ToolInstanceClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具实例的连接状态快照。
 *
 * 心跳失败从不阻断启动（见 {@link ToolInstanceClient} 的退避重试），
 * 所以"进程活着但工具没注册上"这件事只能靠状态区分：这个 Bean 就是那个出口：
 * 接入方注入它接到自己的健康检查端点或 Actuator 上即可。
 *
 * 刻意不实现 Actuator 的 {@code HealthIndicator}：那会给本 SDK 引入
 * spring-boot-starter-actuator 依赖，而本模块的约定是零可选依赖。
 * 接入方已有 Actuator 时，用 {@link #asMap()} 自行桥接即可（字段名固定，稳定）。
 */
public class ToolInstanceConnectionStatus {

    private final ToolInstanceClient client;

    public ToolInstanceConnectionStatus(ToolInstanceClient client) {
        this.client = client;
    }

    /** 最近一次心跳是否成功过（从未成功过且当前无失败记录时视为就绪） */
    public boolean isRegistered() {
        return client.isRegistered();
    }

    /** 已被服务端强制下线：需人工介入，进程再等也不会自己好 */
    public boolean isForcedOffline() {
        return client.isForcedOffline();
    }

    /** 最近一次失败原因；成功后清空 */
    public String lastError() {
        return client.lastError();
    }

    /** 当前是否处于"进程活着但工具不可用"的状态 */
    public boolean isDegraded() {
        return !client.isRegistered();
    }

    /**
     * 状态快照（字段名固定，便于接入方映射成自己的健康响应体）。
     */
    public Map<String, Object> asMap() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("registered", isRegistered());
        status.put("degraded", isDegraded());
        status.put("forcedOffline", isForcedOffline());
        status.put("lastError", lastError() == null ? "" : lastError());
        status.put("hint", hint());
        return status;
    }

    private String hint() {
        if (isForcedOffline()) {
            return "服务端已强制下线该实例，需人工介入恢复";
        }
        if (isDegraded()) {
            return "心跳未成功，正在退避重试；服务端就绪后会自动恢复";
        }
        return "";
    }
}
