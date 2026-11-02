package com.zzkingcc.domainplane.api.agent;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 AgentRequest #42：@JsonCreator 反序列化路径必须与 of() 同一口径，
 * domain 必填且去空白（全空白 / 缺失要抛 IllegalArgumentException）。
 *
 * <p>修复前反序列化路径漏了这一步，HTTP 入口能把全空白域带进编排层，
 * 而下游一律按"域已归一化"假设工作：契约必须在唯一的构造点上落地。</p>
 *
 * <p>全部走 Jackson ObjectMapper 的 JSON 反序列化路径（不调用 of()），确保测的是 HTTP 入口那条链路。</p>
 *
 * @author zzkingcc
 */
@DisplayName("AgentRequest：JSON 反序列化路径的 domain 校验")
class AgentRequestDomainTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("正常 JSON：domain 被 trim 后落地")
    void domainIsTrimmedOnDeserialize() throws Exception {
        String json = "{\"sessionId\":\"s-1\",\"message\":\"你好\",\"domain\":\"  finance  \"}";
        AgentRequest req = mapper.readValue(json, AgentRequest.class);
        assertEquals("finance", req.getDomain());
    }

    @Test
    @DisplayName("全空白 domain：反序列化抛 IllegalArgumentException")
    void blankDomainRejectedOnDeserialize() {
        String json = "{\"sessionId\":\"s-2\",\"message\":\"你好\",\"domain\":\"   \"}";
        // Jackson 会把构造器抛出的异常包成 JsonMappingException，原异常在 cause 里
        JsonMappingException ex = assertThrows(JsonMappingException.class,
                () -> mapper.readValue(json, AgentRequest.class));
        Throwable cause = ex.getCause();
        assertTrue(cause instanceof IllegalArgumentException,
                "cause 应为 IllegalArgumentException，实际: " + cause);
        assertTrue(cause.getMessage().contains("domain 不能为空"),
                "异常消息应说明 domain 不能为空，实际: " + cause.getMessage());
    }

    @Test
    @DisplayName("缺失 domain：反序列化抛 IllegalArgumentException")
    void missingDomainRejectedOnDeserialize() {
        String json = "{\"sessionId\":\"s-3\",\"message\":\"你好\"}";
        JsonMappingException ex = assertThrows(JsonMappingException.class,
                () -> mapper.readValue(json, AgentRequest.class));
        Throwable cause = ex.getCause();
        assertTrue(cause instanceof IllegalArgumentException,
                "cause 应为 IllegalArgumentException，实际: " + cause);
        assertTrue(cause.getMessage().contains("domain 不能为空"),
                "异常消息应说明 domain 不能为空，实际: " + cause.getMessage());
    }

    @Test
    @DisplayName("合法 domain 正常反序列化（回归基线）")
    void validRequestDeserializes() throws Exception {
        String json = "{\"sessionId\":\"s-4\",\"message\":\"查订单\",\"domain\":\"order\"}";
        AgentRequest req = mapper.readValue(json, AgentRequest.class);
        assertEquals("s-4", req.getSessionId());
        assertEquals("order", req.getDomain());
        assertEquals("查订单", req.getMessage());
    }
}
