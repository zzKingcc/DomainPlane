package com.zzkingcc.domainplane.server.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.common.metrics.MetricsRegistry;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import com.zzkingcc.domainplane.runtime.storage.BlobStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 切片原文档案：知识库的权威源，索引是它重建出来的派生数据。
 *
 * 为什么需要它：换向量模型 / 换检索后端都要求"能取回原文"。纯向量库存不下原文，
 * 而 ES 存得下——于是"保原文重灌"这件事在过去是 ES 的隐性福利，换后端即失效。
 * 把原文收进 {@link BlobStore} 之后，重灌从"数据迁移"降级为"重新生成索引"，
 * 这才是 F05 去掉 ES 强依赖的核心收益；接口抽象只是外壳。
 *
 * 载体用已落地的 BlobStore（本地默认，将来可切 S3），键
 * {@code kb/chunks/{indexName}/{docId}.json}——{@code indexName} 直接用
 * {@code KbIndexes.nameOf(domain)} 的全名，不另造哈希：删域时按前缀列举即可整域清除，
 * 也多不出"两套命名分叉"的风险点（一审修订 C1）。
 *
 * 写入序：**ingest 成功 → 写 archive → 返回**。archive 写失败不算上传失败
 * （索引已可用，用户可见、可检索），但必须告警并计入指标——缺档由
 * {@link ChunkArchiveReconciler} 从索引补写，静默失败会让换模型重灌丢文档。
 *
 * 删除：删文档 / 删域级联时一并删，顺序是先删 exporter 预览文件与 archive，
 * 再删 collection（与 exporter 同族：都是"人核对用 / 重灌输入"的旁挂产物）。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class ChunkArchive {

    /** 全部档案键的前缀（对账与整域清除都按它列举） */
    public static final String PREFIX = "kb/chunks/";

    private static final String SUFFIX = ".json";

    private final BlobStore blobStore;
    private final ObjectMapper objectMapper;

    public ChunkArchive(BlobStore blobStore, ObjectMapper objectMapper) {
        this.blobStore = blobStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 写一个文档的全部切片（覆盖语义：同 docId 重传先写后删，天然幂等）。
     *
     * **向量在此剥离**：archive 是原文权威源，向量是派生数据（换模型即废）。
     * 带上向量会让每个 JSON 大出维度倍（768 维 ≈ 6KB/条），而重灌反正要重新
     * embed——存它就是存一份注定作废的复印件。
     *
     * 失败只告警不抛：上传主流程已经成功（索引里可检索），此时抛异常会让
     * 一次成功的上传对外报错；而缺档有对账兜底，代价可承受。
     */
    public void write(String index, String docId, List<KbChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String key = keyOf(index, docId);
        try {
            List<KbChunk> textOnly = new ArrayList<>(chunks.size());
            for (KbChunk chunk : chunks) {
                textOnly.add(chunk.vector() == null
                        ? chunk
                        : new KbChunk(chunk.id(), chunk.text(), chunk.metadata(), null));
            }
            byte[] json = objectMapper.writeValueAsBytes(textOnly);
            blobStore.put(key, new java.io.ByteArrayInputStream(json));
            MetricsRegistry.kbArchiveBytes(json.length);
            log.info("[原文档案] 已归档 docId={}（索引 {}）：{} 条切片，{} 字节",
                    docId, index, chunks.size(), json.length);
        } catch (Exception e) {
            // 告警 + 指标：缺档由对账补写，但这里必须留痕——
            // 静默失败会让"换模型重灌"在很久以后才丢文档，且毫无线索
            log.warn("[原文档案] 归档失败（docId={}，索引 {}），对账将补写: {}",
                    docId, index, e.getMessage());
            MetricsRegistry.kbArchiveMissing();
        }
    }

    /**
     * 读一个文档的全部切片。
     *
     * @return 切片列表；档案不存在时为空。读取或解析失败抛异常——调用方是
     *         重灌与对账，"读不全就往下走"会静默丢文档（设计第 11 节：
     *         读不全立即抛、不往下走）
     */
    public Optional<List<KbChunk>> read(String index, String docId) {
        String key = keyOf(index, docId);
        Optional<InputStream> in;
        try {
            in = blobStore.open(key);
        } catch (Exception e) {
            throw new IllegalStateException("读取原文档案[" + key + "]失败：" + e.getMessage(), e);
        }
        if (in.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream stream = in.get()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            stream.transferTo(out);
            List<KbChunk> chunks = objectMapper.readValue(
                    out.toByteArray(), new TypeReference<List<KbChunk>>() {});
            return Optional.of(chunks);
        } catch (Exception e) {
            throw new IllegalStateException("解析原文档案[" + key + "]失败：" + e.getMessage(), e);
        }
    }

    /** 删一个文档的档案（幂等；删不动只告警——孤儿留给对账回收） */
    public void delete(String index, String docId) {
        String key = keyOf(index, docId);
        try {
            blobStore.delete(key);
            log.info("[原文档案] 已删除 docId={}（索引 {}）", docId, index);
        } catch (Exception e) {
            log.warn("[原文档案] 删除失败（docId={}，索引 {}），孤儿留给对账: {}",
                    docId, index, e.getMessage());
        }
    }

    /**
     * 删一个 collection 的全部档案（删域 / purgeAll 用）。
     *
     * 单个键删不动不中断其余：与 MediaBlobSweeper 同一条纪律，
     * 一个坏对象不该挡住整域清除；漏下的由对账当孤儿回收。
     */
    public int deleteCollection(String index) {
        String prefix = PREFIX + index + "/";
        List<String> keys;
        try {
            keys = blobStore.listKeys(prefix);
        } catch (Exception e) {
            log.warn("[原文档案] 列举索引 {} 的档案失败（跳过，孤儿留给对账）: {}", index, e.getMessage());
            return 0;
        }
        int deleted = 0;
        for (String key : keys) {
            try {
                blobStore.delete(key);
                deleted++;
            } catch (Exception e) {
                log.warn("[原文档案] 删除档案 {} 失败（下轮对账回收）: {}", key, e.getMessage());
            }
        }
        if (deleted > 0) {
            log.info("[原文档案] 已清除索引 {} 的 {} 个文档档案", index, deleted);
        }
        return deleted;
    }

    /**
     * 全部档案的清单：索引名 → 该索引下已归档的 docId 集合（对账用）。
     *
     * 解析规则 = 键布局的逆运算：{@code kb/chunks/{index}/{docId}.json}。
     * 不符合布局的键（未来的其它前缀、人为放进去的文件）跳过不猜——
     * 猜错会把活档案当孤儿删掉。
     */
    public Map<String, Set<String>> inventory() {
        List<String> keys;
        try {
            keys = blobStore.listKeys(PREFIX);
        } catch (Exception e) {
            throw new IllegalStateException("列举原文档案失败，无法对账：" + e.getMessage(), e);
        }
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String key : keys) {
            if (!key.startsWith(PREFIX) || !key.endsWith(SUFFIX)) {
                continue;
            }
            String body = key.substring(PREFIX.length(), key.length() - SUFFIX.length());
            int slash = body.indexOf('/');
            if (slash <= 0 || slash == body.length() - 1) {
                continue;
            }
            out.computeIfAbsent(body.substring(0, slash), k -> new java.util.LinkedHashSet<>())
                    .add(body.substring(slash + 1));
        }
        return out;
    }

    /**
     * 档案键的年龄护栏（对账的竞态防护，与 MediaBlobSweeper 同口径）。
     *
     * 写入序是"索引成功 → 写 archive"，正常不会撞窗口；护栏防的是将来的
     * 写入序变化与"补写刚写完、登记表还没刷新"的瞬时态。取不到时间不跳过：
     * 年龄是护栏不是判据。
     */
    public boolean isYoung(String key, java.time.Duration minAge) {
        if (minAge == null || minAge.isZero()) {
            return false;
        }
        return blobStore.lastModified(key)
                .map(time -> java.time.Duration.between(time, java.time.Instant.now()).compareTo(minAge) < 0)
                .orElse(false);
    }

    /** 该索引下已归档的 docId 集合（缺档判定的"archive 侧"输入） */
    public Set<String> docIdsOf(String index) {
        Set<String> ids = inventory().get(index);
        return ids == null ? Set.of() : ids;
    }

    static String keyOf(String index, String docId) {
        return PREFIX + index + "/" + docId + SUFFIX;
    }

    /** 仅供测试与对账复用：列出全部档案键 */
    List<String> allKeys() {
        try {
            return new ArrayList<>(blobStore.listKeys(PREFIX));
        } catch (Exception e) {
            return List.of();
        }
    }
}
