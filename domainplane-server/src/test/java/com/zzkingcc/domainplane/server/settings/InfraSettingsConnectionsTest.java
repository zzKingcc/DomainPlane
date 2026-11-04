package com.zzkingcc.domainplane.server.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 具名连接 + 知识体：两路槽位的解析与跨条校验。
 *
 * <p>钉四件事：① 一个知识体可以同时配关键字和向量（两路混合），也可以只配一路；
 * ② 平台自己的 ES 认的是<b>关键字槽</b>那个连接（没有就回落到第一个 ES）；
 * ③ 某一路引用了不存在的连接、或连接类型干不了这活时，保存必须被拦下——不然那条召回
 * 路径会永远空结果，而空结果和"知识库是空的"长得一模一样；④ 一路都没配的体是空壳，
 * 域绑了它等于没绑。</p>
 *
 * @author zzkingcc
 */
@DisplayName("InfraSettings：连接 / 知识体两路槽位的解析与校验")
class InfraSettingsConnectionsTest {

    private static InfraSettings.Connection es(String id, String host, int port) {
        InfraSettings.Connection c = new InfraSettings.Connection();
        c.setId(id);
        c.setName(id);
        c.setType(InfraSettings.TYPE_ELASTICSEARCH);
        InfraSettings.Elasticsearch e = new InfraSettings.Elasticsearch();
        e.setHost(host);
        e.setPort(port);
        c.setEs(e);
        return c;
    }

    private static InfraSettings.Connection qdrant(String id, String uri) {
        InfraSettings.Connection c = new InfraSettings.Connection();
        c.setId(id);
        c.setName(id);
        c.setType(InfraSettings.TYPE_QDRANT);
        InfraSettings.VectorStore v = new InfraSettings.VectorStore();
        v.setUri(uri);
        c.setVector(v);
        return c;
    }

    /** 一个知识体：两路各给一个连接 id（null = 这一路不配） */
    private static InfraSettings.KnowledgeBody body(String id, String keyword, String vector) {
        InfraSettings.KnowledgeBody b = new InfraSettings.KnowledgeBody();
        b.setId(id);
        b.setName(id);
        b.setKeyword(keyword);
        b.setVector(vector);
        return b;
    }

    private static InfraSettings withDefaults(InfraSettings.Connection kw,
                                              InfraSettings.Connection vec,
                                              InfraSettings.KnowledgeBody... bodies) {
        InfraSettings s = new InfraSettings();
        Map<String, InfraSettings.Connection> conns = new LinkedHashMap<>();
        conns.put(kw.getId(), kw);
        if (vec != null) {
            conns.put(vec.getId(), vec);
        }
        s.setConnections(conns);
        Map<String, InfraSettings.KnowledgeBody> map = new LinkedHashMap<>();
        for (InfraSettings.KnowledgeBody b : bodies) {
            map.put(b.getId(), b);
        }
        s.setBodies(map);
        return s;
    }

    @Test
    @DisplayName("一个体同时配两路：关键字与向量各认自己的连接，混合召回的前提成立")
    void bothSlots_oneBody() {
        InfraSettings s = withDefaults(
                es("es-kw", "es-a.internal", 9200),
                qdrant("qdrant-vec", "http://qdrant.internal:6333"),
                body("kb-all", "es-kw", "qdrant-vec"));

        InfraSettings.KnowledgeBody b = s.body("kb-all");
        assertTrue(b.hybrid(), "两路都配了才算混合");
        assertEquals(List.of(InfraSettings.KIND_KEYWORD, InfraSettings.KIND_VECTOR), b.modes());
        assertEquals("es-kw", s.platformConnectionId(InfraSettings.KIND_KEYWORD));
        assertEquals("qdrant-vec", s.platformConnectionId(InfraSettings.KIND_VECTOR));
        assertEquals("es-a.internal", s.defaultEs().getHost(), "平台 ES = 关键字槽那个");
        assertEquals("es-kw", s.defaultEsConnectionId());
        assertEquals("http://qdrant.internal:6333", s.defaultQdrant().getUri());
        assertTrue(s.isVectorKindUsable());
        assertNotNull(s.usableBody("kb-all"));
        assertTrue(s.validate().isEmpty(), s.validate().toString());
    }

