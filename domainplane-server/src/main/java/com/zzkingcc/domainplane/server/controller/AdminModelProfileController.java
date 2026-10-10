package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.model.ModelCatalog;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import com.zzkingcc.domainplane.server.model.ProviderType;
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
 * 三条产品规则的落点：
 *
 *   • 档案建完不自动绑任何域，绑定只能通过本接口显式设置；
 *   • 一个域绑一组可调用模型（有序，整体覆盖）—— 首个是当前使用的模型，
 *       其余留给多 agent / 降级；
 *   • 没有内置 {@code default} 模型：对话只走用户自建档案，域必须先显式配置模型，
 *       未配置即"无可调用"。
 *
 *
 * 路径在 {@code /admin/**} 之下，鉴权由凭证拦截器统一处理。
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
        /* 向量模型：当前启用的是哪一个（alias），以及全部向量档案。
           向量模型不参与域绑定、全局只启用一个，所以它单独成段并列展示 ——
           每张卡片自己带「启用」，而不是"新建即占用槽位"。 */
        body.put("embeddingAlias", registry.embeddingAlias());
        body.put("embeddingProfiles", embeddingProfiles());
        body.put("settingsFile", store.filePath());
        return body;
    }

    /**
     * 全部向量档案，供向量段逐张成卡。
     *
     * 遍历的是全部档案而非 {@code registry.profiles()}：纯向量档案已单独存放，
     * 不在那一侧；只看 profiles 会让向量段一张卡片都出不来。
     *
     * 刻意不按"可用"过滤：不可用的向量档案也要能被看到并删除，
     * 过滤掉等于留下一份谁也管不着的档案。能不能启用由卡片上的按钮把关。
     */
    private List<Map<String, Object>> embeddingProfiles() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelProfile p : registry.allProfiles()) {
            if (p.isEmbedding()) {
                out.add(profileView(p));
            }
        }
        return out;
    }

    /**
     * 新建或整体覆盖一个档案（同名即覆盖）。
     *
     * {@code apiKey} 必填 —— 管控台只回填脱敏后的 Key，所以编辑时也必须重新填写。
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
                body.getBaseUrl(), apiKey, ProviderType.parse(body.getProviderType()), body.getModelName(),
                body.getTemperature(), body.getMaxTokens(),
                body.getThinking(), body.getReturnThinking(),
                body.getDimensions(), body.getCapabilities(), body.getFallbacks(),
                body.getParams());

        /* 建档案时若声明了向量维度，同样先探测模型是否接纳该维度，拒绝「填了但不被支持」的值 */
        String dimError = validateDimension(null, profile);
        if (dimError != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, dimError);
        }

        String failure = registry.save(profile);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        return result("saved", alias);
    }

    /**
     * 只改参数 —— 卡片右侧那块的「更改」走这里。
     *
     * 刻意不复用 {@link #save}：那条是整档覆盖、且 apiKey 必填，而卡片上根本没有可信的 Key ——
     * 模型列表只回脱敏值，拿它回写会把真 Key 覆盖成 {@code sk-a1****z9} 这种假串。
     * 所以这里按别名取出原档案，只替换参数，Key / 地址 / 协议 / 类型 / 模态一律原样写回。
     *
     * 参数留空即"不发送该字段"，与表单口径一致（{@link ModelProfile} 会把空值剔掉）。
     */
    @PostMapping("/model-profiles/params")
    public Map<String, Object> saveParams(@RequestBody(required = false) ProfileParamsBody body) {
        if (body == null || body.getAlias() == null || body.getAlias().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "别名不能为空");
        }
        String alias = body.getAlias().trim();
        ModelProfile existing = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        ModelProfile updated = withParams(existing, body.getTemperature(), body.getMaxTokens(),
                body.getThinking(), body.getReturnThinking(), body.getDimensions(), body.getParams());

        /* 维度是 ES 索引 mapping 的不可变契约：存错一个值 = 已灌库向量全部静默作废，且页面上看不出任何异常。
           保存前先用「读取」同款短请求探测模型是否真按该维度返回向量，不支持就直接拒绝，
           而不是等运行时在灌库时才炸 —— 这正是「先探测模型支持、再放行」的把关。 */
        String dimError = validateDimension(existing, updated);
        if (dimError != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, dimError);
        }

        /* 改的若是「当前启用向量模型」的维度，效果等同于换了一个向量模型：
           已灌库的向量长度对不上，ES 写入会被拒。所以必须和切换模型走同一条二次确认，
           绝不能静默存下去 —— 存了之后运行期用新维度、索引还是旧维度，检索直接失效，
           而且页面上看不出任何异常。 */
        String mode = body.getMode() == null ? "" : body.getMode().trim();
        boolean confirmed = "rebuild".equals(mode) || "purge".equals(mode);
        boolean dimensionChanged = updated.isEmbedding()
                && alias.equals(registry.embeddingAlias())
                && !java.util.Objects.equals(existing.dimensions(), updated.dimensions());
        int affectedIndices = existingIndexCount();
        boolean hasVectors = affectedIndices > 0 && hasAnyIndexedContent();
        if (dimensionChanged && hasVectors && !confirmed) {
            Map<String, Object> ask = new LinkedHashMap<>();
            ask.put("code", ErrorCode.INVALID_PARAMETER.getCode());
            ask.put("requiresConfirmation", true);
            ask.put("affectedIndices", affectedIndices);
            ask.put("documentCount", countIndexedDocuments());
            ask.put("previousDimensions", existing.dimensions());
            ask.put("nextDimensions", updated.dimensions());
            ask.put("message", "向量维度要从 " + dimOf(existing) + " 改成 " + dimOf(updated)
                    + "，知识库里已经存好的向量对不上，检索会失效。");
            return ask;
        }

        String failure = registry.save(updated);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        Map<String, Object> out = result("params-saved", alias);
        if (dimensionChanged) {
            /* 先让运行期用上新维度，再重灌 —— 顺序反了会拿老模型重算一遍 */
            holder.refreshEmbedding();
            if (hasVectors && "purge".equals(mode)) {
                out.put("removedIndices", knowledgeBaseService.purgeAll());
            } else if (hasVectors) {
                out.put("reindexed",
                        knowledgeBaseService.rebuildPreservingText(holder.effectiveEmbeddingDimension()));
            }
        }
        out.put("message", dimensionChanged ? "向量维度已更新" : "参数已更新");
        out.put("profile", profileView(updated));
        return out;
    }

    /** 维度的展示文案：未声明时说"按模型默认"，别把 null 直接拼进字符串 */
    private static String dimOf(ModelProfile profile) {
        return profile.dimensions() == null ? "按模型默认" : String.valueOf(profile.dimensions());
    }

    /**
     * 读取提供商元数据，判定一个模型的端点族 / 输入模态 / 输出模态 / 布尔能力。
     *
     * 不发任何试探请求：只读 {@code GET {baseUrl}/models}（地址缺 {@code /v1} 时
     * 按规范补一次重试）。读不到就如实留空，由用户在表单里手工声明 —— 猜类型会让向量模型
     * 被当成对话模型。
     *
     * 唯一的例外是向量维度：{@code GET /models} 按 OpenAI 规范压根不给维度，
     * 而维度是向量链路最硬的契约，所以向量档案会额外发一条极短的 {@code /embeddings}
     * 请求实测（见 {@link #withMeasuredDimension}）。这不是试探能力，只是取一个元数据里
     * 不存在的数。
     */
    @PostMapping("/model-profiles/inspect")
    public Map<String, Object> inspectModel(@RequestBody(required = false) ProfileBody body) {
        if (body == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "请求体不能为空");
        }
        return inspectBody(runInspect(body.getBaseUrl(), body.getApiKey(), body.getModelName(), body.getDimensions()));
    }

    /**
     * 把参数覆盖到原档案上（卡片「更改」用）。
     *
     * Key / 地址 / 协议 / 类型 / 模态 / 降级链一律取原值 —— 卡片只能动参数，
     * 这份构造就是"只动参数"这条约束本身，所以单独拎出来便于直接断言。
     */
    static ModelProfile withParams(ModelProfile existing, Double temperature, Integer maxTokens,
                                   Boolean thinking, Boolean returnThinking, Integer dimensions,
                                   Map<String, Object> params) {
        return new ModelProfile(existing.alias(), existing.endpoints(), existing.input(),
                existing.output(), existing.baseUrl(), existing.apiKey(), existing.providerType(),
                existing.modelName(), temperature, maxTokens, thinking, returnThinking, dimensions,
                existing.capabilities(), existing.fallbacks(), params);
    }

    /**
     * 探测「改后的向量维度」是否被模型真接受 —— 存之前的最后一道把关。
     *
     * 只在维度被显式声明、且相对原档案有变化时才探测：避免对"沿用模型默认（null）"或
     * 未变动的值反复发请求。探测用「读取」同款短请求（{@link LlmModelHolder#testEmbeddingConnection}），
     * 以模型实际返回的向量长度为唯一可信判据 —— 声明 10 却返回 1024，说明上游忽略了
     * {@code dimensions} 参数，该维度不被支持，必须拒绝，否则存进一个 ES 索引无法匹配的长度，
     * 灌库时静默作废全部旧向量、检索直接失效。
     *
     * 探测没跑完一律不算"确认支持"：服务商不认 dimensions、鉴权失败、地址写错、网络不通，
     * 全都拒绝保存并把原因回给用户。理由是拿不到实测维度就存下去，等于把一个没人验证过的
     * 长度写进 ES 索引契约 —— 灌库时静默作废全部旧向量，而页面上看不出任何异常。
     *
     * @param existing  改之前的档案（未变动则跳过探测）；新建档案传 {@code null}
     * @param candidate 改之后的档案
     * @return 不支持 / 探测失败的原因；{@code null} 表示通过
     */
    private String validateDimension(ModelProfile existing, ModelProfile candidate) {
        if (candidate.dimensions() == null) {
            return null;
        }
        if (!candidate.isEmbedding()) {
            return "只有向量模型（endpoints 含 embedding）才能设置 dimensions，当前模型是："
                    + (candidate.isChat() ? "对话" : "未声明") + " 类型";
        }
        if (existing != null && java.util.Objects.equals(candidate.dimensions(), existing.dimensions())) {
            return null; // 维度没变，跳过探测
        }
        Integer want = candidate.dimensions();
        /* 先测模型原生维度（不带 dimensions 参数）：固定维度模型（如 bge-m3）会直接拒绝
           dimensions 参数，哪怕给的就是它自己的 1024。所以"声明值 == 原生维度"就视为支持，
           绝不再发一次带参数的探测去自找 4xx —— 否则"用默认维度、检测后回填 1024"这种正常流程
           反而会被当成"不支持"拒掉。 */
        Integer nativeDim = measureNativeDimension(candidate);
        if (nativeDim != null && nativeDim.equals(want)) {
            return null;
        }
        /* 声明值 ≠ 原生维度 —— 试探模型是否支持自定义维度 */
        LlmSettings probe = new LlmSettings();
        probe.setEmbeddingBaseUrl(candidate.baseUrl());
        probe.setEmbeddingApiKey(candidate.apiKey());
        probe.setEmbeddingModelName(candidate.modelName());
        probe.setEmbeddingDimensions(want);
        try {
            LlmModelHolder.EmbeddingProbe result = holder.testEmbeddingConnection(probe);
            if (!result.success()) {
                return "向量维度 " + want + " 不被模型支持：该模型固定输出 "
                        + result.actualDimension() + " 维。";
            }
            return null;
        } catch (Exception e) {
            /* 探测没跑完就不能算"确认支持"：拿不到实测维度就把值存下去，
               等于把一个没人验证过的长度写进 ES 索引契约，灌库时会静默作废全部旧向量。
               所以这里一律拒绝，并把真实原因回给用户 —— 地址写错、Key 失效、服务商不认
               dimensions 参数都表现为探测失败，光看异常文案无法区分"只是没探成"和"确实不支持"，
               与其猜，不如让用户先把连通性确认掉。 */
            String detail = LlmFailureDescriber.describe(e);
            log.warn("[模型档案] 向量维度 {} 探测失败，拒绝保存: {}", want, detail);
            return "向量维度 " + want + " 设置失败：" + detail;
        }
    }

    /**
     * 不带 {@code dimensions} 参数发一次极短请求，取模型原生向量维度。
     *
     * 固定维度模型（bge-m3 等）压根不认 {@code dimensions} 参数，发带参数的请求会被 4xx 拒绝；
     * 这里刻意不带参数，拿到模型「出厂」维度，供 {@link #validateDimension} 判断"声明值是否等于原生维度"。
     * 探测失败返回 {@code null}（不抛）：上层据此跳过与原生维度的比对，改由带参探测决定放行与否。
     */
    private Integer measureNativeDimension(ModelProfile candidate) {
        LlmSettings probe = new LlmSettings();
        probe.setEmbeddingBaseUrl(candidate.baseUrl());
        probe.setEmbeddingApiKey(candidate.apiKey());
        probe.setEmbeddingModelName(candidate.modelName());
        try {
            return holder.testEmbeddingConnection(probe).actualDimension();
        } catch (Exception e) {
            log.info("[模型档案] 测量原生维度失败，跳过与原生维度的比对: {}", e.getMessage());
            return null;
        }
    }

    private ModelCatalog.CatalogResult runInspect(String baseUrl, String apiKey, String modelName,
                                                     Integer declaredDimension) {
        ModelCatalog.CatalogResult read;
        try {
            read = catalog.inspect(baseUrl, apiKey, modelName);
        } catch (IllegalStateException e) {
            throw new BaseException(ErrorCode.LLM_UNAVAILABLE, "读取提供商信息失败：" + e.getMessage());
        } catch (Exception e) {
            throw new BaseException(ErrorCode.LLM_UNAVAILABLE,
                    "读取提供商信息失败：" + LlmFailureDescriber.describe(e));
        }
        return read.needsDimensionProbe()
                ? withMeasuredDimension(read, baseUrl, apiKey, modelName, declaredDimension)
                : read;
    }

    /**
     * 向量档案补一个实测维度。
     *
     * 为什么必须在这里补：{@code GET /models} 按 OpenAI 规范就不给维度（连
     * OpenRouter 那种带全量元数据的也不给），而维度是向量链路最硬的契约 —— 声明错一个数，
     * 已灌库的向量全部作废。「读取」是用户唯一会主动去按的按钮，让它顺手把维度测出来，
     * 比让用户去「模型设置」页另做一次向量连通性测试更符合直觉。
     *
     * 把已声明的维度一并传下去：上游可能静默忽略 {@code dimensions} 参数并按模型默认
     * 返回。不给声明值就永远看不到这个不一致 —— 而它恰恰是最危险的一种：模型换成了同维度的
     * 另一个、或参数被忽略时，向量空间已经变了，检索结果只是安静地变差。
     *
     * 失败不连坐：维度测不出来（Key 无权调 embeddings、网络不通）只把原因并进说明，
     * 元数据已经读到的部分照常返回 —— 一次「读取」不该因为一条短请求失败就整条作废。
     */
    private ModelCatalog.CatalogResult withMeasuredDimension(ModelCatalog.CatalogResult read,
                                                             String baseUrl, String apiKey, String modelName,
                                                             Integer declaredDimension) {
        LlmSettings candidate = new LlmSettings();
        candidate.setEmbeddingBaseUrl(baseUrl);
        candidate.setEmbeddingApiKey(apiKey);
        candidate.setEmbeddingModelName(modelName);
        /* 先测原生维度（不带 dimensions 参数）：固定维度模型（bge-m3 等）会直接拒绝
           dimensions 参数，带参探测会被 4xx 误伤。先拿到原生维度，声明值恰好等于它时无需再探。 */
        Integer nativeDim;
        try {
            nativeDim = holder.testEmbeddingConnection(candidate).actualDimension();
        } catch (Exception e) {
            log.info("[模型档案] 向量维度实测失败，档案维度保持未声明: {}", e.getMessage());
            return ModelCatalog.withNote(read, "\n向量维度实测失败，需手工填写：" + e.getMessage());
        }
        String note = "\n向量维度已实测：" + nativeDim + " 维";
        if (declaredDimension != null && !declaredDimension.equals(nativeDim)) {
            /* 声明值与原生不同 → 试探模型是否支持自定义维度；失败只并进说明，不连坐整条读取 */
            candidate.setEmbeddingDimensions(declaredDimension);
            try {
                LlmModelHolder.EmbeddingProbe probe = holder.testEmbeddingConnection(candidate);
                if (probe.mismatched()) {
                    note += "（注意：档案声明为 " + declaredDimension + " 维，上游没按声明返回，仍返回 "
                            + probe.actualDimension() + " 维。改模型不改维度时向量空间一样会变，"
                            + "已灌库的向量会全部作废，切换前需先重建索引）";
                } else {
                    note += "（声明 " + declaredDimension + " 维也受支持）";
                }
            } catch (Exception e) {
                note += "（声明 " + declaredDimension + " 维探测失败：" + e.getMessage() + "）";
            }
        } else {
            note += "。";
        }
        return ModelCatalog.withDimension(read, nativeDim, note);
    }

    private static Map<String, Object> inspectBody(ModelCatalog.CatalogResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        /* 元数据来源要让前端能原样显示：探测"读不出东西"本身也是一种结论，
           不说清是谁不给，用户无从判断是自己填错了模型名还是服务商压根不发这些字段 */
        out.put("flavor", result.flavor().name());
        out.put("flavorLabel", result.flavor().label());
        out.put("endpoints", result.endpoints());
        out.put("input", result.input());
        out.put("output", result.output());
        out.put("capabilities", result.capabilities());
        out.put("dimension", result.dimension());
        out.put("message", result.note());
        return out;
    }

    /**
     * 删除档案，并级联清理所有域对该别名的绑定。
     *
     * 被清掉绑定的域立即进入"无可调用"状态，由管控台「域空间」页提示 ——
     * 删除不会被"仍被引用"拦在半路。
     *
     * 别名走查询参数的原因同 {@link #inspectSaved}：别名可含 {@code /}，放进路径段会被
     * Tomcat 以 400 拒收。
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
     * 别名走查询参数的原因同 {@link #inspectSaved}。
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
     * 解绑后该域沿域链向上继承最近一个绑了模型的祖先；整条链都没绑才是真的"无可调用"。
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
     * 选用向量模型（全局唯一，全部域共享）。
     *
     * 换向量模型会让已灌库的向量全部失效，所以只要知识库里已有内容，就必须先由
     * 管控台弹二次确认，并用 {@code mode} 明确用户选了哪条路：
     *
     *   • {@code rebuild} —— 保留原文重灌：删索引按新维度重建后，用新模型把
     *       原有切片重新向量化写回。用户不需要重新上传任何文档；
     *   • {@code purge} —— 丢弃旧内容：删掉全部知识库索引，之后自行重新上传。
     *
     *
     * 触发条件是「换了模型或维度任一变化」，不是只看维度。两个不同模型即使维度相同，
     * 向量空间也完全不一样，旧向量检索出来是噪声 —— 而这种错配不会报错，只会让检索质量
     * 静默下降，比维度不符更难发现。
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
     * 用新向量模型重建全部知识库索引，保留原文（不丢文档）。
     *
     * 供「切换时选重构」与知识库页的「按当前向量模型重灌」共用一条路径。
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
     * 一域都没建索引、或者索引建了但没灌过内容时，换向量模型是零风险的，
     * 不该弹二次确认 —— 那道确认框是为了防止"用户辛苦攒的知识库被清空"，
     * 对着空库弹只会让人养成无脑点确认的习惯。
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
        return "向量模型要从 " + oldName + " 换成 " + newName + "。\n\n"
                + "两个模型算出来的向量不是一回事，知识库里已经存好的向量会对不上，"
                + "继续用检索结果会不准。\n"
                + "当前有 " + indices + " 个索引存着内容。";
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
        /* 协议类型原样下发（枚举名，不是 null）：卡片按它决定哪些参数适用
           （top_k 只有 Anthropic / Ollama 认，惩罚项只有 OpenAI 兼容认）。 */
        item.put("providerType", profile.providerType().name());
        item.put("modelName", profile.modelName());
        item.put("apiKeyMasked", profile.maskedApiKey());
        item.put("temperature", profile.temperature());
        item.put("maxTokens", profile.maxTokens());
        /* 两个思考开关原样下发（null = 未声明）：管控台据此回填复选框，
           并靠 capabilities 里有没有 reasoning 决定要不要显示这两个开关 */
        item.put("thinking", profile.thinking());
        item.put("returnThinking", profile.returnThinking());
        item.put("dimensions", profile.dimensions());
        item.put("usable", profile.isUsable());
        item.put("capabilities", profile.capabilities());
        item.put("fallbacks", profile.fallbacks());
        /* 调优参数原样下发：管控台据此回填参数区；留空表示"不发这个字段" */
        item.put("params", profile.params());
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
        /**
         * 服务商协议类型名（留空 = OpenAI 兼容）。
         *
         * 用字符串接收而非枚举：认不出来的值由 {@code ProviderType.parse} 退回归兼容，
         * 不会让整个请求体反序列化失败。
         */
        private String providerType;
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
        /**
         * 是否开启思考模式。
         *
         * {@code null} = 不声明（不发 {@code enable_thinking}，由服务商默认决定），
         * 不是"关闭"。只有管控台探测到 {@code reasoning} 能力、把开关显示出来，才可能带上它 ——
         * 没探测出来的模型一律传 null，档案里也就不会留下一个来路不明的 false。
         */
        private Boolean thinking;
        /** 是否把思考内容回传给平台（null = 不声明） */
        private Boolean returnThinking;
        /** 向量维度（仅 embedding 用） */
        private Integer dimensions;
        private List<String> capabilities;
        private List<String> fallbacks;
        /** 调优参数（前缀键，如 chat.topP）；未填的项不要下发 */
        private Map<String, Object> params;
    }

    /**
     * 只改参数的请求体（卡片内联编辑用）。
     *
     * 刻意不含 apiKey / baseUrl / providerType / endpoints —— 卡片上只能动参数，
     * 其余字段要靠这里"不接收"来保证不被误改。
     */
    @Data
    public static class ProfileParamsBody {
        private String alias;
        private Double temperature;
        private Integer maxTokens;
        private Integer dimensions;
        /** 三态：null = 不声明（由服务商默认决定） */
        private Boolean thinking;
        private Boolean returnThinking;
        private Map<String, Object> params;
        /**
         * 仅"改了当前启用向量模型的维度"时才有意义：{@code rebuild}（重灌）/ {@code purge}（丢弃）。
         * 缺省表示用户尚未表态，接口会回"需要确认"而不是真的改。
         */
        private String mode;
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
