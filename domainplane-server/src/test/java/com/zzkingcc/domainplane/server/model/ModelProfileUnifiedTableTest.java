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
 * 档案只有<b>一张表</b>（{@code profiles}），旧 {@code embeddingProfiles} 只是读兼容字段。
 *
 * <p>历史上这里测的是"按类型分两个桶"，那套结构已经拆掉：分桶依据（是不是纯向量）
 * 与消费依据（endpoints 含不含 embedding）不是同一个东西，双类型档案两边都出现，
 * 前端还得再过滤一次。现在盯住的是合桶后的四件事：</p>
 *
 * <ol>
 *   <li>旧文件里的 {@code embeddingProfiles} 要合回来，一条都不能丢；</li>
 *   <li>迁移是幂等的，且写回的文件不再带旧键（否则文件里永远留着两份真相）；</li>
 *   <li>任何写入都不能把表里其它档案冲掉（深拷贝漏一个 map 就会）；</li>
 *   <li>{@code embeddingProfiles()} 变成全量表上的视图，双类型档案也在内。</li>
 * </ol>
 */
class ModelProfileUnifiedTableTest {

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
        return new ModelProfile(alias, List.of("chat"), List.of("text"), List.of("text"),
                "https://x/v1", "k", alias, null, null, null, null, List.of(), null);
    }

    private static ModelProfile images(String alias) {
        return new ModelProfile(alias, List.of("images"), List.of("text"), List.of("image"),
                "https://x/v1", "k", alias, null, null, null, null, List.of(), null);
    }

    private static ModelProfile vector(String alias) {
        return new ModelProfile(alias, List.of("embedding"), List.of("text"), List.of("embedding"),
                "https://x/v1", "k", alias, null, null, null, null, List.of(), null);
    }

    /** 同时支持对话与向量：可绑域，也可当向量模型用，两个视图里都该出现 */
    private static ModelProfile dual(String alias) {
        return new ModelProfile(alias, List.of("chat", "embedding"), List.of("text"), List.of("text"),
                "https://x/v1", "k", alias, null, null, null, null, List.of(), null);
    }

    private static ModelProfileSettings.ProfileData data(ModelProfile p) {
        return ModelProfileSettings.ProfileData.from(p);
    }

    private static List<String> aliases(List<ModelProfile> list) {
        return list.stream().map(ModelProfile::alias).sorted().toList();
    }

    // ==================== 纯逻辑：单表 ====================

    @Test
    void oneTableHoldsEveryKindOfProfile() {
        ModelProfileSettings s = new ModelProfileSettings();
        s.put("chat-a", data(chat("chat-a")));
        s.put("img", data(images("img")));
        s.put("vec", data(vector("vec")));
        s.put("dual", data(dual("dual")));

        assertEquals(4, s.getProfiles().size(), "四种档案都在同一张表里，不再分流到第二个 Map");
        assertEquals(4, s.allProfiles().size());
        assertNotNull(s.find("vec"));
        assertNotNull(s.find("dual"));
        assertTrue(s.getEmbeddingProfiles().isEmpty(), "内存态里旧桶恒为空");
        assertFalse(s.mergeLegacyProfiles(), "没有旧桶内容时不该报有改动");
    }

    @Test
    void findAndRemoveOperateOnTheSingleTable() {
        ModelProfileSettings s = new ModelProfileSettings();
        s.put("c", data(chat("c")));
        s.put("v", data(vector("v")));

        assertEquals("c", s.find("c").getModelName());
        assertEquals("v", s.find("v").getModelName());
        assertNull(s.find("nope"));

        assertTrue(s.remove("v"));
        assertFalse(s.remove("v"), "第二次删就没了");
        assertNull(s.find("v"));
        assertEquals(1, s.allProfiles().size());
    }

    @Test
    void putOverwritesInPlaceRegardlessOfTypeChange() {
        ModelProfileSettings s = new ModelProfileSettings();
        s.put("m", data(vector("m")));
        s.put("m", data(chat("m")));

        assertEquals(1, s.getProfiles().size(), "改类型只是覆盖同一条，不该留下两份互相矛盾的记录");
        assertEquals(List.of("chat"), s.find("m").getEndpoints());
    }

    // ==================== 旧桶合并 ====================

    @Test
    void mergeLegacyBucketIsIdempotentAndKeepsNewerEntryOnConflict() {
        ModelProfileSettings s = new ModelProfileSettings();
        s.getProfiles().put("chat-a", data(chat("chat-a")));
        s.getProfiles().put("vec", data(chat("vec")));   // 同名冲突：普通表里的这份是后写的
        s.getEmbeddingProfiles().put("vec", data(vector("vec")));
        s.getEmbeddingProfiles().put("old-vec", data(vector("old-vec")));

        assertTrue(s.mergeLegacyProfiles(), "有旧桶内容时应当报告有改动");

        assertEquals(3, s.getProfiles().size());
        assertEquals(List.of("chat"), s.find("vec").getEndpoints(), "同名以 profiles 里的为准");
        assertNotNull(s.find("old-vec"), "旧桶里独有的条目要合回来");
        assertTrue(s.getEmbeddingProfiles().isEmpty(), "合并完旧桶就地清空");
        assertFalse(s.mergeLegacyProfiles(), "已经合过了，不该再报有改动");
    }

    // ==================== 读盘迁移 ====================

    @Test
    void loadFoldsLegacyBucketAndStopsWritingItBack() throws IOException {
        seed("""
                {"profiles":{
                   "chat-a":{"endpoints":["chat"],"input":["text"],"output":["text"],
                             "baseUrl":"https://x/v1","apiKey":"k","modelName":"chat-a"}
                 },
                 "embeddingProfiles":{
                   "vec":{"endpoints":["embedding"],"input":["text"],"output":["embedding"],
                          "baseUrl":"https://x/v1","apiKey":"k","modelName":"vec","dimensions":2048}
                 },
                 "embeddingAlias":"vec",
                 "domainBindings":{}}
                """);
        ModelProfileStore store = store();
        ModelProfileSettings loaded = store.load();

        assertEquals(2, loaded.getProfiles().size(), "旧桶里的向量档案要合回唯一一张表");
        assertNotNull(loaded.find("vec"));
        assertTrue(loaded.getEmbeddingProfiles().isEmpty());
        assertEquals("vec", loaded.getEmbeddingAlias(), "迁移不能丢掉当前向量槽位的选择");
        assertTrue(loaded.find("chat-a").toProfile("chat-a").params().isEmpty(),
                "老文件没有 params 字段：读入后应为空 map，行为零变化");

        String rewritten = Files.readString(settingsFile(), StandardCharsets.UTF_8);
        assertFalse(rewritten.contains("embeddingProfiles"),
                "WRITE_ONLY 旧键不该再出现在磁盘上，否则文件里一直留着两份真相");

        /* 再读一次：已经是新布局，不需要（也不应该）再次迁移 */
        ModelProfileSettings reread = store.load();
        assertEquals(2, reread.getProfiles().size());
        assertNotNull(reread.find("vec"));
    }

    // ==================== 注册表视图 ====================

    @Test
    void registryViewsAllComeFromTheSameTable() {
        ModelProfileRegistry registry = new ModelProfileRegistry(store());
        registry.save(vector("vec"));
        registry.save(chat("chat-a"));
        registry.save(images("img"));

        assertEquals(List.of("chat-a", "img", "vec"), aliases(registry.profiles()),
                "profiles() 现在是全量，不再是「非向量档案」");
        assertEquals(List.of("vec"), aliases(registry.embeddingProfiles()),
                "向量视图只是全量表按端点族过滤的结果");
        assertEquals(List.of("chat-a", "img", "vec"), aliases(registry.allProfiles()));
        assertTrue(registry.profile("vec").isPresent());
        assertTrue(registry.hasEmbeddingProfile());
        assertTrue(registry.hasChatModel());
    }

    @Test
    void embeddingProfileCountIncludesDualTypeProfiles() {
        ModelProfileRegistry registry = new ModelProfileRegistry(store());
        registry.save(vector("vec"));
        registry.save(chat("chat-a"));
        registry.save(dual("dual"));

        assertEquals(2, registry.embeddingProfileCount(), "纯向量与双类型都算，纯对话不算");
        assertEquals(List.of("dual", "vec"), aliases(registry.embeddingProfiles()),
                "双类型档案同样能被选作向量模型，不该从视图里消失");
    }

    @Test
    void writesKeepTheWholeTable() {
        ModelProfileRegistry registry = new ModelProfileRegistry(store());
        registry.save(vector("vec"));
        registry.setEmbeddingAlias("vec");

        /* 一次与向量无关的写入：深拷贝漏表的话，这里会把已有档案整批冲掉 */
        registry.save(chat("chat-a"));
        registry.unbindDomains(List.of("default.sales"));

        assertEquals(List.of("chat-a", "vec"), aliases(registry.profiles()));
        assertEquals(List.of("vec"), aliases(registry.embeddingProfiles()));

        ModelProfileRegistry fresh = new ModelProfileRegistry(store());
        assertEquals(List.of("chat-a", "vec"), aliases(fresh.profiles()), "两张表都要落盘，重启后还在");
        assertEquals("vec", fresh.embeddingAlias(), "当前向量槽位的选择也要落盘");
    }

    @Test
    void vectorProfileCanBeDeletedAndDualStaysBindable() {
        ModelProfileRegistry registry = new ModelProfileRegistry(store());
        registry.save(vector("vec"));
        registry.save(dual("dual"));

        assertTrue(registry.delete("vec").deleted());

        /* 双类型档案声明了 chat 且模态齐全，仍可绑域 */
        assertNull(registry.bind("default.sales", List.of("dual")));
        assertEquals(List.of("dual"), registry.resolve("default.sales").aliases());
    }
}
