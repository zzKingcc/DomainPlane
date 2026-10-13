package com.zzkingcc.domainplane.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * manifest 解析：域字符串来自<b>外部报文</b>，非法必须整包拒绝。
 *
 * <p>以前只 warn 放过，结果这个字符串照样进"已声明域"集合、让入口放行：
 * 等于任何能调用注册接口的人都能凭空造出一个可用的域。</p>
 */
class ToolManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ToolExecutor NOOP = (request, memoryId) -> "";

    private static JsonNode manifestWithDomains(String domainsJson) {
        String json = "{\"instanceId\":\"ins-1\",\"endpoint\":\"http://10.0.0.5:8081/domainplane/invoke\","
                + "\"manifest\":[{\"name\":\"queryOrder\",\"description\":\"查订单\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{}},"
                + "\"domains\":" + domainsJson + "}]}";
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void illegalDomainRejectsTheWholeManifest() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ToolManifest.parse(manifestWithDomains("[\"sales\"]"), NOOP));

        assertTrue(ex.getMessage().contains("sales"), "要指出是哪个域不合法：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("default"), "要说明正确写法");
    }

    @Test
    void wildcardAndBlankSegmentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolManifest.parse(manifestWithDomains("[\"*\"]"), NOOP));
        assertThrows(IllegalArgumentException.class,
                () -> ToolManifest.parse(manifestWithDomains("[\"default.a..b\"]"), NOOP));
    }

    @Test
    void fullPathsAreAccepted() {
        ToolManifest.Parsed parsed = ToolManifest.parse(
                manifestWithDomains("[\"default.sales\", \"default.sales.order\"]"), NOOP);

        assertEquals(1, parsed.tools().size());
        assertEquals("queryOrder", parsed.tools().get(0).descriptor().name());
        assertEquals(2, parsed.tools().get(0).descriptor().domains().size());
    }

    @Test
    void emptyDomainsMeansRootDomain() {
        ToolManifest.Parsed parsed = ToolManifest.parse(manifestWithDomains("[]"), NOOP);

        ToolRegistry.Registered registered = parsed.tools().get(0);
        assertTrue(registered.descriptor().domains().isEmpty(), "留空不在这里回填根域");
        assertTrue(registered.descriptor().declaredDomains().contains("default"),
                "由 declaredDomains() 统一解释为挂根域");
    }
}
