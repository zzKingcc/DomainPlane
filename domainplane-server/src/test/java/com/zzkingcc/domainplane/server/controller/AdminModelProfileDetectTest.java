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
 * 保存时的端点族 / 模态识别 —— 表单上的类型下拉已删除，改由服务端读服务商元数据（零 token）。
 *
 * 桩掉上游的 {@code GET /v1/models}，覆盖四种元数据形态 + 两条边界：
 *   • OpenRouter 风格（清单带 architecture）→ 端点族与输入 / 输出模态都按元数据填；
 *   • 过滤式（靠 ?sub_type= 反推）→ 端点族按命中的子类型，模态填该端点的基线值；
 *   • 标准 OpenAI 规范（只有 id/object/created/owned_by）→ 全读不到，但**仍允许保存**
 *     （否则 OpenAI / DeepSeek 官方这类服务商一个档案都建不出来），由卡片标「未声明类型」；
 *   • 清单里没有该模型 → 照样保存，原因随响应回给接口调用方；
 *   • 显式声明 endpoints → 根本不发那次读取请求；
 *   • 鉴权被拒 → 拒绝保存（用户能改的错，不该被静默存成一份连不上的档案）。
 *
 * 全程只桩 GET：本类存在的意义就是"不发推理请求"，因此不存在 chat / embeddings 桩。
 *
 * @author zzkingcc
 */
class AdminModelProfileDetectTest {

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

    /** 全量清单：除七个子类型各一个之外，还有一个不属于任何已知子类型的模型（服务商加了新区分类） */
    private static final String SUB_TYPE_FULL_LIST = """
            {"data":[{"id":"m-chat"},{"id":"m-embed"},{"id":"m-rerank"},{"id":"m-t2i"},
                     {"id":"m-i2i"},{"id":"m-asr"},{"id":"m-t2v"},{"id":"m-new"}]}""";

    @TempDir
    Path tempDir;

    private HttpServer server;
    private int port;

    /** 不带 sub_type 的清单响应体 */
    private String listBody = PLAIN_LIST;
    /** 不带 sub_type 的清单状态码（用来造"鉴权被拒"与"若真去读就会失败"两种情形） */
    private int listStatus = 200;
    /** 子类型 → 清单响应体；不含 sub_type 参数时忽略 */
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

    /** 只有一个 models 桩：不带 sub_type 用 listBody，带 sub_type 用子类型清单（缺省回子类型全量） */
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

    /** 内存版设置存储：holder 自身"未配置"，用不到网络 */
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

    /** 不带 endpoints 的请求体 —— 与管控台表单实际发出的形状一致 */
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

