package com.zzkingcc.domainplane.runtime.domain;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域语义回归测试 —— 盯住几条最容易在后续改动里被"顺手改回去"的规则：
 * 域标识是从根域出发的完整路径、沿链补齐不留悬空、删除递归带走子孙、根域不可删、
 * 三种来源同级（只有根域因是树起点而例外）。
 *
 * @author zzkingcc
 */
class DomainSemanticsTest {

    /** 构造一个只关心域声明的工具描述符 */
    private static ToolDescriptor toolWithDomains(String... domains) {
        return new ToolDescriptor("demoTool", "演示工具", "default", "1.0.0",
                Tool.Effect.READ, true, true,
                List.of(), List.of(domains), null, "test#demoTool");
    }

    @Test
    void normalizeFallsBackToDefaultDomain() {
        assertEquals(Domains.DEFAULT, Domains.normalize(null));
        assertEquals(Domains.DEFAULT, Domains.normalize("   "));
        assertEquals("default.sales", Domains.normalize(" default.sales "));
        assertTrue(Domains.isDefault(null));
        assertFalse(Domains.isDefault("default.sales"));
        assertEquals("default", Domains.DEFAULT);
    }

    // ===== 工具可见性：累加语义（命中自身或任一祖先即见） =====

    @Test
    void emptyDeclarationMountsOnRootAndIsVisibleEverywhere() {
        ToolDescriptor tool = toolWithDomains();
        assertTrue(tool.visibleIn(Domains.DEFAULT));
        assertTrue(tool.visibleIn(null), "未指定域归一化为根域");
        assertTrue(tool.visibleIn("  "));
        // 根域在每个域的祖先链里，因此挂根 = 对全树可见
        assertTrue(tool.visibleIn("default.sales"));
        assertTrue(tool.visibleIn("default.sales.order"), "留空即挂根，累加后子域可见");
        assertEquals(List.of(Domains.DEFAULT), tool.declaredDomains());
    }

    @Test
    void declarationOnAParentIsInheritedByDescendants() {
        ToolDescriptor tool = toolWithDomains("default.sales");
        assertTrue(tool.visibleIn("default.sales"));
        assertTrue(tool.visibleIn("default.sales.order"), "挂父域，子域默认可用");
        assertTrue(tool.visibleIn("default.sales.order.refund"), "任意深度的后代都继承");
        assertFalse(tool.visibleIn("default.hr"), "旁支不继承");
        assertFalse(tool.visibleIn(Domains.DEFAULT), "挂在子域的工具不会反向对根域可见");
    }

    @Test
    void multipleDeclarationsCoverSeveralBranches() {
        ToolDescriptor tool = toolWithDomains("default.sales", "default.hr");
        assertTrue(tool.visibleIn("default.sales"));
        assertTrue(tool.visibleIn("default.hr"));
        assertTrue(tool.visibleIn("default.hr.payroll"));
        assertFalse(tool.visibleIn("default.finance"), "未声明的分支不可见");
    }

    @Test
    void wildcardIsNoLongerADomain() {
        ToolDescriptor tool = toolWithDomains("*");
        // "*" 不是合法域路径，不在任何域的祖先链上，因此处处不可见 —— 不再有通配语义
        assertFalse(tool.visibleIn(Domains.DEFAULT));
        assertFalse(tool.visibleIn("default.sales"));
    }

    // ==================== 路径运算 ====================

    @Test
    void pathChainIsSplitFromRoot() {
        assertEquals(List.of("default", "default.a", "default.a.b"), Domains.chainOf("default.a.b"));
        assertEquals(List.of("default"), Domains.chainOf("default"));
        assertEquals(List.of(), Domains.chainOf(null));
    }

    @Test
    void ancestorsExcludeSelf() {
        assertEquals(List.of("default", "default.a"), Domains.ancestorsOf("default.a.b"));
        assertEquals(List.of(), Domains.ancestorsOf("default"));
    }

    @Test
    void parentOfIsTheNearestAncestor() {
        assertEquals("default.a", Domains.parentOf("default.a.b"));
        assertNull(Domains.parentOf("default"), "根域没有父");
        assertNull(Domains.parentOf(null));
    }

    // ==================== 路径校验 ====================

    @Test
    void pathMustStartFromRoot() {
        assertNotNull(Domains.validatePath("sales"), "不从根出发的域必须被拒");
        assertNotNull(Domains.validatePath("other.sales"));
        assertNull(Domains.validatePath("default.sales"));
    }

    @Test
    void pathRejectsIllegalSegment() {
        assertNotNull(Domains.validatePath("default.sales.order!"), "段含非法字符");
        assertNotNull(Domains.validatePath("default.sa les"), "段含空白");
        assertNotNull(Domains.validatePath("default..sales"), "空段");
        assertNotNull(Domains.validatePath("default."), "尾部分隔符");
        assertNotNull(Domains.validatePath("*"), "通配符不是合法域路径");
    }

