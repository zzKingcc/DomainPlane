package com.zzkingcc.domainplane.infrastructure.ingestion.txt;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义分块（semantic chunking，txt 入库可选第 4 步，与 {@link Chunker} 互补）。
 *
 * 原理：把小节正文切成句子，逐句嵌入向量，相邻句子余弦相似度低于阈值时断开，
 * 得到的切片在主题边界上更整齐；同时保留 {@code maxChars} 硬上限（超限句子按码点硬切，
 * 切点避开代理对，保证 UTF-8 写回不乱码）。
 *
 * 与 {@link Chunker} 的分工：两者都不跨标题（标题层由上层已经切好），也不碰代码块/表格
 *（不可切块由 {@link BlockSplitter} 独占处理）。语义分块默认关闭，开启后在同一小节内部
 *按语义边界再切，适合"段落很长但主题多次转折"的文档；结构感知与语义分块二选一，
 *由 {@link SemanticChunking} 的运行期开关控制，未启用或模型不可用时自动退回结构感知切片，
 *导入不中断。
 *
 * @author zzkingcc
 */
@Slf4j
public final class SemanticChunker implements TextChunker {

    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.6;
    public static final int DEFAULT_MIN_SENTENCES = 2;

    private final EmbeddingModel model;
    private final double similarityThreshold;
    private final int minSentences;
    private final int maxChars;
    /** 嵌入失败后只打一次日志，避免刷满日志 */
    private volatile boolean embedFailureLogged;

    public SemanticChunker(EmbeddingModel model, double similarityThreshold, int minSentences, int maxChars) {
        this.model = model;
        this.similarityThreshold = Math.max(0.0, Math.min(1.0, similarityThreshold));
        this.minSentences = Math.max(1, minSentences);
        this.maxChars = Math.max(1, maxChars);
    }

    @Override
    public List<String> chunk(List<String> bodyLines) {
        String body = String.join("\n", bodyLines).strip();
        if (body.isEmpty()) {
            return List.of();
        }

        List<String> sentences = Chunker.splitSentences(body);
        if (sentences.isEmpty()) {
            return List.of();
        }

        int n = sentences.size();
        float[][] vectors = embedAll(sentences);
        if (vectors == null) {
            // 嵌入不可用时退回纯长度打包，导入不中断
            return packBySize(sentences);
        }

        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i > 0 && !current.isEmpty() && current.size() >= minSentences
                    && shouldCut(vectors[i - 1], vectors[i])) {
                emitGroup(current, chunks);
                current = new ArrayList<>();
            }
            current.add(sentences.get(i));
        }
        emitGroup(current, chunks);
        return chunks;
    }

    /**
     * 逐句嵌入；失败返回 null（由调用方决定退回结构感知切片）。
     */
    private float[][] embedAll(List<String> sentences) {
        try {
            List<TextSegment> segments = new ArrayList<>(sentences.size());
            for (String sentence : sentences) {
                segments.add(TextSegment.from(sentence));
            }
            Response<List<Embedding>> response = model.embedAll(segments);
            List<Embedding> embeddings = response == null ? null : response.content();
            if (embeddings == null || embeddings.size() != sentences.size()) {
                throw new IllegalStateException("嵌入返回条数与请求不一致（请求 " + sentences.size()
                        + " 条，返回 " + (embeddings == null ? 0 : embeddings.size()) + " 条）");
            }
            float[][] vectors = new float[sentences.size()][];
            for (int i = 0; i < sentences.size(); i++) {
                vectors[i] = embeddings.get(i).vector();
            }
            return vectors;
        } catch (Throwable t) {
            if (!embedFailureLogged) {
                embedFailureLogged = true;
                log.warn("[语义分块] 句子嵌入失败，退回按长度打包：{}", t.getMessage());
            }
            return null;
        }
    }

    /** 相邻句子余弦相似度低于阈值时断开；向量缺失或维度不一致视为主题断点（切开更安全）。 */
    private boolean shouldCut(float[] prev, float[] curr) {
        if (prev == null || curr == null || prev.length == 0 || curr.length == 0
                || prev.length != curr.length) {
            return true;
        }
        double dot = 0.0;
        double normPrev = 0.0;
        double normCurr = 0.0;
        for (int i = 0; i < prev.length; i++) {
            dot += prev[i] * curr[i];
            normPrev += prev[i] * prev[i];
            normCurr += curr[i] * curr[i];
        }
        if (normPrev <= 0.0 || normCurr <= 0.0) {
            return true;
        }
        return dot / (Math.sqrt(normPrev) * Math.sqrt(normCurr)) < similarityThreshold;
    }

    /**
     * 把一组句子打包成一片；超过 {@code maxChars} 时按句子贪心切分，单句超限再按码点硬切。
     */
    private void emitGroup(List<String> sentences, List<String> out) {
        if (sentences.isEmpty()) {
            return;
        }
        String joined = join(sentences);
        if (count(joined) <= maxChars) {
            out.add(joined);
            return;
        }
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String candidate = current.isEmpty() ? sentence : current + "\n" + sentence;
            if (count(candidate) > maxChars) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current = new StringBuilder();
                }
                if (count(sentence) > maxChars) {
                    out.addAll(hardCut(sentence));
                } else {
                    current.append(sentence);
                }
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
    }

    /** 纯长度打包（嵌入失败时的退路）：贪心按句子装满 maxChars，单句超限硬切。 */
    private List<String> packBySize(List<String> sentences) {
        List<String> chunks = new ArrayList<>();
        emitGroup(sentences, chunks);
        return chunks;
    }

    /** 超限单句按码点硬切，每片不超过 maxChars，切点避开代理对。 */
    private List<String> hardCut(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            current.appendCodePoint(cp);
            if (current.length() >= maxChars) {
                parts.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** 句子拼成切片正文：中文侧直接接，英文侧补一个空格（与 {@link Chunker} 同一条约定）。 */
    private static String join(List<String> sentences) {
        StringBuilder sb = new StringBuilder();
        for (String sentence : sentences) {
            if (sb.length() == 0) {
                sb.append(sentence);
                continue;
            }
            if (cjkSide(sb.charAt(sb.length() - 1))) {
                sb.append(sentence);
            } else {
                sb.append(' ').append(sentence);
            }
        }
        return sb.toString();
    }

    private static boolean cjkSide(char c) {
        return c >= 0x2E80;
    }

    private static int count(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }
}
