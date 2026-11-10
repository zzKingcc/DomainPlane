package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管控台改内置工具说明的落点：只有本地 Bean 工具可替换，且 descriptor / specification
 * 必须一起换。这几条同时钉住「心跳不会把覆盖冲掉」这个隐式保证。
 */
class ToolRegistryLocalTextTest {

    private final ToolRegistry registry = new ToolRegistry();
    private static final ToolExecutor NOOP = (request, context) -> "ok";

    private static ToolDescriptor.Param param(String name, String desc) {
        return new ToolDescriptor.Param(name, "string", desc, true, List.of(), "", false);
    }

    private static ToolDescriptor descriptor(String name, String desc, String paramDesc) {
        return new ToolDescriptor(name, desc, "cat", "1", Tool.Effect.READ, true, true,
                List.of(param("q", paramDesc)), List.of("default"),
                ToolDescriptor.Approval.none(), "Bean#method");
    }

    private static ToolSpecification specification(String name, String desc, String paramDesc) {
        return ToolSpecification.builder()
                .name(name)
                .description(desc)
                .parameters(ParamSchemaRenderer.toJsonSchema(List.of(param("q", paramDesc))))
                .build();
    }

    private static ToolRegistry.Registered local(String name) {
        return new ToolRegistry.Registered(descriptor(name, "原说明", "原参数说明"),
                specification(name, "原说明", "原参数说明"), NOOP, List.of());
    }

    private static ToolRegistry.Registered remote(String name) {
        return new ToolRegistry.Registered(descriptor(name, "远程说明", "远程参数说明"),
                specification(name, "远程说明", "远程参数说明"), NOOP,
                List.of(new InstanceEndpoint("inst-1", "http://127.0.0.1:9000/invoke")));
    }

    @Test
    @DisplayName("本地工具：descriptor 与 specification 一起换，执行链不动")
    void replacesBothFaces() {
        registry.register(local("inner.search"));
        ToolExecutor before = registry.find("inner.search").orElseThrow().executor();
        List<InstanceEndpoint> endpoints = registry.find("inner.search").orElseThrow().endpoints();

        boolean ok = registry.replaceLocalText("inner.search",
                descriptor("inner.search", "新说明", "新参数说明"),
                specification("inner.search", "新说明", "新参数说明"));

        assertTrue(ok);
        ToolRegistry.Registered after = registry.find("inner.search").orElseThrow();
        assertEquals("新说明", after.descriptor().description());
        assertEquals("新说明", after.specification().description());
        assertEquals("新参数说明", after.descriptor().params().get(0).description());
        assertSame(before, after.executor(), "执行器不能被换掉");
        assertEquals(endpoints, after.endpoints(), "副本地址不能被换掉");
        assertEquals(List.of("default"), after.descriptor().domains());
    }

    @Test
    @DisplayName("远程工具拒绝替换，原条目一个字都不变")
    void refusesRemote() {
        registry.register(remote("remote.echo"));

        boolean ok = registry.replaceLocalText("remote.echo",
                descriptor("remote.echo", "新说明", "新参数说明"),
                specification("remote.echo", "新说明", "新参数说明"));

        assertFalse(ok);
        assertEquals("远程说明", registry.find("remote.echo").orElseThrow().descriptor().description());
        assertEquals("远程说明", registry.find("remote.echo").orElseThrow().specification().description());
    }

    @Test
    @DisplayName("工具不存在时返回 false，工具名不一致时拒绝")
    void missingAndMismatch() {
        registry.register(local("inner.search"));
        assertFalse(registry.replaceLocalText("nope",
                descriptor("nope", "x", "y"), specification("nope", "x", "y")));
        assertThrows(IllegalArgumentException.class, () -> registry.replaceLocalText("inner.search",
                descriptor("other", "x", "y"), specification("inner.search", "x", "y")));
    }

    @Test
    @DisplayName("declareDomains 重建 descriptor 后覆盖仍在")
    void survivesDeclareDomains() {
        registry.register(local("inner.search"));
        registry.replaceLocalText("inner.search",
                descriptor("inner.search", "新说明", "新参数说明"),
                specification("inner.search", "新说明", "新参数说明"));

        assertTrue(registry.declareDomains("inner.search", List.of("default.sales")));

        ToolRegistry.Registered after = registry.find("inner.search").orElseThrow();
        assertEquals("新说明", after.descriptor().description());
        assertEquals("新说明", after.specification().description());
        assertEquals(List.of("default.sales"), after.descriptor().domains());
    }

    @Test
    @DisplayName("心跳重建远程副本不会碰到内置工具的覆盖")
    void survivesHeartbeat() {
        registry.register(local("inner.search"));
        registry.replaceLocalText("inner.search",
                descriptor("inner.search", "新说明", "新参数说明"),
                specification("inner.search", "新说明", "新参数说明"));

        registry.replaceInstanceTools("inst-1", "http://127.0.0.1:9000/invoke",
                List.of(remote("remote.echo")));

        assertEquals("新说明", registry.find("inner.search").orElseThrow().descriptor().description());
        assertEquals("新说明", registry.find("inner.search").orElseThrow().specification().description());
    }
}
