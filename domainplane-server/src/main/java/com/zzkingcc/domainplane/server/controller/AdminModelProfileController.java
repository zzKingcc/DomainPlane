package com.zzkingcc.domainplane.server.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.model.CustomParamsGate;
import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
import com.zzkingcc.domainplane.server.model.ModelCapabilityProbe;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 管理面：模型档案与「域 → 档案」绑定。
 *
 * 三条产品规则的落点：
 *
 *   • 档案建完不自动绑任何域，绑定只能通过本接口显式设置；
 *   • 一个域绑一组可调用模型（有序，整体覆盖）：首个是当前使用的模型，
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
    /** 能力探测器：元数据读不出类型时，用手动「探测」实测端点族与模态（花少量 token） */
    private final ModelCapabilityProbe capabilityProbe;
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
                                       ModelCapabilityProbe capabilityProbe,
                                       DomainRegistry domainRegistry,
                                       KnowledgeBaseService knowledgeBaseService) {
        this.registry = registry;
        this.store = store;
        this.holder = holder;
        this.metadataReader = metadataReader;
        this.adapterRegistry = adapterRegistry;
        this.capabilityProbe = capabilityProbe;
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
           向量模型不参与域绑定、全局只启用一个，所以它单独成段并列展示：
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
     * {@code apiKey} 必填：管控台只回填脱敏后的 Key，所以编辑时也必须重新填写。
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
                body.getContextWindow(),
                body.getThinking(), body.getReturnThinking(),
                body.getDimensions(), body.getFallbacks(), body.getParams(), body.getProtocol());

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

        /* 向量档案没填维度时，用模型**原生维度**回填，卡片不再显示"按模型默认"，
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

        /* 建档案时若填了向量维度，校验它是否等于模型原生维度（维度是索引契约，不随请求下发） */
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
     * 只**按模型名命中适配**的档案可调（绿框也只在这些档案上出现，见 {@link #profileView}）：
     * 未适配 / 仅 host 兜底一律拒绝，门槛与绿框下发完全同一口径（{@code byModelName()}）。
     *
     * 注：{@link #checkAdapterTuning} 保存复核用的是更宽松的 {@code matched()} —— 见那里的说明，
     * 两者**刻意不同**：复核宽松只为不把存量已存参数卡死，本接口收紧是因为"能改"必须与"有绿框"对齐。
     */
    @PutMapping("/model-profiles/tuning")
    public Map<String, Object> updateTuning(@RequestBody(required = false) TuningBody body) {
        if (body == null || body.getAlias() == null || body.getAlias().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "别名不能为空");
        }
        String alias = body.getAlias().trim();
        ModelProfile existing = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        /* 未适配就没有可调的键：给一份没有 schema 的档案放行，等于把任意参数字典塞进请求体。
           判据是 byModelName()（不是 matched()）—— host 兜底不算适配，卡片上也没有绿框，
           界面既然不给改，接口就不该收（"能改"与"有绿框"必须同一口径）。 */
        ModelAdapterRegistry.MatchResult matched =
                adapterRegistry.match(existing.baseUrl(), existing.modelName());
        if (!matched.byModelName()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "服务商 / 模型「" + existing.modelName() + "」未做适配，不支持参数调优");
        }

        /* 只换调优字段：其余字段原样带走（见 ModelProfile#withTuning）。
           向量卡片绿框改的就是 dimensions，一并带上，它同样走专用字段，不进 params */
        ModelProfile updated = existing.withTuning(
                body.getTemperature(), body.getMaxTokens(), body.getDimensions(), body.getParams());

        String adapterError = checkAdapterTuning(updated);
        if (adapterError != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, adapterError);
        }
        /* 维度变了要探测模型是否真接纳它（向量卡片改维度的最后一道关，与新建档案同一套判据）；
           端点族不重识别：它不属于"调优"，本接口也改不动。 */
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
     * 调优参数命中适配才可改，本开关对所有档案一视同仁：思考是对话流的概念，
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
     * 只改上下文窗口（卡片上那个独立输入框的保存口）。
     *
     * <p>与 {@link #updateReturnThinking} 一样单薄，但**不能**与它、也不能与
     * {@link #updateTuning} 共用一个接口，两条都是产品口径而非偷懒：</p>
     *
     * <ul>
     *   <li>它不属于"调优"：上下文长度是平台自己消费的一个上限，不是发给服务商的参数
     *       （OpenAI 兼容端点没有这个入参）。塞进 {@code params} 会让它被当成"要发给上游的键"，
     *       而 {@code params} 还要过适配 schema 的关，那套 schema 里没有、也不该有这一项。</li>
     *   <li>{@link #updateTuning} 也搭不上：那个接口按 {@code byModelName()}（是否<b>按模型名</b>命中适配）
     *       放行，而标准 OpenAI / DeepSeek 这类最常见的档案恰恰不命中，从那里根本改不到。
     *       卡片上的绿色调优框同理，只在命中适配且该族真有参数的档案上渲染
     *       （见 {@link #profileView} 的条件），未适配的卡片上压根没有那个框。
     *       故本接口对<b>任何</b>可用档案开放，不设适配门禁。</li>
     * </ul>
     *
     * <p><b>除正数外不做取值校验</b>：这个数只影响"这一轮给模型看多少"，填错只波及填它的人，
     * 不移动任何权限边界（不参与 {@code domainBindable()}）。用户按服务商文档填 32k 还是 128k，
     * 平台照用，但"填大了不构成保证"这件事该由卡片上的输入提示去说，不该由后端假装知道。</p>
     *
     * <p>{@code contextWindow} 传 {@code null} = 清除（回到"未声明"，装配预算维持配置值），
     * 与 {@link TuningBody} 的全量替换语义一致。</p>
     */
    @PutMapping("/model-profiles/context-window")
    public Map<String, Object> updateContextWindow(@RequestBody(required = false) ContextWindowBody body) {
        if (body == null || body.getAlias() == null || body.getAlias().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "别名不能为空");
        }
        String alias = body.getAlias().trim();
        ModelProfile existing = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        Integer window = body.getContextWindow();
        if (window != null && window <= 0) {
            /* 只拦类型层面的错，不拦"填得对不对"：非正数流进装配预算会算出负数，
               降级链会一路退到"只剩系统提示 + 本轮提问"，那是把"输出被截断"这种小事
               放大成"整轮装不下"。这是类型问题，不是信不信用户的问题。 */
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "上下文长度必须是正整数（单位 token）；要清除请留空");
        }

        ModelProfile updated = existing.withContextWindow(window);
        String failure = registry.save(updated);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        Map<String, Object> out = result("context-window", alias);
        out.put("message", window == null
                ? "已清除 " + alias + " 的上下文长度，装配预算回到配置值"
                : "已保存 " + alias + " 的上下文长度为 " + window + " token");
        out.put("profile", profileView(updated));
        return out;
    }

    /**
     * 只改自定义参数（卡片上那个 JSON 文本框的保存口）。
     *
     * <p>这是"用户声明、我透传"那条路线的落点：厂商的参数面一直在变，而我们既没有足够的人
     * 逐个实测，也没有办法保证写进去的 min / max / step 是对的 —— 与其给出一堆"看起来配了、
     * 实际没验证过"的声明，不如把参数面交回给用户。<b>未命中适配的档案恰恰最需要这条路</b>，
     * 而现在 30 个模型里 29 个走它。</p>
     *
     * <p>与 {@link #updateTuning} 并列而非合并：调优框是按适配 schema 渲染控件的，
     * 只出现在按模型名命中适配的档案上；本接口不需要 schema，适用面正好互补。
     * 两者都改 {@code params} 这一个字段，但判据不同 —— 合在一起就得在接口内部再判一次
     * "这份档案走哪条路"，而那个判据前后端各存一份就会漂移。</p>
     *
     * <p>收**文本**而不是收 map：文本框里填的是 JSON，用户写错一个逗号时 Jackson 的
     * 反序列化错误发生在参数绑定阶段，拿不到原始文本，也就没法回一句"第几行多了个逗号"。
     * 收文本再自己解析，解析错误才能带上位置信息 —— 而"看不出哪里错了"正是这类
     * 自助配置最容易劝退人的地方。</p>
     *
     * <p>连接信息 / 标准字段 / 端点族一律原样带走（{@link ModelProfile#withParams}），
     * 客户端根本没有改它们的字段，而不是"前端不发、后端信任"。空白 = 清除。</p>
     */
    @PutMapping("/model-profiles/custom-params")
    public Map<String, Object> updateCustomParams(@RequestBody(required = false) CustomParamsBody body) {
        if (body == null || body.getAlias() == null || body.getAlias().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "别名不能为空");
        }
        String alias = body.getAlias().trim();
        ModelProfile existing = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        ModelProfile updated = existing.withParams(parseCustomParams(body.getParamsJson()));

        String adapterError = checkAdapterTuning(updated);
        if (adapterError != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, adapterError);
        }
        String failure = registry.save(updated);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        Map<String, Object> out = result("custom-params", alias);
        out.put("message", updated.params().isEmpty()
                ? "已清除 " + alias + " 的自定义参数"
                : "已保存 " + alias + " 的自定义参数（" + updated.params().size() + " 个键）");
        out.put("profile", profileView(updated));
        return out;
    }

    /**
     * 只改协议（卡片上那个下拉的保存口）。
     *
     * <p>与其余卡片级保存口分开，理由不是"又一个单薄接口"，而是它的副作用比看上去大：
     * <b>换协议必然清空自定义参数</b>。同一个 {@code max_tokens} 在 OpenAI 下可选、
     * 在 Anthropic 下必填，字段名相同、语义不同；留着旧值的后果是"我明明填了却不生效"，
     * 而这个黑盒用户打不开 —— 他只能看到上游报错，看不到中间那条断层。
     * 清空的代价只是一次重填。</p>
     *
     * <p>标准字段（温度 / 单次最大输出 / 思考回写）不动：两侧 builder 都有同名 setter，语义一致。
     * 连接信息同样原样带走（{@link ModelProfile#withProtocol}）。</p>
     *
     * <p>协议进出指纹，客户端缓存按指纹失效，因此改完不必重启。</p>
     */
    @PutMapping("/model-profiles/protocol")
    public Map<String, Object> updateProtocol(@RequestBody(required = false) ProtocolBody body) {
        if (body == null || body.getAlias() == null || body.getAlias().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "别名不能为空");
        }
        String alias = body.getAlias().trim();
        ModelProfile existing = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        String target = body.getProtocol() == null || body.getProtocol().isBlank()
                ? ModelProfile.PROTOCOL_OPENAI : body.getProtocol().trim();
        if (!ModelProfile.PROTOCOL_OPENAI.equals(target) && !ModelProfile.PROTOCOL_ANTHROPIC.equals(target)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "不支持的协议：" + target
                    + "（可选：" + ModelProfile.PROTOCOL_OPENAI + " / " + ModelProfile.PROTOCOL_ANTHROPIC + "）");
        }
        if (target.equals(existing.protocol())) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, alias + " 的协议已经是 " + target);
        }

        ModelProfile updated = existing.withProtocol(target);
        String failure = registry.save(updated);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        Map<String, Object> out = result("protocol", alias);
        /* 清空了自定义参数这件事必须说出口：静默清掉等于"我填的值自己消失了"，
           与"保存失败"是同一种困惑，但前者连报错都没有。 */
        out.put("message", existing.params().isEmpty()
                ? "已将 " + alias + " 的协议切换为 " + target
                : "已将 " + alias + " 的协议切换为 " + target + "，并清除了 "
                + existing.params().size() + " 个自定义参数（同名键在两种协议下语义不同，请按新协议重填）");
        out.put("clearedParams", !existing.params().isEmpty());
        out.put("profile", profileView(updated));
        return out;
    }

    /**
     * 解析文本框里的 JSON：必须是一个扁平的对象，空 / 空白视作清除。
     *
     * <p>解析错误要带位置信息（"第 3 行第 8 个字符附近"），只说"JSON 格式错误"等于让用户
     * 对着一坨文本自己找。顶层非对象（数组、标量）单独给一句：那些是"填法错了"，
     * 不是"这一行写错了"，两类提示不该混成一句。</p>
     */
    private Map<String, Object> parseCustomParams(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        ObjectMapper mapper = new ObjectMapper();
        /* 禁用"未知键"之外的宽松特性：默认即严格（不注释、不单引号、不尾随逗号），
           与用户心里的 JSON 一致。刻意不开 ALLOW_COMMENTS 之类 —— 编辑器里加注释看着方便，
           存盘的却是注释剥掉后的一坨，用户回头看卡片会以为自己填的还在。 */
        JsonNode tree;
        try {
            tree = mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            String where = e.getLocation() == null ? "" : "（第 " + e.getLocation().getLineNr()
                    + " 行第 " + e.getLocation().getColumnNr() + " 个字符附近）";
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "自定义参数不是合法 JSON" + where
                    + "：" + firstLine(e.getOriginalMessage()));
        }
        if (tree.isNull() || (tree.isObject() && tree.isEmpty())) {
            return Map.of();
        }
        if (!tree.isObject()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "自定义参数必须是一个 JSON 对象（形如 {\"enable_thinking\": true}），当前是"
                            + describeNode(tree));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = tree.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            out.put(field.getKey(), mapper.convertValue(field.getValue(), Object.class));
        }
        return out;
    }

    private static String describeNode(JsonNode node) {
        if (node.isArray()) {
            return "数组";
        }
        if (node.isTextual()) {
            return "字符串";
        }
        if (node.isNumber()) {
            return "数字";
        }
        if (node.isBoolean()) {
            return "布尔";
        }
        return "空值";
    }

    /** 只取首行：Jackson 的原始消息可能带整段 Jackson 类名，直接回给用户既长又无关 */
    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        String head = nl < 0 ? message : message.substring(0, nl);
        return head.length() > 160 ? head.substring(0, 160) + "…" : head;
    }

    /**
     * 读服务商元数据，拿到端点族与输入 / 输出模态：表单不再让用户选类型。
     *
     * 为什么读清单而不是发试探请求：试探要花 token（一条 chat + 一条 embeddings），成败还受配额 /
     * 限流 / 网络抖动影响；而**输出模态根本试不出来**（试探只能回答"这个端点通不通"）。
     * 清单是提供商的声明，{@code GET /models} 不计费，成本上真正是零。
     *
     * "读不到"与"读失败"必须分开，两者后果完全相反：
     *
     *   • 读不到（清单里没这个模型 / 清单是标准 OpenAI 规范只有四个字段 / 服务商不提供
     *     {@code /models}）→ 如实留空存下来，管控台标「未声明类型」。若为此拒绝保存，
     *     OpenAI 与 DeepSeek 官方这类标准规范的服务商一个档案都建不出来：
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
     * 未命中 → 走**用户自助填参**的四道闸门（{@link CustomParamsGate}）。直接调 API 的请求也在这里被挡住。
     *
     * <p>未命中这条分支的口径在 2026-10-26 被改掉了：原先是"params 必须为空"，
     * 现在 30 个模型里 29 个走这条路 —— <b>它从此是常态，不再是异常</b>。
     * 服务的路线是"用户声明、我透传"：厂商参数面一直在变，而我们既没有足够的人逐个实测，
     * 也没有办法保证写进去的 min / max / step 是对的，与其给出一堆"看起来配了、实际没验证过"
     * 的声明，不如把参数面交回给用户，平台只守住不能交出去的那部分（模型名 / 地址 / 协议 / 凭证）。</p>
     *
     * <p>两条路的判据统一在 {@code byModelName()}（是否<b>按模型名</b>命中适配），
     * 与绿框下发、{@link #updateTuning} 完全同一口径：</p>
     * <ul>
     *   <li>命中 → 按厂商 schema 复核（键 / 类型 / min / max / 条件参数），schema 比闸门精确；</li>
     *   <li>未命中（含 host 兜底）→ 走四道闸门。<b>host 兜底走闸门是有意的</b>：它只说明
     *       "这家服务商登记过"，把同 host 下另一模型的参数 schema 摆上来复核就是张冠李戴
     *       （OpenRouter 这种聚合站上尤其明显）。旧口径在这里用 {@code matched()} 复核，
     *       理由是"不然存量已存参数的档案会被任何一次保存拒死" —— 现在那条死局由闸门化解：
     *       这些档案的自定义参数不再被 schema 拒，而是过闸门放行。</li>
     * </ul>
     *
     * @return {@code null} = 通过；否则为可直接展示给用户的拒绝原因
     */
    private String checkAdapterTuning(ModelProfile profile) {
        ModelAdapterRegistry.MatchResult matched =
                adapterRegistry.match(profile.baseUrl(), profile.modelName());
        if (matched.byModelName()) {
            return adapterRegistry.validateParams(matched, tuningValues(matched, profile));
        }
        return CustomParamsGate.validate(profile.params());
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
        /* 全部族的 std 参数都摊平进来：chat 族是温度 / 单次最大输出，embedding 族是向量维度：
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
     * 于是"值未设置（null）"直接 NPE，而"未设置"恰恰是常态（用户只填了一个参数）。
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
     * 校验「向量维度」：只能是该模型的<b>原生维度</b>。
     *
     * <p>维度是<b>本机索引契约</b>，不会作为 {@code dimensions} 请求参数下发
     * （固定维度模型如 bge-m3 直接拒绝该参数，下发等于"填对维度反而灌库失败"）。
     * 既然不下发，声明值就只能等于模型实际产出的长度——填别的值，索引会按填的值建，
     * 而写入的向量是另一个长度，整个域的灌库与检索都会失败。所以这里用实测的原生维度
     * 做唯一判据：不一致直接拒绝，把"该填多少"写进报错。</p>
     *
     * <p>只在维度被显式声明、且相对原档案有变化时才探测：避免对"沿用模型默认（null）"或
     * 未变动的值反复发请求。</p>
     *
     * <p>探测没跑完一律不算通过：服务商不可达、鉴权失败、地址写错时拿不到原生维度，
     * 存下去等于把一个没人验证过的长度写进索引契约。</p>
     *
     * @param existing  改之前的档案（未变动则跳过探测）；新建档案传 {@code null}
     * @param candidate 改之后的档案
     * @return 不通过的原因；{@code null} 表示通过
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
        Integer nativeDim;
        try {
            nativeDim = measureNativeDimensionOrThrow(candidate);
        } catch (Exception e) {
            String detail = LlmFailureDescriber.describe(e);
            log.warn("[模型档案] 向量维度 {} 无法确认（探测失败），拒绝保存: {}", want, detail);
            return "向量维度 " + want + " 无法确认：" + detail
                    + "。请先确认该向量模型可连通（地址 / Key / 模型名），再保存维度。";
        }
        if (!nativeDim.equals(want)) {
            return "向量维度 " + want + " 不被支持：维度只用于本机建索引、不随请求下发，"
                    + "只能是该模型的原生维度 " + nativeDim + " 维（留空则由服务端按原生维度回填）。";
        }
        return null;
    }

    /**
     * 实测模型原生维度，失败时抛异常（由调用方转成提示文案）。
     *
     * <p>与 {@link #measureNativeDimension} 的区别：那个是"尽力而为"的回填，
     * 失败就返回 {@code null}；这里是"校验必须拿到值"，失败必须让用户知道原因。</p>
     */
    private Integer measureNativeDimensionOrThrow(ModelProfile candidate) {
        LlmSettings probe = new LlmSettings();
        probe.setEmbeddingBaseUrl(candidate.baseUrl());
        probe.setEmbeddingApiKey(candidate.apiKey());
        probe.setEmbeddingModelName(candidate.modelName());
        return holder.testEmbeddingConnection(probe).actualDimension();
    }

    /**
     * 不带 {@code dimensions} 参数发一次极短请求，取模型原生向量维度。
     *
     * <p>这是<b>尽力而为</b>的回填（维度留空时用），失败返回 {@code null} 而不抛：
     * 探测不成就保留 null、语义回落到"按模型默认"，不因为一次可选回填失败就拒绝整次保存。
     * 需要"必须拿到值"的场景用 {@link #measureNativeDimensionOrThrow}。</p>
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
     * 被清掉绑定的域立即进入"无可调用"状态，由管控台「域空间」页提示：
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
     * 手动探测一个档案的端点族与输入 / 输出模态，并把「实测确认到的」并回档案。
     *
     * 用途：元数据读不出类型（标准 OpenAI 规范的服务商，如 OpenAI / DeepSeek 官方）的档案
     * 停在「未识别」，绑不了域。点一次「探测」，用一组最小请求把类型与模态实测出来，档案即可绑定。
     *
     * **写回是并集（只增不减）**：探测只确认「支持什么」，失败的探针只说明「这次没确认到」，
     * 不等于「不支持」（网络抖动 / 样本格式被挑剔都会失败）。用 {@link ModelProfile#withConfirmed}
     * 把新确认的并进去，旧声明一律保留，绝不因一次探测失败抹掉用户手填的模态。
     */
    @PostMapping("/model-profiles/detect")
    public Map<String, Object> detect(@RequestParam("alias") String alias) {
        ModelProfile profile = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        ModelCapabilityProbe.ProbeResult probe;
        try {
            probe = capabilityProbe.probeCapabilities(profile.baseUrl(), profile.apiKey(), profile.modelName());
        } catch (Exception e) {
            throw new BaseException(ErrorCode.LLM_UNAVAILABLE, "探测失败：" + LlmFailureDescriber.describe(e));
        }

        ModelProfile updated = profile.withConfirmed(probe.endpoints(), probe.input(), probe.output());
        String failure = registry.save(updated);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        Map<String, Object> out = result("detected", alias);
        out.put("note", probe.note());
        out.put("perModality", probe.perModality());
        out.put("identified", updated.identified());
        out.put("bindable", updated.domainBindable());
        /* 回带新视图：卡片拿它就地重画模态两行与「绑定到域」入口 */
        out.put("profile", profileView(updated));
        return out;
    }

    /**
     * 批量探测：对所有「未识别」（端点族为空）的档案各跑一遍探测，一次把历史遗留补齐。
     *
     * 供发版迁移用：收紧绑域门禁后，此前靠「未识别也放行」绑上域的档案需要补识别；
     * OpenAI / DeepSeek 官方这类走 {@code /chat/completions} 必通，探测后即恢复可绑。
     * 逐个串行（探测本身开销小、频率低），单个失败不影响其余。
     */
    @PostMapping("/model-profiles/detect-all")
    public Map<String, Object> detectAll() {
        List<Map<String, Object>> items = new ArrayList<>();
        int detected = 0;
        for (ModelProfile profile : registry.allProfiles()) {
            if (profile.identified()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("alias", profile.alias());
            item.put("modelName", profile.modelName());
            try {
                ModelCapabilityProbe.ProbeResult probe =
                        capabilityProbe.probeCapabilities(profile.baseUrl(), profile.apiKey(), profile.modelName());
                if (probe.endpoints().isEmpty()) {
                    item.put("success", false);
                    item.put("note", "未确认到任何端点族 —— " + probe.note());
                } else {
                    ModelProfile updated = profile.withConfirmed(probe.endpoints(), probe.input(), probe.output());
                    String failure = registry.save(updated);
                    if (failure != null) {
                        item.put("success", false);
                        item.put("note", failure);
                    } else {
                        item.put("success", true);
                        item.put("endpoints", updated.endpoints());
                        item.put("input", updated.input());
                        item.put("output", updated.output());
                        detected++;
                    }
                }
            } catch (Exception e) {
                item.put("success", false);
                item.put("note", LlmFailureDescriber.describe(e));
            }
            items.add(item);
        }
        Map<String, Object> out = result("detected-all", "model-profiles");
        out.put("processed", items.size());
        out.put("detected", detected);
        out.put("items", items);
        return out;
    }

    /**
     * 设置域的绑定：主模型列表（**整体覆盖**，顺序即优先级）+ 若干能力族（生图 / 语音 / 视频）。
     *
     * <p>一次提交、整体校验通过才落盘 —— 管控台保存一个域时提交的是同一批界面上的值，
     * 按族逐个写会在第二个族失败时留下"主模型改了、生图没改"的中间态。</p>
     *
     * <p>{@code aliases} 空表示解绑主模型（解绑后该域沿域链向上继承最近一个绑了主模型的祖先，
     * 整条链都没绑才是真的"无可调用"）；{@code capabilities} 里没出现的族保持不变，
     * 某族给空列表即解绑该族。</p>
     */
    @PutMapping("/model-bindings/{domain}")
    public Map<String, Object> bind(@PathVariable("domain") String domain,
                                    @RequestBody(required = false) BindBody body) {
        List<String> aliases = body == null ? null : body.getAliases();
        Map<String, List<String>> capabilities = body == null ? null : body.getCapabilities();
        // 绑定是"域 → 模型"的写入入口，域必须在注册表里：
        // 否则会为树里不存在的域落盘一条绑定，并被它的后代域沿链继承（静默生效）
        String normalized = Domains.normalize(domain);
        if (!domainRegistry.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "域不存在：" + normalized + "（已登记的域: " + domainRegistry.ids() + "）；"
                            + "请先在「域空间」创建该域，或把绑定写到它的祖先域上由后代继承");
        }
        /* aliases 为 null 按旧口径表示"解绑主模型"，而注册表那个参数位上的 null 是"本次不动"，
           所以这里显式转成空列表 —— 两种语义不能混。 */
        String failure = registry.bindDomain(domain, aliases == null ? List.of() : aliases, capabilities);
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
     * 向量空间也完全不一样，旧向量检索出来是噪声，而这种错配不会报错，只会让检索质量
     * 静默下降，比维度不符更难发现。
     */
    @PutMapping("/embedding-model")
    public Map<String, Object> selectEmbeddingModel(@RequestBody(required = false) EmbeddingBody body) {
        String alias = body == null ? null : body.getAlias();
        String mode = body == null || body.getMode() == null ? "" : body.getMode().trim();

        String previousAlias = registry.embeddingAlias();
        ModelProfile previous = previousAlias == null ? null : registry.profile(previousAlias).orElse(null);
        // 先校验目标档案（非向量档案 / 必填不齐 / 不存在）：不该让用户在确认弹窗之后才被拒绝
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
     * 不该弹二次确认，那道确认框是为了防止"用户辛苦攒的知识库被清空"，
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
        /* 上下文窗口（用户手填，null = 未声明）。下发原始值而不是 effectiveContextWindow()：
           卡片要能区分"没填"与"填了但被归一掉"，后者应让用户看见自己填的是什么。 */
        item.put("contextWindow", profile.contextWindow());
        /* 两个思考开关原样下发（null = 未声明） */
        item.put("thinking", profile.thinking());
        item.put("returnThinking", profile.returnThinking());
        item.put("dimensions", profile.dimensions());
        item.put("usable", profile.isUsable());
        item.put("fallbacks", profile.fallbacks());
        item.put("params", profile.params());
        /* 协议与可选项一并下发：卡片上的下拉照着渲染，前端不另存一份"有哪些协议"。 */
        item.put("protocol", profile.protocol());
        item.put("protocols", List.of(ModelProfile.PROTOCOL_OPENAI, ModelProfile.PROTOCOL_ANTHROPIC));
        /* 适配摘要：命中适配就下发 id / label 与当前生效的调优值（供卡片渲染绿色工具框）。
           
           门槛是 {@link ModelProfile#mayBeChat()} ——「**有可能**走对话端点」，不是「已确认是对话模型」：
             • 已确认 chat → 下发；
             • 端点族未声明 → **也下发**。标准 OpenAI 规范的服务商（OpenAI / DeepSeek 官方、各类中转）
               的模型清单读不出类型，档案就停在"未声明"；而这两家恰恰是内置预设里唯一有适配的：
               因为"读不出类型"就把工具框收走，等于把最有用的两家用户挡在门外，卡片上还只留下一句
               「未声明类型」，看不出"框本来该有"；
             • 已声明为**向量** → 下发，但下的是 embedding 族的参数（目前只有 dimensions）：
               向量卡片要的就是"维度可就地改"，与 chat 档案的绿框同款交互、不同族参数；
             • 已声明为生图 / 语音 / 重排 / 视频 → 不下发：那些族的调优语义各不相同且尚未登记，
               把别的族的参数摆在它们上是张冠李戴。 */
        ModelAdapterRegistry.MatchResult matched =
                adapterRegistry.match(profile.baseUrl(), profile.modelName());
        if (profile.mayBeChat() || profile.isEmbedding()) {
            /* 两个条件都要满足才下发（卡片上才有绿框）：
               ① 按**模型名**命中（{@link ModelAdapterRegistry.MatchResult#byModelName()}）——
                  host 兜底只说明"这家服务商登记过"，把同 host 下 id 字典序最小的那条适配
                  摆到任意未登记模型上就是张冠李戴（OpenRouter 上尤其明显），故不作为依据。
                  与 {@code POST /admin/model-adapters/match} 下发绿框的口径一致；
               ② 该档案对应族**真有参数** —— 如 api.deepseek.com 上的 deepseek-embedding 的
                  embedding 族为空，自然不给摘要、卡片上也就没有绿框。 */
            if (matched.byModelName() && !matched.tuningParams(profile).isEmpty()) {
                item.put("adapter", adapterSummary(matched, profile));
            }
        }
        /* 自定义参数（用户自助填参）的卡片入口：与 {@link #checkAdapterTuning} 同一份判据 ——
           按模型名命中 → 走 schema 驱动的绿框，这里不给文本框（两条路都改 params，
           同时给就是两个入口改一个字段，用户不知道哪个生效）；
           未命中 → 给文本框 + 上限与提示，前端照着渲染，不自己另存一份判据。 */
        item.put("customParams", customParamsView(profile, matched));
        item.put("usedByDomains", registry.domainsUsing(profile.alias()));
        /* 能不能绑到域上：服务端算好下发，前端只读，避免前端二次实现谓词再漂移
           （旧实现 models.html 自己的 isChatProfile() 就是 mirrors 了 mayBeChat()，口径一变就前后端打架）。 */
        item.put("identified", profile.identified());
        /* 主模型桶的判据单独留一份：卡片上"能否作为主模型"是一句独立的话，
           与"能否作为某个能力"不是同一件事。 */
        item.put("bindable", profile.domainBindable());
        item.put("bindableFamilies", bindableFamilies(profile));
        return item;
    }

    /**
     * 这份档案能被绑到哪些族上 —— 与绑定门禁同一口径（见 {@code ModelProfileRegistry#validateForFamily}）。
     *
     * <p>三类结果刻意分开：</p>
     * <ul>
     *   <li>主模型（{@code chat}）—— 要看 {@link ModelProfile#domainBindable()}：类型已知 + 模态已知
     *       + 能当对话模型用。一个生图档案<b>不该</b>出现主模型段的可选列表里。</li>
     *   <li>能力族（{@code images} / {@code tts} / {@code asr} / {@code video}）—— 只看档案是否
     *       声明了那个端点族。<b>刻意不要求它能当对话模型</b>：生图档案恰恰只能绑生图段。</li>
     *   <li>向量 / 重排 —— 不出现。向量是全局单选，重排不按域配置。</li>
     * </ul>
     *
     * <p>前端只读这个列表，不自己判端点族：判据只该有一份（服务端）。</p>
     */
    private static List<String> bindableFamilies(ModelProfile profile) {
        if (!profile.isUsable()) {
            return List.of();
        }
        List<String> families = new ArrayList<>();
        if (profile.domainBindable()) {
            families.add(ModelProfile.EP_CHAT);
        }
        for (String family : ModelProfile.CAPABILITY_FAMILIES) {
            if (profile.endpoints().contains(family)) {
                families.add(family);
            }
        }
        return families;
    }

    /**
     * 适配摘要：id / label / docUrl + 参数 schema + 当前有值的调优项。
     *
     * 下发 schema（{@code params}）而不是只下发若干「键 = 值」的文字，是因为卡片右侧要放一个
     * **可编辑**的调优框：控件类型（枚举 / 布尔 / 数值及 min / max / step）只能从 schema 得到。
     * 形状与 {@code POST /admin/model-adapters/match} 的 {@code adapter.params} 保持一致：
     * 模态里与卡片上渲染的是同一套 schema，两份形状漂移会让两处控件长得不一样。
     *
     * 下发的参数按**档案类型**取族（见 {@link ModelAdapterRegistry.MatchResult#tuningParams}）：
     * 向量档案给 embedding 族的 dimensions，对话档案给 chat 族：卡片绿框渲染的就是这一份。
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

    /**
     * 卡片上「自定义参数（JSON）」文本框的视图数据。
     *
     * <p>把上限与提示一并下发，而不是让前端各写一份常量：闸门改一个数，卡片上的提示
     * 必须跟着变，两处各存一份必然漂移（漂移的表现是"提示说 32 个，后端拒了 30 个"）。</p>
     *
     * @param schemaDriven 命中适配的档案走 schema 驱动的绿框，文本框不出现（两个入口改同一个
     *                     字段时，用户无从判断哪个生效 —— 所以只留一条）
     */
    private Map<String, Object> customParamsView(ModelProfile profile,
                                                 ModelAdapterRegistry.MatchResult matched) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("schemaDriven", matched.byModelName());
        view.put("paramsJson", toPrettyJson(profile.params()));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxKeys", CustomParamsGate.MAX_KEYS);
        limits.put("maxBytes", CustomParamsGate.MAX_BYTES);
        limits.put("maxStringLength", CustomParamsGate.MAX_STRING_LENGTH);
        limits.put("maxArrayItems", CustomParamsGate.MAX_ARRAY_ITEMS);
        limits.put("maxObjectKeys", CustomParamsGate.MAX_OBJECT_KEYS);
        view.put("limits", limits);
        view.put("stdKeys", CustomParamsGate.stdKeys());
        return view;
    }

    /**
     * 回填文本框用的 JSON。空参数给**空字符串**而不是 {@code {}}：
     * 卡片每次打开都先塞一对空括号，用户会以为"这里本来有内容、被我删了"。
     */
    private String toPrettyJson(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(params);
        } catch (JsonProcessingException e) {
            /* 存盘的值理论上必然可序列化（保存时已过一遍 JSON 文本解析）；走到这里说明
               有别的数据源写进了 params，如实记一笔并降级成 toString —— 宁可显示得难看，
               也不能让"看一眼卡片"变成异常。 */
            log.warn("[模型档案] 自定义参数序列化为 JSON 失败，卡片按原始文本回填：{}", e.getMessage());
            return String.valueOf(new TreeMap<>(params));
        }
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
         * 管制台表单不再采集（用户不选类型）：留空即由服务端读服务商模型清单补上，
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
         * 上下文窗口（token，含输入与输出）；{@code null} = 未声明。
         *
         * 显式声明在这里是为了让脚本 / 集成方一次建好就带上它，卡片上的输入框
         * 只能改已存档案，而"整体覆盖"语义要求这些字段在这里也有入口。
         */
        private Integer contextWindow;
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
         * 自定义参数（如 {@code enable_thinking}）。
         *
         * 命中适配时按适配 schema 复核；未命中时由用户自助填写、原样透传
         * （过 {@link CustomParamsGate} 的四道闸门）。
         */
        private Map<String, Object> params;
        /**
         * 上游协议：{@code openai}（默认）/ {@code anthropic}。
         *
         * 空白与 {@code null} 一律按 {@code openai} 处理：绝大多数档案属于这一类，
         * 不填不该报错。填了不支持的值直接拒绝，不静默回落 —— 回落会让用户以为
         * 选了 Anthropic 而实际在按 OpenAI 发请求，那种错在日志里也看不出来。
         */
        private String protocol;
    }

    /** 只改协议的请求体 */
    @Data
    public static class ProtocolBody {
        private String alias;
        /** {@code openai} / {@code anthropic}；空白按 {@code openai} */
        private String protocol;
    }

    /**
     * 只改调优参数的请求体。
     *
     * 刻意没有 {@code baseUrl} / {@code apiKey} / {@code modelName} / {@code endpoints} /
     * {@code input} / {@code output} 字段：连接信息与端点族 / 模态不是"调优"，
     * 本接口从既有档案原样带过，客户端无从改动。
     *
     * {@code dimensions} 是**例外**：它是向量卡片绿框唯一可改的项，走专用字段（不进 params）。
     *
     * 四个字段都是**全量替换**语义：{@code null} / 空 map = 清除该项（回到服务商默认），
     * 不是"不修改"。卡片上把温度 / 维度清空再保存，就该真的不再发它们：
     * 若按"null = 不动"处理，用户会发现清不掉。
     */
    /**
     * 自定义参数的请求体：收**文本**而不是收 map。
     *
     * <p>收文本的理由写在本接口的说明里 —— 关键是 JSON 写错时要有位置信息，
     * 而让 Spring 直接绑成 map 的话，解析错误发生在参数绑定阶段，原始文本已经拿不到了。</p>
     */
    @Data
    public static class CustomParamsBody {
        private String alias;
        /** 「自定义参数（JSON）」文本框内容；空白 = 清除全部自定义参数 */
        private String paramsJson;
    }

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
     * {@code returnThinking} 缺失按 {@code false} 处理：开关的默认态就是关，
     * "没带上这个字段"与"明确关它"对调用者是同一件事。
     */
    @Data
    public static class ReturnThinkingBody {
        private String alias;
        /** true = 把思考内容回传给调用者；false = 不回传（框架直接丢弃） */
        private Boolean returnThinking;
    }

    /**
     * 只改「上下文窗口」的请求体（卡片上那个独立输入框的保存口）。
     *
     * <p>与 {@link ReturnThinkingBody} 一样刻意单薄：这不是调优参数、不进 {@code params}、
     * 也不受适配 schema 管，所以它既不能塞进 {@link TuningBody}（那个接口有适配门禁），
     * 也不该被前端顺手放进别的保存动作里，一次动作只改一件事，保存失败时才说得清是哪件没成。</p>
     *
     * <p>{@code contextWindow} 为 {@code null} = 清除（回到"未声明"），
     * 与 {@link TuningBody} 的全量替换语义一致：卡片上把输入框清空再保存，就该真的不再声明它。</p>
     */
    @Data
    public static class ContextWindowBody {
        private String alias;
        /** 模型的上下文窗口（token，含输入与输出）；null = 清除声明 */
        private Integer contextWindow;
    }

    /**
     * 绑定请求体。
     *
     * @param aliases      主模型别名列表（顺序即优先级）；空 = 解绑主模型
     * @param capabilities 能力族绑定：族（{@code images} / {@code tts} / {@code asr} / {@code video}）
     *                     → 别名列表，空列表 = 解绑该族；没出现的族保持不变。
     *                     {@code null} = 本次不改任何能力族。
     */
    @Data
    public static class BindBody {
        private List<String> aliases;
        private Map<String, List<String>> capabilities;
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
