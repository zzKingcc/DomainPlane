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
 * 「是否回写工具执行信息」开关的保存口（PUT /admin/domains/{id}/tool-call-echo）。
 *
 * 口径（与前端域卡片头部那颗开关一一对应）：
 *   • 拨到开 / 关都落盘，只动 toolCallEcho 一个域的一个键；
 *   • 只对可调用单元（叶子域）受理 —— 装配节点不能作为入口，设了也永远读不到；
 *   • 域不存在 / 请求体缺失一律拒绝，且原因说得清是哪一个。
 *
 * 控制器其余依赖（domainStore / knowledgeBase / modelProfileRegistry / toolRegistry 等）
 * 不参与本路径，传 null。
 *
 * @author zzkingcc
 */
class AdminDomainToolCallEchoTest {

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
        // domainStore 本路径不落盘，但 view() 要读它的 filePath() 展示给前端，给真实实例
        return new AdminDomainController(registry, new DomainStore(storage), null, store,
                null, null, null, null, null, null);
    }

    private static AdminDomainController.ToolCallEchoBody body(Boolean echo) {
        AdminDomainController.ToolCallEchoBody b = new AdminDomainController.ToolCallEchoBody();
        b.setEcho(echo);
        return b;
    }

    @Test
    void 拨到开落盘() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.echo", true);
        AdminDomainController c = controller(registry);

        Map<String, Object> r = c.setToolCallEcho("default.echo", body(Boolean.TRUE));
        assertEquals(0, r.get("code"));
        assertEquals("tool-call-echo", r.get("action"));

        assertTrue(store.load().toolCallEcho("default.echo"), "开关应落盘为 true");
    }

    @Test
    void 再拨到关同样落盘() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.echo", true);
        AdminDomainController c = controller(registry);
        c.setToolCallEcho("default.echo", body(Boolean.TRUE));

        c.setToolCallEcho("default.echo", body(Boolean.FALSE));
        assertFalse(store.load().toolCallEcho("default.echo"), "再拨一次应落盘为 false");
    }

    @Test
    void 只动这一个域的键() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.a", true);
        registry.create("default.b", true);
        AdminDomainController c = controller(registry);

        c.setToolCallEcho("default.a", body(Boolean.TRUE));

        DomainSettings saved = store.load();
        assertTrue(saved.toolCallEcho("default.a"));
        assertFalse(saved.toolCallEcho("default.b"), "没拨过的域保持默认关");
        assertTrue(saved.getPrompts().isEmpty(), "提示词不该被这颗开关碰到");
    }

    @Test
    void 装配节点被拒() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.parent", true);
        // 父域下建出子域后，父域按叶子规则降为装配节点（不可调用）
        registry.create("default.parent.child", true);

        AdminDomainController c = controller(registry);
        BaseException e = assertThrows(BaseException.class,
                () -> c.setToolCallEcho("default.parent", body(Boolean.TRUE)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("只有叶子域"), "原因要点名叶子规则，实际：" + e.getMessage());

        // 叶子域照样受理
        c.setToolCallEcho("default.parent.child", body(Boolean.TRUE));
        assertTrue(store.load().toolCallEcho("default.parent.child"));
    }

    @Test
    void 域不存在被拒() {
        AdminDomainController c = controller(new DomainRegistry());

        BaseException e = assertThrows(BaseException.class,
                () -> c.setToolCallEcho("default.ghost", body(Boolean.TRUE)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("域不存在"), "实际：" + e.getMessage());
    }

    @Test
    void 请求体缺失或开关值缺失被拒() {
        DomainRegistry registry = new DomainRegistry();
        registry.create("default.echo", true);
        AdminDomainController c = controller(registry);

        BaseException e1 = assertThrows(BaseException.class,
                () -> c.setToolCallEcho("default.echo", null));
        assertEquals(ErrorCode.INVALID_PARAMETER, e1.getErrorCode());
        assertTrue(e1.getMessage().contains("缺少 echo"), "实际：" + e1.getMessage());

        BaseException e2 = assertThrows(BaseException.class,
                () -> c.setToolCallEcho("default.echo", body(null)));
        assertEquals(ErrorCode.INVALID_PARAMETER, e2.getErrorCode());
        assertTrue(e2.getMessage().contains("缺少 echo"), "实际：" + e2.getMessage());
    }
}
