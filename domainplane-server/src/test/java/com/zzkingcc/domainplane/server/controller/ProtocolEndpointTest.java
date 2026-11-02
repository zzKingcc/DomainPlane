package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
import com.zzkingcc.domainplane.server.model.ModelCapabilityProbe;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 连接协议的切换口（PUT /admin/model-profiles/protocol）。
 *
 * <p>钉四件事：</p>
 * <ol>
 *   <li><b>默认是 OpenAI 兼容，且不落盘</b> —— 老 models.json 里没有这个键，
 *       读回来归一为 {@code openai}；写回时同样不凭空造键，避免给所有老文件刷一遍新字段。</li>
 *   <li><b>未知协议一律拒绝，不静默回落</b> —— 回落会让用户以为选了 Anthropic，
 *       实际还在按 OpenAI 发请求，那种错在日志里只表现为"鉴权失败"，看不出是协议选错。</li>
 *   <li><b>换协议会清自定义参数，且要说出口</b> —— 同名键在两种协议下语义不同
 *       （{@code max_tokens} 在 Anthropic 是必填且含义不同），留着就是"我填的值莫名消失"
 *       的反面：值还在，但发错了地方。静默清掉与保存失败是同一种困惑，前者连报错都没有。</li>
 *   <li><b>换了要能落盘再读回来</b> —— 指纹里含协议，落不住的话重启后客户端又换回 OpenAI，
 *       而界面上明明写着 Anthropic。</li>
 * </ol>
 *
 * @author zzkingcc
 */
class ProtocolEndpointTest {

    @TempDir
    Path tempDir;

    private ModelProfileRegistry lastRegistry;

