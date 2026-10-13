package com.zzkingcc.domainplane.toolprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /domainplane/invoke} 的入参容错。
 *
 * <p>controller 是 {@code @RequestBody(required = false)}，请求体可以整个缺失，
 * 此时必须回一个协议化的失败响应，而不是抛 NPE 让容器吐 500 + 堆栈：
 * 这个端点按设计是不鉴权的，任何人都能匿名 POST 一个空 body。</p>
 */
class ToolInstanceInvokePayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolInstanceClient clientWithTool() {
        ToolInstanceConfig config = ToolInstanceConfig.of(
                "127.0.0.1", 9527, "domainplane", "domainplane", "i1",
                "http://127.0.0.1:8081/domainplane/invoke");
        ToolInstanceClient client = new ToolInstanceClient(config);
        client.register(ToolSpec.of("queryOrder", "查订单"), args -> "已发货");
        return client;
    }

    @Test
    void 空请求体不会抛空指针() {
        JsonNode response = clientWithTool().invoke(null);

        assertNotNull(response, "必须回一个响应，而不是让异常逃出去");
        assertFalse(response.path("success").asBoolean(true),
                "空请求体不是一次成功的调用");
        assertEquals("", response.path("requestId").asText(null), "空请求体没有 requestId");
        assertFalse(response.path("retryable").asBoolean(true),
                "空请求体重试多少次都一样，不该被标成可重试");
    }

    @Test
    void arguments字段缺失时按空参数正常执行() {
        ObjectNode request = MAPPER.createObjectNode().put("toolName", "queryOrder");

        JsonNode response = clientWithTool().invoke(request);

        assertTrue(response.path("success").asBoolean(false),
                "工具名有效、参数缺失按空参处理，应执行成功；实际: " + response);
    }

    @Test
    void arguments为显式null时同样按空参数正常执行() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("toolName", "queryOrder");
        request.putNull("arguments");

        assertTrue(clientWithTool().invoke(request).path("success").asBoolean(false));
    }

    @Test
    void 工具名缺失时按未注册处理而不是崩() {
        ObjectNode request = MAPPER.createObjectNode().put("arguments", "{}");

        JsonNode response = clientWithTool().invoke(request);

        assertFalse(response.path("success").asBoolean(true));
        assertTrue(response.path("error").asText("").contains("未在本实例注册"));
    }
}