    @Test
    void pathRejectsDuplicatedSegmentOnItsOwnChain() {
        assertNotNull(Domains.validatePath("default.a.a"), "自身链上重复段");
        assertNotNull(Domains.validatePath("default.a.b.a"));
        // 同名不同父是允许的：两段之间隔着别的节点，不构成链上重复
        assertNull(Domains.validatePath("default.a.x"));
        assertNull(Domains.validatePath("default.b.x"));
    }

    // ==================== 注册表 ====================

    @Test
    void defaultDomainIsRootAndProtected() {
        DomainRegistry registry = new DomainRegistry();

        assertTrue(registry.isBuiltin(Domains.DEFAULT), "default 必须是根域");
        assertTrue(registry.contains(Domains.DEFAULT));
        assertFalse(registry.delete(Domains.DEFAULT).deleted(), "根域不可删除");
        assertFalse(registry.create(Domains.DEFAULT).created(), "根域不可重复创建");
        assertFalse(registry.create("*").created(), "通配符不是合法域路径");
        assertFalse(registry.create("  ").created(), "空白域名应被拒");
    }

    @Test
    void creatingADomainLinksTheWholeChain() {
        DomainRegistry registry = new DomainRegistry();

        assertTrue(registry.create("default.sales.order").created());
        // 中间节点必须一并存在，否则这就是个悬空节点
        assertTrue(registry.contains("default.sales"));
        assertTrue(registry.contains("default.sales.order"));
        assertEquals("default.sales", registry.all().stream()
                .filter(d -> "default.sales.order".equals(d.id()))
                .findFirst().orElseThrow().parentId());
        assertNull(registry.all().stream()
                .filter(d -> "default".equals(d.id()))
                .findFirst().orElseThrow().parentId(), "根域没有父");
    }

    @Test
    void manualDomainsCanBeCreatedAndDeleted() {
        DomainRegistry registry = new DomainRegistry();

        assertTrue(registry.create("default.customer-service").created());
        assertTrue(registry.contains("default.customer-service"));
        assertTrue(registry.manualIds().contains("default.customer-service"));
        assertFalse(registry.create("default.customer-service").created(), "重复创建应失败");

        assertTrue(registry.delete("default.customer-service").deleted());
        assertFalse(registry.contains("default.customer-service"));
        assertTrue(registry.manualIds().isEmpty());
    }

    @Test
    void deletingADomainTakesAllDescendants() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.sales.order");
        registry.create("default.sales.order.refund");
        registry.create("default.hr");

        assertEquals(List.of("default.sales.order.refund"), registry.descendantsOf("default.sales.order"));
        assertEquals(2, registry.descendantsOf("default.sales").size());