    private AdminModelProfileController controller() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        ModelProfileStore store = new ModelProfileStore(new StorageLocations(env));
        ModelProfileRegistry registry = new ModelProfileRegistry(store);
        lastRegistry = registry;
        return new AdminModelProfileController(registry, store,
                null, null, new ModelAdapterRegistry(new DefaultResourceLoader()),
                new ModelCapabilityProbe(), null, null);
    }

    private void seed(AdminModelProfileController c) {
        AdminModelProfileController.ProfileBody b = new AdminModelProfileController.ProfileBody();
        b.setAlias("p");
        b.setApiKey("sk-test");
        b.setBaseUrl("https://api.example.com/v1");
        b.setModelName("some-unregistered-model");
        b.setEndpoints(List.of(ModelProfile.EP_CHAT));
        c.save(b);
    }

    private Map<String, Object> switchTo(AdminModelProfileController c, String protocol) {
        AdminModelProfileController.ProtocolBody body = new AdminModelProfileController.ProtocolBody();
        body.setAlias("p");
        body.setProtocol(protocol);
        return c.updateProtocol(body);
    }

    // ==================== 默认值 ====================

    @Test
    @DisplayName("没声明过协议 = OpenAI 兼容")
    void defaultsToOpenAi() {
        AdminModelProfileController c = controller();
        seed(c);
        assertEquals(ModelProfile.PROTOCOL_OPENAI, lastRegistry.profile("p").orElseThrow().protocol());
    }

    @Test
    @DisplayName("默认协议落盘为 null（与 params 同一口径）：不给老 models.json 刷一个 \"openai\" 出来")
    void defaultPersistedAsNull() throws Exception {
        AdminModelProfileController c = controller();
        seed(c);

        String json = java.nio.file.Files.readString(
                tempDir.resolve("config").resolve("models.json"));
        /* 落盘格式是"每个字段都写、缺省写 null"（见 models.json 里的 temperature / params），
           所以这里断言的"不刷历史文件"是：不把归一下来的默认值写成字面量 "openai"。
           写死了的话，所有老文件一旦被保存一次就多出一个新值，往后换默认协议就再也改不动。 */
        assertTrue(json.contains("\"protocol\" : null"), json);
        assertFalse(json.contains("\"protocol\" : \"openai\""),
                "默认值不该写成字面量 —— 那样一保存就把它钉死在文件里了：" + json);
    }

    // ==================== 切换 ====================

    @Test
    @DisplayName("切到 Anthropic：档案上生效，且落盘后能读回来")
    void switchesToAnthropicAndPersists() {
        AdminModelProfileController c = controller();
        seed(c);

        Map<String, Object> out = switchTo(c, ModelProfile.PROTOCOL_ANTHROPIC);
        assertEquals(ModelProfile.PROTOCOL_ANTHROPIC, lastRegistry.profile("p").orElseThrow().protocol());
        assertTrue(String.valueOf(out.get("message")).contains("anthropic"), String.valueOf(out.get("message")));

        ModelProfileRegistry reloaded = new ModelProfileRegistry(
                new ModelProfileStore(new StorageLocations(env())));
        assertEquals(ModelProfile.PROTOCOL_ANTHROPIC, reloaded.profile("p").orElseThrow().protocol(),
                "换完协议重启就退回 OpenAI 的话，界面上写着 Anthropic 而实际在按 OpenAI 发请求");
    }

    @Test
    @DisplayName("切回 OpenAI 后文件里回到 null：非默认才写值，不留残余")
    void switchingBackDropsTheValue() throws Exception {
        AdminModelProfileController c = controller();
        seed(c);
        switchTo(c, ModelProfile.PROTOCOL_ANTHROPIC);
        assertTrue(java.nio.file.Files.readString(tempDir.resolve("config").resolve("models.json"))
                .contains("\"protocol\" : \"anthropic\""));

        switchTo(c, ModelProfile.PROTOCOL_OPENAI);
        assertTrue(java.nio.file.Files.readString(tempDir.resolve("config").resolve("models.json"))
                .contains("\"protocol\" : null"),
                "切回默认就该回到 null：留着 anthropic 的话，读回来卡片上又变回 Anthropic");
    }

    // ==================== 拒绝 ====================

    @Test
    @DisplayName("未知协议拒绝而不是静默回落")
    void unknownProtocolRejected() {
        AdminModelProfileController c = controller();
        seed(c);

        BaseException e = assertThrows(BaseException.class, () -> switchTo(c, "gemini"));
        assertTrue(e.getMessage().contains("不支持的协议"), e.getMessage());
        assertTrue(e.getMessage().contains("openai") && e.getMessage().contains("anthropic"),
                "拒绝文案要列出可选值：" + e.getMessage());
        assertEquals(ModelProfile.PROTOCOL_OPENAI, lastRegistry.profile("p").orElseThrow().protocol(),
                "被拒就不该留下半改的状态");
    }

    @Test
    @DisplayName("切到当前协议 = 无事发生，明确拒绝而不是假装成功")
    void switchingToSameRejected() {
        AdminModelProfileController c = controller();
        seed(c);

        BaseException e = assertThrows(BaseException.class,
                () -> switchTo(c, ModelProfile.PROTOCOL_OPENAI));
        assertTrue(e.getMessage().contains("已经是"), e.getMessage());
    }

    @Test
    @DisplayName("档案不存在 / 别名为空直接拒")
    void missingAliasRejected() {
        AdminModelProfileController c = controller();
        seed(c);

        assertThrows(BaseException.class, () -> {
            AdminModelProfileController.ProtocolBody b = new AdminModelProfileController.ProtocolBody();
            b.setAlias("  ");
            b.setProtocol(ModelProfile.PROTOCOL_ANTHROPIC);
            c.updateProtocol(b);
        });
        assertThrows(BaseException.class, () -> {
            AdminModelProfileController.ProtocolBody b = new AdminModelProfileController.ProtocolBody();
            b.setAlias("nope");
            b.setProtocol(ModelProfile.PROTOCOL_ANTHROPIC);
            c.updateProtocol(b);
        });
    }

    // ==================== 清参数 ====================

    @Test
    @DisplayName("换协议清掉自定义参数，并且把清了这件事说出口")
    void switchingClearsCustomParamsWithNotice() {
        AdminModelProfileController c = controller();
        seed(c);
        AdminModelProfileController.CustomParamsBody cp = new AdminModelProfileController.CustomParamsBody();
        cp.setAlias("p");
        cp.setParamsJson("{\"enable_thinking\": true, \"repetition_penalty\": 1.05}");
        c.updateCustomParams(cp);
        assertEquals(2, lastRegistry.profile("p").orElseThrow().params().size());

        Map<String, Object> out = switchTo(c, ModelProfile.PROTOCOL_ANTHROPIC);

        assertTrue(lastRegistry.profile("p").orElseThrow().params().isEmpty(),
                "两种协议下同名键语义不同，留着就是值还在、发错了地方");
        assertEquals(Boolean.TRUE, out.get("clearedParams"));
        assertTrue(String.valueOf(out.get("message")).contains("2"),
                "清了几个要说出来：静默清掉与保存失败是同一种困惑：" + out.get("message"));
    }

    @Test
    @DisplayName("本来就没有自定义参数时不提\"清除\"（没清就别说清了）")
    void noNoticeWhenNothingToClear() {
        AdminModelProfileController c = controller();
        seed(c);

        Map<String, Object> out = switchTo(c, ModelProfile.PROTOCOL_ANTHROPIC);
        assertEquals(Boolean.FALSE, out.get("clearedParams"));
        assertFalse(String.valueOf(out.get("message")).contains("清除"), String.valueOf(out.get("message")));
    }

    @Test
    @DisplayName("换协议不动连接信息与标准字段")
    void onlyProtocolAndParamsChange() {
        AdminModelProfileController c = controller();
        seed(c);
        ModelProfile before = lastRegistry.profile("p").orElseThrow();

        switchTo(c, ModelProfile.PROTOCOL_ANTHROPIC);
        ModelProfile after = lastRegistry.profile("p").orElseThrow();

        assertEquals(before.baseUrl(), after.baseUrl());
        assertEquals(before.apiKey(), after.apiKey());
        assertEquals(before.modelName(), after.modelName());
        assertEquals(before.endpoints(), after.endpoints());
    }

    // ==================== 指纹 ====================

    @Test
    @DisplayName("协议进指纹：换协议必须换客户端实例，否则缓存会一直喂旧协议那个")
    void protocolIsPartOfFingerprint() {
        AdminModelProfileController c = controller();
        seed(c);
        String openAiFp = lastRegistry.profile("p").orElseThrow().fingerprint();

        switchTo(c, ModelProfile.PROTOCOL_ANTHROPIC);
        assertNotEquals(openAiFp, lastRegistry.profile("p").orElseThrow().fingerprint());
    }

    @Test
    @DisplayName("null 与 openai 不许算出两个指纹：否则同一份配置缓存出两份等价客户端")
    void blankAndOpenAiShareOneFingerprint() {
        ModelProfile blank = new ModelProfile("a", List.of(), List.of(), List.of(),
                "https://x/v1", "k", "m", null, null, null, null, null, null, List.of(), Map.of(), null);
        ModelProfile explicit = new ModelProfile("a", List.of(), List.of(), List.of(),
                "https://x/v1", "k", "m", null, null, null, null, null, null, List.of(), Map.of(),
                ModelProfile.PROTOCOL_OPENAI);
        assertEquals(blank.protocol(), explicit.protocol());
        assertEquals(blank.fingerprint(), explicit.fingerprint());
    }

    private StandardEnvironment env() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return env;
    }
}
