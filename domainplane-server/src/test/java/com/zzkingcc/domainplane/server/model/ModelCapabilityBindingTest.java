package com.zzkingcc.domainplane.server.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分族域绑定：主模型只收能对话的档案，生图 / 语音 / 视频走各自的能力族，向量与重排不参与。
 *
 * <p>这组用例锁的是本次改造最容易回退的几处：<b>门禁按族分</b>（生图档案当不了主模型、
 * 却必须能绑到生图族）、<b>回落按族走</b>（本域绑了生图不该截断语音向祖先的回落）、
 * <b>整批校验通过才落盘</b>（不许留"主模型改了、生图没改"的中间态）、
 * <b>旧落盘格式还能读</b>（单个数组等价于主模型）。</p>
 *
 * @author zzkingcc
 */
class ModelCapabilityBindingTest {

    @TempDir
    Path tempDir;

    /** 一份足够覆盖各族门禁的档案集：两个对话档案 + 四个能力档案 + 重排 / 向量 / 未识别 */
    private static final String SEED = """
            {"profiles":{
              "chat-a":{"endpoints":["chat"],"input":["text"],"output":["text"],
                        "baseUrl":"https://api.example.com/v1","apiKey":"k","modelName":"m-chat-a"},
              "chat-b":{"endpoints":["chat"],"input":["text"],"output":["text"],
                        "baseUrl":"https://api.example.com/v1","apiKey":"k","modelName":"m-chat-b"},
              "img":{"endpoints":["images"],"input":["text"],"output":["image"],
                     "baseUrl":"https://api.example.com/v1","apiKey":"k","modelName":"m-img"},
              "tts":{"endpoints":["tts"],"input":["text"],"output":["audio"],
                     "baseUrl":"https://api.example.com/v1","apiKey":"k","modelName":"m-tts"},
              "asr":{"endpoints":["asr"],"input":["audio"],"output":["text"],
                     "baseUrl":"https://api.example.com/v1","apiKey":"k","modelName":"m-asr"},
              "vid":{"endpoints":["video"],"input":["text"],"output":["video"],
                     "baseUrl":"https://api.example.com/v1","apiKey":"k","modelName":"m-vid"},
              "rr":{"endpoints":["rerank"],"input":["text"],"output":["score"],
                    "baseUrl":"https://api.example.com/v1","apiKey":"k","modelName":"m-rr"},
              "emb":{"endpoints":["embedding"],"input":["text"],"output":["embedding"],
                     "baseUrl":"https://api.example.com/v1","apiKey":"k","modelName":"m-emb"},
              "noep":{"input":["text"],"output":["text"],
                      "baseUrl":"https://api.example.com/v1","apiKey":"k","modelName":"m-noep"}
            },"domainBindings":{}}
            """;

