package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.domain.rag.store.FanOutStore;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
import com.zzkingcc.domainplane.server.config.RagProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBodyResolver;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeStatsService;
import com.zzkingcc.domainplane.server.knowledge.LiveKnowledgeBackendRegistry;
import com.zzkingcc.domainplane.server.knowledge.RetrievalPlan;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import com.zzkingcc.domainplane.server.settings.LlmModelHolder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 知识库页的服务端：<b>一切以知识体为起点</b>（不按域）。
 *
 * <p>这个控制器取代了 v1 的 {@code /admin/kb/*}：那里的每个写操作都以"域"为参数
 * （上传落到域、重建按域、状态按域），因为 v1 的数据身份是域。v2 起数据身份是知识体，
 * 域只是消费者——所以上传、列表、删文档、重建、删除全部在这里按 <b>bodyId</b> 走，
 * 页面上的每个动作也只从知识体卡片出发。</p>
 *
 * <p>口径纪律（沿用 v1 最重要的一条）：<b>页面显示的数字与"能不能写"必须来自运行时同一套判据</b>。
 * 所以 {@code /target} 的 {@code writable} / {@code fanOut} 直接调
 * {@link RetrievalPlan#writeStoreOfBody(String)}（纯配置解析、无 I/O），
 * 不是另写一份"看起来等价"的规则——那正是"界面允许、后端拒绝"的来源。</p>
 *
 * <p>另外三条与 v1 不同的硬规定：</p>
 * <ul>
 *   <li><b>唯一名创建后不可改</b>：它决定了索引名（创建时冻结）。改名可以，搬数据不行；</li>
 *   <li><b>删知识体是唯一会毁数据的入口</b>：需输名确认，且默认保留原文（{@code keepRaw=true}），
 *       只删索引；要连原文一起删必须显式 {@code keepRaw=false}；</li>
 *   <li><b>删域不经过这里</b>：删域只解绑（见 {@code DomainPurgeService}）。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@Slf4j
@RestController
@RequestMapping("/admin/bodies")
public class AdminBodyController {

    /** 槽位状态：没配 / 连接已删 / 地址没填 / 连接未注册 / 类型不匹配 / 可用 */
    private static final String SLOT_ABSENT = "absent";
    private static final String SLOT_MISSING = "missing";
    private static final String SLOT_BLANK = "blank";
    private static final String SLOT_UNREGISTERED = "unregistered";
    private static final String SLOT_INCAPABLE = "incapable";
    private static final String SLOT_OK = "ok";

    private final KnowledgeStatsService statsService;
    private final KnowledgeBaseService knowledgeBase;
    private final KnowledgeBodyResolver bodyResolver;
    private final InfraSettingsHolder infraHolder;
    private final DomainStore domainStore;
    private final DomainRegistry domainRegistry;
    private final LiveKnowledgeBackendRegistry backendRegistry;
    private final RetrievalPlan retrievalPlan;
    private final RagProperties ragProperties;
    private final LlmModelHolder modelHolder;
    private final StorageLocations storageLocations;

    public AdminBodyController(KnowledgeStatsService statsService,
                               KnowledgeBaseService knowledgeBase,
                               KnowledgeBodyResolver bodyResolver,
                               InfraSettingsHolder infraHolder,
                               DomainStore domainStore,
                               DomainRegistry domainRegistry,
                               @Qualifier("domainplaneKnowledgeBackendRegistry")
                               LiveKnowledgeBackendRegistry backendRegistry,
                               @Qualifier("domainplaneRetrievalPlan") RetrievalPlan retrievalPlan,
                               RagProperties ragProperties,
                               LlmModelHolder modelHolder,
                               StorageLocations storageLocations) {
        this.statsService = statsService;
        this.knowledgeBase = knowledgeBase;
        this.bodyResolver = bodyResolver;
        this.infraHolder = infraHolder;
        this.domainStore = domainStore;
        this.domainRegistry = domainRegistry;
        this.backendRegistry = backendRegistry;
        this.retrievalPlan = retrievalPlan;
        this.ragProperties = ragProperties;
        this.modelHolder = modelHolder;
        this.storageLocations = storageLocations;
    }

    // ==================== 页面快照 ====================

    /**
     * 知识库页整页快照：汇总 + 每个知识体（落点 / 索引 / 绑定域 / 文档数）+ 无主索引。
     *
     * <p>明细只能靠逐台枚举索引拼出来，所以和文档列表共用同一个
     * {@link KnowledgeStatsService} 快照（60s TTL），不各自现扫。数字旧了带 {@code stale}，
     * 页面照常展示并标注。</p>
     */
    @GetMapping
    public Map<String, Object> list() {
        KnowledgeStatsService.Overview snap = statsService.overview();
        InfraSettings infra = infraHolder.current();
        Map<String, List<String>> domainsByBody = bodyResolver.domainsByBody();

        /* 体清单 = 配置里有的（保持配置顺序）+ 只在绑定里出现的悬空 id。
           后者必须显示：域还绑着一个已删除的体，是最需要被看见的一类配置态。 */
        Set<String> bodyIds = new LinkedHashSet<>(infra.bodiesOrEmpty().keySet());
        bodyIds.addAll(domainsByBody.keySet());

        List<Map<String, Object>> bodies = new ArrayList<>(bodyIds.size());
        int inUse = 0;
        int unbound = 0;
        for (String bodyId : bodyIds) {
            InfraSettings.KnowledgeBody body = infra.body(bodyId);
            List<String> domains = domainsByBody.getOrDefault(bodyId, List.of());
            if (domains.isEmpty()) {
                unbound += 1;
            } else {
                inUse += 1;
            }
            bodies.add(row(bodyId, body, domains, snap));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("stale", snap.stale());
        result.put("generatedAt", Instant.now().toString());
        result.put("strategy", retrievalPlan.strategy().name());
        result.put("limits", limits());
        result.put("rawOptions", rawOptions());
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("documents", snap.documents());
        platform.put("chunks", snap.chunks());
        platform.put("bodies", bodies.size());
        platform.put("bodiesInUse", inUse);
        platform.put("bodiesUnbound", unbound);
        platform.put("backends", distinctBackends(snap));
        result.put("platform", platform);
        result.put("bodies", bodies);
        result.put("orphans", orphans(snap));
        return result;
    }

    /**
     * 上传前预检：<b>纯读配置，不碰存储</b>。
     *
     * <p>判据与 {@code upload()} 的拒绝顺序对齐（体存在 → 体可用 → 向量模型可用 →
     * 能解析出写入 store），所以"按钮可点"就等于"后端不会因为配置原因拒"。
     * 文件级的问题（白名单、大小、同名冲突）由 {@code limits} 与文档表判定。</p>
     *
     * @param body 知识体 id
     */
    @GetMapping("/target")
    public Map<String, Object> target(@RequestParam(required = false) String body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("strategy", retrievalPlan.strategy().name());
        result.put("limits", limits());
        boolean embeddingReady = modelHolder.isEmbeddingConfigured();
        result.put("embeddingReady", embeddingReady);

        String bodyId = body == null || body.isBlank() ? null : body.trim();
        InfraSettings.KnowledgeBody definition = bodyResolver.body(bodyId);
        result.put("body", bodyId);
        result.put("bodyName", definition == null ? null : definition.effectiveName());
        result.put("exists", definition != null);

        if (bodyId == null) {
            return reason(result, "NO_BODY", "必须指定知识体");
        }
        if (definition == null) {
            return reason(result, "BODY_MISSING", "知识体已不存在（可能刚被删，请刷新知识库页）");
        }
        InfraSettings infra = infraHolder.current();
        result.put("modes", definition.modes());
        result.put("effectiveModes", effectiveModes(definition));
        result.put("slots", List.of(slotView(infra, definition, InfraSettings.KIND_KEYWORD),
                slotView(infra, definition, InfraSettings.KIND_VECTOR)));
        if (!definition.configured()) {
            return reason(result, "BODY_EMPTY", "该知识体两路都没配：先给它选一个关键字或向量连接");
        }
        if (infra.usableBody(bodyId) == null) {
            return reason(result, "BODY_UNUSABLE",
                    "该知识体配的连接都不可用（地址没填或已被删除）：到「存储」页检查连接，或给体换一路");
        }
        /* 向量模型是上传的前置依赖（导入链路每片都要向量化）：没配时 upload() 以 60009 拒，
           预检若还显示"可写"，用户要先白传一次才被告知缺模型。 */
        if (!embeddingReady) {
            return reason(result, "EMBEDDING_NOT_CONFIGURED",
                    "未配置向量模型，文档无法向量化；请先到「模型设置」页的「向量模型」段启用一个 Embedding 模型");
        }
        try {
            KnowledgeStore store = retrievalPlan.writeStoreOfBody(bodyId);
            boolean fanOut = store instanceof FanOutStore;
            result.put("writable", true);
            result.put("reason", null);
            result.put("fanOut", fanOut);
            result.put("writeBackends", writeBackends(definition, fanOut));
        } catch (RuntimeException e) {
            reason(result, "BODY_UNUSABLE", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> reason(Map<String, Object> result, String reason, String text) {
        result.put("writable", false);
        result.put("reason", reason);
        result.put("reasonText", text);
        return result;
    }

    // ==================== 增 / 改 / 删 ====================

    /**
     * 新建知识体：唯一名 + 两路落点 + 原文落点，索引名在创建时派生并冻结。
     *
     * <p>id 直接用唯一名：它是 API 与域绑定里引用体的稳定标识。唯一名不可改，
     * 所以 id 与唯一名从此一致，不会再出现"两个都叫 name 的字段各说各话"。</p>
     */
    @PostMapping
    public Map<String, Object> create(@RequestBody BodyRequest request) {
        String slug = KbIndexes.normalizeSlug(request == null ? null : request.getSlug());
        String reason = KbIndexes.validateSlug(slug);
        if (reason != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, reason);
        }
        String id = request.getId() == null || request.getId().isBlank() ? slug : request.getId().trim();
        if (!id.equals(slug)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "知识体 id 必须等于唯一名（" + slug + "），这样域绑定与检索排障只看一个名字");
        }
        Map<String, InfraSettings.KnowledgeBody> bodies =
                new LinkedHashMap<>(infraHolder.current().bodiesOrEmpty());
        if (bodies.containsKey(id)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "知识体已存在：" + id);
        }
        for (InfraSettings.KnowledgeBody existing : bodies.values()) {
            if (existing != null && slug.equals(existing.effectiveSlug())) {
                throw new BaseException(ErrorCode.INVALID_PARAMETER,
                        "唯一名「" + slug + "」已被知识体 " + existing.getId() + " 使用："
                                + "唯一名决定索引名，不能共用");
            }
        }
        InfraSettings.KnowledgeBody body = new InfraSettings.KnowledgeBody();
        body.setId(id);
        body.setSlug(slug);
        body.setName(hasText(request.getName()) ? request.getName().trim() : slug);
        body.setDescription(request.getDescription());
        body.setKeyword(trimToNull(request.getKeyword()));
        body.setVector(trimToNull(request.getVector()));
        body.setRaw(hasText(request.getRaw()) ? request.getRaw().trim() : InfraSettings.RAW_LOCAL);
        body.setIndex(KbIndexes.nameOfBody(slug, id));
        if (!body.configured()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "至少给知识体选一路：关键字连接（全文检索）或向量连接（语义检索）");
        }
        bodies.put(id, body);
        infraHolder.replaceBodies(bodies);
        log.info("[知识库] 已创建知识体 {}（唯一名 {}，索引 {}，关键字={}，向量={}，原文={}）",
                id, slug, body.getIndex(), body.keywordId(), body.vectorId(), body.rawLocation());
        Map<String, Object> result = created("created", id);
        result.put("body", row(id, body, List.of(), statsService.overview()));
        return result;
    }

    /**
     * 修改知识体：名字 / 说明 / 两路落点 / 原文落点。
     *
     * <p><b>唯一名与索引名不可改</b>：传了不同的 slug 直接拒——唯一名派生索引名，
     * 改它就是换一个集合，而旧集合不会自动删，于是数据既查不到又不会被清理。
     * 真要换名字，新建一个体、把文档重传一遍，旧体删掉——这是显式的数据迁移，不是改名。</p>
     *
     * <p>换落点（keyword / vector 指向别的连接）<b>不在这里</b>，走
     * {@link #routes}：那条路会先在新连接上从原文把数据写满、校验条数，然后才切指针。
     * 这里收到不同的连接 id 直接拒，避免"指针切了、数据还在旧机器上"的半迁移状态。</p>
     */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") String id, @RequestBody BodyRequest request) {
        String bodyId = id == null ? null : id.trim();
        Map<String, InfraSettings.KnowledgeBody> bodies =
                new LinkedHashMap<>(infraHolder.current().bodiesOrEmpty());
        InfraSettings.KnowledgeBody body = bodies.get(bodyId);
        if (body == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "知识体不存在：" + bodyId);
        }
        if (request == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "缺少请求体");
        }
        if (hasText(request.getSlug())
                && !KbIndexes.normalizeSlug(request.getSlug()).equals(body.effectiveSlug())) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "唯一名创建后不可修改（它决定了索引名 " + body.effectiveIndex()
                            + "）；要换名字请新建知识体并迁移文档");
        }
        // 落点只能在 /routes 上换：那里先在新连接写满再切指针；直接改指针会让检索
        // 立刻指向一个空集合，而旧连接上的数据既查不到也不会被清理
        rejectRouteChange("关键字", request.getKeyword(), body.keywordId());
        rejectRouteChange("向量", request.getVector(), body.vectorId());
        if (hasText(request.getName())) {
            body.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            body.setDescription(request.getDescription());
        }
        if (request.getRaw() != null) {
            body.setRaw(trimToNull(request.getRaw()));
        }
        if (!body.configured()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "至少保留一路：关键字连接或向量连接（两路都清空的知识体等于没有知识库）");
        }
        // 老体可能没有冻结索引（v1 遗留 / yaml 合成）：保存时写实一次，此后永不再算
        if (!hasText(body.getIndex())) {
            body.setIndex(body.effectiveIndex());
        }
        bodies.put(bodyId, body);
        infraHolder.replaceBodies(bodies);
        log.info("[知识库] 已更新知识体 {}（名字 {}，原文 {}）",
                bodyId, body.effectiveName(), body.rawLocation());
        Map<String, Object> result = created("updated", bodyId);
        result.put("needsRebuild", false);
        result.put("documents", documentsOf(bodyId));
        result.put("body", row(bodyId, body, bodyResolver.domainsByBody()
                .getOrDefault(bodyId, List.of()), statsService.overview()));
        return result;
    }

    /**
     * 删除知识体（唯一会毁数据的入口）。
     *
     * @param keepRaw 默认 true = 只删索引、保留原文（以后还能从原文重建/换落点）；
     *                false = 连原文档案与切片预览一起删
     * @param confirm 必须等于知识体名字或唯一名（防误点）
     * @param force   true = 有域还绑着它时一并解绑；默认拒绝并列出绑定域
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id,
                                      @RequestParam(value = "keepRaw", defaultValue = "true") boolean keepRaw,
                                      @RequestParam(value = "confirm", required = false) String confirm,
                                      @RequestParam(value = "force", defaultValue = "false") boolean force) {
        String bodyId = id == null ? null : id.trim();
        InfraSettings.KnowledgeBody body = infraHolder.current().body(bodyId);
        if (body == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "知识体不存在：" + bodyId);
        }
        String expected = body.effectiveName();
        if (!hasText(confirm) || !(confirm.trim().equals(expected) || confirm.trim().equals(bodyId))) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "删除知识体会销毁数据，请输入知识体名称「" + expected + "」确认");
        }
        List<String> bound = bodyResolver.domainsByBody().getOrDefault(bodyId, List.of());
        if (!bound.isEmpty() && !force) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "还有 " + bound.size() + " 个域绑着它：" + bound
                            + "。先解绑，或明确「一并解绑并删除」（force=true）");
        }
        // 先删数据再落配置：反过来一旦存储失败，体已从配置里消失、索引却还在，
        // 页面上再也找不到入口去清理它
        KnowledgeBaseService.BodyDeleteResult deleted = knowledgeBase.deleteBody(bodyId, keepRaw);
        if (!bound.isEmpty()) {
            bound.forEach(domain -> domainStore.setBody(domain, null));
            domainStore.saveDeclarations(domainRegistry.manualConfig());
        }
        Map<String, InfraSettings.KnowledgeBody> bodies =
                new LinkedHashMap<>(infraHolder.current().bodiesOrEmpty());
        bodies.remove(bodyId);
        infraHolder.replaceBodies(bodies);
        statsService.invalidate();
        log.info("[知识库] 已删除知识体 {}（索引 {}，原文{}；解绑域 {}）",
                bodyId, deleted.index(), keepRaw ? "保留" : "一并删除", bound);
        Map<String, Object> result = created("deleted", bodyId);
        result.put("index", deleted.index());
        result.put("indexDeleted", deleted.deleted());
        result.put("rawKept", deleted.rawKept());
        result.put("documents", deleted.documents());
        result.put("unboundDomains", bound);
        return result;
    }

    // ==================== 绑定 ====================

    /** 把知识体绑到一个域（从知识库卡片发起；域的 PUT 端点同样可用） */
    @PostMapping("/{id}/bind")
    public Map<String, Object> bind(@PathVariable("id") String id, @RequestBody BindRequest request) {
        String bodyId = id == null ? null : id.trim();
        if (infraHolder.current().body(bodyId) == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "知识体不存在：" + bodyId);
        }
        if (request == null || !hasText(request.getDomain())) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "缺少 domain（要绑到哪个域）");
        }
        String domain = Domains.normalize(request.getDomain());
        if (!domainRegistry.contains(domain)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域不存在：" + domain);
        }
        String previous = domainStore.bodyOf(domain);
        domainStore.setBody(domain, bodyId);
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        statsService.invalidate();
        log.info("[知识库] 域 {} 的知识体从 {} 改为 {}", domain,
                previous == null ? "未绑定" : previous, bodyId);
        Map<String, Object> result = created("bound", bodyId);
        result.put("domain", domain);
        result.put("previousBody", previous);
        return result;
    }

    /** 解绑一个域（域变成"没有知识库"，数据留在体里） */
    @PostMapping("/{id}/unbind")
    public Map<String, Object> unbind(@PathVariable("id") String id, @RequestBody BindRequest request) {
        String bodyId = id == null ? null : id.trim();
        if (request == null || !hasText(request.getDomain())) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "缺少 domain（要解绑哪个域）");
        }
        String domain = Domains.normalize(request.getDomain());
        if (!domainRegistry.contains(domain)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域不存在：" + domain);
        }
        String bound = domainStore.bodyOf(domain);
        if (!bodyId.equals(bound)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "域 " + domain + " 绑的不是这个体（当前：" + (bound == null ? "未绑定" : bound) + "）");
        }
        domainStore.setBody(domain, null);
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        statsService.invalidate();
        log.info("[知识库] 已解绑域 {}（知识体 {} 的数据保留）", domain, bodyId);
        Map<String, Object> result = created("unbound", bodyId);
        result.put("domain", domain);
        return result;
    }

    // ==================== 文档 ====================

    /** 该知识体索引里的文档（fail-open：存储不可达时返回空表 + 错误说明，页面照常打开） */
    @GetMapping("/{id}/documents")
    public Map<String, Object> documents(@PathVariable("id") String id) {
        String bodyId = id == null ? null : id.trim();
        InfraSettings.KnowledgeBody body = infraHolder.current().body(bodyId);
        if (body == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "知识体不存在：" + bodyId);
        }
        String index = body.effectiveIndex();
        List<KnowledgeBaseService.DocumentItem> items;
        try {
            items = knowledgeBase.list(List.of(index));
        } catch (RuntimeException e) {
            log.warn("[知识库] 读取知识体 {} 的文档列表失败（按空表返回）: {}", bodyId, e.getMessage());
            items = List.of();
        }
        List<Map<String, Object>> documents = new ArrayList<>(items.size());
        for (KnowledgeBaseService.DocumentItem item : items) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("docId", item.docId());
            doc.put("fileName", item.fileName());
            doc.put("chunks", item.chunks());
            doc.put("exportPath", item.exportPath());
            documents.add(doc);
        }
        Map<String, Object> result = created("documents", bodyId);
        result.put("index", index);
        result.put("documents", documents);
        result.put("total", documents.size());
        return result;
    }

    /**
     * 上传文档到知识体。
     *
     * @param file    文件（白名单 / 大小在服务端校验）
     * @param replace true = 同名覆盖（旧文档在新文档确认落库后才删）
     */
    @PostMapping("/{id}/documents")
    public Map<String, Object> upload(@PathVariable("id") String id,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "replace", defaultValue = "false") boolean replace) {
        String bodyId = id == null ? null : id.trim();
        if (file == null || file.isEmpty()) {
            throw new BaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED, "文件为空");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                    "读取上传文件失败：" + e.getMessage());
        }
        KnowledgeBaseService.UploadResult result =
                knowledgeBase.upload(content, file.getOriginalFilename(), replace, bodyId);
        statsService.invalidate();
        Map<String, Object> out = created("uploaded", bodyId);
        out.put("docId", result.docId());
        out.put("fileName", result.fileName());
        out.put("size", result.size());
        out.put("chunks", result.chunks());
        out.put("sections", result.sections());
        out.put("droppedLines", result.droppedLines());
        out.put("encoding", result.encoding());
        out.put("exportPath", result.exportPath());
        return out;
    }

    /** 切片预览：优先读原文档案（换模型重灌后索引变了，原文仍是那份） */
    @GetMapping("/{id}/documents/{docId}/chunks")
    public Map<String, Object> chunks(@PathVariable("id") String id,
                                      @PathVariable("docId") String docId,
                                      @RequestParam(value = "limit", defaultValue = "0") int limit) {
        String bodyId = id == null ? null : id.trim();
        KnowledgeBaseService.Preview preview = knowledgeBase.previewChunks(docId, bodyId, limit);
        Map<String, Object> result = created("chunks", bodyId);
        result.put("docId", preview.docId());
        result.put("index", preview.index());
        result.put("total", preview.total());
        result.put("fromArchive", preview.fromArchive());
        List<Map<String, Object>> chunks = new ArrayList<>(preview.chunks().size());
        for (KnowledgeBaseService.PreviewChunk chunk : preview.chunks()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", chunk.seq());
            row.put("chars", chunk.chars());
            row.put("text", chunk.text());
            chunks.add(row);
        }
        result.put("chunks", chunks);
        return result;
    }

    /** 删除一个文档（切片 + 原文档案 + 预览 txt） */
    @DeleteMapping("/{id}/documents/{docId}")
    public Map<String, Object> deleteDocument(@PathVariable("id") String id,
                                              @PathVariable("docId") String docId) {
        String bodyId = id == null ? null : id.trim();
        boolean deleted = knowledgeBase.delete(bodyId, docId);
        statsService.invalidate();
        Map<String, Object> result = created("document-deleted", bodyId);
        result.put("docId", docId);
        result.put("deleted", deleted);
        if (!deleted) {
            result.put("message", "该文档不在此知识体的索引里（可能已被删除）");
        }
        return result;
    }

    // ==================== 重建 ====================

    /**
     * 重建这个知识体的索引。
     *
     * @param request {@code from}: {@code archive}（默认，从原文重灌，不丢数据）/
     *                {@code reset}（只删了重建，数据需重新上传）
     */
    @PostMapping("/{id}/rebuild")
    public Map<String, Object> rebuild(@PathVariable("id") String id,
                                       @RequestBody(required = false) RebuildRequest request) {
        String bodyId = id == null ? null : id.trim();
        if (infraHolder.current().body(bodyId) == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "知识体不存在：" + bodyId);
        }
        boolean fromArchive = request == null || request.getFrom() == null
                || !"reset".equalsIgnoreCase(request.getFrom().trim());
        if (fromArchive && !modelHolder.isEmbeddingConfigured()) {
            throw new BaseException(ErrorCode.KNOWLEDGE_EMBEDDING_NOT_CONFIGURED,
                    "从原文重建需要用向量模型重新计算每一片：请先在「模型设置」页启用 Embedding 模型");
        }
        KnowledgeBaseService.BodyRebuildResult result = knowledgeBase.rebuildBody(
                bodyId, modelHolder.effectiveEmbeddingDimension(), fromArchive);
        statsService.invalidate();
        Map<String, Object> out = created("rebuilt", bodyId);
        out.put("index", result.index());
        out.put("documents", result.documents());
        out.put("chunksWritten", result.chunksWritten());
        out.put("fromArchive", result.fromArchive());
        out.put("message", result.fromArchive()
                ? "已从原文重建：" + result.chunksWritten() + " 条切片"
                : "已重建为空索引：原文档需重新上传");
        return out;
    }

    // ==================== 换落点 / 健康 ====================

    /**
     * 换某一路落点：<b>先在新连接上写满、再切指针</b>。
     *
     * <p>这条路取代了 v1 的「重灌到新后端」全局向导：换后端就是改一路落点，
     * 索引名不变，粒度是单个知识体。旧连接上的同名集合刻意不删（见
     * {@link KnowledgeBaseService#migrateRoute}），响应里如实告知。</p>
     *
     * @param dryRun {@code true} = 只预演：不读全量档案、不建集合、不改配置
     */
    @PutMapping("/{id}/routes")
    public Map<String, Object> routes(@PathVariable("id") String id,
                                      @RequestBody(required = false) RouteRequest request,
                                      @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {
        String bodyId = id == null ? null : id.trim();
        InfraSettings.KnowledgeBody body = infraHolder.current().body(bodyId);
        if (body == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "知识体不存在：" + bodyId);
        }
        if (request == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "缺少请求体（kind + connection）");
        }
        if (hasText(request.getRaw())) {
            // 原文落点白名单目前只有 local：接受别的值等于给用户一个假下拉
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "原文落点目前只有「本地原文存储」一项；对象存储连接要等 BlobStore 多驱动接入后才开放");
        }
        String kind = request.getKind();
        String target = request.getConnection();

        if (dryRun) {
            KnowledgeBaseService.BodyRoutePlan plan = knowledgeBase.planRoute(bodyId, kind, target);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", 0);
            out.put("dryRun", true);
            out.put("body", bodyId);
            out.put("index", plan.index());
            out.put("kind", plan.kind());
            out.put("from", plan.from());
            out.put("to", plan.to());
            out.put("ready", plan.ready());
            out.put("reason", plan.reason());
            out.put("documents", plan.documents());
            out.put("archivedDocuments", plan.archivedDocuments());
            out.put("missingArchive", plan.missingArchive());
            out.put("recomputeVectors", plan.recomputeVectors());
            out.put("modelDimension", plan.modelDimension());
            out.put("targetDimension", plan.targetDimension());
            return out;
        }

        KnowledgeBaseService.BodyRouteMigration moved = knowledgeBase.migrateRoute(
                bodyId, kind, target, dimensionsForRoute());
        /* 数据写完才切指针：反过来的话，检索会在新集合还没写满时就指向它，
           表现是"迁移过程里知识突然只剩一半"。 */
        Map<String, InfraSettings.KnowledgeBody> bodies =
                new LinkedHashMap<>(infraHolder.current().bodiesOrEmpty());
        InfraSettings.KnowledgeBody current = bodies.get(bodyId);
        if (current == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "知识体在迁移过程中被删除：" + bodyId);
        }
        if (InfraSettings.KIND_KEYWORD.equals(moved.kind())) {
            current.setKeyword(trimToNull(moved.to()));
        } else {
            current.setVector(trimToNull(moved.to()));
        }
        bodies.put(bodyId, current);
        infraHolder.replaceBodies(bodies);
        statsService.invalidate();
        log.info("[知识库] 知识体 {} 的落点已切换：{} {} → {}（索引 {} 不变）",
                bodyId, moved.kind(), moved.from(), moved.to(), moved.index());
        Map<String, Object> out = created("route-migrated", bodyId);
        out.put("index", moved.index());
        out.put("kind", moved.kind());
        out.put("from", moved.from());
        out.put("to", moved.to());
        out.put("chunksWritten", moved.chunksWritten());
        out.put("documents", moved.documents());
        out.put("vectorsRecomputed", moved.vectorsRecomputed());
        out.put("legacyConnection", moved.legacyConnectionId());
        out.put("message", "这一路已切到「" + moved.to() + "」，索引名不变；旧连接「"
                + moved.legacyConnectionId() + "」上的同名集合没有删除，确认不再需要后可手工清理");
        return out;
    }

    /**
     * 知识体健康矩阵：两路落点 + 向量模型的三方对照。
     *
     * <p>单独一个端点而不是塞进 {@code list()}：这里每条都要连远程（问集合在不在、
     * 读 mapping 维度），逐体在主线程算会把知识库首屏拖成秒级。页面点「健康」才查。</p>
     */
    @GetMapping("/{id}/health")
    public Map<String, Object> health(@PathVariable("id") String id) {
        String bodyId = id == null ? null : id.trim();
        InfraSettings.KnowledgeBody body = infraHolder.current().body(bodyId);
        if (body == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "知识体不存在：" + bodyId);
        }
        String index = body.effectiveIndex();
        KnowledgeStatsService.Overview snap = statsService.overview();
        Map<String, Object> keyword = routeHealth(body.keywordId(), index, InfraSettings.KIND_KEYWORD);
        Map<String, Object> vector = routeHealth(body.vectorId(), index, InfraSettings.KIND_VECTOR);

        Integer modelDims = modelHolder.isEmbeddingConfigured()
                ? modelHolder.effectiveEmbeddingDimension() : null;
        Integer kwDims = keyword.get("dimension") instanceof Integer v ? v : null;
        Integer vecDims = vector.get("dimension") instanceof Integer v ? v : null;

        /* 三方对照：关键字 mapping 维度、向量集合维度、模型原生维度。
           "探测全绿、检索报错"最常见的根因就是这三个里有一个不一样。 */
        List<Integer> distinct = new ArrayList<>(3);
        for (Integer dims : new Integer[]{kwDims, vecDims, modelDims}) {
            if (dims != null && dims > 0 && !distinct.contains(dims)) {
                distinct.add(dims);
            }
        }
        boolean hasIndex = Boolean.TRUE.equals(keyword.get("exists"))
                || Boolean.TRUE.equals(vector.get("exists"));
        String consistency;
        String consistencyText;
        if (!hasIndex) {
            consistency = "no_index";
            consistencyText = "这个体还没有索引：先上传文档（空体是正常的，创建时不建集合）";
        } else if (distinct.isEmpty()) {
            consistency = "unknown";
            consistencyText = "读不到任何一方的向量维度：可能是纯关键字后端，或存储暂不可达";
        } else if (distinct.size() > 1) {
            consistency = "dim_mismatch";
            consistencyText = "三方维度不一致（" + distinct.stream()
                    .map(String::valueOf).collect(java.util.stream.Collectors.joining(" / "))
                    + "）：换过向量模型的话点「重建索引 → 从原文」，会按当前模型重算";
        } else {
            consistency = "ok";
            consistencyText = "关键字 mapping / 向量集合 / 当前模型三方维度一致（" + distinct.get(0) + "）";
        }

        long[] acc = snap.byBody().get(bodyId);
        Map<String, Object> result = created("health", bodyId);
        result.put("index", index);
        result.put("existsIndex", hasIndex);
        result.put("keyword", keyword);
        result.put("vector", vector);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("configured", modelHolder.isEmbeddingConfigured());
        model.put("dimension", modelDims);
        result.put("model", model);
        result.put("consistency", consistency);
        result.put("consistencyText", consistencyText);
        result.put("raw", rawView(body));
        result.put("documents", acc == null ? 0L : acc[0]);
        result.put("chunks", acc == null ? 0L : acc[1]);
        return result;
    }

    /**
     * 一路落点的实际情况。
     *
     * <p>状态机与卡片上的槽位一致（absent / missing / blank / unregistered / ok / no_index），
     * 额外给 {@code exists} 与 {@code dimension}：这两个是健康矩阵的原始数据，
     * 卡片上只显示合并后的一个状态字。</p>
     */
    private Map<String, Object> routeHealth(String connectionId, String index, String kind) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("connectionId", connectionId);
        out.put("exists", false);
        out.put("dimension", null);
        out.put("reachable", false);
        if (connectionId == null) {
            out.put("state", SLOT_ABSENT);
            return out;
        }
        InfraSettings.Connection conn = infraHolder.current().connection(connectionId);
        out.put("name", conn == null ? connectionId : conn.effectiveName());
        if (conn == null) {
            out.put("state", SLOT_MISSING);
            return out;
        }
        out.put("type", conn.effectiveType());
        if (!conn.isUsable()) {
            out.put("state", SLOT_BLANK);
            return out;
        }
        KnowledgeStore store;
        try {
            store = retrievalPlan.storeOf(connectionId);
        } catch (RuntimeException e) {
            out.put("state", SLOT_UNREGISTERED);
            out.put("note", e.getMessage());
            return out;
        }
        boolean reachable = store.ping();
        out.put("reachable", reachable);
        if (!reachable) {
            out.put("state", "unreachable");
            return out;
        }
        try {
            out.put("exists", store.exists(index));
        } catch (RuntimeException e) {
            out.put("state", "error");
            out.put("note", "查询集合是否存在失败：" + e.getMessage());
            return out;
        }
        out.put("dimension", dimsOf(store, index));
        out.put("state", Boolean.TRUE.equals(out.get("exists")) ? SLOT_OK : "no_index");
        return out;
    }

    private static Integer dimsOf(KnowledgeStore store, String index) {
        if (index == null) {
            return null;
        }
        try {
            return store.currentVectorDims(index);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 建目标集合时的向量维度。
     *
     * <p>优先当前向量模型（迁过去要按它重算）；模型没配时退回现存的任一索引维度——
     * 纯关键字体迁移本不需要模型，不该因为"没启用 Embedding"就挡下。</p>
     */
    private int dimensionsForRoute() {
        if (modelHolder.isEmbeddingConfigured()) {
            return modelHolder.effectiveEmbeddingDimension();
        }
        Integer existing = knowledgeBase.currentVectorDims();
        if (existing != null) {
            return existing;
        }
        throw new BaseException(ErrorCode.KNOWLEDGE_EMBEDDING_NOT_CONFIGURED,
                "没配向量模型、现有索引也没有可参考的维度：先在「模型设置」页启用 Embedding 模型");
    }

    // ==================== 视图拼装 ====================

    private Map<String, Object> created(String action, String bodyId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("action", action);
        result.put("body", bodyId);
        return result;
    }

    /** 一个知识体卡片 / 抽屉所需的全部字段 */
    private Map<String, Object> row(String bodyId, InfraSettings.KnowledgeBody body,
                                    List<String> domains, KnowledgeStatsService.Overview snap) {
        String index = body == null ? null : body.effectiveIndex();
        long[] acc = snap.byBody().get(bodyId);
        List<String> backends = index == null ? List.of()
                : snap.owners().getOrDefault(index, List.of());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", bodyId);
        row.put("name", body == null ? bodyId : body.effectiveName());
        row.put("slug", body == null ? null : body.effectiveSlug());
        row.put("index", index);
        row.put("description", body == null ? null : body.getDescription());
        /* 两槽的连接 id 单独给一份：设置表单要按它回显下拉。
           从 slots[] 里反查也能做，但键名是 kind/connectionId，前端等于自己重建一层语义。 */
        row.put("keyword", body == null ? null : body.keywordId());
        row.put("vector", body == null ? null : body.vectorId());
        row.put("status", statusOf(body, index, snap));
        row.put("modes", body == null ? List.of() : body.modes());
        row.put("effectiveModes", effectiveModes(body));
        row.put("slots", List.of(slotView(infraHolder.current(), body, InfraSettings.KIND_KEYWORD),
                slotView(infraHolder.current(), body, InfraSettings.KIND_VECTOR)));
        row.put("fanOut", isFanOut(infraHolder.current(), body));
        row.put("writeBackends", body == null ? List.of()
                : writeBackends(body, isFanOut(infraHolder.current(), body)));
        row.put("raw", rawView(body));
        row.put("documents", acc == null ? 0L : acc[0]);
        row.put("chunks", acc == null ? 0L : acc[1]);
        row.put("existsIndex", index != null && snap.indices().contains(index));
        row.put("backends", backends);
        row.put("domains", domains);
        row.put("usable", body != null && infraHolder.current().usableBody(bodyId) != null);
        return row;
    }

    /**
     * 体的状态：missing &gt; empty &gt; dead &gt; no_index &gt; ok。
     *
     * <p>{@code no_index} 必须带 {@code !stale} 才敢判：ES 的 {@code listCollections()}
     * 是 fail-open，"存储不可达"与"一个索引都没有"在数据上同形。快照是旧的还判"有体无索引"，
     * 等于引导用户去点重建——那是最坏的误操作。</p>
     */
    private String statusOf(InfraSettings.KnowledgeBody body, String index,
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
        if (index == null || snap.stale()) {
            return "ok";
        }
        return snap.indices().contains(index) ? "ok" : "no_index";
    }

    /** 一路槽位：空 / 连接已删 / 连接未填地址 / 连接未注册 / 类型不匹配 / 可用 */
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
                // 能力位不匹配（如关键字槽指到 Qdrant）：运行期会静默跳过这一路
                state = SLOT_INCAPABLE;
            } else {
                state = SLOT_OK;
            }
        }
        v.put("state", state);
        v.put("usable", SLOT_OK.equals(state));
        return v;
    }

    /** 原文落点：当前只可能 local；列表来自白名单，页面据此渲染可选值（不造假选项） */
    private Map<String, Object> rawView(InfraSettings.KnowledgeBody body) {
        String location = body == null ? InfraSettings.RAW_LOCAL : body.rawLocation();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("location", location);
        raw.put("label", rawLabel(location));
        raw.put("detail", rawDetail(location));
        return raw;
    }

    private List<Map<String, Object>> rawOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        for (String location : InfraSettings.SUPPORTED_RAW_LOCATIONS) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", location);
            option.put("label", rawLabel(location));
            option.put("detail", rawDetail(location));
            option.put("available", true);
            options.add(option);
        }
        return options;
    }

    private String rawLabel(String location) {
        return InfraSettings.RAW_LOCAL.equals(location) ? "本地原文存储" : location;
    }

    private String rawDetail(String location) {
        return InfraSettings.RAW_LOCAL.equals(location)
                ? storageLocations.blobSource() : "文件服务连接 " + location;
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
        String left = locationOf(body.keywordId());
        String right = locationOf(body.vectorId());
        return left != null && right != null && !left.equals(right);
    }

    private String locationOf(String connectionId) {
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
     * 无主索引：存在、但<b>不属于任何已登记知识体</b>的 {@code domainplane_kb_*}。
     *
     * <p>判据用索引名对比体的冻结索引，不用切片 metadata 里的归属：无主索引里的老文档
     * metadata 还带着 v1 的域路径，按 metadata 判会漏掉它们。这类索引只有两种情况：
     * 迁移器还没跑到（重启后自动登记），或者用户手工在存储侧建的。</p>
     */
    private List<Map<String, Object>> orphans(KnowledgeStatsService.Overview snap) {
        Set<String> expected = bodyResolver.allIndices();
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

    /** 文档数：直接问统计快照（可能 60s 旧，只用于"落点变了要不要提示重建"这种粗判） */
    private Long documentsOf(String bodyId) {
        long[] acc = statsService.overview().byBody().get(bodyId);
        return acc == null ? 0L : acc[0];
    }

    private Map<String, Object> limits() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("allowedExtensions", ragProperties.getAllowedExtensions());
        limits.put("maxFileSize", ragProperties.getMaxFileSize().toBytes());
        limits.put("maxChunksPerDocument", ragProperties.getMaxChunksPerDocument());
        limits.put("chunkMaxChars", ragProperties.getChunking().getMaxChars());
        return limits;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * 落点只能经 {@code PUT /admin/bodies/{id}/routes} 换。
     *
     * <p>只拒"真的变了"的值：前端保存表单时会把两路一起回传，值没变就放过（幂等），
     * 否则改个名字都会因为带了原来的连接 id 而被拦下。</p>
     */
    private static void rejectRouteChange(String routeLabel, String requested, String current) {
        if (requested == null) {
            return;
        }
        if (eq(trimToNull(requested), current)) {
            return;
        }
        throw new BaseException(ErrorCode.INVALID_PARAMETER,
                "换" + routeLabel + "落点不能直接改：请走「迁移」——它会在新连接上从原文把数据写满、"
                        + "校验条数之后才切指针（PUT /admin/bodies/{id}/routes?dryRun=true 可先预演）");
    }

    // ==================== 请求体 ====================

    /** 新建 / 修改知识体 */
    @Data
    public static class BodyRequest {
        /** 唯一名（小写字母 / 数字 / 连字符，2~40，创建后不可改） */
        private String slug;
        /** id（可缺省，缺省 = 唯一名；必须与唯一名一致） */
        private String id;
        /** 展示名 */
        private String name;
        private String description;
        /** 关键字路连接 id */
        private String keyword;
        /** 向量路连接 id */
        private String vector;
        /** 原文落点（当前只有 local） */
        private String raw;
    }

    /** 绑定 / 解绑 */
    @Data
    public static class BindRequest {
        private String domain;
    }

    /** 重建 */
    @Data
    public static class RebuildRequest {
        /** {@code archive}（默认）或 {@code reset} */
        private String from;
    }

    /** 换落点 */
    @Data
    public static class RouteRequest {
        /** {@code keyword} / {@code vector} */
        private String kind;
        /** 这一路要换到的连接 id */
        private String connection;
        /** 原文落点（当前白名单只有 local，传别的会被拒，不做假迁移） */
        private String raw;
    }
}
