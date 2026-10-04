package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.model.ModelCatalog;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileSettings;
import com.zzkingcc.domainplane.server.model.ModelProfileStore;
import com.zzkingcc.domainplane.server.settings.LlmFailureDescriber;
import com.zzkingcc.domainplane.server.settings.LlmModelHolder;
import com.zzkingcc.domainplane.server.settings.LlmSettings;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理面：模型档案与「域 → 档案」绑定。
 *
 * <p>三条产品规则的落点：</p>
 * <ol>
 *   <li>档案建完<b>不自动绑任何域</b>，绑定只能通过本接口显式设置；</li>
 *   <li>一个域绑一组<b>可调用模型</b>（有序，整体覆盖）—— 首个是当前使用的模型，
 *       其余留给多 agent / 降级；</li>
 *   <li>没有内置 {@code default} 模型：对话只走用户自建档案，域必须先显式配置模型，
 *       未配置即"无可调用"。</li>
 * </ol>
 *
 * <p>路径在 {@code /admin/**} 之下，鉴权由凭证拦截器统一处理。</p>
 *
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin")
public class AdminModelProfileController {

    private static final Logger log = LoggerFactory.getLogger(AdminModelProfileController.class);

    private final ModelProfileRegistry registry;
    private final ModelProfileStore store;
    private final LlmModelHolder holder;
    private final ModelCatalog catalog;
    /** 域注册表：绑定只能指向已登记的域（否则绑定会变成永远不生效的孤儿） */
    private final DomainRegistry domainRegistry;
    /** 知识库：换向量模型时用它保留原文重灌，或按用户选择清空 */
    private final KnowledgeBaseService knowledgeBaseService;

    public AdminModelProfileController(ModelProfileRegistry registry,
                                       ModelProfileStore store,
                                       LlmModelHolder holder,
                                       ModelCatalog catalog,
                                       DomainRegistry domainRegistry,
                                       KnowledgeBaseService knowledgeBaseService) {
        this.registry = registry;
        this.store = store;
        this.holder = holder;
        this.catalog = catalog;
        this.domainRegistry = domainRegistry;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 档案全貌：档案列表（Key 脱敏）+ 域绑定 + 落盘位置。
     */
    @GetMapping("/model-profiles")
    public Map<String, Object> list() {
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (ModelProfile profile : registry.profiles()) {
            profiles.add(profileView(profile));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("profiles", profiles);
        body.put("domainBindings", registry.domainBindings());
        /* 向量模型是全局单选：当前选中哪个、是否可用、以及有哪些档案可选（都带在向量段里） */
        ModelProfile selected = registry.embeddingProfile().orElse(null);
        body.put("embeddingAlias", registry.embeddingAlias());
        body.put("embeddingConfigured", holder.isEmbeddingConfigured());
        body.put("embeddingModelName", selected == null ? null : selected.modelName());
        body.put("embeddingDimensions", selected == null ? null : selected.dimensions());
        body.put("embeddingSource", holder.embeddingSource());
        body.put("embeddingCandidates", embeddingCandidates());
        /* 档案总数（模型桶 + 向量桶）：页头计数不能靠前端把两个列表相加得出 ——
           未被选中的向量档案不在 profiles 里、又可能因不可用而不进 candidates，
           前端怎么加都会少报。 */
        body.put("profileCount", registry.allProfiles().size());
        body.put("settingsFile", store.filePath());
        return body;
    }

    /**
     * 可选作向量模型的档案（必须是向量档案且必填齐备），供页面直接渲染单选列表。
     *
     * <p>遍历的是<b>全部</b>档案而非 {@code registry.profiles()}：纯向量档案已单独存放，
     * 不在那一侧；只看 profiles 会让下拉里一个候选都没有。</p>
     */
    private List<Map<String, Object>> embeddingCandidates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelProfile p : registry.allProfiles()) {
            if (!p.isEmbedding() || !p.isUsable()) {
                continue;
            }
            Map<String, Object> item = profileView(p);
            item.put("dimensionsKnown", p.dimensions() != null);
            out.add(item);
        }
        return out;
    }

    /**
     * 新建或整体覆盖一个档案（同名即覆盖）。
     *
     * <p>{@code apiKey} <b>必填</b> —— 管控台只回填脱敏后的 Key，所以编辑时也必须重新填写。</p>
     */
    @PostMapping("/model-profiles")
    public Map<String, Object> save(@RequestBody ProfileBody body) {
        if (body == null || body.getAlias() == null || body.getAlias().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "别名不能为空");
        }
        if (body.getApiKey() == null || body.getApiKey().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "API Key 不能为空");
        }
        String alias = body.getAlias().trim();
        String apiKey = body.getApiKey().trim();

        ModelProfile profile = new ModelProfile(alias, body.getEndpoints(), body.getInput(), body.getOutput(),
                body.getBaseUrl(), apiKey, body.getModelName(), body.getTemperature(), body.getMaxTokens(),
                body.getDimensions(), body.getCapabilities(), body.getFallbacks());

        String failure = registry.save(profile);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        return result("saved", alias);
    }

    /**
     * 读取提供商元数据，判定一个模型的端点族 / 输入模态 / 输出模态 / 布尔能力。
     *
     * <p><b>不发任何试探请求</b>：只读 {@code GET {baseUrl}/models}。读不到就如实留空，
     * 由用户在表单里手工声明 —— 猜类型会让向量模型被当成对话模型。</p>
     */
    @PostMapping("/model-profiles/inspect")
    public Map<String, Object> inspectModel(@RequestBody(required = false) ProfileBody body) {
        if (body == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "请求体不能为空");
        }
        return inspectBody(runInspect(body.getBaseUrl(), body.getApiKey(), body.getModelName()));
    }

    /**
     * 对<b>已保存</b>的档案重新读取元数据，并把读到的部分<b>写回档案</b>。
     *
     * <p>管控台卡片上的「读取」走这里 —— 读完卡片上的标签即刷新。</p>
     *
     * <p><b>读不到的字段一律保留档案里原有的声明</b>：类型判定现在由"提供商元数据 + 手工声明"
     * 共同决定，若用空值覆盖，一次「读取」就会把用户手选的端点族 / 模态 / 能力全部抹掉。</p>
     *
     * <p><b>别名走查询参数，不做路径变量</b>：别名由模型名派生，而模型名常含 {@code /}
     * （如 OpenRouter 的 {@code nvidia/nemotron-3-embed-1b:free}）。前端把这种别名放进路径段
     * 只能编码成 {@code %2F}，而 Tomcat 默认 {@code ALLOW_ENCODED_SLASH=false}，会在 URI 解码
     * 阶段直接回 <b>400</b> —— 且返回的是 Tomcat 自己的 HTML 错误页，请求压根到不了这里。
     * 查询串不受该限制。</p>
     */
    @PostMapping("/model-profiles/inspect-saved")
    public Map<String, Object> inspectSaved(@RequestParam("alias") String alias) {
        ModelProfile existing = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        ModelCatalog.CatalogResult read = runInspect(existing.baseUrl(), existing.apiKey(), existing.modelName());

        ModelProfile updated = new ModelProfile(existing.alias(),
                prefer(read.endpoints(), existing.endpoints()),
                prefer(read.input(), existing.input()),
                prefer(read.output(), existing.output()),
                existing.baseUrl(), existing.apiKey(), existing.modelName(),
                existing.temperature(), existing.maxTokens(),
                read.dimension() != null ? read.dimension() : existing.dimensions(),
                union(read.capabilities(), existing.capabilities()),
                existing.fallbacks());
        String failure = registry.save(updated);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }

        Map<String, Object> out = inspectBody(read);
        out.put("alias", alias);
        out.put("profile", profileView(updated));
        return out;
    }

    /**
     * 端点族 / 输入模态 / 输出模态：元数据读到了就用它，读不到（空）才保留原有声明。
     *
     * <p>这三项提供商的声明是<b>完整</b>的（{@code input_modalities} 列全了它能吃什么），
     * 所以"读到了"就应当覆盖，否则改过的模型会留着陈旧的旧模态。</p>
     */
    static <T> List<T> prefer(List<T> fromMetadata, List<T> declared) {
        return fromMetadata == null || fromMetadata.isEmpty() ? declared : fromMetadata;
    }

    /**
     * 布尔能力：并集。
     *
     * <p>不能用 {@link #prefer} —— {@code supported_parameters} <b>不列某项只代表提供商没声明，
     * 不代表不支持</b>（OpenRouter 就不列 {@code stream}，因为人人都支持）。用元数据覆盖会把
     * 用户已声明的 {@code streaming} 悄悄抹掉 —— 这是"没读到"被当成了"没有"。</p>
     */
    static <T> List<T> union(List<T> fromMetadata, List<T> declared) {
        java.util.LinkedHashSet<T> out = new java.util.LinkedHashSet<>();
        if (declared != null) {
            out.addAll(declared);
        }
        if (fromMetadata != null) {
            out.addAll(fromMetadata);
        }
        return List.copyOf(out);
    }

    private ModelCatalog.CatalogResult runInspect(String baseUrl, String apiKey, String modelName) {
        try {
            return catalog.inspect(baseUrl, apiKey, modelName);
        } catch (IllegalStateException e) {
            throw new BaseException(ErrorCode.LLM_UNAVAILABLE, "读取提供商信息失败：" + e.getMessage());
        } catch (Exception e) {
            throw new BaseException(ErrorCode.LLM_UNAVAILABLE,
                    "读取提供商信息失败：" + LlmFailureDescriber.describe(e));
        }
    }

    private static Map<String, Object> inspectBody(ModelCatalog.CatalogResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("endpoints", result.endpoints());
        out.put("input", result.input());
        out.put("output", result.output());
        out.put("capabilities", result.capabilities());
        out.put("dimension", result.dimension());
        out.put("message", result.note());
        return out;
    }

    /**
     * 删除档案，并<b>级联清理所有域对该别名的绑定</b>。
     *
     * <p>被清掉绑定的域立即进入"无可调用"状态，由管控台「域空间」页提示 ——
     * 删除不会被"仍被引用"拦在半路。</p>
     *
     * <p>别名走查询参数的原因同 {@link #inspectSaved}：别名可含 {@code /}，放进路径段会被
     * Tomcat 以 400 拒收。</p>
     */
    @DeleteMapping("/model-profiles")
    public Map<String, Object> delete(@RequestParam("alias") String alias) {
        ModelProfileRegistry.ProfileDeleteResult deleted = registry.delete(alias);
        if (!deleted.deleted()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, deleted.reason());
        }
        /* 若删掉的正是当前向量模型，单选已被级联撤销，必须立刻重装配：
           否则 embeddingModel 还指着已删除的档案，灌库会继续用一个用户以为已经删掉的模型。 */
        if (deleted.wasEmbedding()) {
            holder.refreshEmbedding();
        }
        Map<String, Object> out = result("deleted", alias);
        out.put("usedByDomains", deleted.usedByDomains());
        out.put("wasEmbedding", deleted.wasEmbedding());
        out.put("embeddingConfigured", holder.isEmbeddingConfigured());
        out.put("message", deleted.wasEmbedding()
                ? "已删除档案 " + alias + "（它正是当前向量模型），向量配置一并取消：知识库检索与灌库已停用"
                : "已删除档案 " + alias);
        return out;
    }

    /**
     * 连通性测试：复用「模型设置」页同一套探测逻辑（只发一条极短请求，避免浪费额度）。
     *
     * <p>别名走查询参数的原因同 {@link #inspectSaved}。</p>
     */
    @PostMapping("/model-profiles/test")
    public Map<String, Object> test(@RequestParam("alias") String alias) {
        ModelProfile profile = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        LlmSettings probe = new LlmSettings();
        probe.setChatBaseUrl(profile.baseUrl());
        probe.setChatApiKey(profile.apiKey());
        probe.setChatModelName(profile.modelName());
        probe.setChatTemperature(profile.temperature());

        String reply;
        try {
            reply = holder.testChatConnection(probe);
        } catch (Exception e) {
            // 失败原因用与模型设置页同一套翻译，措辞一致
            throw new BaseException(ErrorCode.LLM_UNAVAILABLE,
                    "连接失败：" + LlmFailureDescriber.describe(e));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("alias", alias);
        body.put("success", true);
        body.put("reply", reply);
        return body;
    }

    /**
     * 设置域的可调用模型列表（**整体覆盖**，顺序即优先级）；{@code aliases} 空表示解绑。
     *
     * <p>解绑后该域沿域链向上继承最近一个绑了模型的祖先；整条链都没绑才是真的"无可调用"。</p>
     */
    @PutMapping("/model-bindings/{domain}")
    public Map<String, Object> bind(@PathVariable("domain") String domain,
                                    @RequestBody(required = false) BindBody body) {
        List<String> aliases = body == null ? null : body.getAliases();
        // 绑定是"域 → 模型"的写入入口，域必须在注册表里：
        // 否则会为树里不存在的域落盘一条绑定，并被它的后代域沿链继承（静默生效）
        String normalized = Domains.normalize(domain);
        if (!domainRegistry.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "域不存在：" + normalized + "（已登记的域: " + domainRegistry.ids() + "）；"
                            + "请先在「域空间」创建该域，或把绑定写到它的祖先域上由后代继承");
        }
        String failure = registry.bind(domain, aliases);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        boolean unbound = aliases == null || aliases.isEmpty();
        ModelProfileSettings.DomainBinding binding = registry.resolve(domain);
        Map<String, Object> result = result(unbound ? "unbound" : "bound", domain);
        result.put("aliases", binding.aliases());
        // 生效来源要看得见：本域自己绑的，还是从哪个祖先继承来的
        result.put("sourceDomain", binding.sourceDomain());
        result.put("domainBindings", registry.domainBindings());
        return result;
    }

    // ==================== 向量模型：全局单选 ====================

    /**
     * 选用向量模型（<b>全局唯一，全部域共享</b>）。
     *
     * <p><b>换向量模型会让已灌库的向量全部失效</b>，所以只要知识库里已有内容，就必须先由
     * 管控台弹二次确认，并用 {@code mode} 明确用户选了哪条路：</p>
     * <ul>
     *   <li>{@code rebuild} —— <b>保留原文重灌</b>：删索引按新维度重建后，用新模型把
     *       原有切片重新向量化写回。用户不需要重新上传任何文档；</li>
     *   <li>{@code purge} —— <b>丢弃旧内容</b>：删掉全部知识库索引，之后自行重新上传。</li>
     * </ul>
     *
     * <p><b>触发条件是「换了模型或维度任一变化」</b>，不是只看维度。两个不同模型即使维度相同，
     * 向量空间也完全不一样，旧向量检索出来是噪声 —— 而这种错配不会报错，只会让检索质量
     * 静默下降，比维度不符更难发现。</p>
     */
    @PutMapping("/embedding-model")
    public Map<String, Object> selectEmbeddingModel(@RequestBody(required = false) EmbeddingBody body) {
        String alias = body == null ? null : body.getAlias();
        String mode = body == null || body.getMode() == null ? "" : body.getMode().trim();

        String previousAlias = registry.embeddingAlias();
        ModelProfile previous = previousAlias == null ? null : registry.profile(previousAlias).orElse(null);
        // 先校验目标档案（非向量档案 / 必填不齐 / 不存在）—— 不该让用户在确认弹窗之后才被拒绝
        String failure = registry.setEmbeddingAlias(alias);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }

        ModelProfile selected = alias == null || alias.isBlank()
                ? null : registry.profile(alias.trim()).orElse(null);
        boolean changed = !java.util.Objects.equals(previousAlias, registry.embeddingAlias());

        // 知识库里已有内容 → 旧向量即将作废，必须让用户明确表态
        int affectedIndices = existingIndexCount();
        boolean hasVectors = affectedIndices > 0 && hasAnyIndexedContent();
        if (changed && hasVectors) {
            if (!"rebuild".equals(mode) && !"purge".equals(mode)) {
                // 撤销刚才的单选：没有用户确认就不该留下"已切换"的状态
                registry.setEmbeddingAlias(previousAlias);
                Map<String, Object> ask = new LinkedHashMap<>();
                ask.put("code", ErrorCode.INVALID_PARAMETER.getCode());
                ask.put("requiresConfirmation", true);
                ask.put("previousAlias", previousAlias);
                ask.put("previousModelName", previous == null ? null : previous.modelName());
                ask.put("previousDimensions", previous == null ? null : previous.dimensions());
                ask.put("nextModelName", selected == null ? null : selected.modelName());
                ask.put("nextDimensions", selected == null ? null : selected.dimensions());
                ask.put("affectedIndices", affectedIndices);
                ask.put("documentCount", countIndexedDocuments());
                ask.put("message", describeSwitchImpact(previous, selected, affectedIndices));
                return ask;
            }
        }

        // 装配新向量模型（必须在重灌之前：重灌要用它算向量）
        holder.refreshEmbedding();

        // 用户已确认 → 真正执行所选的那条路。放在 refreshEmbedding 之后：
        // 重灌要用刚装配好的新模型算向量，顺序反了会拿老模型重算一遍
        Map<String, Integer> reindexed = null;
        List<String> purged = null;
        if (changed && hasVectors) {
            if ("purge".equals(mode)) {
                purged = knowledgeBaseService.purgeAll();
            } else {
                reindexed = knowledgeBaseService.rebuildPreservingText(holder.effectiveEmbeddingDimension());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("embeddingAlias", registry.embeddingAlias());
        out.put("embeddingSource", holder.embeddingSource());
        out.put("embeddingConfigured", holder.isEmbeddingConfigured());
        out.put("modelName", selected == null ? null : selected.modelName());
        out.put("dimensions", selected == null ? null : selected.dimensions());
        out.put("reindexed", reindexed);
        out.put("removedIndices", purged);
        out.put("message", describeResult(changed, previous, selected, mode, hasVectors));
        out.put("settingsFile", store.filePath());
        return out;
    }

    /**
     * 用新向量模型重建全部知识库索引，<b>保留原文</b>（不丢文档）。
     *
     * <p>供「切换时选重构」与知识库页的「按当前向量模型重灌」共用一条路径。</p>
     */
    @PostMapping("/embedding-model/rebuild")
    public Map<String, Object> rebuildKnowledge() {
        if (!holder.isEmbeddingConfigured()) {
            throw new BaseException(ErrorCode.DEPENDENCY_NOT_CONFIGURED,
                    "向量模型尚未配置：先在「模型设置」页选一个向量模型，再重建知识库");
        }
        int dims = holder.effectiveEmbeddingDimension();
        Map<String, Integer> reindexed = knowledgeBaseService.rebuildPreservingText(dims);
        int total = reindexed.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("dimensions", dims);
        out.put("reindexed", reindexed);
        out.put("totalChunks", total);
        out.put("message", total == 0
                ? "当前没有已灌库的内容，无需重建"
                : "已按向量维度 " + dims + " 重灌 " + reindexed.size() + " 个索引共 " + total + " 条切片（原文保留）");
        return out;
    }

    /**
     * 丢弃全部知识库内容（换向量模型时用户明确选择"不要旧知识"的那条路）。
     */
    @PostMapping("/embedding-model/purge")
    public Map<String, Object> purgeKnowledge() {
        List<String> removed = knowledgeBaseService.purgeAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("removedIndices", removed);
        out.put("message", removed.isEmpty()
                ? "当前没有知识库索引，无需清空"
                : "已丢弃 " + removed.size() + " 个知识库索引的全部内容（原文与切片均已删除，需重新上传）");
        return out;
    }

    private int existingIndexCount() {
        try {
            return knowledgeBaseService.existingIndices().size();
        } catch (Exception e) {
            log.warn("[管控] 读取知识库索引失败，切换向量模型时按「无已存内容」处理: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 索引里是否真的有文档（空索引不算）。
     *
     * <p>一域都没建索引、或者索引建了但没灌过内容时，换向量模型是<b>零风险</b>的，
     * 不该弹二次确认 —— 那道确认框是为了防止"用户辛苦攒的知识库被清空"，
     * 对着空库弹只会让人养成无脑点确认的习惯。</p>
     */
    private boolean hasAnyIndexedContent() {
        return countIndexedDocuments() > 0;
    }

    private long countIndexedDocuments() {
        try {
            return knowledgeBaseService.totalIndexedDocuments();
        } catch (Exception e) {
            log.warn("[管控] 统计知识库文档数失败: {}", e.getMessage());
            return 0;
        }
    }

    private static String describeSwitchImpact(ModelProfile from, ModelProfile to, int indices) {
        String oldName = from == null ? "（未配置）" : from.modelName();
        String newName = to == null ? "（取消配置）" : to.modelName();
        return "向量模型将由 " + oldName + " 变为 " + newName + "，知识库中已灌库的向量会全部作废。\n\n"
                + "当前有 " + indices + " 个知识库索引存有内容。换模型后有两种处理方式：\n"
                + "· 用新向量重灌 —— 原文保留，不需要重新上传文档（耗时与文档量成正比）\n"
                + "· 直接丢弃 —— 全部知识库内容删除，之后需自行重新上传";
    }

    private static String describeResult(boolean changed, ModelProfile previous, ModelProfile selected,
                                         String mode, boolean hadContent) {
        if (!changed) {
            return "向量模型没有变化";
        }
        String oldName = previous == null ? "（未配置）" : previous.modelName();
        String newName = selected == null ? "（已取消配置）" : selected.modelName();
        if (!hadContent) {
            return "向量模型已由 " + oldName + " 切换为 " + newName + "（知识库暂无内容，直接生效）";
        }
        if ("purge".equals(mode)) {
            return "向量模型已切换为 " + newName + "，并已按你的选择丢弃全部知识库内容";
        }
        return "向量模型已切换为 " + newName + "，知识库原文已按新向量重灌（文档无需重新上传）";
    }

    // ==================== 视图 ====================

    private Map<String, Object> profileView(ModelProfile profile) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("alias", profile.alias());
        item.put("endpoints", profile.endpoints());
        item.put("input", profile.input());
        item.put("output", profile.output());
        item.put("baseUrl", profile.baseUrl());
        item.put("modelName", profile.modelName());
        item.put("apiKeyMasked", profile.maskedApiKey());
        item.put("temperature", profile.temperature());
        item.put("maxTokens", profile.maxTokens());
        item.put("dimensions", profile.dimensions());
        item.put("capabilities", profile.capabilities());
        item.put("fallbacks", profile.fallbacks());
        item.put("capabilityHint", profile.capabilityHint());
        item.put("usedByDomains", registry.domainsUsing(profile.alias()));
        return item;
    }

    private Map<String, Object> result(String action, String target) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("action", action);
        body.put("target", target);
        body.put("settingsFile", store.filePath());
        return body;
    }

    /** 新建/覆盖档案的请求体（探测接口复用其中 baseUrl / apiKey / modelName） */
    @Data
    public static class ProfileBody {
        private String alias;
        /** 端点族（可多选；空 = chat） */
        private List<String> endpoints;
        /** 输入模态 */
        private List<String> input;
        /** 输出模态 */
        private List<String> output;
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
        /** 向量维度（仅 embedding 用） */
        private Integer dimensions;
        private List<String> capabilities;
        private List<String> fallbacks;
    }

    /** 绑定请求体（aliases 空 = 解绑；顺序即优先级） */
    @Data
    public static class BindBody {
        private List<String> aliases;
    }

    /**
     * 选用向量模型的请求体。
     *
     * @param alias 目标档案别名；空 = 取消向量模型配置
     * @param mode  已灌库内容时的处理方式：{@code rebuild}（保留原文重灌）/ {@code purge}（丢弃）。
     *              缺省表示用户尚未表态，接口会回"需要确认"而不真正切换。
     */
    @Data
    public static class EmbeddingBody {
        private String alias;
        private String mode;
    }
}
