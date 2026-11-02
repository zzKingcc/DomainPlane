package com.zzkingcc.domainplane.infrastructure.ingestion.txt;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义分块器的回归样例：所有嵌入向量由固定表提供，结果完全确定，不依赖真实模型或网络。
 */
class SemanticChunkerTest {

    /** s1/s2 同一主题（注册），s3/s4/s5 另一主题（上传），跨主题相似度约为 0 */
    private static final float[] V_REGISTER = {1f, 0f};
    private static final float[] V_REGISTER_CLOSE = {0.95f, 0.05f};
    private static final float[] V_UPLOAD = {0f, 1f};
    private static final float[] V_UPLOAD_CLOSE = {0.05f, 0.95f};
    private static final float[] V_UPLOAD_SAME = {0f, 1f};

    private static final Map<String, float[]> VECTORS = new LinkedHashMap<>() {
        {
            put("用户注册后必须验证邮箱地址。", V_REGISTER);
            put("未验证的账号无法发布内容。", V_REGISTER_CLOSE);
            put("文件上传支持以下格式：jpg、png、pdf。", V_UPLOAD);
            put("单个文件大小不得超过 5MB。", V_UPLOAD_CLOSE);
            put("超出限制的文件会被直接拒绝。", V_UPLOAD_SAME);
        }
    };

    private static final String BODY =
            "用户注册后必须验证邮箱地址。未验证的账号无法发布内容。" +
            "文件上传支持以下格式：jpg、png、pdf。单个文件大小不得超过 5MB。" +
            "超出限制的文件会被直接拒绝。";

    private static final EmbeddingModel MODEL = new FixedVectorEmbeddingModel(VECTORS);

    private static List<String> sentences() {
        return List.of(
                "用户注册后必须验证邮箱地址。",
                "未验证的账号无法发布内容。",
                "文件上传支持以下格式：jpg、png、pdf。",
                "单个文件大小不得超过 5MB。",
                "超出限制的文件会被直接拒绝。");
    }

    // ==================== 语义边界 ====================

    @Test
    void 主题转折处断开_同主题句子留在同一片() {
        SemanticChunker chunker = new SemanticChunker(MODEL, 0.6, 2, 400);

        List<String> chunks = chunker.chunk(List.of(BODY));

        assertEquals(2, chunks.size(), "期望在注册|上传主题转折处断开，实际 " + chunks);
        assertTrue(chunks.get(0).contains("验证邮箱地址") && chunks.get(0).contains("无法发布内容"),
                "第一片应是注册主题：" + chunks.get(0));
        assertTrue(chunks.get(1).contains("jpg、png、pdf") && chunks.get(1).contains("直接拒绝"),
                "第二片应是上传主题：" + chunks.get(1));
    }

    @Test
    void 相似度足够高时不切_即使跨了大段() {
        Map<String, float[]> allClose = new LinkedHashMap<>();
        for (String s : sentences()) {
            allClose.put(s, V_REGISTER); // 全按同一向量，相似度 1.0
        }
        SemanticChunker chunker = new SemanticChunker(new FixedVectorEmbeddingModel(allClose), 0.6, 2, 400);

        List<String> chunks = chunker.chunk(List.of(BODY));

        assertEquals(1, chunks.size(), "全部高相似时不应切开，实际 " + chunks.size() + " 片");
        assertEquals(BODY, chunks.get(0));
    }

    @Test
    void 片段不足最小句数不单独切出_即使主题已转折() {
        SemanticChunker chunker = new SemanticChunker(MODEL, 0.6, 3, 400);

        List<String> chunks = chunker.chunk(List.of(BODY));

        // s1-s2 相似（不切），s2-s3 主题转折但当前只有 2 句 < minSentences 3，不断开
        assertEquals(1, chunks.size(), "minSentences=3 时应留成一片，实际 " + chunks);
    }

    // ==================== 硬上限 ====================

    @Test
    void 单句超过上限按码点硬切_每片不超限() {
        SemanticChunker chunker = new SemanticChunker(MODEL, 0.6, 2, 40);
        String longSentence = "甲".repeat(120); // 无标点，一句 120 字

        List<String> chunks = chunker.chunk(List.of(longSentence));

        assertTrue(chunks.size() >= 3, "应被硬切成多片，实际 " + chunks);
        for (String part : chunks) {
            assertTrue(part.codePointCount(0, part.length()) <= 40,
                    "单片 " + part.codePointCount(0, part.length()) + " 字超限：" + part);
        }
        assertEquals("甲".repeat(120), String.join("", chunks), "硬切不能丢字符");
    }

    @Test
    void 空输入与单句() {
        SemanticChunker chunker = new SemanticChunker(MODEL, 0.6, 2, 400);

        assertEquals(List.of(), chunker.chunk(List.of()));
        assertEquals(List.of(), chunker.chunk(List.of("   ")));
        assertEquals(List.of("只有一句。"), chunker.chunk(List.of("只有一句。")));
    }

    // ==================== 运行期开关 ====================

    @Test
    void 未开启时_resolve返回空_开启后返回分块器() {
        SemanticChunking.configure(MODEL, false, 0.6, 2);
        assertNull(SemanticChunking.resolve(400));

        SemanticChunking.configure(MODEL, true, 0.6, 2);
        assertNotNull(SemanticChunking.resolve(400));

        SemanticChunking.configure(MODEL, false, 0.6, 2); // 恢复默认，不影响其他用例
        assertNull(SemanticChunking.resolve(400));
    }

    @Test
    void 模型不可用时降级不抛异常() {
        SemanticChunker chunker = new SemanticChunker(new FailingEmbeddingModel(), 0.6, 2, 400);

        List<String> chunks = assertDoesNotThrow(() -> chunker.chunk(List.of(BODY)));
        assertEquals(1, chunks.size(), "嵌入失败应退回按长度打包，整段一片");
        assertEquals(BODY, chunks.get(0));
    }

    // ==================== 测试替身 ====================

    /** 固定向量表的嵌入模型：查表命中返回预设向量，未命中返回零向量。 */
    static final class FixedVectorEmbeddingModel implements EmbeddingModel {

        private final Map<String, float[]> vectors;

        FixedVectorEmbeddingModel(Map<String, float[]> vectors) {
            this.vectors = vectors;
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return Response.from(Embedding.from(vectors.getOrDefault(textSegment.text(), new float[0])));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            List<Embedding> embeddings = new ArrayList<>(textSegments.size());
            for (TextSegment segment : textSegments) {
                embeddings.add(embed(segment).content());
            }
            return Response.from(embeddings);
        }
    }

    /** 所有 embed 调用都抛异常，用于验证降级路径。 */
    static final class FailingEmbeddingModel implements EmbeddingModel {

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            throw new RuntimeException("模型不可用");
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            throw new RuntimeException("模型不可用");
        }
    }
}
