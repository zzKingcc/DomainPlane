package com.zzkingcc.domainplane.server.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 切片预览导出：把一个文档的全部切片写成一个 txt，落到固定目录。
 *
 * <p>管控台<b>只展示这个文件的路径</b>，不做在线查看 —— 因为要核对的是"切点对不对"，
 * 用编辑器打开、跟原文对着看，比在网页里翻更顺手。</p>
 *
 * <p>导出读的是<b>索引里真正入库的切片</b>（按 {@code chunk_seq} 排序），不是重切一遍，
 * 所以所见即所存：去重跳过了哪片、合并了哪片，文件里都如实反映。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class ChunkExporter {

    /** 单文档最多导出多少片（与列表口径保持一致） */
    private static final int MAX_CHUNKS = 2000;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Set<Character> ILLEGAL_NAME_CHARS =
            Set.of('\\', '/', ':', '*', '?', '"', '<', '>', '|');

    private final ElasticsearchClient esClient;
    private final StorageLocations storageLocations;

    public ChunkExporter(@Qualifier("domainplaneElasticsearchClient") ElasticsearchClient esClient,
                         StorageLocations storageLocations) {
        this.esClient = esClient;
        this.storageLocations = storageLocations;
    }

    /** 切片导出目录 */
    public Path exportDir() {
        return storageLocations.exportDir();
    }

    /**
     * 该文档的切片预览文件路径（不判断是否已存在）。
     */
    public Path pathOf(String fileName, String docId) {
        return storageLocations.exportDir().resolve(fileNameOf(fileName, docId));
    }

    /**
     * 导出该文档的全部切片，覆盖同名文件。
     *
     * @return 落盘路径；该文档在索引里没有任何切片时返回 {@code null}
     */
    public Path export(String index, String docId, String fileName, String domain) {
        List<Chunk> chunks = loadChunks(index, docId);
        if (chunks.isEmpty()) {
            log.warn("[切片导出] docId={} 在索引 {} 中没有任何切片，跳过导出", docId, index);
            return null;
        }
        Path target = pathOf(fileName, docId);
        StringBuilder sb = new StringBuilder();
        sb.append("文件名: ").append(fileName == null ? "" : fileName).append('\n');
        sb.append("docId: ").append(docId).append('\n');
        sb.append("域: ").append(domain == null ? "" : domain).append('\n');
        sb.append("切片数: ").append(chunks.size()).append('\n');
        sb.append("导出时间: ").append(LocalDateTime.now().format(TIMESTAMP)).append('\n');

        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            Chunk chunk = chunks.get(i);
            sb.append("\n================================================\n");
            sb.append('[').append(i + 1).append('/').append(total).append("] ");
            if (!chunk.sectionPath().isBlank()) {
                sb.append(chunk.sectionPath()).append("  ");
            }
            sb.append("(").append(chunk.charCount()).append(" 字)\n");
            sb.append("================================================\n");
            sb.append(chunk.text()).append('\n');
        }

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
            log.info("[切片导出] 已写出 {} 片：{}", total, target);
            return target;
        } catch (IOException e) {
            // 导出只是给人核对用的辅助产物，不能因为它失败就让上传失败
            log.warn("[切片导出] 写文件失败（不影响入库）：{} - {}", target, e.getMessage());
            return null;
        }
    }

    /**
     * 删除某文档的切片预览文件（按 docId 短码在后缀里匹配，不看文件名 —— 文件名可能改过）。
     */
    public void deleteByDocId(String docId) {
        String shortId = shortIdOf(docId);
        String suffix = "." + shortId + ".chunks.txt";
        Path dir = storageLocations.exportDir();
        try (var files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(suffix)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("[切片导出] 删除旧预览文件失败：{} - {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[切片导出] 扫描导出目录失败：{} - {}", dir, e.getMessage());
        }
    }

    // ==================== 内部 ====================

    private List<Chunk> loadChunks(String index, String docId) {
        List<Chunk> chunks = new ArrayList<>();
        try {
            SearchResponse<Map> resp = esClient.search(s -> s
                    .index(index)
                    .size(MAX_CHUNKS)
                    .query(Query.of(q -> q.term(t -> t.field("metadata.doc_id").value(docId))))
                    .sort(sort -> sort.field(f -> f.field("metadata." + ChunkMetadataKeys.CHUNK_SEQ).order(SortOrder.Asc)))
                    .source(src -> src.filter(f -> f.includes("text", "metadata"))), Map.class);
            for (Hit<Map> hit : resp.hits().hits()) {
                chunks.add(toChunk(hit.source()));
            }
        } catch (Exception e) {
            log.warn("[切片导出] 按 chunk_seq 排序读取失败，改为无排序读取：{}", e.getMessage());
            chunks.clear();
            try {
                SearchResponse<Map> resp = esClient.search(s -> s
                        .index(index)
                        .size(MAX_CHUNKS)
                        .query(Query.of(q -> q.term(t -> t.field("metadata.doc_id").value(docId))))
                        .source(src -> src.filter(f -> f.includes("text", "metadata"))), Map.class);
                for (Hit<Map> hit : resp.hits().hits()) {
                    chunks.add(toChunk(hit.source()));
                }
            } catch (Exception inner) {
                log.warn("[切片导出] 读取切片失败：{}", inner.getMessage());
                return List.of();
            }
        }
        chunks.sort(Comparator.comparingInt(Chunk::seq));
        return chunks;
    }

    @SuppressWarnings("unchecked")
    private static Chunk toChunk(Map<String, Object> source) {
        if (source == null) {
            return new Chunk(0, "", "", 0);
        }
        String text = source.get("text") == null ? "" : source.get("text").toString();
        Object metadataObj = source.get("metadata");
        Map<String, Object> metadata = metadataObj instanceof Map ? (Map<String, Object>) metadataObj : Map.of();
        String path = metadata.get(ChunkMetadataKeys.SECTION_PATH) == null
                ? "" : metadata.get(ChunkMetadataKeys.SECTION_PATH).toString();
        int seq = parseInt(metadata.get(ChunkMetadataKeys.CHUNK_SEQ));
        return new Chunk(seq, path, text, text.codePointCount(0, text.length()));
    }

    private static int parseInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@code <原文件名去扩展名>.<docId 前 8 位>.chunks.txt} */
    private static String fileNameOf(String fileName, String docId) {
        String base = fileName == null || fileName.isBlank() ? "document" : fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        StringBuilder safe = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            safe.append(ILLEGAL_NAME_CHARS.contains(c) ? '_' : c);
        }
        String shortId = shortIdOf(docId);
        return safe + "." + shortId + ".chunks.txt";
    }

    private static String shortIdOf(String docId) {
        return docId == null || docId.isBlank()
                ? "unknown"
                : docId.length() <= 8 ? docId : docId.substring(0, 8);
    }

    private record Chunk(int seq, String sectionPath, String text, int charCount) {
    }
}
