package com.zzkingcc.domainplane.server.controller;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.config.AiProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
import com.zzkingcc.domainplane.server.model.ModelCapabilityProbe;
import com.zzkingcc.domainplane.server.model.ModelMetadataReader;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileStore;
import com.zzkingcc.domainplane.server.settings.LlmModelHolder;
import com.zzkingcc.domainplane.server.settings.LlmSettings;
import com.zzkingcc.domainplane.server.settings.LlmSettingsStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「识别」按钮（{@code POST /admin/model-profiles/suggest}）的四种元数据形态与边界。
 *
 * <p>历史上这组用例挂在 {@code save()} 上：保存时服务端读服务商清单，猜端点族与模态。
 * 那套流程已经拆掉 —— 保存只是登记凭据，识别是用户主动点的一次只读请求，
 * 结果只用于预勾表单。所以这里全部改成打 suggest，并额外钉住一条
 * <b>「连清单接口 500 了，save 照样成功」</b>：save 路径上一次上游请求都不该有。</p>
 *
 * <p>桩只接 {@code GET /v1/models}，不存在 chat / embeddings 桩：识别不跑推理、不花 token。</p>
 */
class AdminModelProfileSuggestTest {

    /** OpenRouter 风格：清单每项自带 architecture，模态是完整枚举 */
    private static final String OPENROUTER_LIST = """
            {"data":[{"id":"mm-vl","architecture":{
              "input_modalities":["text","image"],"output_modalities":["text"]}}]}""";

    /** 标准 OpenAI 规范：只有四个字段，类型与模态一项都没有 */
    private static final String PLAIN_LIST = """
            {"data":[{"id":"gpt-x","object":"model","created":1,"owned_by":"openai"}]}""";

    /** 过滤式：全量清单只有 id；各子类型各回一份真子集（过滤确实生效） */
    private static final Map<String, String> SUB_TYPE_LISTS = Map.of(
            "chat", "{\"data\":[{\"id\":\"m-chat\"}]}",
            "embedding", "{\"data\":[{\"id\":\"m-embed\"}]}",
            "reranker", "{\"data\":[{\"id\":\"m-rerank\"}]}",
            "text-to-image", "{\"data\":[{\"id\":\"m-t2i\"}]}",
            "image-to-image", "{\"data\":[{\"id\":\"m-i2i\"}]}",
            "speech-to-text", "{\"data\":[{\"id\":\"m-asr\"}]}",
            "text-to-video", "{\"data\":[{\"id\":\"m-t2v\"}]}");

    /** 全量清单：除七个子类型各一个之外，还有一个不属于任何已知子类型的模型 */
    private static final String SUB_TYPE_FULL_LIST = """
            {"data":[{"id":"m-chat"},{"id":"m-embed"},{"id":"m-rerank"},{"id":"m-t2i"},
                     {"id":"m-i2i"},{"id":"m-asr"},{"id":"m-t2v"},{"id":"m-new"}]}""";

    @TempDir
    Path tempDir;

    private HttpServer server;
    private int port;

    private String listBody = PLAIN_LIST;
    private int listStatus = 200;
    private Map<String, String> subTypeLists = Map.of();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", this::handleModels);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleModels(HttpExchange ex) throws IOException {
        drain(ex);
        String query = ex.getRequestURI().getQuery();
        String subType = query != null && query.startsWith("sub_type=")
                ? query.substring("sub_type=".length()) : null;
        if (subType == null) {
            respond(ex, listStatus, listBody);
            return;
        }
        respond(ex, 200, subTypeLists.getOrDefault(subType, SUB_TYPE_FULL_LIST));
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    /** 请求体必须读完再回，否则连接可能被对端重置 */
    private static void drain(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static final class StubStore extends LlmSettingsStore {
        private LlmSettings current = new LlmSettings();

        StubStore() {
            super(new StorageLocations(new StandardEnvironment()));
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

    /** 控制器 + 真实注册表与真实元数据读取器：读取会打到本机桩上，不碰外网 */
    private ModelProfileRegistry registry(AdminModelProfileController[] out) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));

        ModelProfileStore store = new ModelProfileStore(new StorageLocations(env));
        ModelProfileRegistry registry = new ModelProfileRegistry(store);
        LlmModelHolder holder = new LlmModelHolder(new AiProperties(), new StubStore());
        out[0] = new AdminModelProfileController(registry, store, holder,
                new ModelMetadataReader(),
                new ModelAdapterRegistry(new DefaultResourceLoader()),
                new ModelCapabilityProbe(), null, null);
        return registry;
    }

    private AdminModelProfileController.ProfileBody body(String alias, String modelName) {
        AdminModelProfileController.ProfileBody b = new AdminModelProfileController.ProfileBody();
        b.setAlias(alias);
        b.setApiKey("sk-test");
        b.setBaseUrl("http://127.0.0.1:" + port + "/v1");
        b.setModelName(modelName);
        b.setInput(List.of());
        b.setOutput(List.of());
        return b;
    }

