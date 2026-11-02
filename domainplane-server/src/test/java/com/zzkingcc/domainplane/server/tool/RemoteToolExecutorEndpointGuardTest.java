package com.zzkingcc.domainplane.server.tool;

import com.sun.net.httpserver.HttpServer;
import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.runtime.tool.InstanceEndpoint;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S-02：远程工具调用的两道收口。
 *
 * <p>① 回流地址在<b>调用期</b>再校验一次（注册表里的地址来自心跳报文，不能假设它过了注册期那道闸）；
 * ② 失败响应体<b>不回喂调用方</b>——这条请求的目标由上报方指定，回显响应体等于给对方一个
 * "读内网页面"的通道，而这正是 SSRF 的收益端。响应体仍然进服务端日志。</p>
 *
 * @author zzkingcc
 */
@DisplayName("RemoteToolExecutor：回流地址校验 + 失败响应体不回显")
class RemoteToolExecutorEndpointGuardTest {

    /** 故意放在响应体里的标记：它绝不能出现在工具结果里 */
    private static final String SECRET = "SECRET-INTERNAL-PAGE-BODY";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/invoke", exchange -> {
            byte[] body = SECRET.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/invoke";
    }

    /** 注册一个只有单个远程副本的工具，返回执行器 */
    private RemoteToolExecutor executorFor(String endpoint, String toolName) {
        ToolRegistry registry = new ToolRegistry();
        RemoteToolExecutor executor = new RemoteToolExecutor(registry, 5000, 1);
        ToolDescriptor descriptor = new ToolDescriptor(
                toolName, "测试用远程工具", "default", "1.0.0",
                Tool.Effect.READ, true, true, List.of(), List.of("default"),
                ToolDescriptor.Approval.none(), "remote://inst-1@" + endpoint);
        ToolSpecification specification = ToolSpecification.builder()
                .name(toolName)
                .description("测试用远程工具")
                .parameters(JsonObjectSchema.builder().build())
                .build();
        registry.registerAll(List.of(new ToolRegistry.Registered(
                descriptor, specification, executor, List.of(new InstanceEndpoint("inst-1", endpoint)))));
        return executor;
    }

    private static String call(RemoteToolExecutor executor, String toolName) {
        return executor.execute(ToolExecutionRequest.builder()
                .name(toolName)
                .arguments("{}")
                .build(), "session-1");
    }

    @Test
    @DisplayName("实例返回 500 时：只回状态码，响应体不进工具结果")
    void httpFailureBodyNotEchoed() throws Exception {
        String endpoint = startServer(500);
        RemoteToolExecutor executor = executorFor(endpoint, "boom_tool");

        String result = call(executor, "boom_tool");

        assertFalse(result.contains(SECRET), "响应体不得回传给调用方，实际：" + result);
        assertTrue(result.contains("500"), "状态码要保留，运维才有的查，实际：" + result);
    }

    @Test
    @DisplayName("实例返回非 JSON 时：同样不回显内容，只回 Content-Type")
    void nonJsonBodyNotEchoed() throws Exception {
        String endpoint = startServer(200);
        RemoteToolExecutor executor = executorFor(endpoint, "html_tool");

        String result = call(executor, "html_tool");

        assertFalse(result.contains(SECRET), "非 JSON 响应体同样可能承载内网页面，实际：" + result);
        assertTrue(result.contains("text/html"), "要带上 Content-Type 便于排障，实际：" + result);
    }

    @Test
    @DisplayName("调用期再校验：注册表里被塞进元数据地址也发不出去")
    void invokeTimePolicyBlocksMetadataAddress() {
        RemoteToolExecutor executor = executorFor("http://169.254.169.254/latest/meta-data/", "imds_tool");

        String result = call(executor, "imds_tool");

        assertTrue(result.contains("回流地址不可用"), "应被策略拦下，实际：" + result);
        assertTrue(result.contains("链路本地") || result.contains("云元数据"), "原因要可读，实际：" + result);
    }

    @Test
    @DisplayName("调用期再校验：非 http/https 的伪地址同样发不出去")
    void invokeTimePolicyBlocksNonHttpScheme() {
        RemoteToolExecutor executor = executorFor("file:///etc/passwd", "file_tool");

        String result = call(executor, "file_tool");

        assertTrue(result.contains("回流地址不可用"), "应被策略拦下，实际：" + result);
    }
}
