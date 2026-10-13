package com.zzkingcc.domainplane.server.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「域 → 模型」绑定沿链回落：自身没绑就向上找最近一个绑了模型的祖先。
 *
 * <p>回落的是<b>域链</b>，不是任何内置默认值：根域也没绑就是真的没有。</p>
 */
class ModelBindingChainTest {

    /**
     * 按旧口径（一个域一个列表 = 主模型）造设置。
     *
     * <p>用例写的是"某域绑了哪几个别名"，不关心族；这里统一折成 {@code chat} 族，
     * 让断言继续读 {@code resolveAlong}（那本来就是主模型的解析入口）。</p>
     */
    private static ModelProfileSettings settings(Map<String, List<String>> bindings) {
        ModelProfileSettings settings = new ModelProfileSettings();
        Map<String, ModelProfileSettings.DomainModels> converted = new LinkedHashMap<>();
        bindings.forEach((domain, aliases) ->
                converted.put(domain, ModelProfileSettings.DomainModels.ofChat(aliases)));
        settings.setDomainBindings(converted);
        return settings;
    }

    @Test
    void 自身绑定优先于祖先() {
        ModelProfileSettings s = settings(Map.of(
                "default", List.of("root-model"),
                "default.sales", List.of("sales-model")));

        assertEquals(List.of("sales-model"), s.resolveAlong("default.sales").aliases());
        assertEquals("default.sales", s.resolveAlong("default.sales").sourceDomain());
        // 子域自身没绑，继承最近的祖先
        assertEquals(List.of("sales-model"), s.resolveAlong("default.sales.order").aliases());
        assertEquals("default.sales", s.resolveAlong("default.sales.order").sourceDomain());
    }

    @Test
    void 跨多层向上继承() {
        ModelProfileSettings s = settings(Map.of("default", List.of("root-model")));

        var deep = s.resolveAlong("default.a.b.c");
        assertEquals(List.of("root-model"), deep.aliases());
        assertEquals("default", deep.sourceDomain(), "一路继承到根域");
    }

    @Test
    void 旁支不共享绑定() {
        ModelProfileSettings s = settings(Map.of("default.sales", List.of("sales-model")));

        assertTrue(s.resolveAlong("default.hr").empty(), "sales 的绑定不落到 hr 分支");
        assertNull(s.resolveAlong("default.hr").sourceDomain());
        // 更关键：祖先也拿不到后代的绑定
        assertTrue(s.resolveAlong("default").empty(), "绑定不会反向向上");
    }

    @Test
    void 整条链都没绑就是没有() {
        ModelProfileSettings s = settings(Map.of());

        assertTrue(s.resolveAlong("default").empty());
        assertTrue(s.resolveAlong("default.sales.order").empty());
        assertNull(s.resolveAlong("default.sales.order").sourceDomain());
    }

    @Test
    void 空列表等同未绑定() {
        ModelProfileSettings s = settings(Map.of("default", List.of(), "default.sales", List.of("m")));

        // 根域绑了个空列表：应视为未绑，继续往下找
        assertEquals(List.of("m"), s.resolveAlong("default.sales").aliases());
        assertTrue(s.resolveAlong("default.hr").empty(), "空列表不能当作有效的继承来源");
    }

    @Test
    void 未指定域归一化为根域() {
        ModelProfileSettings s = settings(Map.of("default", List.of("root-model")));

        assertEquals(List.of("root-model"), s.resolveAlong(null).aliases());
        assertEquals(List.of("root-model"), s.resolveAlong("  ").aliases());
    }
}