    @Test
    @DisplayName("只配关键字：向量那一侧就没有默认值，也不会被当成已配置")
    void keywordOnly_noVectorRoute() {
        InfraSettings s = withDefaults(
                es("es-kw", "es.internal", 9200), null,
                body("kb-kw", "es-kw", null));

        InfraSettings.KnowledgeBody b = s.body("kb-kw");
        assertTrue(b.configured());
        assertFalse(b.hybrid());
        assertEquals(List.of(InfraSettings.KIND_KEYWORD), b.modes());
        assertEquals("es-kw", s.platformConnectionId(InfraSettings.KIND_KEYWORD));
        assertNull(s.platformConnectionId(InfraSettings.KIND_VECTOR));
        assertFalse(s.isVectorKindUsable());
    }

    @Test
    @DisplayName("没有知识体：平台 ES 回落到第一个 ES，而不是\"未配置\"")
    void platformEs_fallsBackToFirstEs() {
        InfraSettings s = withDefaults(es("es-arch", "es-b.internal", 9200), null);

        assertNull(s.platformConnectionId(InfraSettings.KIND_KEYWORD));
        assertEquals("es-b.internal", s.defaultEs().getHost());
        assertEquals("es-arch", s.defaultEsConnectionId());
    }

    @Test
    @DisplayName("两路指向同一台 ES：location 相同（写入才能收敛成一份）")
    void sameInstance_bothSlots_sameLocation() {
        InfraSettings s = withDefaults(
                es("es-kw", "es.internal", 9200),
                es("es-vec", "ES.internal", 9200),
                body("kb-all", "es-kw", "es-vec"));

        assertEquals(s.connection("es-kw").location(), s.connection("es-vec").location(),
                "主机名大小写不同仍是同一台实例");
    }

    @Test
    @DisplayName("换端口就是另一台实例：location 不同")
    void differentPort_differentLocation() {
        InfraSettings s = withDefaults(
                es("es-kw", "es.internal", 9200),
                es("es-vec", "es.internal", 9201),
                body("kb-all", "es-kw", "es-vec"));

        assertFalse(s.connection("es-kw").location().equals(s.connection("es-vec").location()));
    }

    @Test
    @DisplayName("某一路引用了不存在的连接：保存时必须被拦下，并点明是哪一路")
    void danglingSlot_isRejected() {
        InfraSettings s = withDefaults(
                es("es-kw", "es.internal", 9200), null,
                body("kb-mixed", "es-kw", "ghost-vec"));

        List<String> problems = s.validate();
        assertTrue(problems.stream().anyMatch(p -> p.contains("ghost-vec") && p.contains("向量")),
                problems.toString());
    }

    @Test
    @DisplayName("一路都没配：保存时必须被拦下（这个体绑给域等于没绑）")
    void emptyBody_isRejected() {
        InfraSettings s = withDefaults(
                es("es-kw", "es.internal", 9200), null,
                body("kb-empty", null, null));

        List<String> problems = s.validate();
        assertTrue(problems.stream().anyMatch(p -> p.contains("一路都没配")), problems.toString());
        assertNull(s.usableBody("kb-empty"));
    }

    @Test
    @DisplayName("连接类型不在白名单：点名连接而不是静默跳过")
    void unsupportedType_isRejected() {
        InfraSettings.Connection weird = es("redis-ish", "x.internal", 1);
        weird.setType("redis");
        InfraSettings s = withDefaults(weird, null);

        List<String> problems = s.validate();
        assertTrue(problems.stream().anyMatch(p -> p.contains("redis-ish")), problems.toString());
    }

    @Test
    @DisplayName("把 Qdrant 填进关键字槽：保存放行（类型合规性是运行期的事，检索时该路会被跳过）")
    void qdrantOnKeywordSlot_isStoredAsIs() {
        InfraSettings s = withDefaults(
                es("es-kw", "es.internal", 9200),
                qdrant("qdrant-vec", "http://qdrant.internal:6333"),
                body("kb-bad", "qdrant-vec", null));

        assertTrue(s.validate().isEmpty(), s.validate().toString());
        // 配置如实落盘；真正检索时 backendFor 会因缺 keyword 能力跳过这一路并留错误日志
        assertNotNull(s.usableBody("kb-bad"), "连接存在且可用，保存不拦");
    }