        var deleted = registry.delete("default.sales");
        assertTrue(deleted.deleted());
        // 递归带走子孙，而不是把子孙提升到根下
        assertFalse(registry.contains("default.sales"));
        assertFalse(registry.contains("default.sales.order"));
        assertFalse(registry.contains("default.sales.order.refund"));
        assertTrue(registry.contains("default.hr"), "旁支不受影响");
        assertEquals(List.of("default.sales", "default.sales.order", "default.sales.order.refund"),
                deleted.removed());
    }

    @Test
    void derivedAndManualSourcesAreEqualInDeleteRules() {
        DomainRegistry registry = new DomainRegistry();
        registry.ensureChain("default.fromTool");
        registry.create("default.fromConsole");

        assertEquals(DomainRegistry.Source.DERIVED, registry.sourceOf("default.fromTool"));
        assertEquals(DomainRegistry.Source.MANUAL, registry.sourceOf("default.fromConsole"));
        // 来源只标记出身，不构成等级：派生域同样可删
        assertTrue(registry.delete("default.fromTool").deleted());
        assertTrue(registry.delete("default.fromConsole").deleted());
    }

    @Test
    void existingNodesKeepTheirSourceWhenRelinked() {
        DomainRegistry registry = new DomainRegistry();
        registry.ensureChain("default.sales");
        assertEquals(DomainRegistry.Source.DERIVED, registry.sourceOf("default.sales"));

        // 管控台复用同一条链时，已存在节点不该被改写成 MANUAL
        registry.create("default.sales.order");
        assertEquals(DomainRegistry.Source.DERIVED, registry.sourceOf("default.sales"));
        assertEquals(DomainRegistry.Source.MANUAL, registry.sourceOf("default.sales.order"));
    }

    @Test
    void illegalPersistedDomainsAreSkipped() {
        DomainRegistry registry = new DomainRegistry();
        Map<String, Boolean> stored = new LinkedHashMap<>();
        stored.put("*", true);
        stored.put("legacy-short-name", true);
        stored.put("default.kept", true);
        registry.loadManual(stored);

        assertFalse(registry.contains("*"), "落盘里的通配符不应被当成域");
        assertFalse(registry.contains("legacy-short-name"), "非完整路径的存量域应被跳过");
        assertTrue(registry.contains("default.kept"));
    }

    // ==================== 可调用单元 ====================

    @Test
    void onlyExplicitlyDeclaredDomainsAreCallable() {
        DomainRegistry registry = new DomainRegistry();

        // 根域恒可调用：不传域会被归一化到它
        assertTrue(registry.isCallable(Domains.DEFAULT));

        registry.create("default.sales.order");

        assertTrue(registry.isCallable("default.sales.order"), "被显式创建的域是可调用单元");
        assertFalse(registry.isCallable("default.sales"), "沿链补齐的祖先只做装配");
    }

    @Test
    void toolDeclaredDomainIsCallableButItsAncestorsAreNot() {
        DomainRegistry registry = new DomainRegistry();

        registry.ensureChain("default.sales.order");

        assertTrue(registry.isCallable("default.sales.order"), "工具显式声明的那个域可以当入口");
        assertFalse(registry.isCallable("default.sales"), "派生出来的祖先只做装配");
    }

    @Test
    void callableSwitchDoesNotPropagate() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.sales.order");

        assertTrue(registry.setCallable("default.sales.order", false).changed());
        assertFalse(registry.isCallable("default.sales.order"));
        assertFalse(registry.setCallable("default.ghost", false).changed(), "域不存在时切换失败");
    }

    @Test
    void 有子域的域降级为装配节点() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.sales");

        assertTrue(registry.isCallable("default.sales"), "建它时它还是叶子，可调用");

        // 给它加一个子域 —— 它立刻不再是叶子，必须降级为装配节点
        registry.ensureChain("default.sales.order");

        assertFalse(registry.isCallable("default.sales"),
                "有了子域就不再是叶子，不能再当入口");
        assertTrue(registry.isCallable("default.sales.order"), "新叶子可调用");
        assertEquals(Set.of("default.sales.order"), registry.callableIds(),
                "可调用集只含叶子域 —— 根域此刻也有了子域，同样不在其中");
    }

    @Test
    void 根域只在它是叶子时可调用() {
        DomainRegistry registry = new DomainRegistry();

        assertTrue(registry.isCallable(Domains.DEFAULT), "整棵树只有根域时，它就是叶子，可调用");

        registry.create("default.sales");

        assertFalse(registry.isCallable(Domains.DEFAULT),
                "根域一旦有了子域，同样降级为装配节点：叶子规则对它没有豁免");
        assertTrue(registry.isCallable("default.sales"));
    }

    @Test
    void 已有子域的域不能再被设为可调用() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.sales.order");

        DomainRegistry.CallableResult result = registry.setCallable("default.sales", true);

        assertFalse(result.changed(), "有子域的域不能作为可调用单元");
        assertTrue(result.reason().contains("只有叶子域"),
                "拒绝理由要说清规则，实际: " + result.reason());
        assertFalse(registry.isCallable("default.sales"));
    }

    @Test
    void 标记是真的也拦不住派生判据() {
        // 即使底层标记被设成 true（历史落盘值 / 工具声明派生），
        // 只要它已有子域，生效判据就必须是"不可调用"
        DomainRegistry registry = new DomainRegistry();
        registry.ensureChain("default.a.b");

        assertEquals(Set.of("default.a.b"), registry.callableIds(),
                "沿链补齐的祖先即使标记为真也不得出现在可调用集里");
    }

    @Test
    void childrenAndDescendantsAreDifferentQuestions() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.sales.order.refund");

        assertEquals(List.of("default.sales"), registry.childrenOf(Domains.DEFAULT));
        assertEquals(List.of("default.sales.order"), registry.childrenOf("default.sales"));
        assertTrue(registry.hasChildren("default.sales"));
        assertFalse(registry.hasChildren("default.sales.order.refund"));
        assertEquals(2, registry.descendantsOf("default.sales").size(), "后代含更深层级");
    }

    @Test
    void manualConfigOnlyKeepsExplicitDecisions() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.sales.order");
        registry.ensureChain("default.fromTool");

        Map<String, Boolean> config = registry.manualConfig();

        assertTrue(config.containsKey("default.sales.order"), "显式创建的域要落盘");
        assertFalse(config.containsKey("default.sales"), "沿链补齐的祖先不落盘");
        assertFalse(config.containsKey("default.fromTool"), "派生域不落盘，重启后由工具声明重建");
        assertTrue(config.get("default.sales.order"));
    }

    @Test
    void deletingADomainForgetsItsCallableDeclaration() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.sales");
        assertEquals(1, registry.manualConfig().size());

        registry.delete("default.sales");

        assertFalse(registry.manualConfig().containsKey("default.sales"));
    }
}
