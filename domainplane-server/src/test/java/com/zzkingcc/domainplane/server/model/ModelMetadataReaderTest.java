package com.zzkingcc.domainplane.server.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 元数据解析的纯单测 —— 一个网络请求都不发。
 *
 * 把"清单长什么样 → 判定成什么"这件事整段钉住：名称匹配的三种结果、模态词表翻译、
 * 由输出模态推端点族、子类型过滤的两道保护（过滤器被忽略时不采信 / 命中多条端点族）、
 * {@code /v1} 补全规则。控制器侧只覆盖"读到的结果怎么落档"，解析细节全在这里。
 *
 * @author zzkingcc
 */
class ModelMetadataReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode list(String json) {
        try {
            return MAPPER.readTree(json).path("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 名称匹配 ====================

    @Test
    void 名称精确匹配时不做任何放宽() {
        JsonNode data = list("""
                {"data":[{"id":"Qwen3-8B"},{"id":"qwen3-8b"}]}""");

        ModelMetadataReader.NameMatch match = ModelMetadataReader.matchByName(data, "Qwen3-8B");

        assertFalse(match.normalized(), "精确命中就不该走规范化");
        assertEquals("Qwen3-8B", match.id());
        assertFalse(match.ambiguous());
    }

    @Test
    void 名称按忽略大小写与vendor前缀规范化匹配() {
        JsonNode data = list("""
                {"data":[{"id":"vendor/Qwen3-8B"}]}""");

        ModelMetadataReader.NameMatch match = ModelMetadataReader.matchByName(data, "qwen3-8b");

        assertTrue(match.normalized(), "大小写 + vendor/ 前缀差异应被规范化吃掉");
        assertEquals("vendor/Qwen3-8B", match.id());
    }

    @Test
    void 规范化后命中多条判歧义且不挑一条() {
        JsonNode data = list("""
                {"data":[{"id":"providerA/Qwen3-8B"},{"id":"providerB/Qwen3-8B"}]}""");

        ModelMetadataReader.NameMatch match = ModelMetadataReader.matchByName(data, "Qwen3-8B");

        assertTrue(match.ambiguous(), "同名模型分属两家时必须报歧义 —— 挑一条等于绑到别的模型上");
        assertNull(match.entry());
        assertEquals(2, match.candidates().size());
    }

    @Test
    void 清单里没有该模型时返回未命中() {
        JsonNode data = list("""
                {"data":[{"id":"a"}]}""");

        ModelMetadataReader.NameMatch match = ModelMetadataReader.matchByName(data, "b");

        assertNull(match.entry());
        assertFalse(match.ambiguous(), "一条都没撞上不是歧义");
    }

    // ==================== 模态词表 ====================

    @Test
    void 模态词表只认认识的词其余丢弃() {
        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE),
                ModelMetadataReader.mapModalities(List.of("text", "image")));
        assertEquals(List.of(ModelProfile.MOD_EMBEDDING),
                ModelMetadataReader.mapModalities(List.of("embeddings")), "复数写法也要认");
        assertEquals(List.of(ModelProfile.MOD_SCORE),
                ModelMetadataReader.mapModalities(List.of("relevance_score")),
                "重排的分数：服务商响应字段名是 relevance_score");
        assertEquals(List.of(), ModelMetadataReader.mapModalities(List.of("telepathy")),
                "不认识的词直接丢弃，绝不猜");
    }