    @Test
    @DisplayName("ES 填进向量槽是合法的：同一份索引既能做关键字也能做向量，不该被拦")
    void esOnVectorSlot_isAllowed() {
        InfraSettings s = withDefaults(
                es("es-kw", "es.internal", 9200),
                es("es-vec", "es.internal", 9200),
                body("kb-all", "es-kw", "es-vec"));

        assertTrue(s.validate().isEmpty(), s.validate().toString());
    }

    @Test
    @DisplayName("连接在但没填地址：这一路算坏的，整块不可用（不是\"配了就好\"）")
    void slotWithBlankConnection_isNotUsable() {
        InfraSettings.Connection blank = es("es-blank", "", 9200);
        InfraSettings s = withDefaults(blank, null, body("kb-blank", "es-blank", null));

        assertNotNull(s.body("kb-blank"));
        assertTrue(s.body("kb-blank").configured(), "填了连接 id 就算配置过");
        assertNull(s.usableBody("kb-blank"), "但地址空着，连不上，不能算可用");
    }

    @Test
    @DisplayName("一路坏一路好：体仍可用（关键字体那台停了不该拖垮向量路）")
    void oneSlotBroken_bodyStillUsable() {
        InfraSettings.Connection blank = es("es-blank", "", 9200);
        InfraSettings s = withDefaults(
                blank,
                qdrant("qdrant-vec", "http://qdrant.internal:6333"),
                body("kb-half", "es-blank", "qdrant-vec"));

        assertNotNull(s.usableBody("kb-half"));
    }

    // ==================== 原文落点（s3 连接，P4） ====================

    private static InfraSettings.Connection s3(String id) {
        InfraSettings.Connection c = new InfraSettings.Connection();
        c.setId(id);
        c.setName(id);
        c.setType(InfraSettings.TYPE_S3);
        InfraSettings.ObjectStorage o = new InfraSettings.ObjectStorage();
        o.setEndpoint("https://cos.ap-guangzhou.myqcloud.com");
        o.setBucket("dp-prod");
        o.setRegion("ap-guangzhou");
        o.setAccessKey("AKIDexample");
        o.setSecretKey("secret");
        o.setPrefix("domainplane/");
        c.setS3(o);
        return c;
    }

    @Test
    @DisplayName("原文落点：local + 每条 s3 连接都可选；指向非对象存储 / 已删连接都会被拦")
    void s3ConnectionAsRawLocation() {
        InfraSettings s = withDefaults(es("es-kw", "es-a.internal", 9200), null,
                body("kb-raw", "es-kw", null));
        s.getConnections().put("cos-kb", s3("cos-kb"));

        assertEquals(List.of("local", "cos-kb"), s.rawLocations(),
                "没有 s3 连接时只有本地；有了连接立刻可选（页面不做假选项）");

        InfraSettings.KnowledgeBody b = s.body("kb-raw");
        b.setRaw("cos-kb");
        assertTrue(s.validate().isEmpty(), s.validate().toString());

        b.setRaw("es-kw");
        assertTrue(s.validate().stream().anyMatch(p -> p.contains("原文落点") && p.contains("es-kw")),
                "原文落在 ES 连接上必须报错（它不在可选的落点清单里）：" + s.validate());

        b.setRaw("cos-gone");
        assertTrue(s.validate().stream().anyMatch(p -> p.contains("原文落点") && p.contains("cos-gone")),
                "指向不存在的连接要拦下：" + s.validate());
    }

    @Test
    @DisplayName("s3 连接不参与召回：没有归属段，也拿得到物理位置指纹")
    void s3ConnectionHasNoRecallUsage() {
        InfraSettings.Connection c = s3("cos-kb");
        assertNull(c.effectiveUsage(), "对象存储只可能是原文落点，不该混进两张召回路表");
        assertTrue(c.isUsable(), "endpoint / 桶 / AK / SK 齐了才算可用");
        assertEquals("s3://dp-prod@https://cos.ap-guangzhou.myqcloud.com/domainplane",
                c.location(), "同桶同前缀 = 同一份数据");
    }
}
