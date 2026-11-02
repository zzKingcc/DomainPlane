package com.zzkingcc.domainplane.server.settings;

import com.zzkingcc.domainplane.server.env.StorageLocations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储配置文件的三种旧格式迁移（读盘路径，不经过校验）。
 *
 * <p>钉的是"升级不该丢东西"：迁移跑在读盘时，一旦漏掉某个体，域上就变成
 * 「知识体已失效」——不报错，只是这个域静默没有知识库。</p>
 */
@DisplayName("InfraSettingsStore：旧格式迁移不丢知识体")
class InfraSettingsMigrateTest {

    @TempDir
    Path tempDir;

    /** 两条连接：一台 ES（做关键字）、一台 Qdrant（做向量） */
    private static String connections() {
        return "\"connections\":{"
                + "\"es-main\":{\"id\":\"es-main\",\"type\":\"elasticsearch\",\"es\":{\"host\":\"es.internal\"}},"
                + "\"qdrant-main\":{\"id\":\"qdrant-main\",\"type\":\"qdrant\",\"vector\":{\"uri\":\"http://q:6333\"}}"
                + "}";
    }

    private InfraSettings load(String json) throws Exception {
        Path dir = tempDir.resolve("config");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("infra-settings.json"), json, StandardCharsets.UTF_8);

        StandardEnvironment env = new StandardEnvironment();
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("domainplane.settings.path", dir.toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new InfraSettingsStore(new StorageLocations(env)).load();
    }

    @Test
    @DisplayName("中间版存储集：一个集 = 一个双路知识体，原文槽不迁")
    void setsBecomeDualSlotBodies() throws Exception {
        InfraSettings s = load("{"
                + connections() + ","
                + "\"sets\":{\"default\":{\"name\":\"默认集\",\"keyword\":\"es-main\","
                + "\"vector\":\"qdrant-main\",\"text\":\"blob-main\"}}"
                + "}");

        InfraSettings.KnowledgeBody body = s.body("default");
        assertNotNull(body, "集 id 沿用为体 id");
        assertEquals("es-main", body.keywordId());
        assertEquals("qdrant-main", body.vectorId());
        assertTrue(body.hybrid(), "集里关键字与向量都填了 → 迁出来就该是混合体");
    }

    @Test
    @DisplayName("单用途体：keyword → 关键字槽，text → 丢弃（原文不再是知识体的一路）")
    void kindBodiesBecomeSingleSlot() throws Exception {
        InfraSettings s = load("{"
                + connections() + ","
                + "\"bodies\":{"
                + "\"kb-kw\":{\"id\":\"kb-kw\",\"kind\":\"keyword\",\"connection\":\"es-main\"},"
                + "\"kb-txt\":{\"id\":\"kb-txt\",\"kind\":\"text\",\"connection\":\"es-main\"}"
                + "}}");

        InfraSettings.KnowledgeBody kw = s.body("kb-kw");
        assertNotNull(kw);
        assertEquals("es-main", kw.keywordId());
        assertNull(kw.vectorId());
        assertNull(s.body("kb-txt"), "原文体不再属于知识体");
    }

    @Test
    @DisplayName("混合格式：已经是双槽的体不能被 kind 迁移顺手丢掉")
    void mixedFormat_keepsDualSlotBodies() throws Exception {
        InfraSettings s = load("{"
                + connections() + ","
                + "\"bodies\":{"
                + "\"kb-old\":{\"id\":\"kb-old\",\"kind\":\"keyword\",\"connection\":\"es-main\"},"
                + "\"kb-new\":{\"id\":\"kb-new\",\"name\":\"新体\",\"keyword\":\"es-main\","
                + "\"vector\":\"qdrant-main\"}"
                + "}}");

        InfraSettings.KnowledgeBody old = s.body("kb-old");
        assertNotNull(old, "旧格式体迁成单槽体");
        assertEquals("es-main", old.keywordId());

        InfraSettings.KnowledgeBody converted = s.body("kb-new");
        assertNotNull(converted, "已经是双槽格式的体必须原样保留：丢了它，绑它的域就静默没有知识库");
        assertEquals("es-main", converted.keywordId());
        assertEquals("qdrant-main", converted.vectorId());
    }
}
