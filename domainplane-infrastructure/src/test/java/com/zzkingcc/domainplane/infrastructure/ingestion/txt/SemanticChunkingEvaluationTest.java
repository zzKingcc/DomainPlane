package com.zzkingcc.domainplane.infrastructure.ingestion.txt;

import com.zzkingcc.domainplane.infrastructure.ingestion.txt.SemanticChunkerTest.FixedVectorEmbeddingModel;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构感知切片 vs 语义分块的对比评估（F05 P2）。
 *
 * 结论口径：语义分块作为 {@link Chunker} 的互补，在不改变结构完整性（标题/代码/表格由上层
 * 独占，语义分块只介入小节内部正文）的前提下，能把主题转折处切开，而结构感知只按
 * 长度打包会把不同主题的段落拼进同一片。
 */
@Slf4j
class SemanticChunkingEvaluationTest {

    private static final String BODY =
            "用户注册后必须验证邮箱地址。未验证的账号无法发布内容。" +
            "文件上传支持以下格式：jpg、png、pdf。单个文件大小不得超过 5MB。" +
            "超出限制的文件会被直接拒绝。";

    private static final float[] V_REGISTER = {1f, 0f};
    private static final float[] V_REGISTER_CLOSE = {0.95f, 0.05f};
    private static final float[] V_UPLOAD = {0f, 1f};
    private static final float[] V_UPLOAD_CLOSE = {0.05f, 0.95f};

    private static EmbeddingModel model() {
        Map<String, float[]> vectors = new LinkedHashMap<>();
        vectors.put("用户注册后必须验证邮箱地址。", V_REGISTER);
        vectors.put("未验证的账号无法发布内容。", V_REGISTER_CLOSE);
        vectors.put("文件上传支持以下格式：jpg、png、pdf。", V_UPLOAD);
        vectors.put("单个文件大小不得超过 5MB。", V_UPLOAD_CLOSE);
        vectors.put("超出限制的文件会被直接拒绝。", V_UPLOAD);
        return new FixedEvalModel(vectors);
    }

    @Test
    void 同一文档_结构切片一片_语义切片按主题成两片() {
        List<String> body = List.of(BODY);

        // 结构感知：整段 150 余字 < maxChars 400，打包成一片（不会为了"长"而按主题切）
        TextChunker structure = new Chunker(400, 1, 60);
        List<String> structureChunks = structure.chunk(body);
        assertEquals(1, structureChunks.size(), "结构感知不应按主题切开，实际 " + structureChunks.size() + " 片");

        // 语义分块：注册|上传主题转折处断开
        TextChunker semantic = new SemanticChunker(model(), 0.6, 2, 400);
        List<String> semanticChunks = semantic.chunk(body);
        assertEquals(2, semanticChunks.size(), "语义分块应在主题转折处断开，实际 " + semanticChunks);
        assertTrue(semanticChunks.get(0).contains("验证邮箱地址") && semanticChunks.get(0).contains("无法发布内容"),
                "第一片应为注册主题：" + semanticChunks.get(0));
        assertTrue(semanticChunks.get(1).contains("jpg、png、pdf") && semanticChunks.get(1).contains("直接拒绝"),
                "第二片应为上传主题：" + semanticChunks.get(1));

        // 两者都不丢字符，也不拦腰截断句子（未触硬上限）
        assertEquals(BODY, String.join("", structureChunks));
        assertEquals(BODY, String.join("", semanticChunks));

        log.info("[语义分块评估] 结构切片 {} 片 -> {}；语义切片 {} 片 -> {}",
                structureChunks.size(), structureChunks, semanticChunks.size(), semanticChunks);
    }

    @Test
    void 开启语义分块后_txt_splitter_边界随之变化_关闭后恢复() {
        Document doc = Document.from(BODY, Metadata.from("file_name", "评估.txt"));

        // 默认：结构感知，一片
        assertEquals(1, new TxtSplitter(400, 1, 60).split(doc).size());

        SemanticChunking.configure(model(), true, 0.6, 2);
        try {
            assertEquals(2, new TxtSplitter(400, 1, 60).split(doc).size(), "开启后 txt 管线也按语义切分");
        } finally {
            SemanticChunking.configure(model(), false, 0.6, 2); // 恢复默认，不影响其他测试类
        }

        assertEquals(1, new TxtSplitter(400, 1, 60).split(doc).size(), "关闭后回退结构感知，行为不变");
    }

    // ==================== 测试替身 ====================

    static final class FixedEvalModel implements EmbeddingModel {

        private final Map<String, float[]> vectors;

        FixedEvalModel(Map<String, float[]> vectors) {
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
}