    private ModelProfileStore store() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new ModelProfileStore(
                new com.zzkingcc.domainplane.server.env.StorageLocations(env));
    }

    private void seed(String json) throws IOException {
        Path file = tempDir.resolve("config").resolve("models.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    /** 每次调用都新建注册表：既拿到干净内存态，也验证"读的是同一个文件" */
    private ModelProfileRegistry seeded() throws IOException {
        seed(SEED);
        return new ModelProfileRegistry(store());
    }

    // ==================== 兼容旧格式 ====================

    @Test
    void 旧格式的单个数组等价于主模型() throws IOException {
        seed("""
                {"profiles":{},"domainBindings":{"default.sales":["m1"]}}
                """);
        ModelProfileRegistry registry = new ModelProfileRegistry(store());

        assertEquals(List.of("m1"), registry.resolve("default.sales").aliases());
        assertEquals(List.of("m1"),
                registry.domainBindings().get("default.sales").family(ModelProfile.EP_CHAT),
                "旧格式的一个列表就是主模型那一桶");
    }

    @Test
    void 新格式落盘后能原样读回() throws IOException {
        ModelProfileRegistry registry = seeded();
        assertNull(registry.bindDomain("default", List.of("chat-a"),
                Map.of(ModelProfile.EP_IMAGES, List.of("img"))));

        ModelProfileRegistry reloaded = new ModelProfileRegistry(store());
        assertEquals(List.of("chat-a"),
                reloaded.domainBindings().get("default").family(ModelProfile.EP_CHAT));
        assertEquals(List.of("img"),
                reloaded.domainBindings().get("default").family(ModelProfile.EP_IMAGES),
                "族要分开落盘，重读后仍然各归各桶");
    }

    // ==================== 门禁按族分 ====================

    @Test
    void 生图档案能绑生图族但不能当主模型() throws IOException {
        ModelProfileRegistry registry = seeded();

        assertNull(registry.bindDomain("default", null, Map.of(ModelProfile.EP_IMAGES, List.of("img"))),
                "生图档案恰恰是生图族唯一合格的东西");

        String reason = registry.bindDomain("default", List.of("img"), null);
        assertNotNull(reason, "生图档案不能当主模型");
        assertTrue(reason.contains("生图"), "拒绝理由要指出它该绑到哪一段：" + reason);
    }

    @Test
    void 语音与视频档案各归各族() throws IOException {
        ModelProfileRegistry registry = seeded();

        assertNull(registry.bindDomain("default", null, Map.of(
                ModelProfile.EP_TTS, List.of("tts"),
                ModelProfile.EP_ASR, List.of("asr"),
                ModelProfile.EP_VIDEO, List.of("vid"))));
        assertEquals(List.of("tts"), registry.domainBindings().get("default").family(ModelProfile.EP_TTS));
        assertEquals(List.of("asr"), registry.domainBindings().get("default").family(ModelProfile.EP_ASR));
        assertEquals(List.of("vid"), registry.domainBindings().get("default").family(ModelProfile.EP_VIDEO));
    }

    @Test
    void 能力族要求档案确实声明了那个端点族() throws IOException {
        ModelProfileRegistry registry = seeded();

        String reason = registry.bindDomain("default", null,
                Map.of(ModelProfile.EP_IMAGES, List.of("chat-a")));
        assertNotNull(reason, "对话档案没有生图端点，不能绑到生图族");
        assertTrue(reason.contains("没有声明"), reason);
    }

    @Test
    void 未识别档案两边都拒() throws IOException {
        ModelProfileRegistry registry = seeded();

        String asPrimary = registry.bindDomain("default", List.of("noep"), null);
        assertNotNull(asPrimary, "未识别的档案当不了主模型");
        assertTrue(asPrimary.contains("探测"), "要引导用户去点探测：" + asPrimary);

        String asCapability = registry.bindDomain("default", null,
                Map.of(ModelProfile.EP_IMAGES, List.of("noep")));
        assertNotNull(asCapability, "未识别 = 没声明任何端点族，能力族同样拒");
    }

    @Test
    void 向量与重排不参与域绑定() throws IOException {
        ModelProfileRegistry registry = seeded();

        String embedding = registry.bindDomain("default", null,
                Map.of(ModelProfile.EP_EMBEDDING, List.of("emb")));
        assertNotNull(embedding);
        assertTrue(embedding.contains("不参与域绑定"), embedding);

        String rerank = registry.bindDomain("default", null,
                Map.of(ModelProfile.EP_RERANK, List.of("rr")));
        assertNotNull(rerank);
        assertTrue(rerank.contains("不参与域绑定"), rerank);

        // 解析侧也认这套：这两族永远解不出东西，哪怕落盘被手工塞进去
        assertTrue(registry.resolveCapability("default", ModelProfile.EP_RERANK).empty());
        assertTrue(registry.resolveCapability("default", ModelProfile.EP_EMBEDDING).empty());
    }

    @Test
    void 主模型放进能力族会被指回aliases() throws IOException {
        ModelProfileRegistry registry = seeded();

        String reason = registry.bindDomain("default", null,
                Map.of(ModelProfile.EP_CHAT, List.of("chat-a")));
        assertNotNull(reason);
        assertTrue(reason.contains("aliases"), "别让人以为主模型不参与域绑定：" + reason);
    }

    @Test
    void 绑定的域必须是从根域出发的完整路径() throws IOException {
        ModelProfileRegistry registry = seeded();
        assertNotNull(registry.bindDomain("sales", List.of("chat-a"), null),
                "少一层根域会让绑定落到树外的域上，必须写入时就拒");
    }

    // ==================== 按族回落 ====================

    @Test
    void 各族独立沿域链回落() throws IOException {
        ModelProfileRegistry registry = seeded();
        assertNull(registry.bindDomain("default", List.of("chat-a"),
                Map.of(ModelProfile.EP_IMAGES, List.of("img"))));
        assertNull(registry.bindDomain("default.sales", List.of("chat-a"),
                Map.of(ModelProfile.EP_TTS, List.of("tts"))));

        // 生图只有根域绑了 → 后代沿链继承
        ModelProfileSettings.CapabilityBinding images =
                registry.resolveCapability("default.sales.order", ModelProfile.EP_IMAGES);
        assertEquals(List.of("img"), images.aliases());
        assertEquals("default", images.sourceDomain());

        // 语音在本域自己绑着 → 用自己的，不被"本域已有别的族"截断
        ModelProfileSettings.CapabilityBinding tts =
                registry.resolveCapability("default.sales.order", ModelProfile.EP_TTS);
        assertEquals(List.of("tts"), tts.aliases());
        assertEquals("default.sales", tts.sourceDomain());

        // 后代的绑定不会反向漏给祖先
        assertTrue(registry.resolveCapability("default", ModelProfile.EP_TTS).empty(),
                "绑定只向下继承，不会向上冒");
    }

    @Test
    void 旁支的能力绑定不共享() throws IOException {
        ModelProfileRegistry registry = seeded();
        assertNull(registry.bindDomain("default.sales", null,
                Map.of(ModelProfile.EP_IMAGES, List.of("img"))));

        assertTrue(registry.resolveCapability("default.hr", ModelProfile.EP_IMAGES).empty(),
                "sales 的生图模型不该落到 hr 分支");
    }

    // ==================== 写入语义 ====================

    @Test
    void 整批校验通过才落盘() throws IOException {
        ModelProfileRegistry registry = seeded();
        assertNull(registry.bindDomain("default", List.of("chat-a"), null));

        /* 一批里含一个非法绑定（chat-b 没声明生图端点）：整批都不该落盘：
           否则用户看到一句错误，磁盘上却留下"主模型已改"的中间态 */
        String reason = registry.bindDomain("default", List.of("chat-b"),
                Map.of(ModelProfile.EP_IMAGES, List.of("chat-b")));
        assertNotNull(reason);

        assertEquals(List.of("chat-a"),
                registry.domainBindings().get("default").family(ModelProfile.EP_CHAT),
                "校验失败时主模型不能被改成 chat-b");
        assertTrue(registry.domainBindings().get("default").family(ModelProfile.EP_IMAGES).isEmpty(),
                "同一个批次里合法的那个族也不该落盘");
    }

    @Test
    void 没提到的族保持不变() throws IOException {
        ModelProfileRegistry registry = seeded();
        assertNull(registry.bindDomain("default", null, Map.of(ModelProfile.EP_IMAGES, List.of("img"))));
        assertNull(registry.bindDomain("default", List.of("chat-a"), null));

        assertEquals(List.of("img"),
                registry.domainBindings().get("default").family(ModelProfile.EP_IMAGES),
                "改主模型不该顺手抹掉生图绑定");
        assertEquals(List.of("chat-a"),
                registry.domainBindings().get("default").family(ModelProfile.EP_CHAT));
    }

    @Test
    void 空列表即解绑该族() throws IOException {
        ModelProfileRegistry registry = seeded();
        assertNull(registry.bindDomain("default", List.of("chat-a"), Map.of(
                ModelProfile.EP_IMAGES, List.of("img"),
                ModelProfile.EP_TTS, List.of("tts"))));

        assertNull(registry.bindDomain("default", null, Map.of(ModelProfile.EP_IMAGES, List.of())));
        assertTrue(registry.domainBindings().get("default").family(ModelProfile.EP_IMAGES).isEmpty(),
                "空列表 = 解绑该族");
        assertEquals(List.of("tts"), registry.domainBindings().get("default").family(ModelProfile.EP_TTS),
                "解绑一族不动别的族");
    }

    @Test
    void 空列表解绑主模型_但null不动主模型() throws IOException {
        ModelProfileRegistry registry = seeded();
        assertNull(registry.bindDomain("default", List.of("chat-a"), null));

        // bindDomain 里的 null = 本次不动主模型（只改能力族）
        assertNull(registry.bindDomain("default", null, Map.of(ModelProfile.EP_IMAGES, List.of("img"))));
        assertEquals(List.of("chat-a"), registry.domainBindings().get("default").family(ModelProfile.EP_CHAT),
                "chatAliases == null 是「不动」，不是「解绑」");

        // 而 registry.bind 沿旧口径：null 或空都表示解绑主模型
        assertNull(registry.bind("default", null));
        assertTrue(registry.domainBindings().get("default").family(ModelProfile.EP_CHAT).isEmpty());
        assertEquals(List.of("img"), registry.domainBindings().get("default").family(ModelProfile.EP_IMAGES),
                "解绑主模型不该连能力族一起清掉");
    }

    // ==================== 级联与反查 ====================

    @Test
    void 删除档案会清掉它在能力族里的绑定() throws IOException {
        ModelProfileRegistry registry = seeded();
        assertNull(registry.bindDomain("default", null, Map.of(ModelProfile.EP_IMAGES, List.of("img"))));
        assertTrue(registry.domainsUsing("img").contains("default"),
                "能力族绑定同样算「在用」，删除时要能提示到是哪个域");

        registry.delete("img");

        assertFalse(registry.domainBindings().containsKey("default"),
                "摘空之后整条绑定要移除，落盘不留空壳");
    }

    @Test
    void 删除档案不影响其它族的绑定() throws IOException {
        ModelProfileRegistry registry = seeded();
        assertNull(registry.bindDomain("default", List.of("chat-a"), Map.of(
                ModelProfile.EP_IMAGES, List.of("img"),
                ModelProfile.EP_TTS, List.of("tts"))));

        registry.delete("img");

        assertEquals(List.of("chat-a"), registry.domainBindings().get("default").family(ModelProfile.EP_CHAT));
        assertEquals(List.of("tts"), registry.domainBindings().get("default").family(ModelProfile.EP_TTS));
        assertTrue(registry.domainBindings().get("default").family(ModelProfile.EP_IMAGES).isEmpty());
    }

    @Test
    void 可绑族列表与绑定门禁同源() throws IOException {
        ModelProfileRegistry registry = seeded();

        assertTrue(registry.profile("chat-a").orElseThrow().domainBindable(),
                "对话档案能当主模型");
        assertFalse(registry.profile("img").orElseThrow().domainBindable(),
                "生图档案当不了主模型 —— 卡片上的「绑定」入口据此隐藏");
        assertTrue(registry.profile("img").orElseThrow().endpoints().contains(ModelProfile.EP_IMAGES),
                "但它能被绑到生图族（门禁只看端点族）");
        assertTrue(ModelProfile.BINDABLE_FAMILIES.containsAll(ModelProfile.CAPABILITY_FAMILIES));
        assertFalse(ModelProfile.BINDABLE_FAMILIES.contains(ModelProfile.EP_EMBEDDING));
        assertFalse(ModelProfile.BINDABLE_FAMILIES.contains(ModelProfile.EP_RERANK));
    }
}
