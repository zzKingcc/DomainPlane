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
 * 由输出模态推端点族、{@code /v1} 补全规则。控制器侧只覆盖"读到的结果怎么落档"，
 * 解析细节全在这里。
 *
 * <p>服务商私有的 {@code sub_type} 过滤已整套移除：那套语义只有一家认，且"忽略参数回全量清单"
 * 会被误读成"命中所有子类型"。能力只来自清单里的能力字段（{@code architecture}）或实测探测。</p>
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