    @Test
    void 清单带architecture时端点族与输入输出模态都按元数据填() {
        listBody = OPENROUTER_LIST;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        ModelProfileRegistry registry = registry(holder);

        holder[0].save(body("p", "mm-vl"));

        ModelProfile saved = registry.profile("p").orElseThrow();
        assertEquals(List.of(ModelProfile.EP_CHAT), saved.endpoints(),
                "输出模态含文本 → 判定为对话端点");
        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), saved.input(),
                "architecture 是完整枚举，输入模态应原样落档（含 image）");
        assertEquals(List.of(ModelProfile.MOD_TEXT), saved.output(),
                "输出模态应原样落档");
    }

    @Test
    void 过滤式清单靠子类型反推端点族并填基线模态() {
        listBody = SUB_TYPE_FULL_LIST;
        subTypeLists = SUB_TYPE_LISTS;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        ModelProfileRegistry registry = registry(holder);

        holder[0].save(body("e", "m-embed"));
        ModelProfile embedding = registry.profile("e").orElseThrow();
        assertEquals(List.of(ModelProfile.EP_EMBEDDING), embedding.endpoints(),
                "只出现在 embedding 子类型清单里 → 向量档案");
        assertEquals(List.of(ModelProfile.MOD_TEXT), embedding.input(), "向量端点入参是文本");
        assertEquals(List.of(ModelProfile.MOD_EMBEDDING), embedding.output(), "向量端点产出是向量");

        holder[0].save(body("r", "m-rerank"));
        ModelProfile rerank = registry.profile("r").orElseThrow();
        assertEquals(List.of(ModelProfile.EP_RERANK), rerank.endpoints(), "重排档案");
        assertEquals(List.of(ModelProfile.MOD_SCORE), rerank.output(),
                "重排产出相关性分数 —— 这一格空着会让卡片看着像识别失败");
    }

    @Test
    void 标准OpenAI规范读不出类型时仍允许保存且如实留空() {
        listBody = PLAIN_LIST;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        ModelProfileRegistry registry = registry(holder);

        Map<String, Object> out = holder[0].save(body("p", "gpt-x"));

        ModelProfile saved = registry.profile("p").orElseThrow();
        assertTrue(saved.endpoints().isEmpty(),
                "清单不给类型就不猜 —— 猜成 chat 会让向量 / 生图模型能被绑到域却用不了");
        assertTrue(saved.input().isEmpty(), "模态同样未声明，卡片两行显示 —");
        assertTrue(saved.output().isEmpty());
        assertNotNull(out.get("note"), "读不到的原因要回给接口调用方，别静默");
    }

    @Test
    void 过滤生效但模型不属于任何已知子类型时如实说清而不是当成标准规范() {
        listBody = SUB_TYPE_FULL_LIST;
        subTypeLists = SUB_TYPE_LISTS;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        ModelProfileRegistry registry = registry(holder);

        Map<String, Object> out = holder[0].save(body("p", "m-new"));

        assertTrue(registry.profile("p").orElseThrow().endpoints().isEmpty(), "判不出来就不猜");
        String note = String.valueOf(out.get("note"));
        assertTrue(note.contains("已知子类型"),
                "要说清是「服务商加了新区分类」，而不是误报成「清单是标准 OpenAI 规范」，实际：" + note);
        assertFalse(note.contains("标准 OpenAI 规范"), "两件事不能混成一句");
    }

    @Test
    void 清单里没有该模型时照样保存并把原因回给调用方() {
        listBody = PLAIN_LIST;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        ModelProfileRegistry registry = registry(holder);

        Map<String, Object> out = holder[0].save(body("p", "typo-model"));

        assertTrue(registry.profile("p").isPresent(), "别为一次读不到就把档案挡在门外");
        assertTrue(String.valueOf(out.get("note")).contains("清单里没有"),
                "要指出是「清单里没有这个模型」，实际：" + out.get("note"));
    }

    @Test
    void 适配文件登记过的模型直接取声明不发任何上游请求() {
        /* 让清单接口直接 500：适配文件命中就不该去读元数据，能存下来即证明零请求 */
        listStatus = 500;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        ModelProfileRegistry registry = registry(holder);

        holder[0].save(body("p", "deepseek-flash"));

        ModelProfile saved = registry.profile("p").orElseThrow();
        assertEquals(List.of(ModelProfile.EP_CHAT), saved.endpoints(),
                "适配文件登记了 chat 参数 ⇒ 端点族就是 chat，不必问服务商");
        assertEquals(List.of(ModelProfile.MOD_TEXT), saved.input(), "输入模态取自适配文件");
        assertEquals(List.of(ModelProfile.MOD_TEXT), saved.output(), "输出模态取自适配文件");
    }

    @Test
    void 显式声明端点时不触发元数据读取() {
        /* 让清单接口直接 500：若还去读就会抛错，能存下来即证明没走读取 */
        listStatus = 500;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        ModelProfileRegistry registry = registry(holder);

        AdminModelProfileController.ProfileBody b = body("p", "m");
        b.setEndpoints(List.of(ModelProfile.EP_CHAT));

        holder[0].save(b);

        assertEquals(List.of(ModelProfile.EP_CHAT),
                registry.profile("p").orElseThrow().endpoints(),
                "显式传了端点族就按传的存（脚本 / 集成方的直接声明路径）");
    }

    @Test
    void 鉴权被拒时拒绝保存并把原因说清() {
        listStatus = 401;
        AdminModelProfileController[] holder = new AdminModelProfileController[1];
        ModelProfileRegistry registry = registry(holder);

        BaseException e = assertThrows(BaseException.class, () -> holder[0].save(body("p", "m")));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage().contains("API Key"),
                "Key 失效是用户能改的错，要说清，实际：" + e.getMessage());
        assertTrue(registry.profile("p").isEmpty(), "读失败就不该落盘");
    }
}
