package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
import com.zzkingcc.domainplane.server.model.ModelMetadataReader;
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
    /** 元数据读取器：端点族与输入 / 输出模态的唯一来源（读服务商清单，不发推理请求） */
    private final ModelMetadataReader metadataReader;
    /** 服务商适配注册表：保存时复核 params，列表视图下发适配摘要 */
    private final ModelAdapterRegistry adapterRegistry;
    /** 域注册表：绑定只能指向已登记的域（否则绑定会变成永远不生效的孤儿） */
    private final DomainRegistry domainRegistry;
    /** 知识库：换向量模型时用它保留原文重灌，或按用户选择清空 */
    private final KnowledgeBaseService knowledgeBaseService;

    public AdminModelProfileController(ModelProfileRegistry registry,
                                       ModelProfileStore store,
                                       LlmModelHolder holder,
                                       ModelMetadataReader metadataReader,
                                       ModelAdapterRegistry adapterRegistry,
                                       DomainRegistry domainRegistry,
                                       KnowledgeBaseService knowledgeBaseService) {
        this.registry = registry;
        this.store = store;
        this.holder = holder;
        this.metadataReader = metadataReader;
        this.adapterRegistry = adapterRegistry;
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
     *
     * 请求体不带 {@code endpoints} 时由服务端读服务商元数据补上（见 {@link #readMetadata(ModelProfile)}）：
     * 管控台表单不再让用户选类型，识别结果决定这份档案归入哪一段、卡片上的模态两行显示什么。
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
                body.getBaseUrl(), apiKey, body.getModelName(),
                body.getTemperature(), body.getMaxTokens(),
                body.getThinking(), body.getReturnThinking(),
                body.getDimensions(), body.getFallbacks(), body.getParams());

        /* 服务商适配复核：命中了才允许 params，且必须过 schema；没命中则 params 必须为空。
           前端可被绕过（直接调 API），"未适配不能调优"在这里强制成立 */
        String adapterError = checkAdapterTuning(profile);
        if (adapterError != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, adapterError);
        }

        /* 端点族与输入 / 输出模态：表单都不采集（用户不选类型）。空值按两级取：
           ① **先看适配文件** —— 登记过这个模型（按模型名命中）就以它为准：端点族是登记了参数的
              族名、模态是同一个文件里写明的。内置模型因此一次上游请求都不用发；
           ② 没登记才去读服务商的模型清单（GET /models，只发 GET，零 token）。
           放在上面那道门禁之后：参数不合规的请求不该先花掉一次上游往返 */
        ModelMetadataReader.MetadataResult metadata = null;
        if (profile.endpoints().isEmpty()) {
            ModelAdapterRegistry.Declaration declared =
                    adapterRegistry.declaration(profile.baseUrl(), profile.modelName()).orElse(null);
            if (declared != null) {
                profile = profile.withDetected(declared.endpoints(), declared.input(), declared.output(), true);
                log.info("[模型档案] 端点族与模态取自适配文件：model={} adapter={} endpoints={} input={} output={}",
                        profile.modelName(), declared.adapterId(), declared.endpoints(),
                        declared.input(), declared.output());
            } else {
                metadata = readMetadata(profile);
                profile = profile.withDetected(metadata.endpoints(), metadata.input(), metadata.output(),
                        metadata.authoritative());
            }
        }

        /* 向量档案没填维度时，用模型**原生维度**回填 —— 卡片不再显示"按模型默认"，
           而是显示模型实际会产出的维度（如 bge-m3 的 1024），用户一眼看得见要拿什么去配 ES。
           尽力而为：探测失败（网络 / Key / 服务商不认）保留 null，语义回落到"按模型默认"，
           不因为一次可选回填失败就拒绝整次保存。 */
        if (profile.isEmbedding() && profile.dimensions() == null) {
            Integer nativeDim = measureNativeDimension(profile);
            if (nativeDim != null) {
                profile = profile.withDimensions(nativeDim);
                log.info("[模型档案] 向量维度未指定，按模型原生维度回填：model={} dimensions={}",
                        profile.modelName(), nativeDim);
            }
        }

        /* 建档案时若声明了向量维度，同样先探测模型是否接纳该维度，拒绝「填了但不被支持」的值 */
        String dimError = validateDimension(null, profile);
        if (dimError != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, dimError);
        }

        String failure = registry.save(profile);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        Map<String, Object> out = result("saved", alias);
        /* 类型没读到时把原因回给接口调用方（脚本 / 集成方排障用）。
           界面不展示这段：卡片上的「未声明类型」徽标与模态行的「—」已经是给用户看的信号，
           同一件事不必说两遍。 */
        if (metadata != null && profile.endpoints().isEmpty()) {
            out.put("note", metadata.note());
        }
        return out;
    }

    /**
     * 只改调优参数（卡片右侧绿色调优框的保存口）。
     *
     * 与 {@link #save(ProfileBody)} 分开的原因有两条，都是产品口径而非偷懒：
     *
     *   • 连接信息（服务商地址 / API Key / 模型名）**不可改**。老接口要求重填明文 Key，
     *     而卡片上只有脱敏值 —— 为改一个温度就得让用户把 Key 再输一遍，既麻烦又把
     *     密钥多暴露一次。本接口从既有档案带过连接信息与端点族 / 模态，
     *     客户端**根本没有**改它们的字段，而不是"前端不发、后端信任"。
     *   • 调优是高频微调动作。走老接口会连 `endpoints` 一起重算 —— 端点族为空时还会
     *     触发一次元数据读取（最多 1 + 7 条上游 GET），改个温度不该付这个代价。
     *
     * 只命中适配的档案可调（绿框也只在它们上面出现）；未适配一律拒绝，
     * 与 {@link #save(ProfileBody)} 的复核口径一致。
     */
    @PutMapping("/model-profiles/tuning")
    public Map<String, Object> updateTuning(@RequestBody(required = false) TuningBody body) {
        if (body == null || body.getAlias() == null || body.getAlias().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "别名不能为空");
        }
        String alias = body.getAlias().trim();
        ModelProfile existing = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        /* 未适配就没有可调的键：给一份没有 schema 的档案放行，等于把任意参数字典塞进请求体 */
        ModelAdapterRegistry.MatchResult matched =
                adapterRegistry.match(existing.baseUrl(), existing.modelName());
        if (!matched.matched()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "服务商 / 模型「" + existing.modelName() + "」未做适配，不支持参数调优");
        }

        /* 只换调优字段：其余字段原样带走（见 ModelProfile#withTuning）。
           向量卡片绿框改的就是 dimensions，一并带上 —— 它同样走专用字段，不进 params */
        ModelProfile updated = existing.withTuning(
                body.getTemperature(), body.getMaxTokens(), body.getDimensions(), body.getParams());

        String adapterError = checkAdapterTuning(updated);
        if (adapterError != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, adapterError);
        }
        /* 维度变了要探测模型是否真接纳它（向量卡片改维度的最后一道关，与新建档案同一套判据）；
           端点族不重识别 —— 它不属于"调优"，本接口也改不动。 */
        String dimError = validateDimension(existing, updated);
        if (dimError != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, dimError);
        }
        String failure = registry.save(updated);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        Map<String, Object> out = result("tuned", alias);
        out.put("message", "已保存 " + alias + " 的调优参数");
        /* 回带新视图：卡片拿它就地重画绿框与「已适配」摘要，不必为一次调优重拉整张列表 */
        out.put("profile", profileView(updated));
        return out;
    }

    /**
     * 只改「是否回写思考内容」（模型卡片头部那颗开关的保存口）。
     *
     * 与 {@link #updateTuning} 并列的第二条卡片级保存路径，分工是产品口径：
     * 调优参数命中适配才可改，本开关对所有档案一视同仁 —— 思考是对话流的概念，
     * 与"这家服务商有没有登记调优 schema"无关。连接信息同样不可改
     * （{@link ModelProfile#withReturnThinking()} 复制时原样带走），拨开关的人
     * 根本没有改它们的字段。
     *
     * 不要求重填 API Key：卡片上只有脱敏值，为拨一颗开关让用户把密钥再输一遍，
     * 既麻烦又把密钥多暴露一次。
     */
    @PutMapping("/model-profiles/return-thinking")
    public Map<String, Object> updateReturnThinking(@RequestBody(required = false) ReturnThinkingBody body) {
        if (body == null || body.getAlias() == null || body.getAlias().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "别名不能为空");
        }
        String alias = body.getAlias().trim();
        ModelProfile existing = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        /* 显式布尔：null（不声明）与 false 对调用者后果相同，拨过一趟就落定 */
        ModelProfile updated = existing.withReturnThinking(Boolean.TRUE.equals(body.getReturnThinking()));

        String failure = registry.save(updated);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        /* 指纹含 returnThinking（ModelProfile#fingerprint），客户端按指纹缓存：
           档案一改指纹就变，新实例带上新开关，不必重启。 */
        Map<String, Object> out = result("return-thinking", alias);
        out.put("message", "已保存 " + alias + " 的思考回写开关");
        out.put("profile", profileView(updated));
        return out;
    }

    /**
     * 读服务商元数据，拿到端点族与输入 / 输出模态 —— 表单不再让用户选类型。
     *
     * 为什么读清单而不是发试探请求：试探要花 token（一条 chat + 一条 embeddings），成败还受配额 /
     * 限流 / 网络抖动影响；而**输出模态根本试不出来**（试探只能回答"这个端点通不通"）。
     * 清单是提供商的声明，{@code GET /models} 不计费，成本上真正是零。
     *
     * "读不到"与"读失败"必须分开，两者后果完全相反：
     *
     *   • 读不到（清单里没这个模型 / 清单是标准 OpenAI 规范只有四个字段 / 服务商不提供
     *     {@code /models}）→ 如实留空存下来，管控台标「未声明类型」。若为此拒绝保存，
     *     OpenAI 与 DeepSeek 官方这类标准规范的服务商一个档案都建不出来 ——
     *     而这恰恰是内置预设里那两家。
     *   • 读失败（连不上 / 鉴权被拒 / 响应不是 JSON）→ 拒绝保存并把原因回给用户。
     *     那是用户能改的错（地址、Key），不该被静默存成一份连不上的档案。
     *
     * @throws BaseException 读失败；原因原样带上，用户据此判断是地址写错还是 Key 失效
     */
    private ModelMetadataReader.MetadataResult readMetadata(ModelProfile candidate) {
        try {
            ModelMetadataReader.MetadataResult meta =
                    metadataReader.read(candidate.baseUrl(), candidate.apiKey(), candidate.modelName());
            if (meta.endpoints().isEmpty()) {
                log.info("[模型档案] 元数据未声明模型类型：model={} baseUrl={} —— {}",
                        candidate.modelName(), candidate.baseUrl(), meta.note());
            } else {
                log.info("[模型档案] 元数据识别：model={} endpoints={} input={} output={}（模态权威={}）",
                        candidate.modelName(), meta.endpoints(), meta.input(), meta.output(),
                        meta.authoritative());
            }
            return meta;
        } catch (ModelMetadataReader.MetadataException e) {
            log.warn("[模型档案] 读取模型元数据失败：model={} baseUrl={} 原因={}",
                    candidate.modelName(), candidate.baseUrl(), e.getMessage());
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "无法读取模型元数据：" + e.getMessage());
        }
    }

    /** 维度的展示文案：未声明时说"按模型默认"，别把 null 直接拼进字符串 */
    private static String dimOf(ModelProfile profile) {
        return profile.dimensions() == null ? "按模型默认" : String.valueOf(profile.dimensions());
    }

    /**
     * 服务商适配复核（保存前的最后一道关）。
     *
     * 命中适配 → 全部调优值必须过 schema（未知键 / 类型 / 越界一律拒绝，文案带合法键）；
     * 未命中 → params 必须为空。"没适配就不能调优"对直接调 API 同样成立 ——
     * 前端只是不渲染工具栏，绕过前端的请求在这里被挡住。
     *
     * @return {@code null} = 通过；否则为可直接展示给用户的拒绝原因
     */
    private String checkAdapterTuning(ModelProfile profile) {
        ModelAdapterRegistry.MatchResult matched =
                adapterRegistry.match(profile.baseUrl(), profile.modelName());
        if (matched.matched()) {
            return adapterRegistry.validateParams(matched, tuningValues(matched, profile));
        }
        if (!profile.params().isEmpty()) {
            return "服务商 / 模型「" + profile.modelName() + "」未做适配，不支持参数调优";
        }
        return null;
    }

    /**
     * 把档案上的调优值摊平成一个 key→value map（私有参数 + 标准参数），交给 schema 统一复核。
     *
     * 标准参数（温度 / 单次最大输出）落在 {@link ModelProfile} 的老字段里，不在 {@code params} map 里；
     * 不摊平就漏过 min / max 校验 —— 前端 number 控件上的 min / max 只是填表便利，
     * 直接调 API 一样能提交越界值，把关必须在这里。
     */
    private static Map<String, Object> tuningValues(ModelAdapterRegistry.MatchResult matched,
                                                    ModelProfile profile) {
        Map<String, Object> values = new LinkedHashMap<>(profile.params());
        /* 全部族的 std 参数都摊平进来：chat 族是温度 / 单次最大输出，embedding 族是向量维度 ——
           漏掉任一族的 std 参数，它的 min / max 就没人把关，直接调 API 能塞越界值 */
        for (ModelAdapterRegistry.ParamSpec spec : matched.allParams()) {
            if (!spec.std()) {
                continue;
            }
            Object value = stdValueOf(spec, profile);
            if (value != null) {
                values.put(spec.key(), value);
            }
        }
        return values;
    }

    /**
     * 标准参数（std=true）在档案上的落点：按 wire 取老字段（见 ModelAdapterRegistry.STD_WIRES）。
     *
     * 刻意写成 if 而不是三元表达式：`maxTokens()` 是 {@code Integer}、`temperature()` 是 {@code Double}，
     * 两者放进同一个三元里会被 Java 做**二元数值提升**（先各自解箱再取公共类型 {@code double}），
     * 于是"值未设置（null）"直接 NPE —— 而"未设置"恰恰是常态（用户只填了一个参数）。
     */
    private static Object stdValueOf(ModelAdapterRegistry.ParamSpec spec, ModelProfile profile) {
        if ("max_tokens".equals(spec.wire())) {
            return profile.maxTokens();
        }
        if ("dimensions".equals(spec.wire())) {
            return profile.dimensions();
        }
        return profile.temperature();
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

    /**
     * 删除档案，并级联清理所有域对该别名的绑定。
     *
     * 被清掉绑定的域立即进入"无可调用"状态，由管控台「域空间」页提示 ——
     * 删除不会被"仍被引用"拦在半路。
     *
     * 别名走查询参数：别名可含 {@code /}，放进路径段会被 Tomcat 以 400 拒收。
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
     * 别名走查询参数的原因同 {@link #delete(String)}：别名可含 {@code /}。
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
        item.put("modelName", profile.modelName());
        item.put("apiKeyMasked", profile.maskedApiKey());
        item.put("temperature", profile.temperature());
        item.put("maxTokens", profile.maxTokens());
        /* 两个思考开关原样下发（null = 未声明） */
        item.put("thinking", profile.thinking());
        item.put("returnThinking", profile.returnThinking());
        item.put("dimensions", profile.dimensions());
        item.put("usable", profile.isUsable());
        item.put("fallbacks", profile.fallbacks());
        item.put("params", profile.params());
        /* 适配摘要：命中适配就下发 id / label 与当前生效的调优值（供卡片渲染绿色工具框）。
           
           门槛是 {@link ModelProfile#mayBeChat()} ——「**有可能**走对话端点」，不是「已确认是对话模型」：
             • 已确认 chat → 下发；
             • 端点族未声明 → **也下发**。标准 OpenAI 规范的服务商（OpenAI / DeepSeek 官方、各类中转）
               的模型清单读不出类型，档案就停在"未声明"；而这两家恰恰是内置预设里唯一有适配的 ——
               因为"读不出类型"就把工具框收走，等于把最有用的两家用户挡在门外，卡片上还只留下一句
               「未声明类型」，看不出"框本来该有"；
             • 已声明为**向量** → 下发，但下的是 embedding 族的参数（目前只有 dimensions）：
               向量卡片要的就是"维度可就地改"，与 chat 档案的绿框同款交互、不同族参数；
             • 已声明为生图 / 语音 / 重排 / 视频 → 不下发：那些族的调优语义各不相同且尚未登记，
               把别的族的参数摆在它们上是张冠李戴。 */
        if (profile.mayBeChat() || profile.isEmbedding()) {
            ModelAdapterRegistry.MatchResult matched =
                    adapterRegistry.match(profile.baseUrl(), profile.modelName());
            /* 该档案对应族**真有参数**才下发：host 兜底命中的向量档案（如 api.deepseek.com 上的
               deepseek-embedding）只说明这家服务商登记过，套上 chat 族的参数就是张冠李戴 ——
               它的 embedding 族为空，自然不给摘要、卡片上也就没有绿框 */
            if (matched.matched() && !matched.tuningParams(profile).isEmpty()) {
                item.put("adapter", adapterSummary(matched, profile));
            }
        }
        item.put("usedByDomains", registry.domainsUsing(profile.alias()));
        return item;
    }

    /**
     * 适配摘要：id / label / docUrl + 参数 schema + 当前有值的调优项。
     *
     * 下发 schema（{@code params}）而不是只下发若干「键 = 值」的文字，是因为卡片右侧要放一个
     * **可编辑**的调优框：控件类型（枚举 / 布尔 / 数值及 min / max / step）只能从 schema 得到。
     * 形状与 {@code POST /admin/model-adapters/match} 的 {@code adapter.params} 保持一致 ——
     * 模态里与卡片上渲染的是同一套 schema，两份形状漂移会让两处控件长得不一样。
     *
     * 下发的参数按**档案类型**取族（见 {@link ModelAdapterRegistry.MatchResult#tuningParams}）：
     * 向量档案给 embedding 族的 dimensions，对话档案给 chat 族 —— 卡片绿框渲染的就是这一份。
     *
     * {@code items} 只列当前有值的项（std 参数从老字段取，私有参数从 params 取），供前端回填。
     */
    private Map<String, Object> adapterSummary(ModelAdapterRegistry.MatchResult matched, ModelProfile profile) {
        List<ModelAdapterRegistry.ParamSpec> specs = matched.tuningParams(profile);
        Map<String, Object> ad = new LinkedHashMap<>();
        ad.put("id", matched.adapter().id());
        ad.put("label", matched.adapter().label());
        ad.put("docUrl", matched.adapter().docUrl());
        ad.put("params", specs);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ModelAdapterRegistry.ParamSpec spec : specs) {
            Object value = spec.std() ? stdValueOf(spec, profile) : profile.params().get(spec.key());
            if (value != null) {
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("key", spec.key());
                it.put("label", spec.label());
                it.put("value", value);
                items.add(it);
            }
        }
        ad.put("items", items);
        return ad;
    }

    private Map<String, Object> result(String action, String target) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("action", action);
        body.put("target", target);
        body.put("settingsFile", store.filePath());
        return body;
    }

    /** 新建/覆盖档案的请求体 */
    @Data
    public static class ProfileBody {
        private String alias;
        /**
         * 端点族。
         *
         * 管制台表单不再采集（用户不选类型）—— 留空即由服务端读服务商模型清单补上，
         * 识别结果决定这份档案归入「模型」段还是「向量模型」段；
         * 显式传值则原样采用（供脚本 / 集成方直接声明）。
         */
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
        /**
         * 是否开启思考模式。
         *
         * {@code null} = 不声明（由服务商默认决定），不是"关闭"。
         */
        private Boolean thinking;
        /** 是否把思考内容回传给平台（null = 不声明） */
        private Boolean returnThinking;
        /** 向量维度（仅 embedding 用） */
        private Integer dimensions;
        private List<String> fallbacks;
        /**
         * 服务商适配的私有调优参数（如 {@code enable_thinking}）。
         *
         * 未命中适配的档案必须为空 / null；命中时由服务端按适配 schema 复核。
         */
        private Map<String, Object> params;
    }

    /**
     * 只改调优参数的请求体。
     *
     * 刻意没有 {@code baseUrl} / {@code apiKey} / {@code modelName} / {@code endpoints} /
     * {@code input} / {@code output} 字段：连接信息与端点族 / 模态不是"调优"，
     * 本接口从既有档案原样带过，客户端无从改动。
     *
     * {@code dimensions} 是**例外**——它是向量卡片绿框唯一可改的项，走专用字段（不进 params）。
     *
     * 四个字段都是**全量替换**语义：{@code null} / 空 map = 清除该项（回到服务商默认），
     * 不是"不修改"。卡片上把温度 / 维度清空再保存，就该真的不再发它们 ——
     * 若按"null = 不动"处理，用户会发现清不掉。
     */
    @Data
    public static class TuningBody {
        private String alias;
        /** 私有调优参数（key → 值）；命中适配时按 schema 复核 */
        private Map<String, Object> params;
        /** 温度（std 参数）；null = 清除 */
        private Double temperature;
        /** 单次最大输出 token（std 参数）；null = 清除 */
        private Integer maxTokens;
        /** 向量维度（embedding 族 std 参数）；null = 清除（回到"按模型默认"） */
        private Integer dimensions;
    }

    /**
     * 只改「是否回写思考内容」的请求体（卡片头部开关的保存口）。
     *
     * 与 {@link TuningBody} 一样刻意单薄：连接信息 / 调优值 / 端点族都不是这颗开关
     * 能碰的，服务端从既有档案原样带过。
     *
     * {@code returnThinking} 缺失按 {@code false} 处理 —— 开关的默认态就是关，
     * "没带上这个字段"与"明确关它"对调用者是同一件事。
     */
    @Data
    public static class ReturnThinkingBody {
        private String alias;
        /** true = 把思考内容回传给调用者；false = 不回传（框架直接丢弃） */
        private Boolean returnThinking;
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
