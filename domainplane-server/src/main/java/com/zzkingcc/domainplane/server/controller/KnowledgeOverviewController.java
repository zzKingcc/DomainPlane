package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.domain.rag.store.FanOutStore;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
import com.zzkingcc.domainplane.server.config.RagProperties;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeStatsService;
import com.zzkingcc.domainplane.server.knowledge.LiveKnowledgeBackendRegistry;
import com.zzkingcc.domainplane.server.knowledge.RetrievalPlan;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 知识库页的两个只读聚合接口。
 *
 * <p>为什么单开一个控制器：{@code AdminController} 的构造器已经有 20 多个依赖，而这两个
 * 接口只需要"存储配置 + 域绑定 + 统计快照"三样东西。加在那边等于让所有既有测试补参数，
 * 换来的只是省一个类。</p>
 *
 * <p>两者共同的口径纪律：<b>页面显示的数字与"能不能写"必须来自运行时同一套判据</b>。
 * 所以 {@code /target} 的 {@code writable} / {@code fanOut} 是直接调
 * {@link RetrievalPlan#writeStore(String)}（纯配置解析、无 I/O）得出的，
 * 不是另写一份"看起来等价"的规则——那正是"界面允许、后端拒绝"的来源。</p>
 *
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin/kb")
public class KnowledgeOverviewController {

    /** 无域上下文时给前端的空槽占位（前端按 state 上色，不解析文案） */
    private static final String SLOT_ABSENT = "absent";
    private static final String SLOT_MISSING = "missing";
    private static final String SLOT_BLANK = "blank";
    private static final String SLOT_UNREGISTERED = "unregistered";
    private static final String SLOT_INCAPABLE = "incapable";
    private static final String SLOT_OK = "ok";

    private final KnowledgeStatsService knowledgeStatsService;
    private final InfraSettingsHolder infraHolder;
    private final DomainStore domainStore;
    private final DomainRegistry domainRegistry;
    private final LiveKnowledgeBackendRegistry backendRegistry;
    private final RetrievalPlan retrievalPlan;
    private final RagProperties ragProperties;

    public KnowledgeOverviewController(KnowledgeStatsService knowledgeStatsService,
                                       InfraSettingsHolder infraHolder,
                                       DomainStore domainStore,
                                       DomainRegistry domainRegistry,
                                       @Qualifier("domainplaneKnowledgeBackendRegistry")
                                       LiveKnowledgeBackendRegistry backendRegistry,
                                       @Qualifier("domainplaneRetrievalPlan") RetrievalPlan retrievalPlan,
                                       RagProperties ragProperties) {
        this.knowledgeStatsService = knowledgeStatsService;
        this.infraHolder = infraHolder;
        this.domainStore = domainStore;
        this.domainRegistry = domainRegistry;
        this.backendRegistry = backendRegistry;
        this.retrievalPlan = retrievalPlan;
        this.ragProperties = ragProperties;
    }

    /**
     * 知识库页整页快照：汇总 + 每个知识体的两槽落点 / 绑定域 / 文档数 + 孤儿索引。
     *
     * <p>明细只能靠逐台枚举索引拼出来，所以这一份和 {@code /kb/stats} 共用同一个
     * {@link KnowledgeStatsService} 快照（60s TTL），不各自现扫。数字旧了带 {@code stale}，
     * 页面照常展示。</p>
     */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        KnowledgeStatsService.Overview snap = knowledgeStatsService.overview();
        InfraSettings infra = infraHolder.current();
        Map<String, String> bindings = domainStore.bodies();

        // 域 → 体 反转成 体 → 域（一个体可以被多个域绑，这是常态不是异常）
        Map<String, List<String>> domainsByBody = new LinkedHashMap<>();
        bindings.forEach((domain, bodyId) -> {
            if (bodyId == null || bodyId.isBlank()) {
                return;
            }
            domainsByBody.computeIfAbsent(bodyId, key -> new ArrayList<>()).add(domain);
        });

        /* 体清单 = 配置里有的（保持配置顺序）+ 只在绑定里出现的悬空 id。
           后者必须显示：域还绑着一个已删除的体，是最需要被看见的一类配置态。 */
        Set<String> bodyIds = new LinkedHashSet<>(infra.bodiesOrEmpty().keySet());
        bodyIds.addAll(domainsByBody.keySet());

        List<Map<String, Object>> bodies = new ArrayList<>(bodyIds.size());
        int inUse = 0;
        for (String bodyId : bodyIds) {
            InfraSettings.KnowledgeBody body = infra.body(bodyId);
            List<String> domains = domainsByBody.getOrDefault(bodyId, List.of());
            if (!domains.isEmpty()) {
                inUse += 1;
            }
            long[] acc = snap.byBody().get(bodyId);
            List<Map<String, Object>> domainRows = domainRows(domains, snap);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", bodyId);
            row.put("name", body == null ? null : body.effectiveName());
            row.put("status", statusOf(body, domains, snap));
            row.put("modes", body == null ? List.of() : body.modes());
            row.put("effectiveModes", effectiveModes(body));
            row.put("slots", List.of(slotView(infra, body, InfraSettings.KIND_KEYWORD),
                    slotView(infra, body, InfraSettings.KIND_VECTOR)));
            row.put("fanOut", isFanOut(infra, body));
            row.put("domainCount", domains.size());
            row.put("domains", domainRows);
            row.put("documents", acc == null ? 0L : acc[0]);
            row.put("chunks", acc == null ? 0L : acc[1]);
            row.put("indexCount", domainRows.stream()
                    .filter(d -> Boolean.TRUE.equals(d.get("exists")))
                    .count());
            bodies.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("stale", snap.stale());
        body.put("generatedAt", Instant.now().toString());
        body.put("strategy", retrievalPlan.strategy().name());
        body.put("limits", limits());
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("documents", snap.documents());
        platform.put("chunks", snap.chunks());
        platform.put("bodies", bodies.size());
        platform.put("bodiesInUse", inUse);
        platform.put("backends", distinctBackends(snap));
        body.put("platform", platform);
        body.put("bodies", bodies);
        body.put("orphans", orphans(snap));
        return body;
    }

    /**
     * 上传前的落点预检：<b>纯读配置，不碰存储</b>。
     *
     * <p>判据与 {@code upload()} 的拒绝顺序对齐（域已登记 → 绑了体 → 体可用 → 能解析出写入
     * store），所以"按钮可点"就等于"后端不会因为配置原因拒"。文件级的问题（白名单、大小、
     * 同名冲突）分别由 {@code limits} 与前端的文档表判定——那三件事不需要再问一次后端。</p>
     *
     * @param domain 待上传的归属域（留空 = 根域）
     */
    @GetMapping("/target")
    public Map<String, Object> target(@RequestParam(required = false) String domain) {
        String raw = domain == null || domain.isBlank() ? Domains.DEFAULT : domain.trim();
        String normalized = Domains.normalize(raw);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("domain", normalized);
        body.put("strategy", retrievalPlan.strategy().name());
        body.put("limits", limits());

        String invalid = Domains.validatePath(raw);
        if (invalid != null) {
            body.put("domainExists", false);
            body.put("writable", false);
            body.put("reason", "DOMAIN_INVALID");
            body.put("reasonText", "域路径不合法：" + invalid);
            return body;
        }

        boolean exists = domainRegistry.contains(normalized);
        String bodyId = domainStore.bodyOf(normalized);
        InfraSettings infra = infraHolder.current();
        InfraSettings.KnowledgeBody bound = infra.body(bodyId);

        body.put("domainExists", exists);
        body.put("bound", bodyId != null && !bodyId.isBlank());
        body.put("bodyId", bodyId);
        body.put("bodyName", bound == null ? null : bound.effectiveName());
        body.put("modes", bound == null ? List.of() : bound.modes());
        body.put("effectiveModes", effectiveModes(bound));
        body.put("slots", List.of(slotView(infra, bound, InfraSettings.KIND_KEYWORD),
                slotView(infra, bound, InfraSettings.KIND_VECTOR)));

        if (!exists) {
            body.put("writable", false);
            body.put("reason", "DOMAIN_NOT_FOUND");
            body.put("reasonText", "域不存在，请先在「域空间」创建");
            return body;
        }
        if (bodyId == null || bodyId.isBlank()) {
            body.put("writable", false);
            body.put("reason", "NO_BODY");
            body.put("reasonText", "该域未绑定知识体");
            return body;
        }
        if (bound == null) {
            body.put("writable", false);
            body.put("reason", "BODY_MISSING");
            body.put("reasonText", "该域绑的知识体已不存在");
            return body;
        }
        if (!bound.configured()) {
            body.put("writable", false);
            body.put("reason", "BODY_EMPTY");
            body.put("reasonText", "该知识体两路都没配");
            return body;
        }

        /* 写入落点直接问运行时：writeStore 只做配置解析（取后端、判同机），不发网络请求。
           这样"界面判定的可写"与"后端真正会怎么走"不可能分叉。 */
        try {
            KnowledgeStore store = retrievalPlan.writeStore(normalized);
            boolean fanOut = store instanceof FanOutStore;
            body.put("writable", true);
            body.put("reason", null);
            body.put("fanOut", fanOut);
            body.put("writeBackends", writeBackends(bound, fanOut));
        } catch (RuntimeException e) {
            body.put("writable", false);
            body.put("reason", "BODY_UNUSABLE");
            body.put("reasonText", e.getMessage());
        }
        return body;
    }

    // ==================== 视图拼装 ====================

    /** 一路槽位：空 / 连接已删 / 连接未填地址 / 连接未注册 / 可用 */
    private Map<String, Object> slotView(InfraSettings infra, InfraSettings.KnowledgeBody body, String kind) {
        String connectionId = body == null ? null
                : (InfraSettings.KIND_KEYWORD.equals(kind) ? body.keywordId() : body.vectorId());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("kind", kind);
        v.put("connectionId", connectionId);
        if (connectionId == null) {
            v.put("state", SLOT_ABSENT);
            v.put("usable", false);
            return v;
        }
        InfraSettings.Connection conn = infra.connection(connectionId);
        v.put("name", conn == null ? connectionId : conn.effectiveName());
        v.put("type", conn == null ? null : conn.effectiveType());
        String state;
        if (conn == null) {
            state = SLOT_MISSING;
        } else if (!conn.isUsable()) {
            state = SLOT_BLANK;
        } else {
            KnowledgeBackend backend = backendOf(connectionId);
            if (backend == null) {
                state = SLOT_UNREGISTERED;
            } else if (!supportsKind(backend, kind)) {
                // 能力位不匹配（如关键字槽指到 Qdrant）：运行期会静默跳过这一路，
                // 预检若显示"可用"就是又一个"配置看着对、运行少一路"
                state = SLOT_INCAPABLE;
            } else {
                state = SLOT_OK;
            }
        }
        v.put("state", state);
        v.put("usable", SLOT_OK.equals(state));
        return v;
    }

    private KnowledgeBackend backendOf(String connectionId) {
        return backendRegistry.all().stream()
                .filter(b -> b.id().equals(connectionId))
                .findFirst()
                .orElse(null);
    }

    /** 这种类型的连接能不能干这个用途：关键字只有 ES；向量 ES 与 Qdrant 都行 */
    private static boolean supportsKind(KnowledgeBackend backend, String kind) {
        if (InfraSettings.KIND_KEYWORD.equals(kind)) {
            return backend.capabilities().keyword();
        }
        return InfraSettings.KIND_VECTOR.equals(kind) && backend.capabilities().vector();
    }

    /** 两槽都配了、且物理上不是同一台 = 写入要扇出两份 */
    private boolean isFanOut(InfraSettings infra, InfraSettings.KnowledgeBody body) {
        if (body == null || !body.hybrid()) {
            return false;
        }
        String left = locationOf(infra, body.keywordId());
        String right = locationOf(infra, body.vectorId());
        return left != null && right != null && !left.equals(right);
    }

    private String locationOf(InfraSettings infra, String connectionId) {
        if (connectionId == null) {
            return null;
        }
        return backendRegistry.all().stream()
                .filter(b -> b.id().equals(connectionId))
                .map(KnowledgeBackend::location)
                .findFirst()
                .orElse(null);
    }

    private List<String> writeBackends(InfraSettings.KnowledgeBody body, boolean fanOut) {
        List<String> ids = new ArrayList<>(2);
        if (body.keywordId() != null) {
            ids.add(body.keywordId());
        }
        if (body.vectorId() != null && !ids.contains(body.vectorId())) {
            ids.add(body.vectorId());
        }
        return fanOut ? ids : ids.subList(0, Math.min(1, ids.size()));
    }

    /**
     * 生效召回模式 = 体的两槽 × 全局策略。
     *
     * <p>策略是 yaml 的 {@code domainplane.knowledge.strategy}，在通道层直接闸门
     * （KEYWORD 时向量通道根本不开）。只看体的两槽会把"混合体 + 只开关键字"显示成混合，
     * 于是又一个"配置看着对、运行少一路"。</p>
     */
    private List<String> effectiveModes(InfraSettings.KnowledgeBody body) {
        if (body == null) {
            return List.of();
        }
        KnowledgeProperties.Strategy strategy = retrievalPlan.strategy();
        List<String> out = new ArrayList<>(2);
        for (String mode : body.modes()) {
            if (strategy == KnowledgeProperties.Strategy.KEYWORD
                    && !InfraSettings.KIND_KEYWORD.equals(mode)) {
                continue;
            }
            if (strategy == KnowledgeProperties.Strategy.VECTOR
                    && !InfraSettings.KIND_VECTOR.equals(mode)) {
                continue;
            }
            out.add(mode);
        }
        return out;
    }

    /**
     * 体的状态：missing &gt; empty &gt; dead &gt; no_index &gt; ok。
     *
     * <p>{@code no_index} 必须带 {@code !stale} 才敢判：ES 的 {@code listCollections()}
     * 是 fail-open，"存储不可达"与"一个索引都没有"在数据上同形。快照本身是旧的/失败兜底时
     * 判"有绑定无索引"，等于引导用户去点重建——那是最坏的误操作。</p>
     */
    private String statusOf(InfraSettings.KnowledgeBody body, List<String> domains,
                            KnowledgeStatsService.Overview snap) {
        if (body == null) {
            return "missing";
        }
        if (!body.configured()) {
            return "empty";
        }
        if (infraHolder.current().usableBody(body.getId()) == null) {
            return "dead";
        }
        if (domains.isEmpty() || snap.stale()) {
            return "ok";
        }
        boolean hasIndex = domains.stream().anyMatch(d -> snap.indices().contains(KbIndexes.nameOf(d)));
        return hasIndex ? "ok" : "no_index";
    }

    /** 一个体下每个绑定域的明细：索引名、是否存在、在哪几台、文档/切片 */
    private List<Map<String, Object>> domainRows(List<String> domains, KnowledgeStatsService.Overview snap) {
        List<Map<String, Object>> rows = new ArrayList<>(domains.size());
        for (String domain : domains) {
            String index = KbIndexes.nameOf(domain);
            long[] acc = snap.byDomain().get(domain);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("domain", domain);
            row.put("index", index);
            row.put("exists", snap.indices().contains(index));
            row.put("backends", snap.owners().getOrDefault(index, List.of()));
            row.put("documents", acc == null ? 0L : acc[0]);
            row.put("chunks", acc == null ? 0L : acc[1]);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 孤儿索引：不属于任何现存域的 {@code domainplane_kb_*}。
     *
     * <p>判据用<b>索引名反推</b>（拿现存域的 {@code nameOf} 做集合），不用切片 metadata 里的
     * 域：孤儿索引里的老文档 metadata 还带着已删的域，按 metadata 判会漏掉它们。</p>
     */
    private List<Map<String, Object>> orphans(KnowledgeStatsService.Overview snap) {
        Set<String> expected = new LinkedHashSet<>();
        for (String domain : domainRegistry.ids()) {
            expected.add(KbIndexes.nameOf(domain));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String index : new TreeSet<>(snap.indices())) {
            if (expected.contains(index)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", index);
            row.put("backends", snap.owners().getOrDefault(index, List.of()));
            out.add(row);
        }
        return out;
    }

    private List<String> distinctBackends(KnowledgeStatsService.Overview snap) {
        Set<String> ids = new LinkedHashSet<>();
        snap.owners().values().forEach(ids::addAll);
        return List.copyOf(ids);
    }

    private Map<String, Object> limits() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("allowedExtensions", ragProperties.getAllowedExtensions());
        limits.put("maxFileSize", ragProperties.getMaxFileSize().toBytes());
        return limits;
    }
}