    // ==================== 按需识别 ====================

    @Test
    void 清单带architecture时端点族与输入输出模态都按元数据填() {
        listBody = OPENROUTER_LIST;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        registry(holder);

        Map<String, Object> out = holder[0].suggest(body("p", "mm-vl"));

        assertEquals("provider", out.get("source"));
        assertEquals(List.of(ModelProfile.EP_CHAT), out.get("endpoints"),
                "输出模态含文本 → 建议对话端点");
        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), out.get("input"),
                "architecture 是完整枚举，输入模态原样回给表单（含 image）");
        assertEquals(List.of(ModelProfile.MOD_TEXT), out.get("output"));
    }

    @Test
    void 过滤式清单靠子类型反推端点族并填基线模态() {
        listBody = SUB_TYPE_FULL_LIST;
        subTypeLists = SUB_TYPE_LISTS;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        registry(holder);

        Map<String, Object> embedding = holder[0].suggest(body("e", "m-embed"));
        assertEquals(List.of(ModelProfile.EP_EMBEDDING), embedding.get("endpoints"),
                "只出现在 embedding 子类型清单里 → 建议向量端点");
        assertEquals(List.of(ModelProfile.MOD_TEXT), embedding.get("input"));
        assertEquals(List.of(ModelProfile.MOD_EMBEDDING), embedding.get("output"));

        Map<String, Object> rerank = holder[0].suggest(body("r", "m-rerank"));
        assertEquals(List.of(ModelProfile.EP_RERANK), rerank.get("endpoints"));
        assertEquals(List.of(ModelProfile.MOD_SCORE), rerank.get("output"),
                "重排产出相关性分数 —— 这一格空着会让用途看着像没识别出来");
    }

    @Test
    void 标准OpenAI规范读不出类型时如实留空并把原因说清() {
        listBody = PLAIN_LIST;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        registry(holder);

        Map<String, Object> out = holder[0].suggest(body("p", "gpt-x"));

        assertEquals(List.of(), out.get("endpoints"),
                "清单不给类型就不猜 —— 猜成 chat 会让向量 / 生图模型能绑域却用不了");
        assertEquals(List.of(), out.get("input"));
        assertEquals(List.of(), out.get("output"));
        assertNotNull(out.get("note"), "读不到的原因要回给表单，别静默");
    }

    @Test
    void 过滤生效但模型不属于任何已知子类型时如实说清而不是当成标准规范() {
        listBody = SUB_TYPE_FULL_LIST;
        subTypeLists = SUB_TYPE_LISTS;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        registry(holder);

        String note = String.valueOf(holder[0].suggest(body("p", "m-new")).get("note"));

        assertTrue(note.contains("已知子类型"),
                "要说清是「服务商加了新区分类」，而不是误报成「清单是标准 OpenAI 规范」，实际：" + note);
        assertFalse(note.contains("标准 OpenAI 规范"), "两件事不能混成一句");
    }

    @Test
    void 清单里没有该模型时返回原因() {
        listBody = PLAIN_LIST;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        registry(holder);

        Map<String, Object> out = holder[0].suggest(body("p", "typo-model"));

        assertTrue(String.valueOf(out.get("note")).contains("清单里没有"),
                "要指出是「清单里没有这个模型」，实际：" + out.get("note"));
    }

    @Test
    void 适配文件登记过的模型直接取声明不发任何上游请求() {
        /* 让清单接口直接 500：适配文件命中就不该去读元数据 */
        listStatus = 500;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        registry(holder);

        Map<String, Object> out = holder[0].suggest(body("p", "deepseek-flash"));

        assertEquals("adapter", out.get("source"));
        assertEquals(List.of(ModelProfile.EP_CHAT), out.get("endpoints"),
                "适配文件登记了 chat 族 ⇒ 端点族就是 chat，不必问服务商");
        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), out.get("input"));
        assertEquals(List.of(ModelProfile.MOD_TEXT), out.get("output"));
    }

    @Test
    void 鉴权被拒时识别失败并说清原因() {
        listStatus = 401;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        registry(holder);

        BaseException e = assertThrows(BaseException.class,
                () -> holder[0].suggest(body("p", "m")));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("API Key"),
                "Key 失效是用户能改的错，要说清，实际：" + e.getMessage());
    }

    // ==================== 保存与识别彻底解耦 ====================

    @Test
    void 清单接口挂了也不影响保存_保存路径零上游请求() {
        listStatus = 500;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        ModelProfileRegistry registry = registry(holder);

        holder[0].save(body("p", "m"));

        ModelProfile saved = registry.profile("p").orElseThrow();
        assertTrue(saved.endpoints().isEmpty(), "用户没勾用途就是没声明，不需要问服务商");
        assertEquals("m", saved.modelName());
    }
}
