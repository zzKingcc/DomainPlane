package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.server.config.AiProperties;
import com.zzkingcc.domainplane.server.config.RagProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileStore;
import com.zzkingcc.domainplane.server.settings.LlmModelHolder;
import com.zzkingcc.domainplane.server.settings.LlmSettings;
import com.zzkingcc.domainplane.server.settings.LlmSettingsStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #51：管控台的业务失败必须带上对应的 HTTP 状态。
 *
 * <p>原来这些分支一律 {@code return 失败体}（HTTP 200）：调用方（SDK / 脚本 / 网关）只能
 * 解析 body 才知道失败，重试与告警拿不到任何信号。全局异常处理器是按
 * {@code ErrorCode#getHttpStatus()} 定状态的，控制器不能自外于这套约定。</p>
 *
 * <p>本测试把"状态码"本身钉死：任何人把某个分支改回 {@code return fail;}，这里立刻红。</p>
 *
 * @author zzkingcc
 */
@DisplayName("AdminController：业务失败也要有正确的 HTTP 状态")
class AdminControllerStatusTest {

    private static StorageLocations storage() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", "target/test-storage/settings");
        props.put("domainplane.logging.path", "target/test-storage/logs");
        props.put("domainplane.export.path", "target/test-storage/export");
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new StorageLocations(env);
    }

    static final class StubSettingsStore extends LlmSettingsStore {
        LlmSettings current = new LlmSettings();

        StubSettingsStore() {
            super(storage());
        }

        @Override
        public LlmSettings load() {
            return current;
        }

        @Override
        public void save(LlmSettings settings) {
            this.current = settings;
        }
    }

    static final class StubKnowledgeBase extends KnowledgeBaseService {
        Integer dims = 768;

        StubKnowledgeBase() {
            super(null, null, new RagProperties(), null, null, null);
        }

        @Override
        public Integer currentVectorDims() {
            return dims;
        }

        @Override
        public void shutdown() {
            /* 不关别人的池 */
        }
    }

    static final class StubProfileRegistry extends ModelProfileRegistry {
        StubProfileRegistry() {
            super(new ModelProfileStore(storage()));
        }
    }

    /** 探测结论、保存是否失败、连通是否失败，全部可控 */
    static final class StubHolder extends LlmModelHolder {
        LlmModelHolder.EmbeddingProbe probe =
                new LlmModelHolder.EmbeddingProbe(1536, null, true);
        RuntimeException applyFailure;
        RuntimeException chatFailure;

        StubHolder() {
            super(new AiProperties(), new StubSettingsStore());
        }

        @Override
        public LlmSettings currentSettings() {
            LlmSettings s = new LlmSettings();
            s.setChatBaseUrl("https://current.test/v1");
            s.setEmbeddingBaseUrl("https://current-emb.test/v1");
            s.setEmbeddingModelName("current-emb-model");
            return s;
        }

        @Override
        public synchronized void apply(LlmSettings incoming) {
            if (applyFailure != null) {
                throw applyFailure;
            }
        }

        @Override
        public String testChatConnection(LlmSettings candidate) {
            if (chatFailure != null) {
                throw chatFailure;
            }
            return "pong";
        }

        @Override
        public LlmModelHolder.EmbeddingProbe testEmbeddingConnection(LlmSettings candidate) {
            if (chatFailure != null) {
                throw chatFailure;
            }
            return probe;
        }
    }

    private AdminController controller(LlmModelHolder holder, KnowledgeBaseService kb,
                                       ModelProfileRegistry registry) {
        return new AdminController(
                null, null, null, holder, new StubSettingsStore(), null, null, null, null,
                null, null, null, null, null, kb, null, null, registry);
    }

    /** 改动了向量配置的入参：只有"向量侧变了"才会走维度预检 */
    private static LlmSettings embeddingChangedIncoming() {
        LlmSettings in = new LlmSettings();
        in.setEmbeddingBaseUrl("https://new-emb.test/v1");
        in.setEmbeddingModelName("new-emb-model");
        return in;
    }

    @Test
    @DisplayName("声明的维度上游不兑现：400，并带回声明值与实测值")
    void declaredDimensionMismatchIsRejectedWith400() {
        StubHolder holder = new StubHolder();
        holder.probe = new LlmModelHolder.EmbeddingProbe(1024, 2048, false);

        ResponseEntity<Map<String, Object>> resp =
                controller(holder, new StubKnowledgeBase(), new StubProfileRegistry())
                        .saveSettings(embeddingChangedIncoming(), false);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals(Boolean.FALSE, body.get("success"), "声明不被兑现时必须拒绝保存");
        assertEquals(2048, body.get("declaredDimension"));
        assertEquals(1024, body.get("newDimension"));
        assertTrue(String.valueOf(body.get("error")).contains("声明"),
                "要说清是声明与实测不符，实际：" + body.get("error"));
    }

    @Test
    @DisplayName("维度变了但用户没确认重建：409，带 requiresRebuild")
    void dimensionChangeWithoutConfirmationIs409() {
        StubHolder holder = new StubHolder();
        holder.probe = new LlmModelHolder.EmbeddingProbe(1536, null, true);
        StubKnowledgeBase kb = new StubKnowledgeBase();
        kb.dims = 768;

        ResponseEntity<Map<String, Object>> resp =
                controller(holder, kb, new StubProfileRegistry())
                        .saveSettings(embeddingChangedIncoming(), false);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode(),
                "缺一次确认与参数非法是两回事，不能都用 400");
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals(Boolean.TRUE, body.get("requiresRebuild"));
        assertEquals(768, body.get("indexDimension"));
        assertEquals(1536, body.get("newDimension"));
    }

    @Test
    @DisplayName("保存落盘失败：500，不能报成功")
    void applyFailureIs500() {
        StubHolder holder = new StubHolder();
        holder.applyFailure = new IllegalStateException("设置文件被占用");

        LlmSettings in = new LlmSettings();
        in.setEmbeddingBaseUrl("https://current-emb.test/v1");
        in.setEmbeddingModelName("current-emb-model");

        ResponseEntity<Map<String, Object>> resp =
                controller(holder, new StubKnowledgeBase(), new StubProfileRegistry())
                        .saveSettings(in, false);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(Boolean.FALSE, resp.getBody().get("success"));
        assertTrue(String.valueOf(resp.getBody().get("error")).contains("设置文件被占用"));
    }

    @Test
    @DisplayName("保存成功：200")
    void successIs200() {
        StubHolder holder = new StubHolder();
        LlmSettings in = new LlmSettings();
        in.setEmbeddingBaseUrl("https://current-emb.test/v1");
        in.setEmbeddingModelName("current-emb-model");

        ResponseEntity<Map<String, Object>> resp =
                controller(holder, new StubKnowledgeBase(), new StubProfileRegistry())
                        .saveSettings(in, false);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(Boolean.TRUE, resp.getBody().get("success"));
    }

    @Test
    @DisplayName("连通性测试：对话模型上游不可达 → 502")
    void chatConnectionFailureIs502() {
        StubHolder holder = new StubHolder();
        holder.chatFailure = new RuntimeException("connect timed out");

        ResponseEntity<Map<String, Object>> resp =
                controller(holder, new StubKnowledgeBase(), new StubProfileRegistry())
                        .testSettings(new LlmSettings(), "chat");

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode(),
                "入参没问题、是上游不可达，与参数非法区分开");
        assertNotNull(resp.getBody());
        assertEquals(Boolean.FALSE, resp.getBody().get("success"));
        assertTrue(String.valueOf(resp.getBody().get("error")).length() > 0,
                "失败原因必须回给调用方");
    }

    @Test
    @DisplayName("连通性测试：向量维度声明与实测不符 → 400")
    void embeddingMismatchInProbeIs400() {
        StubHolder holder = new StubHolder();
        holder.probe = new LlmModelHolder.EmbeddingProbe(1024, 2048, false);

        ResponseEntity<Map<String, Object>> resp =
                controller(holder, new StubKnowledgeBase(), new StubProfileRegistry())
                        .testSettings(embeddingChangedIncoming(), "embedding");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(Boolean.FALSE, resp.getBody().get("success"));
        assertEquals("embedding", resp.getBody().get("type"));
    }
}
