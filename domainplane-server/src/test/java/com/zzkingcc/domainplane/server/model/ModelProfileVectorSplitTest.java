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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量档案与其它档案<b>分开存放</b>（{@code profiles} vs {@code embeddingProfiles}）。
 *
 * <p>分开的动机：向量模型是全局唯一单选、不参与域绑定，与"可绑到域上按需调用"的
 * 对话 / 生图 / 语音那类档案是两种东西。混在一个 map 里时，管控台「模型」段只能靠
 * endpoints 过滤才不至于把向量模型画进去：漏一处就会冒出"怎么还有一个向量模型"。</p>
 *
 * <p>这里盯住三件最容易出错的事：迁移旧布局、类型变化时清另一侧、
 * 以及<b>任何写入都不能把向量桶整个丢掉</b>（深拷贝漏一个 map 就会）。</p>
 */
class ModelProfileVectorSplitTest {

    @TempDir
    Path tempDir;

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

    private Path settingsFile() {
        return tempDir.resolve("config").resolve("models.json");
    }

    private void seed(String json) throws IOException {
        Path file = settingsFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static ModelProfile chat(String alias) {
        return new ModelProfile(alias,List.of("chat"),List.of("text"),List.of("text"),"https://x/v1","k",alias,null,null,null,null,null,List.of(),null);
    }

    private static ModelProfile images(String alias) {
        return new ModelProfile(alias,List.of("images"),List.of("text"),List.of("image"),"https://x/v1","k",alias,null,null,null,null,null,List.of(),null);
    }

    private static ModelProfile vector(String alias) {
        return new ModelProfile(alias,List.of("embedding"),List.of("text"),List.of("embedding"),"https://x/v1","k",alias,null,null,null,null,2048,List.of(),null);
    }

    /** 同时支持对话与向量：它必须留在模型桶，否则既不能绑域、也从「模型」段消失 */
    private static ModelProfile dual(String alias) {
        return new ModelProfile(alias,List.of("chat", "embedding"),List.of("text"),List.of("text"),"https://x/v1","k",alias,null,null,null,null,1024,List.of(),null);
    }

    private static ModelProfileSettings.ProfileData data(ModelProfile p) {
        return ModelProfileSettings.ProfileData.from(p);
    }

    private static List<String> aliases(List<ModelProfile> list) {
        return list.stream().map(ModelProfile::alias).sorted().toList();
    }

    // ==================== 纯逻辑：分桶 ====================

    @Test
    void splitMovesOnlyVectorOnlyProfilesOutOfTheModelBucket() {
        ModelProfileSettings s = new ModelProfileSettings();
        s.getProfiles().put("chat-a", data(chat("chat-a")));
        s.getProfiles().put("img", data(images("img")));
        s.getProfiles().put("vec", data(vector("vec")));
        s.getProfiles().put("dual", data(dual("dual")));

        assertTrue(s.splitVectorProfiles(), "有纯向量档案时应当报告有改动");

        assertTrue(s.getEmbeddingProfiles().containsKey("vec"));
        assertFalse(s.getProfiles().containsKey("vec"), "纯向量档案不该留在模型桶");
        assertTrue(s.getProfiles().containsKey("dual"), "同时支持 chat 的档案要留在模型桶，否则没法绑域");
        assertTrue(s.getProfiles().containsKey("chat-a"));
        assertTrue(s.getProfiles().containsKey("img"), "生图这类非对话档案也要留在模型桶（否则页面上会消失）");
        assertFalse(s.splitVectorProfiles(), "已经分开过了，不该再报有改动");
    }

    @Test
    void putRoutesByTypeAndClearsTheOtherBucket() {
        ModelProfileSettings s = new ModelProfileSettings();
        s.put("m", data(vector("m")));
        assertTrue(s.getEmbeddingProfiles().containsKey("m"));
        assertFalse(s.getProfiles().containsKey("m"));

        /* 重新探测后它变成了对话模型：必须从向量桶摘掉，否则两个桶各留一份、语义矛盾 */
        s.put("m", data(chat("m")));
        assertTrue(s.getProfiles().containsKey("m"));
        assertFalse(s.getEmbeddingProfiles().containsKey("m"), "类型变了就不能再留在向量桶");

        /* 反向同理 */
        s.put("m", data(vector("m")));
        assertTrue(s.getEmbeddingProfiles().containsKey("m"));
        assertFalse(s.getProfiles().containsKey("m"));
    }

    @Test
    void findLooksInBothBucketsAndRemoveClearsBoth() {
        ModelProfileSettings s = new ModelProfileSettings();
        s.put("c", data(chat("c")));
        s.put("v", data(vector("v")));

        assertEquals("c", s.find("c").getModelName());
        assertEquals("v", s.find("v").getModelName(), "纯向量档案也要能按别名查到");
        assertEquals(null, s.find("nope"));
        assertEquals(2, s.allProfiles().size());

        assertTrue(s.remove("v"));
        assertFalse(s.remove("v"), "第二次删就没了");
        assertEquals(null, s.find("v"));
    }

    // ==================== 读盘迁移 ====================

    @Test
    void loadMigratesLegacyLayoutAndPersistsTheSplit() throws IOException {
        /* 旧布局：向量档案混在 profiles 里，只靠 endpoints 区分 */
        seed("""
                {"profiles":{
                   "chat-a":{"endpoints":["chat"],"input":["text"],"output":["text"],
                             "baseUrl":"https://x/v1","apiKey":"k","modelName":"chat-a"},
                   "vec":{"endpoints":["embedding"],"input":["text"],"output":["embedding"],
                          "baseUrl":"https://x/v1","apiKey":"k","modelName":"vec","dimensions":2048}
                 },
                 "embeddingAlias":"vec",
                 "domainBindings":{}}
                """);
        ModelProfileStore store = store();
        ModelProfileSettings loaded = store.load();

        assertTrue(loaded.getEmbeddingProfiles().containsKey("vec"), "旧布局里的向量档案要迁到独立存放");
        assertFalse(loaded.getProfiles().containsKey("vec"));
        assertEquals("vec", loaded.getEmbeddingAlias(), "迁移不能丢掉当前向量模型的选择");
        assertTrue(loaded.find("chat-a").toProfile("chat-a").params().isEmpty(),
                "老文件没有 params 字段：读入后应为空 map，行为零变化");

        String rewritten = Files.readString(settingsFile(), StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("embeddingProfiles"),
                "迁移结果必须落盘，否则文件里看不出到底分开了没有");

        /* 再读一次：已经是新布局，不需要（也不应该）再次迁移 */
        ModelProfileSettings reread = store.load();
        assertTrue(reread.getEmbeddingProfiles().containsKey("vec"));
        assertFalse(reread.getProfiles().containsKey("vec"));
    }

    // ==================== 注册表视图 ====================

    @Test
    void registryKeepsVectorProfilesOutOfTheModelList() {
        ModelProfileRegistry registry = new ModelProfileRegistry(store());
        registry.save(vector("vec"));
        registry.save(chat("chat-a"));
        registry.save(images("img"));

        assertEquals(List.of("chat-a", "img"), aliases(registry.profiles()),
                "「模型」段的数据源不该出现向量档案");
        assertEquals(List.of("vec"), aliases(registry.embeddingProfiles()));
        assertEquals(List.of("chat-a", "img", "vec"), aliases(registry.allProfiles()));
        assertTrue(registry.profile("vec").isPresent(), "按别名仍要能取到向量档案");
        assertTrue(registry.hasEmbeddingProfile());
        assertTrue(registry.hasChatModel());
    }

    @Test
    void embeddingProfileCountMatchesTheVectorSectionSource() {
        ModelProfileRegistry registry = new ModelProfileRegistry(store());
        registry.save(vector("vec"));
        registry.save(chat("chat-a"));
        registry.save(dual("dual"));

        /* 概览与「模型设置」向量段共用同一个数据源：endpoints 含 embedding 的都算，
           双类型档案在两个段各出现一次，这里也必须计进去：两处数不一致会让人以为档案丢了。 */
        assertEquals(2, registry.embeddingProfileCount(), "纯向量与双类型都算，纯对话不算");
        assertEquals(List.of("vec"), aliases(registry.embeddingProfiles()),
                "双类型档案不在向量桶里，它留在模型桶（否则没法绑域）");
    }

    @Test
    void writesDoNotDropTheVectorBucket() {
        ModelProfileRegistry registry = new ModelProfileRegistry(store());
        registry.save(vector("vec"));
        registry.setEmbeddingAlias("vec");

        /* 一次与向量无关的写入：深拷贝漏掉向量桶的话，这里会把它整桶丢掉 */
        registry.save(chat("chat-a"));
        registry.unbindDomains(List.of("default.sales"));

        assertEquals(List.of("vec"), aliases(registry.embeddingProfiles()), "写入后向量桶不能少东西");

        ModelProfileRegistry fresh = new ModelProfileRegistry(store());
        assertEquals(List.of("vec"), aliases(fresh.embeddingProfiles()),
                "向量档案必须落盘，重启后还在");
        assertEquals("vec", fresh.embeddingAlias(), "当前向量模型的选择也要落盘");
    }

    @Test
    void vectorProfileCanBeDeletedWhileDualProfileStaysBindable() {
        ModelProfileRegistry registry = new ModelProfileRegistry(store());
        registry.save(vector("vec"));
        registry.save(dual("dual"));

        /* 纯向量档案在另一桶里，删除必须能找到它（旧实现只查 profiles 会报"档案不存在"） */
        assertTrue(registry.delete("vec").deleted(), "独立存放的向量档案也要能删掉");

        /* 双类型档案留在模型桶，因此仍可绑域 */
        assertNull(registry.bind("default.sales", List.of("dual")));
        assertEquals(List.of("dual"), registry.resolve("default.sales").aliases());
    }
}
