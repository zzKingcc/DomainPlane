package com.zzkingcc.domainplane.server.tool;

import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.settings.DomainSettings;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具执行信息回写开关的解析口径：显式 true 才回写，其余一切按关。
 *
 * @author zzkingcc
 */
class DomainToolCallEchoResolverTest {

    @TempDir
    Path tempDir;

    private DomainToolCallEchoResolver resolver(DomainSettingsStore store) {
        return new DomainToolCallEchoResolver(store);
    }

    private DomainSettingsStore store() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new DomainSettingsStore(new StorageLocations(env));
    }

    @Test
    void 显式开着才回写() {
        DomainSettingsStore store = store();
        DomainSettings seeded = new DomainSettings();
        seeded.getToolCallEcho().put("default.a", Boolean.TRUE);
        store.save(seeded);

        DomainToolCallEchoResolver r = resolver(store);
        assertTrue(r.echoOn("default.a"), "显式 true → 回写");
        assertFalse(r.echoOn("default.b"), "未配置 → 关");
        assertFalse(r.echoOn("default"), "没设过 → 关");
    }

    @Test
    void 没有设置文件时一律按关() {
        assertFalse(resolver(store()).echoOn("default.a"),
                "文件不存在（全新部署）不能炸，按关处理");
    }

    @Test
    void 显式关也是关() {
        DomainSettingsStore store = store();
        DomainSettings seeded = new DomainSettings();
        seeded.getToolCallEcho().put("default.a", Boolean.FALSE);
        store.save(seeded);

        assertFalse(resolver(store).echoOn("default.a"));
    }
}
