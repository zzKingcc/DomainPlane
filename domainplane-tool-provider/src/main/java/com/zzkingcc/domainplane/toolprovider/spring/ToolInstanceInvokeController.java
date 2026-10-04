package com.zzkingcc.domainplane.toolprovider.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzkingcc.domainplane.toolprovider.ToolInstanceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具调用端点（服务端 → 本实例）
 *
 * @author zzkingcc
 */
@RestController
public class ToolInstanceInvokeController {

    private static final Logger log = LoggerFactory.getLogger(ToolInstanceInvokeController.class);

    /** 工具调用端点路径（与 {@code endpoint} 配置的路径部分一致） */
    public static final String INVOKE_PATH = "/domainplane/invoke";

    private final ToolInstanceClient client;

    public ToolInstanceInvokeController(ToolInstanceClient client) {
        this.client = client;
    }

    /**
     * 执行一次工具调用。
     */
    @PostMapping(INVOKE_PATH)
    public JsonNode invoke(@RequestBody(required = false) JsonNode request) {
        JsonNode response = client.invoke(request);
        if (!response.path("success").asBoolean(false)) {
            log.warn("[工具实例] 工具调用未成功: {}", response.path("error").asText(""));
        }
        return response;
    }
}
