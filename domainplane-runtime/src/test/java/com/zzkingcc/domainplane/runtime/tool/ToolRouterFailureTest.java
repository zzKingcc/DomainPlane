package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具执行失败时回喂给模型的文本。
 *
 * <p>回喂失败描述（而不是抛异常）是刻意的：模型需要知道"这一步没成"才能改口或换路，
 * 让整轮对话因一次工具失败崩掉对用户更糟。这条测试盯的是另一个问题 ——
 * 那段文本会经模型转述给终端用户，<b>不能带内部细节</b>。</p>
 */
class ToolRouterFailureTest {

    private final ToolRegistry registry = new ToolRegistry();

    private ToolRouter routerThrowing(RuntimeException failure) {
        ToolDescriptor d = new ToolDescriptor("closeOrder", "关单", "order", "1",
                null, true, true, List.of(), List.of("default"), null, "domainplane");
        ToolSpecification spec = ToolSpecification.builder().name("closeOrder").description("关单").build();
        ToolExecutor boom = (request, context) -> {
            throw failure;
        };
        registry.register(new ToolRegistry.Registered(d, spec, boom, List.of()));
        return new ToolRouter(registry);
    }

    private static String run(ToolRouter router) {
        return router.execute(ToolExecutionRequest.builder()
                .name("closeOrder")
                .arguments("{\"orderNo\":\"FR1\"}")
                .build());
    }

    @Test
    @DisplayName("失败回喂模型，但不泄漏 SQL、连接串、凭据与磁盘路径")
    void failureTextLeaksNothingInternal() {
        String out = run(routerThrowing(new IllegalStateException(
                "Connection refused to jdbc:postgresql://10.0.3.7:5432/orders?user=admin&password=hunter2"
                        + " at /opt/domainplane/server/lib/orders-dao.jar")));

        assertTrue(out.contains("closeOrder"), "仍要说明是哪个工具失败");
        assertFalse(out.contains("hunter2"), "凭据不得进入对话");
        assertFalse(out.contains("10.0.3.7"), "内网地址不得进入对话");
        assertFalse(out.contains("orders-dao.jar"), "磁盘路径不得进入对话");
        assertFalse(out.toLowerCase().contains("password="), "连接串不得原样进入对话");
    }

    @Test
    @DisplayName("失败文本明确指示模型不要编造结果")
    void failureTextTellsModelNotToFabricate() {
        String out = run(routerThrowing(new RuntimeException("订单不存在")));

        assertTrue(out.contains("不要编造"), "模型拿到失败后最危险的动作是自行编一个结果：" + out);
        assertTrue(out.contains("订单不存在"), "业务原因应保留，它对模型判断是否重试有用");
    }

    @Test
    @DisplayName("异常类型保留，消息为空时也不会崩")
    void failureTextSurvivesNullMessage() {
        String out = run(routerThrowing(new RuntimeException()));

        assertTrue(out.contains("RuntimeException"), "类型名让模型能区分参数错还是服务不可用");
        assertTrue(out.contains("closeOrder"));
    }

    @Test
    @DisplayName("超长异常消息被截断，不会把整段堆栈灌进上下文")
    void failureTextIsTruncated() {
        String out = run(routerThrowing(new RuntimeException("A".repeat(5_000))));

        assertTrue(out.length() < 300, "回喂文本长度失控会挤占上下文预算，实际 " + out.length());
    }

    @Test
    @DisplayName("工具不存在时用 ErrorCode 文案，不带内部细节")
    void unknownToolUsesErrorCodeText() {
        String out = new ToolRouter(new ToolRegistry()).execute(
                ToolExecutionRequest.builder().name("ghost").arguments("{}").build());

        assertEquals(ErrorCode.TOOL_NOT_FOUND.getMessage() + ": ghost", out,
                "文案须与 ErrorCode 同源，与 toolsNode 复核层拒绝时同一说法");
    }

    @Test
    @DisplayName("正常执行结果原样返回，不被脱敏逻辑影响")
    void successResultPassesThrough() {
        ToolDescriptor d = new ToolDescriptor("query", "查", "q", "1",
                null, true, false, List.of(), List.of("default"), null, "domainplane");
        ToolSpecification spec = ToolSpecification.builder().name("query").description("查").build();
        ToolExecutor ok = (request, context) -> "{\"path\":\"/opt/data/a.json\"}";
        registry.register(new ToolRegistry.Registered(d, spec, ok, List.of()));

        String out = new ToolRouter(registry).execute(
                ToolExecutionRequest.builder().name("query").arguments("{}").build());

        assertEquals("{\"path\":\"/opt/data/a.json\"}", out,
                "成功结果绝不脱敏 —— 那会破坏工具语义");
    }
}