    @Test
    void 输出模态推端点族() {
        assertEquals(Set.of(ModelProfile.EP_CHAT),
                ModelMetadataReader.endpointsOf(List.of(ModelProfile.MOD_TEXT)));
        assertEquals(Set.of(ModelProfile.EP_EMBEDDING),
                ModelMetadataReader.endpointsOf(List.of(ModelProfile.MOD_EMBEDDING)));
        assertEquals(Set.of(ModelProfile.EP_RERANK),
                ModelMetadataReader.endpointsOf(List.of(ModelProfile.MOD_SCORE)),
                "分数输出单独判：它既无文本也无向量输出");
        assertEquals(Set.of(ModelProfile.EP_IMAGES),
                ModelMetadataReader.endpointsOf(List.of(ModelProfile.MOD_IMAGE)),
                "只出图 = 内容生成端点");
        assertEquals(Set.of(ModelProfile.EP_CHAT),
                ModelMetadataReader.endpointsOf(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE)),
                "又能出文本又能出图 = 多模态输出的对话模型，仍归对话");
    }

    // ==================== architecture ====================

    @Test
    void architecture的两个模态数组优先于modality字符串() {
        JsonNode entry = list("""
                {"data":[{"id":"m","architecture":{
                  "input_modalities":["text","image"],
                  "output_modalities":["text"],
                  "modality":"audio->video"}}]}""").get(0);

        ModelMetadataReader.MetadataResult r = ModelMetadataReader.fromArchitecture(entry);

        assertTrue(r.authoritative(), "architecture 是完整枚举，可以覆盖档案里过时的声明");
        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), r.input());
        assertEquals(List.of(ModelProfile.MOD_TEXT), r.output());
        assertEquals(List.of(ModelProfile.EP_CHAT), r.endpoints());
    }

    @Test
    void 只给modality字符串时按箭头拆分() {
        JsonNode entry = list("""
                {"data":[{"id":"m","architecture":{"modality":"text+image->text"}}]}""").get(0);

        ModelMetadataReader.MetadataResult r = ModelMetadataReader.fromArchitecture(entry);

        assertEquals(List.of(ModelProfile.MOD_TEXT, ModelProfile.MOD_IMAGE), r.input());
        assertEquals(List.of(ModelProfile.MOD_TEXT), r.output());
    }

    // ==================== sub_type 过滤 ====================

    @Test
    void 过滤器被忽略时判不出端点族() {
        Set<String> full = Set.of("a", "b");
        Map<String, Set<String>> bySubType = new LinkedHashMap<>();
        /* 每个子类型都回了全量清单 —— 说明服务商不认 sub_type，任何结论都是假的 */
        bySubType.put("chat", Set.of("a", "b"));
        bySubType.put("embedding", Set.of("a", "b"));

        assertNull(ModelMetadataReader.decideBySubType("a", full, bySubType),
                "过滤没生效就不能采信，否则每个子类型都会命中、所有端点族都会被标上");
    }

    @Test
    void 过滤生效时按命中的子类型判定端点族() {
        Set<String> full = Set.of("m-chat", "m-rerank");
        Map<String, Set<String>> bySubType = new LinkedHashMap<>();
        bySubType.put("chat", Set.of("m-chat"));
        bySubType.put("reranker", Set.of("m-rerank"));

        ModelMetadataReader.MetadataResult r =
                ModelMetadataReader.decideBySubType("m-rerank", full, bySubType);

        assertNotNull(r);
        assertFalse(r.authoritative(), "sub_type 给的是端点规范基线值，不能覆盖已有声明");
        assertEquals(List.of(ModelProfile.EP_RERANK), r.endpoints());
        assertEquals(List.of(ModelProfile.MOD_TEXT), r.input());
        assertEquals(List.of(ModelProfile.MOD_SCORE), r.output());
    }

    @Test
    void 过滤生效但该模型一个子类型都没命中时判不出() {
        Set<String> full = Set.of("m-chat", "m-embed");
        Map<String, Set<String>> bySubType = new LinkedHashMap<>();
        bySubType.put("chat", Set.of("m-chat"));

        assertNull(ModelMetadataReader.decideBySubType("m-ghost", full, bySubType));
    }

    @Test
    void 子类型清单里的vendor前缀也能对上() {
        Set<String> full = Set.of("vendor/m-embed");
        Map<String, Set<String>> bySubType = new LinkedHashMap<>();
        bySubType.put("chat", Set.of("m-chat"));
        bySubType.put("embedding", Set.of("vendor/m-embed"));

        ModelMetadataReader.MetadataResult r =
                ModelMetadataReader.decideBySubType("m-embed", full, bySubType);

        assertNotNull(r, "规范化匹配对子类型清单同样成立");
        assertEquals(List.of(ModelProfile.EP_EMBEDDING), r.endpoints());
    }

    // ==================== 地址 ====================

    @Test
    void 只对漏了版本段的地址补v1() {
        assertEquals("https://api.example.com/v1", ModelMetadataReader.appendV1("https://api.example.com"));
        assertEquals("https://api.example.com/v1", ModelMetadataReader.appendV1("https://api.example.com/"));
        assertNull(ModelMetadataReader.appendV1("https://api.example.com/v1"),
                "已经带 /v1 就没有别的候选可试，不该拼成 /v1/v1");
        assertNull(ModelMetadataReader.appendV1("  "));
    }

    // ==================== 空读 ====================

    @Test
    void 什么都没读到时模态与端点族都是空而不是null() {
        ModelMetadataReader.MetadataResult r = ModelMetadataReader.MetadataResult.undeclared("测试");

        assertEquals(List.of(), r.endpoints());
        assertEquals(List.of(), r.input());
        assertEquals(List.of(), r.output());
        assertFalse(r.authoritative());
        assertEquals("测试", r.note());
    }
}
