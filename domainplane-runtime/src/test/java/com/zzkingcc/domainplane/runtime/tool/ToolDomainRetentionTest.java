package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域的存活：工具断开（熔断 / 判死摘副本）不该让域跟着消失，入口也不该把它判成不存在。
 */
class ToolDomainRetentionTest {

    private final ToolRegistry registry = new ToolRegistry();

    private static ToolRegistry.Registered tool(String name, String source, List<String> domains) {
        ToolDescriptor d = new ToolDescriptor(name, "desc", "cat", "1",
                null, true, true, List.of(), domains, null, source);
        ToolSpecification spec = ToolSpecification.builder().name(name).description("desc").build();
        ToolExecutor noop = (request, context) -> "ok";
        return new ToolRegistry.Registered(d, spec, noop, List.of());
    }

    /** 域一经声明就不再抹掉：副本全部摘除后，它仍在已知域里、入口仍受理 */
    @Test
    void knownDomains_surviveReplicaRemoval() {
        registry.register(tool("local_tool", "domainplane", List.of("default.ops")));
        registry.replaceInstanceTools("i1", "http://10.0.0.5:8081/invoke",
                List.of(tool("remote_tool", "remote://i1", List.of("default.finance"))));

        assertTrue(registry.containsDomain("default.finance"));

        registry.removeInstance("i1");
        assertTrue(registry.find("remote_tool").isEmpty(), "副本应已摘除");
        assertTrue(registry.knownDomains().contains("default.finance"), "域不应随工具断开而消失");
        assertTrue(registry.containsDomain("default.finance"), "域还可用，不能判 10004");
    }

    /** 见过域之后才开始拦：陌生域仍要被拒，这条 fail-fast 不能松 */
    @Test
    void unknownDomainStillRejectedOnceAnyDeclared() {
        registry.register(tool("local_tool", "domainplane", List.of("default.ops")));
        assertFalse(registry.containsDomain("default.ghost"));
    }

    /** 工具声明的域必须沿链派生进域树：只声明末节点会留下悬空节点 */
    @Test
    void declarationLinksTheWholeChainIntoDomainTree() {
        DomainRegistry domains = new DomainRegistry();
        ToolRegistry registry = new ToolRegistry(domains);
        registry.register(tool("deep_tool", "domainplane", List.of("default.sales.order")));

        assertTrue(domains.contains("default.sales"), "缺失的祖先必须一并建出");
        assertTrue(domains.contains("default.sales.order"));
        assertEquals(DomainRegistry.Source.DERIVED, domains.sourceOf("default.sales"));
    }

    /**
     * 显式删域时要把声明记录一并摘掉：否则被删的域会一直留在"域全集"里
     * （两个清单端点照旧列出它，但它已经不在域树里、也删不掉）。
     */
    @Test
    void forgetDomainsRemovesDeletedDomainsFromKnownSet() {
        registry.register(tool("local_tool", "domainplane", List.of("default.ops")));
        registry.replaceInstanceTools("i1", "http://10.0.0.5:8081/invoke",
                List.of(tool("remote_tool", "remote://i1", List.of("default.finance"))));

        // 工具下线后域仍在（这是有意的保留语义）
        registry.removeInstance("i1");
        assertTrue(registry.knownDomains().contains("default.finance"));

        // 显式删域则把它从保留集合里摘掉
        registry.forgetDomains(List.of("default.finance"));

        assertFalse(registry.knownDomains().contains("default.finance"), "被删的域不该再出现在域全集里");
        assertTrue(registry.knownDomains().contains("default.ops"), "其它域不受影响");
    }

