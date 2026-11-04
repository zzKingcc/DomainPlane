package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBodyResolver;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeStatsService;
import com.zzkingcc.domainplane.server.knowledge.RetrievalPlan;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 知识库运维区：诊断、无主集合认领、遗留集合清理、合并知识体。
 *
 * <p><b>为什么单独一个控制器</b>：这些动作的共同点是"越过正常的体级动线"——
 * 诊断要直接看存储侧有哪些集合，认领要为<b>已经存在</b>的集合补一个体身份，
 * 清理要删"没有体在用"的集合，合并要跨两个体搬数据。它们都不是日常操作，
 * 混进 {@link AdminBodyController} 会让那张卡片式的接口表失去"一切以体为起点"的形状。</p>
 *
 * <p>路径用 {@code /admin/bodies/ops/*} 而不是 {@code /admin/bodies/*}：后者会把
 * {@code diagnostics} 之类当成知识体 id，等哪天补上 {@code GET /admin/bodies/{id}} 就撞车。</p>
 *
 * <p><b>三条安全纪律</b>：</p>
 * <ul>
 *   <li>诊断只读，任何一格读不到都 fail-open（一台坏后端不该让整个运维区打不开）；</li>
 *   <li>清理必须输集合名确认，且<b>活体当前落点上的集合一律拒删</b>——那是数据不是残留；</li>
 *   <li>合并只写目标体，源体一个字节都不动（是否删源体由用户在卡片上显式决定）。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@Slf4j
@RestController
@RequestMapping("/admin/bodies/ops")
public class AdminBodyOpsController {

    private final RetrievalPlan retrievalPlan;
    private final KnowledgeBodyResolver bodyResolver;
    private final InfraSettingsHolder infraHolder;
    private final KnowledgeBaseService knowledgeBase;
    private final KnowledgeStatsService statsService;

    public AdminBodyOpsController(@Qualifier("domainplaneRetrievalPlan") RetrievalPlan retrievalPlan,
                                  KnowledgeBodyResolver bodyResolver,
                                  InfraSettingsHolder infraHolder,
                                  KnowledgeBaseService knowledgeBase,
                                  KnowledgeStatsService statsService) {
        this.retrievalPlan = retrievalPlan;
        this.bodyResolver = bodyResolver;
        this.infraHolder = infraHolder;
        this.knowledgeBase = knowledgeBase;
        this.statsService = statsService;
    }

    // ==================== 诊断 ====================

    /**
     * 逐连接列出全部 {@code domainplane_kb_*} 集合：属于哪个体、维度、切片数、状态。
     *
     * <p>状态三种：{@code ok}（体当前就在用这台）、{@code legacy}（体还在，但它已经换到
     * 别的落点，这个是残留）、{@code orphan}（不属于任何体，多半是迁移中断或手工建的）。
     * 后两种才是运维区要处理的对象。</p>
     */
    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics() {
        InfraSettings infra = infraHolder.current();
        List<Map<String, Object>> backends = new ArrayList<>();
        int totalCollections = 0;
        int orphans = 0;
        int legacy = 0;
        long totalChunks = 0;

        for (KnowledgeBackend backend : retrievalPlan.backends()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", backend.id());
            row.put("location", backend.location());
            row.put("keyword", backend.capabilities().keyword());
            row.put("vector", backend.capabilities().vector());
            Optional<KnowledgeStore> storeOpt = backend.store();
            List<Map<String, Object>> collections = new ArrayList<>();
            boolean reachable = false;
            if (storeOpt.isPresent()) {
                KnowledgeStore store = storeOpt.get();
                reachable = store.ping();
                if (reachable) {
                    for (String name : store.listCollections()) {
                        if (!name.startsWith(KbIndexes.PREFIX)) {
                            continue;
                        }
                        Map<String, Object> col = collectionRow(infra, backend, store, name);
                        String status = String.valueOf(col.get("status"));
                        if ("orphan".equals(status)) {
                            orphans++;
                        } else if ("legacy".equals(status)) {
                            legacy++;
                        }
                        totalChunks += ((Number) col.getOrDefault("chunks", 0)).longValue();
                        totalCollections++;
                        collections.add(col);
                    }
                }
            }
            row.put("reachable", reachable);
            row.put("collections", collections);
            backends.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("collections", totalCollections);
        summary.put("orphans", orphans);
        summary.put("legacy", legacy);
        summary.put("chunks", totalChunks);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("generatedAt", Instant.now().toString());
        result.put("summary", summary);
        result.put("backends", backends);
        return result;
    }

    /** 一个集合的诊断行；每一格都 fail-open（读不到就如实说读不到，不打断整页） */
    private Map<String, Object> collectionRow(InfraSettings infra, KnowledgeBackend backend,
                                              KnowledgeStore store, String name) {
        String bodyId = bodyResolver.bodyIdOfIndex(name);
        InfraSettings.KnowledgeBody body = bodyId == null ? null : infra.body(bodyId);
        String status;
        if (body == null) {
            status = "orphan";
        } else if (liveLocations(body).contains(backend.location())) {
            status = "ok";
        } else {
            status = "legacy";
        }
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("name", name);
        col.put("bodyId", bodyId);
        col.put("bodyName", body == null ? null : body.effectiveName());
        col.put("status", status);
        try {
            col.put("dimension", store.currentVectorDims(name));
        } catch (RuntimeException e) {
            col.put("dimension", null);
        }
        try {
            col.put("chunks", store.count(name));
        } catch (RuntimeException e) {
            col.put("chunks", 0L);
        }
        return col;
    }

    /** 这个体当前真正落在哪几台机器上（按 location 比，不看连接 id） */
    private Set<String> liveLocations(InfraSettings.KnowledgeBody body) {
        Set<String> out = new LinkedHashSet<>();
        for (String connectionId : new String[]{body.keywordId(), body.vectorId()}) {
            if (connectionId == null) {
                continue;
            }
            String location = locationOf(connectionId);
            if (location != null) {
                out.add(location);
            }
        }
        return out;
    }

    private String locationOf(String connectionId) {
        for (KnowledgeBackend backend : retrievalPlan.backends()) {
            if (backend.id().equals(connectionId)) {
                return backend.location();
            }
        }
        return null;
    }

    // ==================== 无主集合认领 ====================

    /**
     * 把一个无主集合认领成知识体：索引名沿用现名（<b>不重新派生</b>），
     * 体 id 用唯一名，两路按这条连接的实际能力填。
     *
     * <p>索引名不重新派生是这条路的全部意义：它已经装着数据，改名字等于换一个集合，
     * 而旧集合既查不到也不会被清理。</p>
     */
    @PostMapping("/adopt")
    public Map<String, Object> adopt(@RequestBody AdoptRequest request) {
        if (request == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "缺少请求体");
        }
        String index = trimToNull(request.getIndex());
        String connection = trimToNull(request.getConnection());
        String slug = KbIndexes.normalizeSlug(request.getSlug());
        if (index == null || connection == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "要指定连接与集合名");
        }
        if (!index.startsWith(KbIndexes.PREFIX)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "集合名必须以 " + KbIndexes.PREFIX + " 开头：" + index);
        }
        String reason = KbIndexes.validateSlug(slug);
        if (reason != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, reason);
        }
        String owner = bodyResolver.bodyIdOfIndex(index);
        if (owner != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "集合「" + index + "」已经属于知识体 " + owner + "，不需要认领");
        }
        KnowledgeBackend backend = requireBackend(connection);
        KnowledgeStore store = backend.store().orElseThrow(() -> new BaseException(
                ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                "连接「" + connection + "」不提供存储能力，不能认领集合"));
        try {
            if (!store.exists(index)) {
                throw new BaseException(ErrorCode.INVALID_PARAMETER,
                        "连接「" + connection + "」上没有集合「" + index + "」：先刷新诊断");
            }
        } catch (BaseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "查询集合是否存在失败：" + e.getMessage());
        }

        Map<String, InfraSettings.KnowledgeBody> bodies =
                new LinkedHashMap<>(infraHolder.current().bodiesOrEmpty());
        for (InfraSettings.KnowledgeBody existing : bodies.values()) {
            if (existing != null && slug.equals(existing.effectiveSlug())) {
                throw new BaseException(ErrorCode.INVALID_PARAMETER,
                        "唯一名「" + slug + "」已被知识体 " + existing.getId() + " 使用");
            }
        }
        if (bodies.containsKey(slug)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "知识体已存在：" + slug);
        }

        InfraSettings.KnowledgeBody body = new InfraSettings.KnowledgeBody();
        body.setId(slug);
        body.setSlug(slug);
        body.setName(hasText(request.getName()) ? request.getName().trim() : slug);
        body.setDescription(request.getDescription());
        body.setIndex(index);
        body.setRaw(InfraSettings.RAW_LOCAL);
        boolean keyword = backend.capabilities().keyword();
        boolean vector = backend.capabilities().vector();
        if (!keyword && !vector) {
            throw new BaseException(ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                    "连接「" + connection + "」既不能做关键字也不能做向量，认领没有意义");
        }
        body.setKeyword(keyword ? connection : null);
        body.setVector(vector ? connection : null);
        bodies.put(slug, body);
        infraHolder.replaceBodies(bodies);
        statsService.invalidate();
        log.info("[知识库] 已认领集合 {}（连接 {}）为知识体 {}（关键字={}，向量={}）",
                index, connection, slug, body.keywordId(), body.vectorId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("action", "adopted");
        result.put("body", slug);
        result.put("index", index);
        result.put("keyword", body.keywordId());
        result.put("vector", body.vectorId());
        return result;
    }

    // ==================== 遗留集合清理 ====================

    /**
     * 删除一个诊断列出的集合（正常路径用不到：删体自会清索引）。
     *
     * <p><b>活体当前落点上的集合一律拒删</b>：那是在用的数据，不是残留。判断按 location，
     * 不是连接 id——同一台 ES 上的两条连接指向同一份集合。</p>
     *
     * @param confirm 必须等于集合名（防误点）
     */
    @DeleteMapping("/collections")
    public Map<String, Object> deleteCollection(@RequestParam("connection") String connection,
                                                @RequestParam("index") String index,
                                                @RequestParam(value = "confirm", required = false) String confirm) {
        String conn = trimToNull(connection);
        String name = trimToNull(index);
        if (conn == null || name == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "要指定连接与集合名");
        }
        if (!name.equals(trimToNull(confirm))) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "删除集合不可恢复，请输入集合名「" + name + "」确认");
        }
        InfraSettings.KnowledgeBody body = infraHolder.current().body(bodyResolver.bodyIdOfIndex(name));
        if (body != null && liveLocations(body).contains(locationOf(conn))) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "集合「" + name + "」正被知识体「" + body.effectiveName()
                            + "」使用（它是这个体当前的落点），不能删；要清理请先给这个体换落点");
        }
        KnowledgeBackend backend = requireBackend(conn);
        KnowledgeStore store = backend.store().orElseThrow(() -> new BaseException(
                ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED, "连接「" + conn + "」不提供存储能力"));
        boolean deleted;
        try {
            deleted = store.deleteCollection(name);
        } catch (RuntimeException e) {
            throw new BaseException(ErrorCode.KNOWLEDGE_BASE_ERROR,
                    "删除集合「" + name + "」失败（存储不可达或权限不足）：" + e.getMessage());
        }
        statsService.invalidate();
        log.info("[知识库] 已清理集合 {}（连接 {}，属于体 {}），结果={}",
                name, conn, body == null ? "无" : body.getId(), deleted);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("action", "collection-deleted");
        result.put("connection", conn);
        result.put("index", name);
        result.put("deleted", deleted);
        if (!deleted) {
            result.put("message", "连接上本来就没有这个集合");
        }
        return result;
    }

    // ==================== 合并知识体 ====================

    /** 把源体的原文重灌进目标体（源体不动） */
    @PostMapping("/merge")
    public Map<String, Object> merge(@RequestBody MergeRequest request) {
        if (request == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "缺少请求体");
        }
        KnowledgeBaseService.MergeResult merged = knowledgeBase.mergeBodies(
                request.getTarget(), request.getSource(), request.isReplace());
        statsService.invalidate();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("action", "merged");
        result.put("target", merged.target());
        result.put("source", merged.source());
        result.put("documents", merged.documents());
        result.put("skipped", merged.skipped());
        result.put("total", merged.total());
        result.put("chunksWritten", merged.chunksWritten());
        result.put("message", "已合并 " + merged.documents() + " 个文档（跳过 " + merged.skipped()
                + "）；源知识体没有删除，确认不需要后可到卡片上删除");
        return result;
    }

    // ==================== 工具 ====================

    private KnowledgeBackend requireBackend(String connectionId) {
        for (KnowledgeBackend backend : retrievalPlan.backends()) {
            if (backend.id().equals(connectionId)) {
                return backend;
            }
        }
        throw new BaseException(ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                "连接「" + connectionId + "」未注册（可能刚被删除或地址没填）");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ==================== 请求体 ====================

    /** 认领无主集合 */
    @Data
    public static class AdoptRequest {
        /** 集合所在的连接 id */
        private String connection;
        /** 集合名（必须以 domainplane_kb_ 开头，认领后原样作为体的冻结索引） */
        private String index;
        /** 唯一名（小写字母 / 数字 / 连字符，2~40） */
        private String slug;
        private String name;
        private String description;
    }

    /** 合并知识体 */
    @Data
    public static class MergeRequest {
        /** 目标体（接收方） */
        private String target;
        /** 源体（提供原文，合并后不删） */
        private String source;
        /** 同名文档是否覆盖 */
        private boolean replace;
    }
}
