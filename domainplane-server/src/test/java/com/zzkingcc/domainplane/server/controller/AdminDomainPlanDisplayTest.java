package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.settings.DomainSettings;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「是否展示执行计划」开关的保存口（PUT /admin/domains/{id}/plan-display）。
 *
 * 口径与 {@code AdminDomainToolCallEchoTest} 同构（两颗开关是一对，语义边界一致）：
 *   • 拨到开 / 关都落盘，只动 planDisplay 一个域的一个键；
 *   • 只对可调用单元（叶子域）受理：装配节点不能作为入口，设了也永远读不到；
 *   • 域不存在 / 请求体缺失一律拒绝。
 *
 * 与全局 {@code domainplane.planning.enabled} 的关系：那颗管"规划做不做"，
 * 这颗只管"计划发不发前端"，两者互不校验（规划关着时展示开关开着也是合法配置，
 * 只是永远没有 PLAN 事件可发）。
 *
 * @author zzkingcc
 */
class AdminDomainPlanDisplayTest {

    @TempDir
    Path tempDir;

    private DomainSettingsStore store;

    private AdminDomainController controller(DomainRegistry registry) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        StorageLocations storage = new StorageLocations(env);
        store = new DomainSettingsStore(storage);
        return new AdminDomainController(registry, new DomainStore(storage), null, store,
                null, null, null, null);
    }

    private static AdminDomainController.PlanDisplayBody body(Boolean display) {
        AdminDomainController.PlanDisplayBody b = new AdminDomainController.PlanDisplayBody();
        b.setDisplay(display);
        return b;
    }

    @Test
    void 拨到开落盘() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.plan", true);
        AdminDomainController c = controller(registry);

        Map<String, Object> r = c.setPlanDisplay("default.plan", body(Boolean.TRUE));
        assertEquals(0, r.get("code"));
        assertEquals("plan-display", r.get("action"));

        assertTrue(store.load().planDisplay("default.plan"), "开关应落盘为 true");
    }

    @Test
    void 再拨到关同样落盘() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.plan", true);
        AdminDomainController c = controller(registry);
        c.setPlanDisplay("default.plan", body(Boolean.TRUE));

        c.setPlanDisplay("default.plan", body(Boolean.FALSE));
        assertFalse(store.load().planDisplay("default.plan"), "再拨一次应落盘为 false");
    }

    @Test
    void 只动这一个域的键且不碰另一颗开关() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.a", true);
        registry.create("default.b", true);
        AdminDomainController c = controller(registry);

        c.setPlanDisplay("default.a", body(Boolean.TRUE));

        DomainSettings saved = store.load();
        assertTrue(saved.planDisplay("default.a"));
        assertFalse(saved.planDisplay("default.b"), "没拨过的域保持默认关");
        assertTrue(saved.getToolCallEcho().isEmpty(), "另一颗开关不该被碰到");
        assertTrue(saved.getPrompts().isEmpty(), "提示词不该被这颗开关碰到");
    }

    @Test
    void 装配节点被拒() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.parent", true);
        registry.create("default.parent.child", true);

        AdminDomainController c = controller(registry);
        BaseException e = assertThrows(BaseException.class,
                () -> c.setPlanDisplay("default.parent", body(Boolean.TRUE)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("只有叶子域"), "原因要点名叶子规则，实际：" + e.getMessage());

        c.setPlanDisplay("default.parent.child", body(Boolean.TRUE));
        assertTrue(store.load().planDisplay("default.parent.child"));
    }

    @Test
    void 域不存在被拒() {
        AdminDomainController c = controller(new DomainRegistry());

        BaseException e = assertThrows(BaseException.class,
                () -> c.setPlanDisplay("default.ghost", body(Boolean.TRUE)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("域不存在"), "实际：" + e.getMessage());
    }

    @Test
    void 请求体缺失或开关值缺失被拒() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.plan", true);
        AdminDomainController c = controller(registry);

        BaseException e1 = assertThrows(BaseException.class,
                () -> c.setPlanDisplay("default.plan", null));
        assertEquals(ErrorCode.INVALID_PARAMETER, e1.getErrorCode());
        assertTrue(e1.getMessage().contains("缺少 display"), "实际：" + e1.getMessage());

        BaseException e2 = assertThrows(BaseException.class,
                () -> c.setPlanDisplay("default.plan", body(null)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e2.getErrorCode());
        assertTrue(e2.getMessage().contains("缺少 display"), "实际：" + e2.getMessage());
    }
}