    /**
     * 生产路径：<b>工具还活着</b>时删域。
     *
     * <p>{@code knownDomains()} 会从活着的工具声明里把域捞回来，所以只清 declaredDomains 是不够的 ——
     * 那会让被删的域继续留在「域空间」列表里，再点删除报"域不存在"，看着像没删干净。
     * 上一版只覆盖了"工具已下线"的路径，生产路径没测到。</p>
     */
    @Test
    void forgetDomainsDetachesDomainsFromLiveTools() {
        registry.register(tool("local_tool", "domainplane", List.of("default.ops")));
        registry.replaceInstanceTools("i1", "http://10.0.0.5:8081/invoke",
                List.of(tool("remote_tool", "remote://i1", List.of("default.finance", "default.ops"))));

        assertTrue(registry.knownDomains().contains("default.finance"), "前提：域当前可见");

        registry.forgetDomains(List.of("default.finance"));

        assertFalse(registry.knownDomains().contains("default.finance"),
                "工具还活着也不能把被删的域捞回来");
        assertTrue(registry.find("remote_tool").isPresent(), "工具本身不受影响");
        assertTrue(registry.find("remote_tool").get().descriptor().visibleIn("default.ops"),
                "工具的其他域声明不受影响");
        assertFalse(registry.find("remote_tool").get().descriptor().visibleIn("default.finance"),
                "删域等于撤销该域的授权边界");
    }

    /**
     * 回归：删域把工具的<b>全部</b>声明剥光时，绝不能让它回落成「挂在根域」。
     *
     * <p>回落即全树可见 —— 删域（意图：撤销授权）会变成放开授权，且工具越多、声明越集中的场景越明显。
     * 这条断言是本次修复的核心：之前只覆盖了「多声明删其一」，剥光路径没人测过。</p>
     */
    @Test
    void forgetDomainsDoesNotWidenToolWhenAllDomainsStripped() {
        registry.register(tool("payroll_tool", "domainplane", List.of("default.hr.payroll")));
        assertTrue(registry.find("payroll_tool").get().descriptor().visibleIn("default.hr.payroll"));

        // 递归删父域：affected = 自身 + 全部子孙，正好把唯一那条声明剥光
        registry.forgetDomains(List.of("default.hr", "default.hr.payroll"));

        ToolDescriptor descriptor = registry.find("payroll_tool").orElseThrow().descriptor();
        assertFalse(descriptor.visibleIn("default.sales"), "剥光后绝不能对无关域可见（授权放大）");
        assertFalse(descriptor.visibleIn("default"), "根域同样不可见");
        assertFalse(descriptor.visibleIn("default.hr"), "被删的域不可见");
        assertFalse(descriptor.visibleIn("default.hr.payroll"), "被删的子孙域不可见");
        assertTrue(registry.find("payroll_tool").isPresent(), "工具条目保留，只是不再对任何域可见");
    }

    /** 哨兵不是域：不能让它出现在「域空间」的域全集里 */
    @Test
    void revokedDomainNeverAppearsInknownDomains() {
        registry.register(tool("payroll_tool", "domainplane", List.of("default.hr.payroll")));

        registry.forgetDomains(List.of("default.hr.payroll"));

        assertFalse(registry.knownDomains().contains(ToolDescriptor.REVOKED_DOMAIN),
                "哨兵不能被当成一个真域");
        assertFalse(registry.containsDomain(ToolDescriptor.REVOKED_DOMAIN),
                "哨兵不能被当成一个可调用域");
    }

    /** 重新注册（作者改了代码）后必须能恢复：哨兵不能变成永久烙印 */
    @Test
    void reRegisteringAfterRevocationRestoresVisibility() {
        registry.register(tool("payroll_tool", "domainplane", List.of("default.hr.payroll")));
        registry.forgetDomains(List.of("default.hr.payroll"));
        assertFalse(registry.find("payroll_tool").orElseThrow().descriptor().visibleIn("default.hr"));

        // 模拟重启：同一实例再次整包上报，声明里带回了原来的域
        registry.replaceInstanceTools("i1", "http://10.0.0.5:8081/invoke", List.of());
        registry.register(tool("payroll_tool2", "domainplane", List.of("default.hr")));

        assertTrue(registry.find("payroll_tool2").orElseThrow().descriptor().visibleIn("default.hr"),
                "新声明不受哨兵影响");
    }
}
