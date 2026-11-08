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
import com.zzkingcc.domainplane.server.model.VectorIdentity;
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

    /** 用户声明能力时的输入模态基线（纯本地映射，不发请求） */
    private static final Map<String, String> BASELINE_INPUT = Map.of(
            ModelProfile.EP_CHAT, ModelProfile.MOD_TEXT,
            ModelProfile.EP_EMBEDDING, ModelProfile.MOD_TEXT,
            ModelProfile.EP_RERANK, ModelProfile.MOD_TEXT,
            ModelProfile.EP_IMAGES, ModelProfile.MOD_TEXT,
            ModelProfile.EP_TTS, ModelProfile.MOD_TEXT,
            ModelProfile.EP_ASR, ModelProfile.MOD_AUDIO,
            ModelProfile.EP_VIDEO, ModelProfile.MOD_TEXT);

    /** 用户声明能力时的输出模态基线；与 {@link #BASELINE_INPUT} 成对使用 */
    private static final Map<String, String> BASELINE_OUTPUT = Map.of(
            ModelProfile.EP_CHAT, ModelProfile.MOD_TEXT,
            ModelProfile.EP_EMBEDDING, ModelProfile.MOD_EMBEDDING,
            ModelProfile.EP_RERANK, ModelProfile.MOD_SCORE,
            ModelProfile.EP_IMAGES, ModelProfile.MOD_IMAGE,
            ModelProfile.EP_TTS, ModelProfile.MOD_AUDIO,
            ModelProfile.EP_ASR, ModelProfile.MOD_TEXT,
            ModelProfile.EP_VIDEO, ModelProfile.MOD_VIDEO);

    private final ModelProfileRegistry registry;
    private final ModelProfileStore store;
    private final LlmModelHolder holder;
    /** 元数据读取器：只服务「识别」按钮的按需预勾（读服务商清单，不发推理请求），不在保存路径上 */
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
     * 档案全貌：档案列表（Key 脱敏）+ 域绑定 + 槽位 + 落盘位置。
     *
     * <p>{@code profiles} 是<b>全量一张表</b>，向量 / 对话 / 生图档案都在里面，
     * 由前端按 {@code endpoints} 渲染能力标签。「这份档案用来干什么」不再由它
     * 落在哪个列表里决定，而是由 {@code assignments} 里的槽位决定。</p>
     */
    @GetMapping("/model-profiles")
    public Map<String, Object> list() {
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (ModelProfile profile : registry.profiles()) {
            profiles.add(profileView(profile));
        }
        Map<String, Object> assignments = new LinkedHashMap<>();
        assignments.put("embedding", registry.embeddingAlias());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("profiles", profiles);
        body.put("domainBindings", registry.domainBindings());
        /* 兼容保留：预览桩与既有脚本读的是这个字段，语义与 assignments.embedding 相同 */
        body.put("embeddingAlias", registry.embeddingAlias());
        body.put("assignments", assignments);
        body.put("settingsFile", store.filePath());
        return body;
    }

    /**
     * 新建或整体覆盖一个档案（同名即覆盖）。
     *
     * <p>{@code apiKey} 必填：管控台只回填脱敏后的 Key，所以编辑时也必须重新填写。</p>
     *
     * <p><b>保存只是登记一份连接凭据</b>：端点族由用户在表单里勾选并随请求体提交，
     * 服务端不再读服务商清单、不再探测、不再测量或校验向量维度 —— 整条路径零上游请求。
     * 识别能力是弹窗上一个用户主动点的按钮（{@link #suggest(ProfileBody)}），不在保存里；
     * 能不能真的调通由运行期的请求负责，声明错了会拿到明确的 404 / 400，
     * 而不是在配置期造出一个「未声明类型」的第三种状态。</p>
     *
     * <p>唯一保留的复核是 {@link #checkAdapterTuning}：它管的是"参数能不能到达请求体"，
     * 属于本机契约，与服务商能力无关。</p>
     *
     * <p><b>唯一被拒的保存</b>：把<b>当前向量档案</b>的向量身份（地址 / 模型名 / 维度）改掉。
     * 本接口没有重灌确认，放过去就是索引静默错配 —— 检索照常返回结果，只是全是噪声。
     * 换模型应当新建档案再走向量槽位切换（{@link #selectEmbeddingModel}），那里有确认。
     * 换 Key / 改协议不受影响：它们不改变向量，不需要重灌。</p>
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

        String adapterError = checkAdapterTuning(profile);
        if (adapterError != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, adapterError);
        }

        /* 声明了能力却没填模态时补一组基线值（纯本地映射，零上游请求）。
           不补的话 ModelProfileRegistry#validateForFamily 的「输入 / 输出模态未知」
           会把这份档案挡在域绑定之外 —— 而用户看到的是「保存成功」，
           错误要等到去域空间页绑定时才冒出来。 */
        profile = withBaselineModalities(profile);

        /* 覆盖的是当前启用的向量档案吗？写盘后必须重装配，否则内存里仍是旧实例。 */
        boolean wasEmbedding = alias.equals(registry.embeddingAlias());
        /* 当前向量档案不允许从"保存"这条路改掉向量身份（地址 / 模型名 / 维度）。
           本接口没有重灌确认，改完就是静默错配：查询用新模型算向量、库里还是旧模型的，
           检索照常返回结果，只是全是噪声 —— 这种错比维度不符难发现得多，因为它不报错。
           fail-closed，把用户送到带确认的切换入口（新建档案 → 向量槽位切换）。 */
        if (wasEmbedding) {
            ModelProfile before = registry.profile(alias).orElse(null);
            if (before != null && !VectorIdentity.same(before, profile)) {
                throw new BaseException(ErrorCode.INVALID_PARAMETER,
                        "「" + alias + "」是当前向量模型，直接改服务商地址 / 模型名 / 维度会让索引里的向量失配"
                                + "（旧向量还能被查出来，但结果不准，而且不会报错）。"
                                + "要换模型请新建一份档案，再到「模型设置」页的向量槽位切换 —— "
                                + "那里会带你确认保留原文重灌还是清空知识库。");
            }
        }
        String failure = registry.save(profile);
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        if (wasEmbedding) {
            /* 落盘已经成功：重装配失败不能把这次保存报成失败，否则用户会重复提交。
               连接信息 / Key 的改动就此立即生效。维度或模型名真的换了，
               应当走「切换向量模型」入口（那里带重灌确认），不是这里。 */
            try {
                holder.refreshEmbedding();
            } catch (RuntimeException e) {
                log.warn("[模型档案] 档案 {} 已保存，但向量模型重装配失败（下次切换或重启会重试）：{}",
                        alias, e.getMessage());
            }
        }
        return result("saved", alias);
    }

    /**
     * 用户勾了能力但没填模态时，按族补一组基线值。
     *
     * <p>端点族与模态是两件事：族决定"怎么调它"，模态决定"能吃什么"。
     * 表单只让用户勾族，若不同时补模态，{@code ModelProfileRegistry#validateForFamily}
     * 会以「输入 / 输出模态未知」拒绝绑域 —— 用户看到的是"保存成功"，
     * 直到去域空间页才撞墙。补齐是纯本地映射，不读服务商清单、不发请求，
     * 也不是在猜能力：它只是端点规范自带的最低描述。</p>
     *
     * <p>显式传了模态的请求原样采用（脚本 / 集成方常手填更准的模态，如 image+text 输入）；
     * 只有一侧为空时补那一侧，另一侧保持用户填的值。</p>
     */
    private static ModelProfile withBaselineModalities(ModelProfile profile) {
        if (profile.endpoints().isEmpty()
                || (!profile.input().isEmpty() && !profile.output().isEmpty())) {
            return profile;
        }
        List<String> input = new ArrayList<>(profile.input());
        List<String> output = new ArrayList<>(profile.output());
        for (String family : profile.endpoints()) {
            String in = BASELINE_INPUT.get(family);
            if (in != null && !input.contains(in)) {
                input.add(in);
            }
            String out = BASELINE_OUTPUT.get(family);
            if (out != null && !output.contains(out)) {
                output.add(out);
            }
        }
        return profile.withDetected(profile.endpoints(), input, output, false);
    }

    /**
     * 按需识别端点族与模态，供添加弹窗的「识别」按钮预勾用途。
     *
     * <p><b>不在保存路径上</b>：只有用户主动点才会调。两级取数 ——
     * ① 本地适配文件按模型名命中就直接用，一次上游请求都不发；
     * ② 没命中才读服务商 {@code GET /models} 清单（零 token，不跑推理）。
     * 结果只是给表单一个初值，用户可以改，保存以用户提交为准。</p>
     *
     * <p>清单不给类型（标准 OpenAI / DeepSeek 官方都是这种形态）时如实返回空数组 +
     * {@code note}，由用户自己勾，绝不猜一个 chat 出来。</p>
     */
    @PostMapping("/model-profiles/suggest")
    public Map<String, Object> suggest(@RequestBody(required = false) ProfileBody body) {
        if (body == null || body.getModelName() == null || body.getModelName().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "模型名不能为空");
        }
        String modelName = body.getModelName().trim();
        Map<String, Object> out = result("suggested", modelName);

        ModelAdapterRegistry.Declaration declared =
                adapterRegistry.declaration(body.getBaseUrl(), modelName).orElse(null);
        if (declared != null && !declared.endpoints().isEmpty()) {
            out.put("endpoints", declared.endpoints());
            out.put("input", declared.input());
            out.put("output", declared.output());
            out.put("source", "adapter");
            out.put("note", "取自本地适配声明");
            return out;
        }

        ModelMetadataReader.MetadataResult meta =
                readMetadata(body.getBaseUrl(), body.getApiKey(), modelName);
        out.put("endpoints", meta.endpoints());
        out.put("input", meta.input());
        out.put("output", meta.output());
        out.put("source", "provider");
        out.put("note", meta.note());
        return out;
    }

    /**
     * 只改调优参数（卡片上"温度 / 单次最大输出"与"自定义参数"两个保存口共用）。
     *
     * 与 {@link #save(ProfileBody)} 分开的原因有两条，都是产品口径而非偷懒：
     *
     *   • 连接信息（服务商地址 / API Key / 模型名）**不可改**。老接口要求重填明文 Key，
     *     而卡片上只有脱敏值 —— 为改一个温度就得让用户把 Key 再输一遍，既麻烦又把
     *     密钥多暴露一次。本接口从既有档案带过连接信息与端点族 / 模态，
     *     客户端**根本没有**改它们的字段，而不是"前端不发、后端信任"。
     *   • 调优是高频微调动作。走老接口会连 `endpoints` 一起提交 —— 那是"建档案"的语义，
     *       为了改一个温度让用户重填整个表单（含明文 Key）既麻烦又多余。
     *
     * <b>门禁是"能不能当对话模型用"（{@link ModelProfile#mayBeChat()}）或向量档案，不再看适配。</b>
     * 对话族的 schema 下架后，命中与未命中已经没有区别 —— 每张对话卡片都有标准字段与 JSON 框，
     * 接口若还按 {@code byModelName()} 拦，最标准的 OpenAI / DeepSeek 档案反而改不了温度。
     * 生图 / 语音 / 重排 / 视频仍然拒绝：那些族没有注入点，收了也只是存下来不生效。
     */
    @PutMapping("/model-profiles/tuning")
    public Map<String, Object> updateTuning(@RequestBody(required = false) TuningBody body) {
        if (body == null || body.getAlias() == null || body.getAlias().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "别名不能为空");
        }
        String alias = body.getAlias().trim();
        ModelProfile existing = registry.profile(alias)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER, "档案不存在：" + alias));

        /* 没有注入点的档案不给存：参数存下来却发不出去，等于让用户以为配了 */
        if (!existing.mayBeChat() && !existing.isEmbedding()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "档案「" + alias + "」不是对话模型，不支持参数调优");
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
        /* 这里不需要"索引维度对不上"的提醒：validateDimension 已经把维度钉在模型原生维度上，
           能通过保存的只有 null ↔ 原生值 这一种变化，而两者按实测得到的是同一个维度 ——
           索引不会失配。真正会改掉索引维度的是换模型，那条路走 selectEmbeddingModel，带重灌确认。 */
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
     *   <li>{@link #updateTuning} 也搭不上：那个接口是<b>全量替换</b>语义（temperature / max_tokens /
     *       dimensions / params 一起落），从卡片上单改一个上下文长度却要把另外四项原样重发一遍，
     *       漏带任何一个都会被清空。而上下文长度本身又不属于"要发给上游的参数"，
     *       混进同一个报文只会让两件事互相牵制。故本接口对<b>任何</b>可用档案开放，不设门禁。</li>
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
     * <p>与 {@link #updateTuning} 并列而非合并：那个接口是<b>全量替换</b>（温度、单次最大输出、
     * 向量维度、params 四项一起写），卡片上标准字段改动即存时需要带上另外三项防止被清空；
     * 本接口<b>只</b>写 {@code params}，其余字段原样带走，正好服务"只改 JSON 框"这一个动作。
     * 两者都改 {@code params}，但一个要求调用方把别的字段也搬一遍，一个不需要 ——
     * 合并就得在接口内部再判一次"这次到底是哪种改动"，而那个判据前后端各存一份就会漂移。</p>
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
     * 读服务商清单，只为「识别」按钮提供预勾建议。
     *
     * <p><b>已从保存路径上撤下</b>：保存不再依赖它，用户不点「识别」就一次都不会调。
     * {@code GET /models} 不计费、不跑推理；但清单只是服务商的声明，
     * 标准 OpenAI 规范形态只有 id / object / owned_by，读不到类型时如实返回空，
     * 由用户自己勾用途，绝不猜一个 chat 出来。</p>
     *
     * <p>读失败（连不上 / 鉴权被拒 / 响应不是 JSON）会把原因回给用户 ——
     * 那是用户能改的错（地址、Key），而且只影响"要不要预勾"，不影响保存。</p>
     *
     * @throws BaseException 读失败；原因原样带上
     */
    private ModelMetadataReader.MetadataResult readMetadata(String baseUrl, String apiKey, String modelName) {
        try {
            ModelMetadataReader.MetadataResult meta = metadataReader.read(baseUrl, apiKey, modelName);
            if (meta.endpoints().isEmpty()) {
                log.info("[模型档案] 元数据未声明模型类型：model={} baseUrl={} —— {}",
                        modelName, baseUrl, meta.note());
            } else {
                log.info("[模型档案] 元数据识别：model={} endpoints={} input={} output={}（模态权威={}）",
                        modelName, meta.endpoints(), meta.input(), meta.output(), meta.authoritative());
            }
            return meta;
        } catch (ModelMetadataReader.MetadataException e) {
            log.warn("[模型档案] 读取模型元数据失败：model={} baseUrl={} 原因={}",
                    modelName, baseUrl, e.getMessage());
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "无法读取模型元数据：" + e.getMessage());
        }
    }

    /** 维度的展示文案：未声明时说"按模型默认"，别把 null 直接拼进字符串 */
    private static String dimOf(ModelProfile profile) {
        return profile.dimensions() == null ? "按模型默认" : String.valueOf(profile.dimensions());
    }

    /**
     * 保存前的最后一道关：调优值该怎么复核。
     *
     * <p>判据不是"有没有命中适配"，而是<b>该档案对应族还有没有 schema</b>：</p>
     * <ul>
     *   <li>有 → 全部调优值过 schema（未知键 / 类型 / 越界一律拒绝，文案带合法键）。
     *       当前只剩向量的 {@code dimensions} 走这条；</li>
     *   <li>没有 → 走**用户自助填参**的四道闸门（{@link CustomParamsGate}）。
     *       对话族全体（含命中 deepseek-flash 的档案）走这条。直接调 API 的请求也在这里被挡住。</li>
     * </ul>
     *
     * <p>对话族下架 schema 后，这条分支从"30 个模型里 29 个"变成"全部对话模型"——
     * 它是常态而非异常。服务的路线是"用户声明、我透传"：厂商参数面一直在变，而我们既没有
     * 足够的人逐个实测，也没有办法保证写进去的 min / max / step 是对的，与其给出一堆
     * "看起来配了、实际没验证过"的声明，不如把参数面交回给用户，平台只守住不能交出去的
     * 那部分（模型名 / 地址 / 协议 / 凭证 / 与标准字段重名的键）。</p>
     *
     * @return {@code null} = 通过；否则为可直接展示给用户的拒绝原因
     */
    private String checkAdapterTuning(ModelProfile profile) {
        ModelAdapterRegistry.MatchResult matched =
                adapterRegistry.match(profile.baseUrl(), profile.modelName());
        if (matched.byModelName() && !matched.tuningParams(profile).isEmpty()) {
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
     * <p>触发条件是<b>向量身份变了</b>（见 {@link VectorIdentity}：服务商地址 / 模型名 / 维度
     * 任一变化），既不是"别名变了"，也不是只看维度。两个不同模型即使维度相同，
     * 向量空间也完全不一样，旧向量检索出来是噪声，而这种错配不会报错，只会让检索质量
     * 静默下降，比维度不符更难发现。</p>
     *
     * <p>反过来也成立：换了档案但身份没变（删掉重建同一个模型，前端派生别名会加 {@code -2}
     * 后缀）<b>不弹确认、不重灌</b> —— 索引里那些向量就是它算的，重算一遍纯属浪费。</p>
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
        /* 两个变化必须分开算，它们驱动的动作不同：

           • aliasChanged 只管"档案换了一个" —— 影响文案与重新装配；
           • vectorChanged 才决定"动不动索引"。判据是向量身份（地址 / 模型名 / 维度），
             不是别名：别名只是配置句柄，删了重建同一个模型会拿到新别名（前端派生别名
             遇重名会加 -2 后缀），拿它判等就会把一次"完全没换模型"的操作判成换模型，
             逼用户全量重灌一遍 —— 白花时间与 embed 费用，还把索引短暂推进半成品状态。

           反过来，同一个别名被覆盖成别的模型名（脚本 / 手改落盘）也必须能判出来，
           否则索引静默错配：检索结果变噪声，一条错误都不报。 */
        boolean aliasChanged = !java.util.Objects.equals(previousAlias, registry.embeddingAlias());
        /* 索引里那批向量是谁算的 —— 有记录就以记录为准。

           为什么不能只跟"上一个选中的档案"比：删掉向量档案会级联清空槽位，
           之后再建一份同样的模型选上时，槽位已经不知道上一个是谁了，这条最常见的
           「删了重建」路径会被判成换模型、白重灌一遍。记录与档案增删无关，所以能扛住。

           没有记录（老配置 / 从未配过）才退化为跟上一个档案比：保守，可能多问一次，但不会漏。 */
        VectorIdentity recorded = registry.embeddingIdentity();
        VectorIdentity target = VectorIdentity.of(selected);
        boolean vectorChanged = recorded != null
                ? !recorded.equals(target)
                : !VectorIdentity.same(previous, selected);

        // 知识库里已有内容 → 旧向量即将作废，必须让用户明确表态
        int affectedIndices = existingIndexCount();
        boolean hasVectors = affectedIndices > 0 && hasAnyIndexedContent();
        if (vectorChanged && hasVectors) {
            if (!"rebuild".equals(mode) && !"purge".equals(mode)) {
                // 撤销刚才的单选：没有用户确认就不该留下"已切换"的状态
                registry.setEmbeddingAlias(previousAlias);
                Map<String, Object> ask = new LinkedHashMap<>();
                /* 这是「成功但需要用户再表态」，不是错误：code 必须为 0。
                   前端 Console.api.put 的 normalize 见到非零 code 会直接 reject，
                   下面的 requiresConfirmation 分支根本走不到，用户只会看到「切换失败」。 */
                ask.put("code", 0);
                ask.put("success", true);
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
        if (vectorChanged && hasVectors) {
            if ("purge".equals(mode)) {
                purged = knowledgeBaseService.purgeAll();
            } else {
                reindexed = knowledgeBaseService.rebuildPreservingText(holder.effectiveEmbeddingDimension());
            }
        }

        /* 到这里索引要么已按新模型重灌完、要么本来就无需动 —— 才把"索引是谁算的"记下来。
           刻意放在重灌之后：中途失败会抛在这里之前，记录保持旧值，下次仍然会要求重灌，
           不会把一次半成品状态固化成"已一致"。

           只在真的发生了切换时才写：重复点选当前档案（aliasChanged 与 vectorChanged 都为 false）
           不该顺手补记 —— 老配置的库里那批向量可能是历史上别的模型算的，一次误点就把这个
           疑问永久抹平了。宁可不记（下次还是保守弹框），也不要记错。 */
        if (aliasChanged || vectorChanged) {
            registry.markEmbeddingIdentity(target);
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
        /* 换了档案、但向量被原样复用。前端据此确认"没漏掉重灌"，不必自己去比对
           地址 / 模型名 / 维度（那等于把判据复制到前端，迟早漂移）。 */
        out.put("vectorsReused", aliasChanged && !vectorChanged && hasVectors);
        out.put("message", describeResult(aliasChanged, vectorChanged, previous, selected, mode, hasVectors));
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

    /**
     * 切换结果的一句话交代。
     *
     * <p>三态而不是两态：只把"换了 / 没换"说清楚不够。别名变了但向量身份没变时，
     * 索引原样复用是一个<b>用户看不见的动作</b> —— 他明明换了一个档案，却没被要求重灌，
     * 如果界面上不给一句明确交代，他会怀疑是不是漏了、然后自己跑去点「重灌」，
     * 于是我们省下来的那次全量 embed 又被手动补了回去。</p>
     */
    private static String describeResult(boolean aliasChanged, boolean vectorChanged,
                                         ModelProfile previous, ModelProfile selected,
                                         String mode, boolean hadContent) {
        String oldName = previous == null ? "（未配置）" : previous.modelName();
        String newName = selected == null ? "（已取消配置）" : selected.modelName();
        if (!vectorChanged) {
            if (!aliasChanged) {
                return "向量模型没有变化";
            }
            return "已切换到 " + newName + "：它与原档案是同一个向量模型（地址 / 模型名 / 维度一致），"
                    + "索引里的向量可以直接复用，不需要重灌";
        }
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
           
           **只给向量档案下发**。对话族的 schema 已经下架（参数面改成"标准字段 + 用户自填 JSON"），
           对话卡片上不该再出现绿框，也就不该再有「已适配」这个徽标 —— 一个没有任何行为差异的
           徽标只会让人以为"这家服务商有特殊待遇"。适配文件对对话档案仍然有用，但那个用处
           （{@code declaration()}：不发上游就能定端点族 / 模态）发生在保存时，不在卡片上。 */
        ModelAdapterRegistry.MatchResult matched =
                adapterRegistry.match(profile.baseUrl(), profile.modelName());
        if (profile.isEmbedding()) {
            /* 两个条件都要满足才下发：① 按**模型名**命中（host 兜底只说明"这家服务商登记过"，
               把同 host 下 id 字典序最小的那条适配摆到任意未登记模型上就是张冠李戴）；
               ② embedding 族**真有参数** —— 如 api.deepseek.com 上的 deepseek-embedding
               该族为空，自然不给摘要、卡片上也就没有绿框。 */
            if (matched.byModelName() && !matched.embeddingParams().isEmpty()) {
                item.put("adapter", adapterSummary(matched, profile));
            }
        }
        /* 自定义参数（用户自助填参）的卡片入口：判据与保存复核同一个来源 ——
           该族有 schema（当前只有向量的 dimensions）→ 走绿框，不给文本框（两条路都改 params，
           同时给就是两个入口改一个字段，用户不知道哪个生效）；
           其余（全部对话档案）→ 给文本框 + 上限与提示，前端照着渲染，不自己另存一份判据。 */
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
     * 现在只剩向量档案会走到这里（{@code dimensions} 一个数值控件），对话档案的 schema 已下架。
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
     * <p>{@code applies} 是"这张卡片该不该出这个框"的**唯一判据**，由服务端算好下发
     * （{@link ModelProfile#mayBeChat()}）：前端原先自己拼 {@code chat || 无 endpoints}，
     * 用的是 {@code bindable}（= {@code domainBindable()}，比 {@code mayBeChat()} 严），
     * 于是"声明了 chat 但模态未填"的半识别档案一个框都没有。</p>
     *
     * @param schemaDriven 该档案对应族仍然**真有 schema** 时走绿框、文本框不出现（两个入口改同一个
     *                     字段时，用户无从判断哪个生效 —— 所以只留一条）。对话族的 schema 已下架，
     *                     因此对话档案恒为 {@code false}；当前只有向量的 {@code dimensions}
     *                     还让它为真
     */
    private Map<String, Object> customParamsView(ModelProfile profile,
                                                 ModelAdapterRegistry.MatchResult matched) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("schemaDriven", profile.isEmbedding() && matched.byModelName()
                && !matched.embeddingParams().isEmpty());
        view.put("applies", profile.mayBeChat());
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
         * 端点族（用户声明的能力，可多选）。
         *
         * <p>管控台弹窗里的「用途」勾选框提交到这里，服务端原样采用、不再自动识别。
         * 留空 = 未声明，档案仍会保存（{@code mayBeChat()} 把它当可对话用），
         * 但绑域时会被 {@code validateForFamily} 要求先识别或补声明。</p>
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
         * 自定义参数（如 {@code reasoning_effort}、{@code enable_thinking}）。
         *
         * 对话档案一律由用户自助填写、原样透传（过 {@link CustomParamsGate} 的四道闸门）；
         * 向量档案在该族仍有 schema 时按 schema 复核（当前只有 {@code dimensions}）。
         * 键名必须是**服务商文档里的线上字段名**，平台不再做任何翻译。
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
