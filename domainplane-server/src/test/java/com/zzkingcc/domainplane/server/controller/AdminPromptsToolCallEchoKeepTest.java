package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.config.PromptProperties;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词保存（POST /admin/prompts）不能把「工具执行信息回写」开关冲掉。
 *
 * 保存是整份覆盖写，而提示词页的请求体只有 prompts 段：不合并的话，
 * 在提示词页点一次保存，各域拨好的回写开关就全部静默回到默认（关）。
 *
 * @author zzkingcc
 */
class AdminPromptsToolCallEchoKeepTest {

    @TempDir
    Path tempDir;

    private AdminController controller(DomainSettingsStore store) {
        // savePrompts 只碰 domainSettingsStore（第 10 参）、promptProperties（第 11 参）
        // 与 domainRegistry（第 21 参），其余依赖传 null
        // （McpConnectionStore / CancellationRegistry / DomainStore 是域空间聚合新加的依赖，本用例同样不碰）
        return new AdminController(null, null, null, null, null, null, null, null,
                null, store, new PromptProperties(), null, null, null, null, null,
                null, null, null, null, new DomainRegistry(), null, null, null, null);
    }

    @Test
    void 保存提示词后各域的回写开关原样保留() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        DomainSettingsStore store = new DomainSettingsStore(new StorageLocations(env));

        // 盘上先有一份：default.echo 开着，default.other 关着
        DomainSettings seeded = new DomainSettings();
        seeded.getPrompts().put("default", "根提示词");
        seeded.getToolCallEcho().put("default.echo", Boolean.TRUE);
        seeded.getToolCallEcho().put("default.other", Boolean.FALSE);
        // 计划展示开关同样不归提示词页管：它也不是提示词页的字段
        seeded.getPlanDisplay().put("default.echo", Boolean.TRUE);
        store.save(seeded);

        // 提示词页只发 prompts 段（前端那页根本没有回写开关）
        DomainSettings incoming = new DomainSettings();
        incoming.getPrompts().put("default", "根提示词改过了");

        Map<String, Object> r = controller(store).savePrompts(incoming);
        assertEquals(Boolean.TRUE, r.get("success"), "保存本身要成功");

        DomainSettings reloaded = store.load();
        assertEquals("根提示词改过了", reloaded.getPrompts().get("default"), "提示词要存下来");
        assertEquals(Boolean.TRUE, reloaded.getToolCallEcho().get("default.echo"),
                "开着的开关不能被提示词保存冲掉");
        assertEquals(Boolean.FALSE, reloaded.getToolCallEcho().get("default.other"),
                "关着的开关（显式值）同样要保留");
        assertEquals(Boolean.TRUE, reloaded.getPlanDisplay().get("default.echo"),
                "计划展示开关也不能被提示词保存冲掉（同属整份覆盖写的受害者）");
    }
}
