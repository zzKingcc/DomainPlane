package com.zzkingcc.domainplane.infrastructure.ingestion.txt;

import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.infrastructure.ingestion.processor.SplitResult;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * txt 切片入口：把 {@link TxtNormalizer} → {@link TxtCleaner} → {@link OutlineReader} → {@link Chunker}
 * 四步串起来。
 *
 * <p>四步里<b>只有「认标题」与格式相关</b> —— 将来加 md / html 只换那一步，其余三步原样复用。
 * 这也是 {@code DocumentProcessStrategyFactory} 与各策略保留不动的意义。</p>
 *
 * <p>切片正文的构造：{@code section_path + 空行 + 正文}。把标题路径写进正文，模型能看出这段话
 * 在第几章第几节；同时向量通道与 BM25 通道吃的是同一份文本，两条召回不会各看各的。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class TxtSplitter implements DocumentSplitter {

    public static final int DEFAULT_MAX_CHARS = 400;
    public static final int DEFAULT_OVERLAP_SENTENCES = 1;
    public static final int DEFAULT_MIN_CHARS = 60;

    private final Chunker chunker;
    private final int maxChars;

    public TxtSplitter() {
        this(DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_SENTENCES, DEFAULT_MIN_CHARS);
    }

    public TxtSplitter(int maxChars, int overlapSentences, int minChars) {
        this.maxChars = maxChars;
        this.chunker = new Chunker(maxChars, overlapSentences, minChars);
    }

    @Override
    public List<TextSegment> split(Document document) {
        return splitWithStats(document).segments();
    }

    @Override
    public List<TextSegment> splitAll(List<Document> documents) {
        return splitAllWithStats(documents).segments();
    }

    /** 分片并带回诊断计数（上传返回值用） */
    public SplitResult splitWithStats(Document document) {
        String fileName = safeFileName(document);
        String text = TxtNormalizer.normalize(document.text());
        if (text.isBlank()) {
            return SplitResult.empty();
        }

        TxtCleaner.CleanResult cleaned = TxtCleaner.clean(text);
        OutlineReader.OutlineResult outline = OutlineReader.read(cleaned.lines(), stripExtension(fileName));

        List<TextSegment> segments = new ArrayList<>();
        List<OutlineReader.Section> owners = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        for (OutlineReader.Section section : outline.sections()) {
            for (String body : chunker.chunk(section.bodyLines())) {
                if (body.codePointCount(0, body.length()) > maxChars) {
                    log.debug("[txt切片] 文件[{}] 切片超出上限（{} 字），通常来自无标点的长文本", fileName,
                            body.codePointCount(0, body.length()));
                }
                bodies.add(body);
                owners.add(section);
            }
        }

        int total = bodies.size();
        for (int i = 0; i < total; i++) {
            OutlineReader.Section section = owners.get(i);
            String path = section.pathText();
            String full = path.isEmpty() ? bodies.get(i) : path + "\n\n" + bodies.get(i);

            Metadata metadata = document.metadata().copy();
            metadata.put(ChunkMetadataKeys.SECTION_PATH, path);
            metadata.put(ChunkMetadataKeys.SECTION_TITLE, section.title());
            metadata.put(ChunkMetadataKeys.CHUNK_SEQ, i + 1);
            metadata.put(ChunkMetadataKeys.CHUNK_TOTAL, total);
            segments.add(TextSegment.from(full, metadata));
        }

        log.info("[txt切片] 文件[{}]：识别标题 {} 个，删噪 {} 行，产出切片 {} 片",
                fileName, outline.titles(), cleaned.dropped(), total);
        return new SplitResult(segments, outline.titles(), cleaned.dropped());
    }

    /** 批量分片并汇总诊断计数 */
    public SplitResult splitAllWithStats(List<Document> documents) {
        List<TextSegment> all = new ArrayList<>();
        int sections = 0;
        int dropped = 0;
        for (Document document : documents) {
            SplitResult one = splitWithStats(document);
            all.addAll(one.segments());
            sections += one.sections();
            dropped += one.droppedLines();
        }
        return new SplitResult(all, sections, dropped);
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 三级回退取文件名：file_name → source → absolute_path */
    private static String safeFileName(Document doc) {
        try {
            String name = doc.metadata().getString(ChunkMetadataKeys.FILE_NAME);
            if (name != null && !name.isBlank()) {
                return name;
            }
            String src = doc.metadata().getString("source");
            if (src != null && !src.isBlank()) {
                int sep = Math.max(src.lastIndexOf('/'), src.lastIndexOf('\\'));
                return sep >= 0 ? src.substring(sep + 1) : src;
            }
            String abs = doc.metadata().getString("absolute_path");
            if (abs != null && !abs.isBlank()) {
                int sep = Math.max(abs.lastIndexOf('/'), abs.lastIndexOf('\\'));
                return sep >= 0 ? abs.substring(sep + 1) : abs;
            }
        } catch (Exception ignored) {
            // metadata 缺失时按 unknown 处理
        }
        return "(unknown)";
    }
}
