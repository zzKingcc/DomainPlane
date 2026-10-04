package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.server.ServerInfo;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存活探测端点 {@code GET /health}（免鉴权）
 * @author zzkingcc
 */
@RestController
public class HealthController {

    private final ServerInfo serverInfo;

    public HealthController(ServerInfo serverInfo) {
        this.serverInfo = serverInfo;
    }

    /**
     * 返回服务名与版本，形状与需要凭证的 {@code /api/agent/health} 保持一致，
     * 接入方不必为两种探测各写一套解析；版本取自 {@link ServerInfo}，两处不会漂移。
     */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("status", "UP");
        body.put("service", "domainplane-server");
        body.put("version", serverInfo.version());
        return body;
    }
}
