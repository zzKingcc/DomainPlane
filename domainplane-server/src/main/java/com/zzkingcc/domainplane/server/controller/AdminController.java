package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.runtime.cancellation.CancellationRegistry;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.runtime.tool.InstanceLifecycle;
import com.zzkingcc.domainplane.runtime.tool.InstanceRegistry;
import com.zzkingcc.domainplane.runtime.tool.InstanceSession;
import com.zzkingcc.domainplane.runtime.tool.InstanceState;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import com.zzkingcc.domainplane.server.config.RedisProperties;
import com.zzkingcc.domainplane.server.config.PromptProperties;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import com.zzkingcc.domainplane.server.model.ModelProfileSettings;
import com.zzkingcc.domainplane.server.mcp.McpConnectionStore;
import com.zzkingcc.domainplane.server.prompt.DomainSystemPromptResolver;
import com.zzkingcc.domainplane.server.settings.EsCompatibility;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import com.zzkingcc.domainplane.server.settings.InfraSettingsStore;
import com.zzkingcc.domainplane.server.settings.LlmFailureDescriber;
import com.zzkingcc.domainplane.server.settings.LlmModelCatalog;
import com.zzkingcc.domainplane.server.settings.LlmModelHolder;
import com.zzkingcc.domainplane.server.settings.LlmSettings;
import com.zzkingcc.domainplane.server.settings.LlmSettingsStore;
import com.zzkingcc.domainplane.server.settings.DomainSettings;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.embedding.EmbeddingModel;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zzkingcc.domainplane.server.env.RedisPersistenceAudit;
import com.zzkingcc.domainplane.server.knowledge.ChunkExporter;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBackendValidator;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeStatsService;
import com.zzkingcc.domainplane.server.knowledge.LiveKnowledgeBackendRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * DomainPlane 服务端管控接口。
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    /** 连接类失败对用户的基础措辞；后面会拼上具体原因（如「连接失败：API Key 无效或已过期」） */
    private static final String CONNECT_FAILED = "连接失败";

    private final ToolRouter toolRouter;
    private final ElasticsearchClient esClient;
    private final EmbeddingModel embeddingModel;
    private final LlmModelHolder modelHolder;
    private final LlmSettingsStore settingsStore;
    private final LlmModelCatalog modelCatalog;
    private final InfraSettingsHolder infraHolder;
    private final InfraSettingsStore infraSettingsStore;
    private final RedisProperties redisProperties;
    private final DomainSettingsStore domainSettingsStore;
    private final PromptProperties promptProperties;
    private final ToolRegistry toolRegistry;
    /** 域注册表：内置域与人工创建的域在这里；工具声明派生的域在 ToolRegistry 里 */
    private final DomainRegistry domainRegistry;
    private final InstanceRegistry instanceRegistry;
    private final InstanceLifecycle instanceLifecycle;
    private final KnowledgeBaseService knowledgeBaseService;
    /** 切片预览导出：文档列表要展示导出文件路径与导出目录 */
    private final ChunkExporter chunkExporter;
    /** 知识库统计（带缓存与熔断）：域空间页与删域预演的数字都从这里来 */
    private final KnowledgeStatsService knowledgeStatsService;
    /** 知识库能力校验器：启动不再校验，保存存储配置时反馈（AdminApiController#saveInfra） */
    private final KnowledgeBackendValidator knowledgeBackendValidator;
    /** 后端注册表：判"这次保存配没配知识库"，没配就不做能力校验 */
    private final LiveKnowledgeBackendRegistry knowledgeBackendRegistry;
    /** Redis 持久化自检：原启动自检移到"保存 Redis 配置时"执行 */
    private final RedisPersistenceAudit redisPersistenceAudit;
    private final ModelProfileRegistry modelProfileRegistry;
    /** MCP 连接库：域空间页要显示每个域直接绑定的连接（界面上不再自己按域链推继承） */
    private final McpConnectionStore mcpConnectionStore;
    /** 取消信号中心：域空间页要显示每个域有没有在飞的会话（只读 runningKeys，绝不发停止） */
    private final CancellationRegistry cancellationRegistry;
    /** 域存储：域空间页要显示每个域绑的知识体（绑定在域数据里，不在 InfraSettings 里） */
    private final DomainStore domainStore;

    public AdminController(ToolRouter toolRouter,
                           @Qualifier("domainplaneElasticsearchClient") ElasticsearchClient esClient,
                           @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
                           LlmModelHolder modelHolder,
                           LlmSettingsStore settingsStore,
                           LlmModelCatalog modelCatalog,
                           InfraSettingsHolder infraHolder,
                           InfraSettingsStore infraSettingsStore,
                           RedisProperties redisProperties,
                           DomainSettingsStore domainSettingsStore,
                           PromptProperties promptProperties,
                           ToolRegistry toolRegistry,
                           InstanceRegistry instanceRegistry,
                           InstanceLifecycle instanceLifecycle,
                           KnowledgeBaseService knowledgeBaseService,
                           ChunkExporter chunkExporter,
                           KnowledgeStatsService knowledgeStatsService,
                           KnowledgeBackendValidator knowledgeBackendValidator,
                           LiveKnowledgeBackendRegistry knowledgeBackendRegistry,
                           RedisPersistenceAudit redisPersistenceAudit,
                           DomainRegistry domainRegistry,
                           ModelProfileRegistry modelProfileRegistry,
                           McpConnectionStore mcpConnectionStore,
                           CancellationRegistry cancellationRegistry,
                           DomainStore domainStore) {
        this.toolRouter = toolRouter;
        this.esClient = esClient;
        this.embeddingModel = embeddingModel;
        this.modelHolder = modelHolder;
        this.settingsStore = settingsStore;
        this.modelCatalog = modelCatalog;
        this.infraHolder = infraHolder;
        this.infraSettingsStore = infraSettingsStore;
        this.redisProperties = redisProperties;
        this.domainSettingsStore = domainSettingsStore;
        this.promptProperties = promptProperties;
        this.toolRegistry = toolRegistry;
        this.instanceRegistry = instanceRegistry;
        this.instanceLifecycle = instanceLifecycle;
        this.knowledgeBaseService = knowledgeBaseService;
        this.chunkExporter = chunkExporter;
        this.knowledgeStatsService = knowledgeStatsService;
        this.knowledgeBackendValidator = knowledgeBackendValidator;
        this.knowledgeBackendRegistry = knowledgeBackendRegistry;
        this.redisPersistenceAudit = redisPersistenceAudit;
        this.domainRegistry = domainRegistry;
        this.modelProfileRegistry = modelProfileRegistry;
        this.mcpConnectionStore = mcpConnectionStore;
        this.cancellationRegistry = cancellationRegistry;
        this.domainStore = domainStore;
    }

    /**
     * 读取当前模型设置（API Key 脱敏）
     */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        LlmSettings current = modelHolder.currentSettings();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chatBaseUrl", current.getChatBaseUrl());
        body.put("chatApiKeyMasked", current.getMaskedChatApiKey());
        body.put("chatApiKeySet", current.getChatApiKey() != null && !current.getChatApiKey().isBlank());
        body.put("chatModelName", current.getChatModelName());
        body.put("chatTemperature", current.getChatTemperature());
        body.put("chatMaxTokens", current.getChatMaxTokens());
        body.put("chatCapabilities", current.getChatCapabilities());
        /* 向量模型的名字/地址优先取选中的档案，档案为空才回落 yaml。
           embeddingConfigured 与展示值必须同源，否则知识库页会显示"已配置"却取不到模型名。 */
        ModelProfile embProfile = modelProfileRegistry.embeddingProfile().orElse(null);
        body.put("embeddingBaseUrl", embProfile != null
                ? embProfile.baseUrl() : current.getEmbeddingBaseUrl());
        body.put("embeddingApiKeyMasked", current.getMaskedEmbeddingApiKey());
        body.put("embeddingModelName", embProfile != null
                ? embProfile.modelName() : current.getEmbeddingModelName());
        body.put("embeddingDimensions", current.getEmbeddingDimensions());
        body.put("embeddingCapabilities", current.getEmbeddingCapabilities());
        body.put("chatConfigured", modelHolder.isConfigured() || modelProfileRegistry.hasChatModel());
        body.put("embeddingConfigured", modelHolder.isEmbeddingConfigured());
        /* 已登记的向量档案数：概览据此区分「未配置」（一个档案都没有）与「未启用」（有档案但没启用）。
           这两件事该给的指引完全不同，只看 embeddingConfigured 会把它们混成同一个"未配置"。 */
        body.put("embeddingProfileCount", modelProfileRegistry.embeddingProfileCount());
        body.put("settingsFile", settingsStore.filePath());
        return body;
    }

    /**
     * 保存模型设置并立即生效（无需重启）
     *
     * @param rebuildIndex 管控台已二次确认"维度变化、同意重建索引"时为 {@code true}
     */
    @PostMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody LlmSettings settings,
                                            @RequestParam(defaultValue = "false") boolean rebuildIndex) {
        // 入口回显只留 debug：成功路径由 LlmModelHolder 的"已生效"承接，
        // 失败路径有自己的 warn/error，INFO 再打一遍只是噪音
        log.debug("[管控] 收到模型设置保存请求: chatBaseUrl={}, chatModel={}, embeddingModel={}, rebuildIndex={}",
                settings.getChatBaseUrl(), settings.getChatModelName(),
                settings.getEmbeddingModelName(), rebuildIndex);
        // Key 留空表示"不修改"，用已保存的值补齐：避免管控台不发明文导致 Key 被清空
        fillBlankKeysFromCurrent(settings);

        DimCheck dim = checkEmbeddingDimension(settings);
        if (dim.verdict() == DimVerdict.DECLARED_MISMATCH) {
            // 声明了一个模型不支持的维度：保存下去必然灌库失败，直接拒绝（不受重建开关影响）
            Map<String, Object> fail = failBody(dim.message());
            fail.put("declaredDimension", dim.declaredDims());
            fail.put("newDimension", dim.newDims());
            return status(HttpStatus.BAD_REQUEST, fail);
        }
        if (dim.verdict() == DimVerdict.NEEDS_REBUILD && !rebuildIndex) {
            // 维度变了但用户还没确认重建 → 本次保存不生效，配置保持原样
            Map<String, Object> fail = failBody(dim.message());
            fail.put("requiresRebuild", true);
            fail.put("indexDimension", dim.indexDims());
            fail.put("newDimension", dim.newDims());
            // 409：请求本身没错，是还缺一次"同意重建"的确认，与参数非法区分开
            return status(HttpStatus.CONFLICT, fail);
        }

        try {
            modelHolder.apply(settings);
        } catch (IllegalStateException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, failBody(e.getMessage()));
        }

        // 维度变化且用户已确认 → 紧接着按新维度重灌（原文保留，不需要重新上传）
        boolean rebuilt = false;
        if (dim.verdict() == DimVerdict.NEEDS_REBUILD) {
            log.warn("[管控] 维度 {} → {}，开始从原文按新维度重建知识体索引", dim.indexDims(), dim.newDims());
            try {
                // 维度必须显式传入：索引维度是 mapping 不可变参数，取 apply 后生效的新模型维度。
                // 走"保留原文"的那条：archives 里存着全部切片，只需用新模型重算向量，
                // 用户不用把每个文档重新上传一遍（旧实现在这里直接清空，是一次静默的数据事故）。
                Map<String, Integer> reindexed =
                        knowledgeBaseService.rebuildPreservingText(modelHolder.effectiveEmbeddingDimension());
                rebuilt = true;
                log.info("[管控] 知识体索引重灌完成（dims={}，索引 {}）", dim.newDims(), reindexed.keySet());
            } catch (Exception e) {
                // 配置已落盘并生效，只是索引重建失败：必须如实告知，不能报成功
                log.error("[管控] 知识体索引重灌失败（配置已生效）: {}", e.getMessage(), e);
                Map<String, Object> fail = failBody(
                        "配置已保存并生效，但知识体索引重建失败：" + e.getMessage()
                                + "。请到「知识库」页对受影响的体逐个「重建索引」，重建成功前灌库与检索不可用。");
                fail.put("saved", true);
                fail.put("rebuilt", false);
                fillKeyState(fail);
                return status(HttpStatus.INTERNAL_SERVER_ERROR, fail);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("chatConfigured", modelHolder.isConfigured() || modelProfileRegistry.hasChatModel());
        result.put("embeddingConfigured", modelHolder.isEmbeddingConfigured());
        fillKeyState(result);
        result.put("rebuilt", rebuilt);
        // message 不带主语，由管控台拼成「<模型名>：<message>」
        result.put("message", rebuilt
                ? "已保存并立即生效，知识库索引已从原文按新维度 " + dim.newDims() + " 重灌（不需要重传文档）"
                : (dim.note() != null ? "已保存并立即生效（" + dim.note() + "）" : "已保存并立即生效"));
        return ResponseEntity.ok(result);
    }

    /**
     * 清空内置的某个模型配置（{@code kind} = {@code chat} / {@code embedding}）。
     *
     * 只清目标那一组，另一组原样保留。清空后该模型视为「未配置」；
     * 若 yaml 里也没有兜底值，则未绑定任何档案的域将没有可用模型：管控台需二次确认。
     */
    @DeleteMapping("/settings/{kind}")
    public ResponseEntity<Map<String, Object>> clearSettings(@PathVariable("kind") String kind) {
        boolean embedding = "embedding".equalsIgnoreCase(kind);
        if (!embedding && !"chat".equalsIgnoreCase(kind)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "kind 只能是 chat 或 embedding");
        }

        LlmSettings current = modelHolder.currentSettings();
        LlmSettings next = new LlmSettings();
        if (current != null) {
            if (embedding) {
                next.setChatBaseUrl(current.getChatBaseUrl());
                next.setChatApiKey(current.getChatApiKey());
                next.setChatModelName(current.getChatModelName());
                next.setChatTemperature(current.getChatTemperature());
                next.setChatMaxTokens(current.getChatMaxTokens());
                next.setChatCapabilities(current.getChatCapabilities());
            } else {
                next.setEmbeddingBaseUrl(current.getEmbeddingBaseUrl());
                next.setEmbeddingApiKey(current.getEmbeddingApiKey());
                next.setEmbeddingModelName(current.getEmbeddingModelName());
                next.setEmbeddingDimensions(current.getEmbeddingDimensions());
                next.setEmbeddingCapabilities(current.getEmbeddingCapabilities());
            }
        }

        log.warn("[管控] 清空内置{}的配置", embedding ? "向量模型" : "对话模型");
        try {
            modelHolder.apply(next);
        } catch (IllegalStateException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, failBody(e.getMessage()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("chatConfigured", modelHolder.isConfigured() || modelProfileRegistry.hasChatModel());
        result.put("embeddingConfigured", modelHolder.isEmbeddingConfigured());
        fillKeyState(result);
        result.put("message", (embedding ? "向量模型" : "对话模型") + "配置已清空");
        return ResponseEntity.ok(result);
    }

    /** 保存失败/需确认时的统一响应体 */
    private static Map<String, Object> failBody(String message) {
        Map<String, Object> fail = new LinkedHashMap<>();
        fail.put("success", false);
        fail.put("error", message);
        return fail;
    }

    /**
     * 统一出口：业务失败也带上对应的 HTTP 状态。
     *
     * <p>全局异常处理器按 {@code ErrorCode#getHttpStatus()} 定状态，控制器里"直接 return 失败体"
     * 的路径却一律 200：调用方（SDK / 脚本 / 网关）只能靠解析 body 才知道失败，
     * 重试与告警都拿不到信号。</p>
     */
    private static <T> ResponseEntity<T> status(HttpStatus httpStatus, T body) {
        return ResponseEntity.status(httpStatus).body(body);
    }

    /**
     * 回传 Key 掩码/配置状态。
     */
    private void fillKeyState(Map<String, Object> body) {
        LlmSettings now = modelHolder.currentSettings();
        body.put("chatApiKeyMasked", now.getMaskedChatApiKey());
        body.put("chatApiKeySet", hasText(now.getChatApiKey()));
        body.put("embeddingApiKeyMasked", now.getMaskedEmbeddingApiKey());
    }

    // ===== 向量维度预检 =====

    /** 维度预检结论 */
    private enum DimVerdict {
        /** 无变化、或索引维度与实测一致，或尚无索引 */
        OK,
        /** 维度与现有索引不符，必须重建索引后才能保存 */
        NEEDS_REBUILD,
        /** 声明的维度与实测不符（该模型不支持这个维度） */
        DECLARED_MISMATCH,
        /** 连不上/填不全，无法实测；放行保存并提示 */
        UNKNOWN
    }

    /**
     * @param verdict    结论
     * @param indexDims  现有索引维度（无索引为 null）
     * @param newDims    实测的新维度（未实测为 null）
     * @param declaredDims 用户声明的维度（未声明为 null）
     * @param message    给用户看的说明（OK/UNKNOWN 时为附加提示，可为 null）
     */
    private record DimCheck(DimVerdict verdict, Integer indexDims, Integer newDims,
                            Integer declaredDims, String message) {
        /** 附加到成功消息后的短提示 */
        String note() {
            return verdict == DimVerdict.UNKNOWN ? message : null;
        }
    }

    /**
     * 保存前的向量维度预检。
     */
    private DimCheck checkEmbeddingDimension(LlmSettings incoming) {
        Integer indexDims = null;
        if (!embeddingChanged(incoming, modelHolder.currentSettings())) {
            return new DimCheck(DimVerdict.OK, null, null, null, null);
        }
        // 读不到索引维度（ES 未配置/不可达/索引不存在）不阻断保存：退化为不做预检。
        // 否则用户会被卡在"必须先配好 ES 才能改模型"的死循环里，无法自救。
        try {
            indexDims = knowledgeBaseService.currentVectorDims();
        } catch (Exception e) {
            log.warn("[管控] 读取索引向量维度失败，跳过维度预检（ES 未配置或索引不存在）: {}",
                    e.getMessage());
        }

        LlmModelHolder.EmbeddingProbe probe;
        try {
            probe = modelHolder.testEmbeddingConnection(incoming);
        } catch (Exception e) {
            // 未填全 / Key 无效 / 网络不通时仍须放行保存：否则用户连改 Key 自救都做不到。
            String reason = LlmFailureDescriber.describe(e);
            log.warn("[管控] 保存前无法实测向量维度，跳过预检并放行: {}", reason);
            return new DimCheck(DimVerdict.UNKNOWN, indexDims, null, null,
                    "未能实测向量维度（" + reason + "），已按原样保存；若索引维度与模型不符，灌库前请先重建索引");
        }

        int newDims = probe.actualDimension();
        if (probe.mismatched()) {
            return new DimCheck(DimVerdict.DECLARED_MISMATCH, indexDims, newDims, probe.declaredDimension(),
                    "向量维度与模型不符：声明 " + probe.declaredDimension() + " 维，该模型实际返回 " + newDims
                            + " 维。维度只用于本机建索引、不会作为请求参数下发（各家服务商对自定义维度的"
                            + "支持也不一致），所以声明值不会被兑现，按它建索引会让灌库被存储拒绝。"
                            + "请把维度改填该模型的原生维度 " + newDims + "。");
        }
        if (indexDims == null) {
            return new DimCheck(DimVerdict.OK, null, newDims, probe.declaredDimension(), null);
        }
        if (indexDims == newDims) {
            return new DimCheck(DimVerdict.OK, indexDims, newDims, probe.declaredDimension(), null);
        }
        return new DimCheck(DimVerdict.NEEDS_REBUILD, indexDims, newDims, probe.declaredDimension(),
                "向量维度将由 " + indexDims + " 变为 " + newDims + "，与已建好的知识库索引不一致。"
                        + "ES 索引的向量维度是 mapping 参数，建好后无法修改，必须先重建索引；"
                        + "否则写入会被 ES 以维度不符拒绝，检索同样报错，且不会自愈。");
    }

    /**
     * 本次请求是否改动了向量配置。
     */
    private static boolean embeddingChanged(LlmSettings in, LlmSettings cur) {
        if (cur == null) {
            return true;
        }
        return !Objects.equals(in.getEmbeddingBaseUrl(), cur.getEmbeddingBaseUrl())
                || !Objects.equals(in.getEmbeddingApiKey(), cur.getEmbeddingApiKey())
                || !Objects.equals(in.getEmbeddingModelName(), cur.getEmbeddingModelName())
                || !Objects.equals(in.getEmbeddingDimensions(), cur.getEmbeddingDimensions());
    }

    /**
     * 连通性测试（保存前先验，避免填错后才发现）
     *
     * @param type {@code chat}（默认）或 {@code embedding}
     */
    @PostMapping("/settings/test")
    public ResponseEntity<Map<String, Object>> testSettings(@RequestBody(required = false) LlmSettings settings,
                                            @RequestParam(defaultValue = "chat") String type) {
        LlmSettings candidate = settings == null ? new LlmSettings() : settings;
        fillBlankKeysFromCurrent(candidate);
        Map<String, Object> result = new LinkedHashMap<>();
        HttpStatus httpStatus = HttpStatus.OK;
        try {
            if ("embedding".equalsIgnoreCase(type)) {
                httpStatus = testEmbedding(candidate, result);
            } else {
                String reply = modelHolder.testChatConnection(candidate);
                result.put("success", true);
                result.put("type", "chat");
                result.put("reply", reply);
                result.put("message", "对话模型连通正常，模型回复：" + reply);
            }
        } catch (Exception e) {
            String reason = LlmFailureDescriber.describe(e);
            log.warn("[管控] 模型连通性测试失败: 原因={} 原始={}", reason, e.getMessage());
            result.put("success", false);
            result.put("type", type);
            result.put("message", CONNECT_FAILED + "：" + reason);
            result.put("error", reason);
            result.put("detail", e.getMessage());
            // 502：本次请求的入参没问题，是上游不可达/拒绝，与参数非法区分开
            httpStatus = HttpStatus.BAD_GATEWAY;
        }
        return status(httpStatus, result);
    }

    /**
     * 向量测试的完整裁决：连通性 + 维度契约 + 索引一致性
     *
     * @return 对应的 HTTP 状态：维度声明与模型不符属入参问题（400），其余按成功处理
     */
    private HttpStatus testEmbedding(LlmSettings candidate, Map<String, Object> result) {
        LlmModelHolder.EmbeddingProbe probe = modelHolder.testEmbeddingConnection(candidate);
        int actual = probe.actualDimension();
        Integer declared = probe.declaredDimension();
        Integer indexDims = knowledgeBaseService.currentVectorDims();

        result.put("type", "embedding");
        result.put("dimension", actual);
        result.put("declaredDimension", declared);
        result.put("indexDimension", indexDims);

        if (probe.mismatched()) {
            String detail = "该模型的原生维度是 " + actual + " 维，与声明的 " + declared + " 维不符";
            log.warn("[管控] 向量维度校验未通过：{}", detail);
            result.put("success", false);
            result.put("message", "已连通，但" + detail + "。维度只用于本机建索引、不随请求下发，"
                    + "所以请把「向量维度」填成 " + actual + "（模型原生维度）。");
            result.put("error", detail);
            return HttpStatus.BAD_REQUEST;
        }

        String dimNote = declared != null
                ? "维度 " + actual + " 维已确认（与声明一致）"
                : "该模型默认维度 " + actual + " 维（未指定维度，ES 索引将按实测值创建）";
        String idxNote;
        if (indexDims == null) {
            idxNote = "；当前尚无知识库索引，下次启动或手动重建时按该维度创建";
        } else if (indexDims == actual) {
            idxNote = "；当前索引维度 " + indexDims + "，一致，无需重建";
        } else {
            idxNote = "；当前索引维度 " + indexDims + " 与模型不一致，需到「知识库」页重建索引后才能灌库";
        }
        result.put("success", true);
        result.put("message", "向量模型连通正常，" + dimNote + idxNote);
        return HttpStatus.OK;
    }

    /**
     * 拉取服务商侧的模型目录，供管控台「下拉选择 + 手动输入」控件使用
     * @param type {@code chat}（默认）或 {@code embedding}
     */
    @PostMapping("/models")
    public Map<String, Object> models(@RequestBody(required = false) LlmSettings settings,
                                      @RequestParam(defaultValue = "chat") String type) {
        boolean embedding = "embedding".equalsIgnoreCase(type);
        String label = embedding ? "向量模型" : "对话模型";
        LlmSettings candidate = settings == null ? new LlmSettings() : settings;

        String[] endpoint = resolveCatalogEndpoint(candidate, embedding);
        String baseUrl = endpoint[0];
        String apiKey = endpoint[1];

        Map<String, Object> result = new LinkedHashMap<>();
        if (!hasText(baseUrl) || !hasText(apiKey)) {
            String missing = hasText(baseUrl) ? "API Key" : "服务商地址";
            log.warn("[管控] 拉取{}目录未发起：缺少{}", label, missing);
            return connectFailed(result, "缺少" + missing, null);
        }

        try {
            List<String> ids = modelCatalog.listModelIds(baseUrl, apiKey);
            log.info("[管控] 拉取{}目录成功：baseUrl={} 共 {} 个（仅候选池，不代表已授权）",
                    label, baseUrl, ids.size());
            result.put("success", true);
            result.put("models", ids);
            return result;
        } catch (Exception e) {
            String reason = LlmFailureDescriber.describe(e);
            log.warn("[管控] 拉取{}目录失败：baseUrl={} 原因={} 原始={}", label, baseUrl, reason, e.getMessage());
            return connectFailed(result, reason, e.getMessage());
        }
    }

    /**
     * 连接失败的统一响应体。
     *
     * @param reason 给用户看的中文原因（如「API Key 无效或已过期」）
     * @param detail 原始报文，仅供接口层诊断与 F12 查看，页面不展示
     */
    private static Map<String, Object> connectFailed(Map<String, Object> result, String reason, String detail) {
        result.put("success", false);
        result.put("message", CONNECT_FAILED + "：" + reason);
        result.put("error", reason);
        result.put("detail", detail);
        return result;
    }

    /** 解析拉取目录要用的地址与 Key：向量缺项回落对话，Key 缺项回落已保存值 */
    private String[] resolveCatalogEndpoint(LlmSettings candidate, boolean embedding) {
        LlmSettings current = modelHolder.currentSettings();
        if (embedding) {
            String baseUrl = hasText(candidate.getEmbeddingBaseUrl())
                    ? candidate.getEmbeddingBaseUrl() : candidate.getChatBaseUrl();
            String apiKey = firstText(candidate.getEmbeddingApiKey(), candidate.getChatApiKey(),
                    current == null ? null : current.effectiveEmbeddingApiKey());
            return new String[]{baseUrl, apiKey};
        }
        String apiKey = firstText(candidate.getChatApiKey(),
                current == null ? null : current.getChatApiKey());
        return new String[]{candidate.getChatBaseUrl(), apiKey};
    }

    private static String firstText(String... values) {
        for (String v : values) {
            if (hasText(v)) {
                return v;
            }
        }
        return null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** Key 留空 = "不修改"，用已保存值补齐；这样用户只改地址/模型名时不必重填 Key */
    private void fillBlankKeysFromCurrent(LlmSettings incoming) {
        LlmSettings current = modelHolder.currentSettings();
        if (current == null) {
            return;
        }
        if (incoming.getChatApiKey() == null || incoming.getChatApiKey().isBlank()) {
            incoming.setChatApiKey(current.getChatApiKey());
        }
        if (incoming.getEmbeddingApiKey() == null || incoming.getEmbeddingApiKey().isBlank()) {
            incoming.setEmbeddingApiKey(current.getEmbeddingApiKey());
        }
    }

    /**
     * 已注册工具清单
     */
    @GetMapping("/tools")
    public List<ToolDescriptor> tools() {
        return toolRouter.toolDescriptors();
    }

    /**
     * 「域空间」：注册表里每个域实际注册到的内容 + 全局统计。
     */
    @GetMapping("/domains")
    public Map<String, Object> domains() {
        List<ToolDescriptor> all = toolRouter.toolDescriptors();
        Set<String> known = new TreeSet<>(toolRouter.knownDomains());
        DomainSettings console = domainSettingsStore.load();
        DomainSystemPromptResolver resolver =
                new DomainSystemPromptResolver(domainSettingsStore, promptProperties);

        /* 全域可见＝声明带根域 default 的工具（声明留空即挂根），对每个域都可见。 */
        List<ToolDescriptor> globalTools = all.stream()
                .filter(d -> d.declaredDomains().contains(Domains.DEFAULT))
                .toList();

        int approvalTotal = 0;
        int missingPrompt = 0;
        /* 在飞会话按域计数——只读 runningKeys 快照，与删域时的判定同源，但绝不发停止。
           知识库文档 / 切片数不在这里算：那是唯一要碰存储的聚合，已拆到 GET /admin/kb/stats
           （KnowledgeStatsService，带缓存与熔断），前端拿到树后异步补数——树不能被
           存储故障拖住，这与 knowledgeByDomain 时代 fail-open 的意图一致，只是补上了
           "不慢"这半边。 */
        Map<String, Integer> runningByDomain = new LinkedHashMap<>();
        for (String key : cancellationRegistry.runningKeys()) {
            String domainOfKey = SessionKeys.domainOf(key);
            if (domainOfKey != null) {
                runningByDomain.merge(domainOfKey, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> domains = new ArrayList<>();
        for (String domain : known) {
            Set<String> visibleNames = toolRouter.toolSpecifications(domain).stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toSet());
            List<ToolDescriptor> visible = all.stream()
                    .filter(d -> visibleNames.contains(d.name()))
                    .sorted(Comparator.comparing(ToolDescriptor::name))
                    .toList();

            List<Map<String, Object>> tools = new ArrayList<>();
            Map<String, Integer> bySideEffect = new LinkedHashMap<>();
            bySideEffect.put("READ", 0);
            bySideEffect.put("WRITE", 0);
            bySideEffect.put("DESTRUCTIVE", 0);
            int approval = 0;
            for (ToolDescriptor d : visible) {
                tools.add(toolView(d, domain));

                if (d.sideEffect() != null) {
                    bySideEffect.merge(d.sideEffect().name(), 1, Integer::sum);
                }
                if (d.requiresApproval()) {
                    approval++;
                }
            }

            String diff = firstNonBlank(console.domainPrompt(domain),
                    promptProperties.domainPrompt(domain));
            boolean hasPrompt = diff != null && !diff.isBlank();
            if (!hasPrompt) {
                missingPrompt++;
            }
            approvalTotal += approval;

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", domain);
            // 来源决定这个域能不能删：内置是兜底、派生归工具，只有人工创建的域可删
            String source = domainSource(domain);
            d.put("source", source);
            d.put("sourceLabel", sourceLabel(source));
            // 三种来源同级，只有根域因是整棵树的起点而不可删
            d.put("deletable", !DomainRegistry.Source.BUILTIN.name().equals(source));
            d.put("parentId", Domains.parentOf(domain));
            d.put("childrenCount", domainRegistry.descendantsOf(domain).size());
            // 直接子域数（childrenCount 是全部后代，两回事）
            d.put("directChildCount", domainRegistry.childrenOf(domain).size());
            d.put("hasChildren", domainRegistry.hasChildren(domain));
            // 可调用单元 = 能当入口；装配节点只把工具 / 提示词 / 模型 / 知识传给后代
            d.put("callable", domainRegistry.isCallable(domain));
            // 软删宽限期状态（F03）：标记与结构判据无关，管控台据此显示"待删除/可撤销"，
            // 彻底删除时间由删除接口返回的 hardDeleteAt 为准
            Long deletedAt = domainRegistry.pendingDeleteAt(domain);
            d.put("pendingDelete", deletedAt != null);
            d.put("deletedAt", deletedAt);
            d.put("toolCount", tools.size());
            d.put("exclusiveToolCount", tools.stream()
                    .filter(t -> Boolean.TRUE.equals(t.get("exclusive"))).count());
            d.put("providerCount", tools.stream()
                    .map(t -> String.valueOf(t.get("provider")))
                    .distinct()
                    .count());
            d.put("approvalCount", approval);
            d.put("sideEffects", bySideEffect);
            d.put("hasPrompt", hasPrompt);
            d.put("promptLength", hasPrompt ? diff.trim().length() : 0);
            // 工具执行信息回写开关现值（默认关）；管控台只给可调用单元显示，这里全量带回
            d.put("toolCallEcho", console.toolCallEcho(domain));
            // 执行计划展示开关现值（默认关）：规划结果发不给调用方，与 planning.enabled 互相独立
            d.put("planDisplay", console.planDisplay(domain));
            // 该域实际生效的 SystemMessage 预览（preview 不打日志，见 DomainSystemPromptResolver）
            d.put("promptPreview", resolver.preview(domain, console));
            // 生效提示词的链上构成（哪些祖先域拼了内容、各多少字）——沿链累加语义的可视化
            List<Map<String, Object>> promptChain = new ArrayList<>();
            for (DomainSystemPromptResolver.PromptSegment seg : resolver.chain(domain, console)) {
                Map<String, Object> sv = new LinkedHashMap<>();
                sv.put("domain", seg.domain());
                sv.put("chars", seg.chars());
                promptChain.add(sv);
            }
            d.put("promptChain", promptChain);
            // 生效模型绑定（沿链解析 + 来源域）：前端不再用 JS 重实现「沿链覆盖」规则
            d.put("modelBindings", modelBindingsView(domain));
            // MCP：直接绑定的连接 + 继承来源（与模型同一套沿链语义）
            Map<String, Object> mcp = new LinkedHashMap<>();
            mcp.put("connections", mcpConnectionStore.connectionsOfDomain(domain));
            mcp.put("inheritedFrom", nearestAncestorMcp(domain));
            d.put("mcp", mcp);
            // 知识库：文档 / 切片数由前端从 GET /admin/kb/stats 异步补（不在这里算）
            Map<String, Object> knowledge = new LinkedHashMap<>();
            // 该域绑的知识体（**不继承**：没绑就是没有知识库，不去父域借）。
            // bodyMissing=true 表示绑的体已被删——前端据此提示"重新选一个"，而不是静默当成没配。
            // bodyDead=true 是另一种：体还在，但它两路的连接都不可用（等于没有知识库）。
            String bodyId = domainStore.bodyOf(domain);
            InfraSettings.KnowledgeBody boundBody = infraHolder.current().body(bodyId);
            knowledge.put("body", bodyId);
            knowledge.put("bodyName", boundBody == null ? null : boundBody.effectiveName());
            knowledge.put("bodyModes", boundBody == null ? null : boundBody.modes());
            knowledge.put("bodyMissing", bodyId != null && boundBody == null);
            knowledge.put("bodyDead", bodyId != null && boundBody != null
                    && infraHolder.current().usableBody(bodyId) == null);
            d.put("knowledge", knowledge);
            // 在飞会话数：这个域下正在跑几轮对话（删域前最有意义的数）
            d.put("runningSessions", runningByDomain.getOrDefault(domain, 0));
            d.put("tools", tools);
            domains.add(d);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("domainCount", known.size());
        stats.put("builtinCount", known.stream().filter(domainRegistry::isBuiltin).count());
        stats.put("manualCount", known.stream()
                .filter(domain -> DomainRegistry.Source.MANUAL.name().equals(domainSource(domain))).count());
        stats.put("derivedCount", known.stream()
                .filter(domain -> "DERIVED".equals(domainSource(domain))).count());
        stats.put("toolCount", all.size());
        stats.put("globalToolCount", globalTools.size());
        stats.put("approvalToolCount", approvalTotal);
        stats.put("missingPromptCount", missingPrompt);

        List<Map<String, Object>> globalList = new ArrayList<>();
        for (ToolDescriptor d : globalTools) {
            globalList.add(toolView(d));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stats", stats);
        body.put("domains", domains);
        body.put("globalTools", globalList);
        /* 可绑的知识体清单：域卡片上那个下拉的选项。列出全部知识体——绑定时只落盘不做
           校验（与「保存只管保存」同口径），没配好的体也允许先绑，选项文案带
           「未配连接 / 连接不可用」标注，检索工具按体可用性动态上下线。
           带上 slug / index 是为了让前端能显示"你绑的是哪份数据"，而不是只给一个名字；
           raw 也带出来，域详情里可以直接说清原文落在哪（体是权威，域只是引用方）。 */
        List<Map<String, Object>> bodyOptions = new ArrayList<>();
        InfraSettings infra = infraHolder.current();
        infra.bodiesOrEmpty().forEach((id, b) -> {
            if (b == null) {
                return;
            }
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("id", id);
            opt.put("name", b.effectiveName());
            opt.put("slug", b.effectiveSlug());
            opt.put("index", b.effectiveIndex());
            opt.put("modes", b.modes());
            opt.put("hybrid", b.hybrid());
            opt.put("raw", b.rawLocation());
            opt.put("rawLabel", InfraSettings.rawLabel(b.rawLocation()));
            opt.put("usable", infra.usableBody(id) != null);
            bodyOptions.add(opt);
        });
        body.put("knowledgeBodies", bodyOptions);
        List<String> orphans = new ArrayList<>(console.getPrompts().keySet());
        orphans.removeAll(known);
        Collections.sort(orphans);
        body.put("orphanPrompts", orphans);
        body.put("settingsFile", domainSettingsStore.filePath());
        return body;
    }

    /**
     * 知识库统计：<b>知识体总量</b> + <b>每个域可检索的量</b>（带缓存与熔断，见 {@link KnowledgeStatsService}）。
     *
     * <p>两个视图回答两个不同的问题，别混用：</p>
     *
     * <ul>
     *   <li>{@code bodies}：这个知识体里有多少文档（基础设施视角，容量规划用）。
     *       体可被多个域共用，所以它<b>不对应任何一个域</b>；{@code domainCount} 给出共用域数，
     *       前端据此决定要不要提示"体共 N"。</li>
     *   <li>{@code domains}：这个域一次检索能查到多少（本域 + 已绑体的祖先域，与
     *       {@code RetrievalPlan.channels} 同口径）。域卡片上的主数字用这个。</li>
     * </ul>
     *
     * <p>与 {@code /admin/domains} 拆开的原因：这是唯一要碰存储的聚合，ES 不可达时
     * 不能把树接口一起拖住。前端拿到域树后异步补这里的数字；数字旧了（stale=true）
     * 也照常展示，展示路径不值得为时效硬扛一次 connectTimeout。</p>
     */
    @GetMapping("/kb/stats")
    public Map<String, Object> kbStats() {
        KnowledgeStatsService.Stats stats = knowledgeStatsService.stats();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stale", stats.stale());
        /* 体 → 被几个域绑着（共用时要提示"体共 N"，否则用户会把体的总量当成这个域的量） */
        Map<String, Integer> boundDomains = new LinkedHashMap<>();
        domainStore.bodies().values().forEach(bodyId -> boundDomains.merge(bodyId, 1, Integer::sum));
        /* 先把"配置里存在的知识体"按 0 铺一遍再让实测值覆盖：统计只产出有文档的体，
           只按实测值出列表会让"绑了体但还没传文档"的域在卡片上落到"统计不可用"——
           那是"取不到数"，不是"这里没有数"，两句话差别很大。 */
        Map<String, long[]> merged = new LinkedHashMap<>();
        infraHolder.current().bodiesOrEmpty().keySet()
                .forEach(bodyId -> merged.put(bodyId, new long[2]));
        merged.putAll(stats.byBody());
        List<Map<String, Object>> bodies = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : merged.entrySet()) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("id", entry.getKey());
            b.put("name", knowledgeStatsService.bodyName(entry.getKey()));
            b.put("documents", entry.getValue()[0]);
            b.put("chunks", entry.getValue()[1]);
            b.put("domainCount", boundDomains.getOrDefault(entry.getKey(), 0));
            bodies.add(b);
        }
        body.put("bodies", bodies);
        /* 域口径（可检索）：前端卡片的主数字。只给有数的域，缺省即 0 */
        Map<String, Object> domains = new LinkedHashMap<>();
        knowledgeStatsService.retrievableBy(toolRouter.knownDomains()).forEach((domain, acc) -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("documents", acc[0]);
            v.put("chunks", acc[1]);
            domains.put(domain, v);
        });
        body.put("domains", domains);
        return body;
    }

    /**
     * 某域生效的模型绑定：主模型 + 各能力族，全部沿链解析并带来源域。
     *
     * 这是「写存意图、读取算生效」里"算"的那一半：空列表 = 整条链都没绑（不是"解绑了"）；
     * sourceDomain 为空 = 确无可调用模型。前端据此显示「继承自 X」，不再自己沿链推。
     */
    private Map<String, Object> modelBindingsView(String domain) {
        Map<String, Object> view = new LinkedHashMap<>();
        ModelProfileSettings.DomainBinding chat = modelProfileRegistry.resolve(domain);
        view.put("chat", bindingView(chat.aliases(), chat.sourceDomain(), domain));
        Map<String, Object> capabilities = new LinkedHashMap<>();
        for (String family : ModelProfile.CAPABILITY_FAMILIES) {
            ModelProfileSettings.CapabilityBinding cap =
                    modelProfileRegistry.resolveCapability(domain, family);
            capabilities.put(family, bindingView(cap.aliases(), cap.sourceDomain(), domain));
        }
        view.put("capabilities", capabilities);
        return view;
    }

    /** 一条绑定解析结果的 JSON 视图：生效别名 + 来源域 + 是否继承而来 */
    private Map<String, Object> bindingView(List<String> aliases, String sourceDomain, String domain) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("aliases", aliases == null ? List.of() : aliases);
        v.put("sourceDomain", sourceDomain);
        v.put("inherited", sourceDomain != null && !sourceDomain.equals(domain));
        return v;
    }

    /**
     * 最近一个直接绑了 MCP 连接的祖先域（不含自身；沿链由近及远）。
     *
     * 与 MCP 绑定弹窗里「继承自 X」标签同口径，只是算在后端：
     * 弹窗仍按自己的域链推（P3 再收敛），这里保证域空间页显示的是同一句话。
     */
    private String nearestAncestorMcp(String domain) {
        List<String> chain = Domains.chainOf(Domains.normalize(domain));
        for (int i = chain.size() - 2; i >= 0; i--) {
            String step = chain.get(i);
            if (!mcpConnectionStore.connectionsOfDomain(step).isEmpty()) {
                return step;
            }
        }
        return null;
    }

    /**
     * 删域影响面预演（dry-run，只读）：删这个域会连带删掉什么、各多少。
     *
     * 与 DELETE /admin/domains/{id} 同一套判定与口径，但一个字节都不删：
     * 删除确认框该显示真数，而不是让用户赌一把。特别注意：这里只统计在飞会话，
     * 绝不做 requestStopAndAwait——停掉在飞会话是真删时才做的动作。
     *
     * 计数 fail-open（ES / 存储临时不可达按 0 报）：预演的价值在数量级，
     * 为一次统计失败把预览整个打成 500，用户反而连"要删什么"都看不到。
     */
    @GetMapping("/domains/{id}/delete-impact")
    public Map<String, Object> domainDeleteImpact(@PathVariable("id") String id) {
        String normalized = Domains.normalize(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("domain", normalized);
        if (!domainRegistry.contains(normalized)) {
            body.put("removable", false);
            body.put("reason", "域不存在：" + normalized);
            return body;
        }
        if (domainRegistry.isBuiltin(normalized)) {
            body.put("removable", false);
            body.put("reason", "根域不可删除（它是整棵域树的起点）");
            return body;
        }
        List<String> descendants = new ArrayList<>(domainRegistry.descendantsOf(normalized));
        List<String> affected = new ArrayList<>(descendants);
        affected.add(normalized);
        Set<String> affectedSet = Set.copyOf(affected);

        body.put("removable", true);
        body.put("descendants", descendants);
        /* 知识库：删域<b>只解绑，不删任何存储</b>（v2）。所以这里报"会被保留的知识体"，
           而不是"会删掉几个索引"——确认框要能明确说"知识不会丢"。
           数字走统计缓存，存储不可达时 fail-open 按 0 报。 */
        Map<String, long[]> byBody = knowledgeStatsService.stats().byBody();
        List<String> retainedBodyIds = new ArrayList<>();
        for (String domain : affected) {
            String bodyId = domainStore.bodyOf(domain);
            if (bodyId != null && !bodyId.isBlank() && !retainedBodyIds.contains(bodyId)) {
                retainedBodyIds.add(bodyId);
            }
        }
        long docs = 0;
        long chunks = 0;
        List<Map<String, Object>> retained = new ArrayList<>(retainedBodyIds.size());
        for (String bodyId : retainedBodyIds) {
            long[] acc = byBody.get(bodyId);
            long d = acc == null ? 0L : acc[0];
            long c = acc == null ? 0L : acc[1];
            docs += d;
            chunks += c;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", bodyId);
            row.put("name", knowledgeStatsService.bodyName(bodyId));
            row.put("documents", d);
            row.put("chunks", c);
            retained.add(row);
        }
        Map<String, Object> knowledge = new LinkedHashMap<>();
        knowledge.put("retainedBodies", retainedBodyIds);
        knowledge.put("retained", retained);
        knowledge.put("documents", docs);
        knowledge.put("chunks", chunks);
        // 明确写死 0：前端据此渲染"0 个索引会被删除"，不必猜后端语义
        knowledge.put("indicesDeleted", 0);
        knowledge.put("note", "删域只解绑：知识体（原文 + 索引）完整保留，可随时再绑给新域");
        body.put("knowledge", knowledge);
        // 会被级联清理的模型绑定 / MCP 绑定（删域只摘绑定，不删连接与档案本身）
        List<String> modelBound = modelProfileRegistry.domainBindings().keySet().stream()
                .filter(affectedSet::contains)
                .sorted()
                .toList();
        List<String> mcpBound = mcpConnectionStore.allDomainBindings().keySet().stream()
                .filter(affectedSet::contains)
                .sorted()
                .toList();
        body.put("modelBindings", modelBound);
        body.put("mcpBindings", mcpBound);
        // 连带清理的提示词域数（提示词沿链拼接，少一段整条链的生效内容都会变）
        DomainSettings consoleSettings = domainSettingsStore.load();
        long prompts = consoleSettings.getPrompts().keySet().stream()
                .filter(affectedSet::contains)
                .count();
        body.put("prompts", prompts);
        // 声明里涉及这些域的工具条目数（删域会从声明里剥掉这些域，工具可能因此变不可见）
        long toolDeclarations = toolRouter.toolDescriptors().stream()
                .filter(t -> t.declaredDomains().stream().anyMatch(affectedSet::contains))
                .count();
        body.put("toolDeclarations", toolDeclarations);
        // 在飞会话数（只计数；真删时先停再删，见 AdminDomainController#delete）
        long running = cancellationRegistry.runningKeys().stream()
                .map(SessionKeys::domainOf)
                .filter(affectedSet::contains)
                .count();
        body.put("runningSessions", running);
        return body;
    }

    /**
     * 域来源：根域 / 人工创建 / 工具声明派生（三种同级，不构成等级）。
     *
     * 两处来源合并判断：{@code DomainRegistry} 管内置与人工，派生域归 {@code ToolRegistry}。
     */
    private String domainSource(String domain) {
        DomainRegistry.Source source = domainRegistry.sourceOf(domain);
        // 未登记的域只可能是尚未被扫描进来的工具声明，按派生计
        return source == null ? DomainRegistry.Source.DERIVED.name() : source.name();
    }

    /** 来源的中文标签（给管控台直接用，避免前端各写一份映射） */
    private static String sourceLabel(String source) {
        return switch (source) {
            case "BUILTIN" -> "根域";
            case "MANUAL" -> "人工创建";
            default -> "工具派生";
        };
    }

    /**
     * 「在线实例」：谁在提供工具、它还活着吗。
     */
    @GetMapping("/instances")
    public Map<String, Object> instances() {
        List<InstanceSession> sessions = instanceRegistry.all().stream()
                .sorted(Comparator.comparing(InstanceSession::instanceId))
                .toList();

        int online = 0;
        int draining = 0;
        int muted = 0;
        int forceOffline = 0;
        List<Map<String, Object>> list = new ArrayList<>();
        for (InstanceSession session : sessions) {
            if (session.state() == InstanceState.ONLINE) {
                online++;
            } else if (session.state() == InstanceState.MUTED) {
                muted++;
            } else if (session.state() == InstanceState.DRAINING) {
                draining++;
            } else if (session.state().isRejecting()) {
                forceOffline++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", session.instanceId());
            item.put("endpoint", session.endpoint());
            item.put("state", session.state().name());
            item.put("stateLabel", session.state().label());
            item.put("rejecting", session.state().isRejecting());
            item.put("muted", session.state() == InstanceState.MUTED);
            item.put("lastSeen", session.lastSeen());
            item.put("silentMillis", session.silentMillis(System.currentTimeMillis()));
            item.put("toolCount", session.toolNames().size());
            item.put("toolNames", List.copyOf(new TreeSet<>(session.toolNames())));
            // 摘要只给前 12 位：它的用途是"排查时确认两端看到的是不是同一份 manifest"，
            // 不需要完整值（完整值 64 位只会把表格撑开）
            String digest = session.manifestDigest();
            item.put("digest", digest == null ? "" : digest.substring(0, Math.min(12, digest.length())));
            list.add(item);
        }

        // 副本总数：注册表里所有非本地副本之和（与"逻辑工具数"是两个量，都要看得见）
        int replicas = toolRegistry.all().stream()
                .mapToInt(r -> (int) r.endpoints().stream().filter(e -> !e.isLocal()).count())
                .sum();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("instanceCount", sessions.size());
        stats.put("onlineCount", online);
        stats.put("mutedCount", muted);
        stats.put("drainingCount", draining);
        stats.put("forceOfflineCount", forceOffline);
        stats.put("registeredToolCount", toolRegistry.size());
        stats.put("remoteReplicaCount", replicas);
        stats.put("timeoutSeconds", instanceLifecycle.timeoutMillis() / 1000);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stats", stats);
        body.put("instances", list);
        return body;
    }

    /**
     * 熔断一个工具实例：不断心跳，只摘副本。
     */
    @PostMapping("/instances/{instanceId}/mute")
    public Map<String, Object> muteInstance(@PathVariable String instanceId) {
        int tools = toolRegistry.toolsOfInstance(instanceId).size();
        boolean muted = instanceLifecycle.mute(instanceId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", muted);
        body.put("instanceId", instanceId);
        body.put("removedToolCount", muted ? tools : 0);
        body.put("message", muted
                ? "已熔断：摘除 " + tools + " 个工具条目的副本（心跳仍受理，可在控制台「恢复」）"
                : "该实例不在在线表中，或已被强制下线");
        return body;
    }

    /**
     * 解除熔断：副本在下一次心跳到来时重建。
     */
    @PostMapping("/instances/{instanceId}/restore")
    public Map<String, Object> restoreInstance(@PathVariable String instanceId) {
        boolean restored = instanceLifecycle.restore(instanceId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", restored);
        body.put("instanceId", instanceId);
        body.put("message", restored
                ? "已解除熔断：下一次心跳会重建副本（会话不受影响）"
                : "该实例不在在线表中，或当前不是熔断状态");
        return body;
    }

    /**
     * 强制下线一个工具实例。
     */
    @PostMapping("/instances/{instanceId}/offline")
    public Map<String, Object> forceOfflineInstance(@PathVariable String instanceId) {
        int tools = toolRegistry.toolsOfInstance(instanceId).size();
        boolean kicked = instanceLifecycle.forceOffline(instanceId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", kicked);
        body.put("instanceId", instanceId);
        body.put("removedToolCount", kicked ? tools : 0);
        body.put("message", kicked
                ? "已强制下线：摘除 " + tools + " 个工具条目的副本，其下次心跳将收到 410（会话不受影响）"
                : "该实例不在在线表中（可能已自然下线）");
        return body;
    }

    /**
     * 「域空间」里一个工具的统一呈现字段（全局列表视角，无域上下文）。
     */
    private Map<String, Object> toolView(ToolDescriptor d) {
        return toolView(d, null);
    }

    /**
     * 「域空间」里一个工具的统一呈现字段（某域视角）。
     *
     * @param domain 当前展示的域；{@code null} = 全局列表（不解析继承来源）
     */
    private Map<String, Object> toolView(ToolDescriptor d, String domain) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", d.name());
        t.put("description", d.description());
        t.put("category", d.category());
        t.put("version", d.version());
        t.put("sideEffect", d.sideEffect() == null ? null : d.sideEffect().name());
        t.put("idempotent", d.idempotent());
        t.put("toModel", d.toModel());
        t.put("requiresApproval", d.requiresApproval());
        t.put("approvalMode", d.approval() == null ? "NONE" : d.approval().mode());
        t.put("approvalReason", d.approval() == null ? "" : d.approval().reason());
        t.put("paramCount", d.params() == null ? 0 : d.params().size());
        t.put("provider", providerOf(d.source()));
        t.put("source", d.source());
        // 该工具是否"只声明了一个域"：一眼看出域的专属能力，还是全域通用能力
        t.put("exclusive", d.domains() != null && d.domains().size() == 1);
        // 该工具对本域的授权来自链上哪一段：声明域 ∩ 域链的最深命中（含自身）。
        // 恰好命中自身 = 本域直接授权；命中祖先 = 沿链继承（"挂父域、子域默认可用"）。
        // null 只会出现在全局列表（无域上下文）——不可见的工具进不来这个列表。
        t.put("inheritedFrom", deepestDeclared(d, domain));
        return t;
    }

    /**
     * 工具声明域里，命中该域链最深的那一段（链由根向下，最后一个命中即最深）。
     *
     * 与 {@link ToolDescriptor#visibleIn} 同一套判定，只是把"是否可见"换成"授权来自哪一层"：
     * 可见性是一票通过（任一声明命中即可见），归属取最具体的那个声明。
     */
    private String deepestDeclared(ToolDescriptor d, String domain) {
        if (domain == null) {
            return null;
        }
        List<String> chain = Domains.chainOf(Domains.normalize(domain));
        List<String> declared = d.declaredDomains();
        String hit = null;
        for (String step : chain) {
            if (declared.contains(step)) {
                hit = step;
            }
        }
        return hit;
    }

    // ===== 存储配置（连接 / Redis） =====
    //
    // 这里只剩"连哪里"（具体 ES / Qdrant 实例 + Redis）。知识体的增删改已搬到知识库页
    // （`/admin/bodies`）：体是数据实体（唯一名 / 冻结索引 / 两路落点 / 原文落点 / 文档），
    // 与"连接池"不是同一层的东西，放在存储页只会让两个页各管一半。
    //
    // 域用哪个知识体不在这里：那是域自己的声明，见 AdminDomainController。

    /**
     * 读取存储配置（口令脱敏）
     */
    @GetMapping("/infra")
    public Map<String, Object> infra() {
        InfraSettings cur = infraHolder.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("redis", redisBody(cur.getRedis()));

        List<Map<String, Object>> connections = new ArrayList<>();
        cur.connectionsOrEmpty().forEach((id, c) -> {
            if (c != null) {
                connections.add(connectionBody(c));
            }
        });
        body.put("connections", connections);

        /* 知识体只在这里<b>只读回显</b>：增删改在知识库页（/admin/bodies）。
           存储页只管"连接池 + Redis"，体是数据实体，不该由连接页来建。 */
        List<Map<String, Object>> bodies = new ArrayList<>();
        cur.bodiesOrEmpty().forEach((id, b) -> {
            if (b != null) {
                bodies.add(bodyBody(b, cur));
            }
        });
        body.put("bodies", bodies);
        body.put("bodiesManagedAt", "/admin/bodies");
        body.put("configured", cur.isConfigured());

        body.put("esConfigured", infraHolder.isEsConfigured());
        body.put("redisConfigured", infraHolder.isRedisConfigured());
        body.put("vectorSlotConfigured", infraHolder.isVectorSlotConfigured());
        body.put("esSource", infraHolder.esSource());
        body.put("redisSource", infraHolder.redisSource());
        body.put("vectorSlotSource", infraHolder.vectorSlotSource());
        body.put("indexPattern", KbIndexes.WILDCARD);
        body.put("settingsFile", infraSettingsStore.filePath());
        body.put("supportedTypes", InfraSettings.SUPPORTED_TYPES);
        return body;
    }

    /** 一个具名连接：类型 + 地址 + 脱敏口令。前端按 type 决定渲染哪张表单 */
    private static Map<String, Object> connectionBody(InfraSettings.Connection c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.effectiveName());
        m.put("type", c.effectiveType());
        m.put("usage", c.effectiveUsage());
        m.put("usable", c.isUsable());
        m.put("location", c.location());
        m.put("description", c.describe());
        if (c.getEs() != null) {
            m.put("es", esBody(c.getEs()));
        }
        if (c.getVector() != null) {
            m.put("vector", vectorStoreBody(c.getVector()));
        }
        return m;
    }

    /**
     * 一个知识体：两路各可选，每路一条连接。
     *
     * <p>每路单独下发 {@code {slot}Type / {slot}Location / {slot}Missing}，而不是合成一个
     * "这个体可不可用"：页面要能画出"关键字那台没了、向量还在"这种半残状态，让用户知道
     * 该修哪一行，而不是整块变红。</p>
     */
    private static Map<String, Object> bodyBody(InfraSettings.KnowledgeBody b, InfraSettings all) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("name", b.effectiveName());
        // 唯一名与冻结索引跟着体走：存储页只读展示，知识库页才是管理入口
        m.put("slug", b.effectiveSlug());
        m.put("index", b.effectiveIndex());
        m.put("raw", b.rawLocation());
        m.put("keyword", b.keywordId());
        m.put("vector", b.vectorId());
        m.put("modes", b.modes());
        m.put("hybrid", b.hybrid());
        // 整块可用 = 至少一路连得上；configured=false 表示这个体空着（一路都没配）
        m.put("configured", b.configured());
        m.put("usable", b.configured() && all.usableBody(b.getId()) != null);
        putSlot(m, "keyword", b.keywordId(), all);
        putSlot(m, "vector", b.vectorId(), all);
        return m;
    }

    /** 一路的下发形态：配了谁、连的是哪台、那台还行不行 */
    private static void putSlot(Map<String, Object> target, String slot, String connectionId,
                                InfraSettings all) {
        InfraSettings.Connection c = all.connection(connectionId);
        String prefix = "keyword".equals(slot) ? "keyword" : "vector";
        target.put(prefix + "Type", c == null ? null : c.effectiveType());
        target.put(prefix + "Location", c == null ? null : c.location());
        // 填了 id 但取不到连接，或者连接没填地址 → 这一路是坏的
        target.put(prefix + "Missing", connectionId != null && (c == null || !c.isUsable()));
    }

    private static Map<String, Object> vectorStoreBody(InfraSettings.VectorStore vs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uri", vs.getUri());
        m.put("tokenMasked", vs.getMaskedToken());
        m.put("tokenSet", hasText(vs.getToken()));
        m.put("collectionPrefix", vs.getCollectionPrefix());
        m.put("metric", vs.effectiveMetric());
        return m;
    }

    private static Map<String, Object> esBody(InfraSettings.Elasticsearch es) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", es.getHost());
        m.put("port", es.getPort());
        m.put("scheme", es.getScheme());
        m.put("username", es.getUsername());
        m.put("passwordMasked", es.getMaskedPassword());
        m.put("passwordSet", hasText(es.getPassword()));
        m.put("connectTimeout", es.getConnectTimeout());
        m.put("socketTimeout", es.getSocketTimeout());
        return m;
    }

    private static Map<String, Object> redisBody(InfraSettings.Redis redis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", redis.getHost());
        m.put("port", redis.getPort());
        m.put("passwordMasked", redis.getMaskedPassword());
        m.put("passwordSet", hasText(redis.getPassword()));
        m.put("database", redis.getDatabase());
        return m;
    }

    /**
     * 保存存储配置并立即生效（无需重启）。
     *
     * <p>整份覆盖：前端把当前页面上的<b>全部连接</b>、Redis 一起提交。不做增量补丁是有意的——
     * "少传一条就当没动过"会让界面上删掉的行在重启后复活。</p>
     *
     * <p><b>知识体不在这份请求里</b>（v2）：它们的增删改走知识库页的 {@code /admin/bodies}。
     * 旧版请求若仍带 {@code bodies}，按"带了就整份替换"处理以兼容；不带则沿用当前配置——
     * 否则存储页保存一次连接，就会把所有知识体清空。</p>
     *
     * <p>请求体里连接是<b>数组</b>（页面上就是一张表），落盘时才转成以 id 为键的对象——
     * 人读配置文件时按 id 找比按数组下标找方便。</p>
     */
    @PostMapping("/infra")
    public Map<String, Object> saveInfra(@RequestBody InfraSaveRequest request) {
        InfraSettings incoming = new InfraSettings();
        incoming.setConfigured(true);
        incoming.setRedis(request.redis());
        incoming.setConnections(asConnectionMap(request.connections()));
        if (request.bodies() == null) {
            // 存储页不再管理知识体：不传 = 保留现状（否则保存连接会把体全部清空）
            incoming.setBodies(infraHolder.current().bodiesOrEmpty());
        } else {
            incoming.setBodies(asBodyMap(request.bodies()));
        }
        // 同上：入口回显降 debug，结果由 InfraSettingsHolder 的"生效配置"与下方的"被拒绝"承担
        log.debug("[管控] 收到存储配置保存请求：连接 {} 个，知识体 {} 个，Redis={}",
                incoming.connectionsOrEmpty().size(),
                incoming.bodiesOrEmpty().size(),
                incoming.getRedis() == null ? "-" : incoming.getRedis().describe());
        try {
            infraHolder.apply(incoming);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[管控] 存储配置被拒绝: {}", e.getMessage());
            return failBody(e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("esConfigured", infraHolder.isEsConfigured());
        result.put("redisConfigured", infraHolder.isRedisConfigured());
        result.put("vectorSlotConfigured", infraHolder.isVectorSlotConfigured());
        result.put("esSource", infraHolder.esSource());
        result.put("redisSource", infraHolder.redisSource());
        result.put("vectorSlotSource", infraHolder.vectorSlotSource());
        /* 保存只做"落盘 + 热替换"，不在这里发任何远程请求。
           以前这里会顺带跑 Redis 持久化自检（一条 CONFIG GET，Redis 慢时能拖十几秒，
           前端表现为"一直保存不上"）和知识库能力自检（把一条配置错误放大成保存失败）。
           检测一律挪到真正要用的地方：
             · 连通性 → 用户点「测试连接」走 /admin/infra/test；
             · 能力是否够用 → 检索/灌库那一轮，RetrievalPlan.backendFor 会跳过不合格的那一路并留错误日志。
           能力自检仍跑一次，但只写日志、不回传给前端，避免"已保存，但有 N 条提醒"把人吓退。 */
        logBackendCapability();
        result.put("warnings", List.of());
        result.put("message", "存储配置已保存并立即生效");
        fillSecretState(result);
        return result;
    }

    /**
     * 保存后顺手记一条能力结论，<b>只进日志</b>。
     *
     * <p>纯内存判断（不连任何后端），所以放在保存路径上不会有性能代价；但它回答的是
     * "这份配置真跑起来够不够用"，属于调用期才该拦的事，因此不阻断保存、也不回传前端。</p>
     */
    private void logBackendCapability() {
        if (knowledgeBackendRegistry.isEmpty()) {
            return;
        }
        try {
            knowledgeBackendValidator.validate();
        } catch (Exception e) {
            log.warn("[管控] 存储配置已保存，但知识库能力自检未通过（不影响保存，检索时会跳过该路）: {}",
                    e.getMessage());
        }
    }

    /** 数组 → 以 id 为键的表；id 为空/重复直接抛（保存前就该拦住，不能落成半份配置） */
    private static Map<String, InfraSettings.Connection> asConnectionMap(
            List<InfraSettings.Connection> list) {
        Map<String, InfraSettings.Connection> map = new LinkedHashMap<>();
        if (list == null) {
            return map;
        }
        for (InfraSettings.Connection c : list) {
            if (c == null || c.getId() == null || c.getId().isBlank()) {
                throw new IllegalArgumentException("连接缺少名称（id 不能为空）");
            }
            if (map.putIfAbsent(c.getId().trim(), c) != null) {
                throw new IllegalArgumentException("连接名称重复：「" + c.getId() + "」");
            }
        }
        return map;
    }

    /** 数组 → 以 id 为键的表；id 为空/重复直接抛（保存前就该拦住，不能落成半份配置） */
    private static Map<String, InfraSettings.KnowledgeBody> asBodyMap(
            List<InfraSettings.KnowledgeBody> list) {
        Map<String, InfraSettings.KnowledgeBody> map = new LinkedHashMap<>();
        if (list == null) {
            return map;
        }
        for (InfraSettings.KnowledgeBody b : list) {
            if (b == null || b.getId() == null || b.getId().isBlank()) {
                throw new IllegalArgumentException("知识体缺少 id（域绑定时要靠它引用）");
            }
            if (map.putIfAbsent(b.getId().trim(), b) != null) {
                throw new IllegalArgumentException("知识体 id 重复：「" + b.getId() + "」");
            }
        }
        return map;
    }

    /**
     * 存储配置保存的请求体：连接与知识体按数组传（页面上就是表与卡片）。
     */
    public record InfraSaveRequest(InfraSettings.Redis redis,
                                   List<InfraSettings.Connection> connections,
                                   List<InfraSettings.KnowledgeBody> bodies) {
    }

    /**
     * 回传口令掩码/是否已设置。
     */
    private void fillSecretState(Map<String, Object> body) {
        InfraSettings cur = infraHolder.current();
        body.put("redisPasswordMasked", cur.getRedis().getMaskedPassword());
        body.put("redisPasswordSet", hasText(cur.getRedis().getPassword()));
        List<Map<String, Object>> secrets = new ArrayList<>();
        cur.connectionsOrEmpty().forEach((id, c) -> {
            if (c == null) {
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            if (c.getEs() != null) {
                m.put("passwordMasked", c.getEs().getMaskedPassword());
                m.put("passwordSet", hasText(c.getEs().getPassword()));
            }
            if (c.getVector() != null) {
                m.put("tokenMasked", c.getVector().getMaskedToken());
                m.put("tokenSet", hasText(c.getVector().getToken()));
            }
            secrets.add(m);
        });
        body.put("connectionSecrets", secrets);
    }

    /**
     * 连接测试（保存前先验，避免填错后才发现）。
     *
     * @param type 测哪种：{@code es}（默认）/ {@code qdrant}（旧名 {@code vector} 仍认）/ {@code redis}
     */
    @PostMapping("/infra/test")
    public Map<String, Object> testInfra(@RequestBody(required = false) InfraTestRequest request,
                                         @RequestParam(defaultValue = "es") String type) {
        InfraTestRequest req = request == null ? new InfraTestRequest(null, null) : request;
        InfraSettings.Connection conn = req.connection();
        if (conn != null && conn.getId() == null) {
            conn.setId("");
        }
        // 口令留空 = 沿用已存的那份：按连接 id 补齐（编辑时不会把已存的密码冲掉）
        InfraSettings probe = new InfraSettings();
        if (conn != null) {
            Map<String, InfraSettings.Connection> one = new LinkedHashMap<>();
            one.put(conn.getId(), conn);
            probe.setConnections(one);
        }
        probe.setRedis(req.redis());
        infraHolder.fillBlankSecrets(probe);

        String kind = type == null ? "es" : type.trim().toLowerCase();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", kind);
        try {
            if ("redis".equals(kind)) {
                InfraSettingsHolder.RedisProbe probeResult =
                        infraHolder.testRedis(probe.getRedis(), redisProperties);
                /* 持久化 / 淘汰策略自检跟着「测试连接」走：它本来就要连 Redis，顺带的边际成本
                   几乎为零；放在保存路径上则是每次保存都多一次 CONFIG GET（Redis 慢时能拖十几秒，
                   前端表现为"一直保存不上"）。只在"被测的正是当前生效那台"时给结论——
                   否则拿到的是旧连接的自检结果，比不给更误导。 */
                List<String> audit = sameRedisTarget(probe.getRedis(),
                        infraHolder.current().getRedis())
                        ? redisPersistenceAudit.audit() : List.of();
                result.put("success", true);
                result.put("version", probeResult.version());
                result.put("mode", probeResult.mode());
                result.put("database", probeResult.database());
                result.put("databases", probeResult.databases());
                result.put("dbSize", probeResult.dbSize());
                result.put("note", probeResult.note());
                result.put("warnings", audit);
                String msg = "Redis 连接正常：版本 " + text(probeResult.version())
                        + "（" + modeLabel(probeResult.mode()) + "），库 " + probeResult.database()
                        + " 当前有 " + text(probeResult.dbSize()) + " 个 key";
                if (!audit.isEmpty()) {
                    msg = msg + "；注意：" + String.join("；", audit);
                }
                result.put("message", msg);
            } else if ("vector".equals(kind) || "qdrant".equals(kind)) {
                InfraSettingsHolder.VectorStoreProbe probeResult =
                        infraHolder.testVectorStore(conn == null ? null : conn.getVector());
                result.put("success", true);
                result.put("version", probeResult.version());
                result.put("metric", probeResult.metric());
                result.put("collectionCount", probeResult.collectionCount());
                result.put("note", probeResult.note());
                result.put("message", probeResult.note() == null
                        ? "向量库连接正常" : probeResult.note());
            } else {
                InfraSettingsHolder.EsProbe probeResult =
                        infraHolder.testEs(conn == null ? null : conn.getEs());
                result.put("success", true);
                result.put("clusterName", probeResult.clusterName());
                result.put("version", probeResult.version());
                result.put("buildFlavor", probeResult.buildFlavor());
                result.put("compatibility", compatBody(probeResult.compatibility()));
                Map<String, Object> ik = new LinkedHashMap<>();
                ik.put("available", probeResult.ikAvailable());
                ik.put("note", probeResult.ikNote());
                result.put("ik", ik);
                result.put("indexPattern", KbIndexes.WILDCARD);
                result.put("message", "Elasticsearch 连接正常：集群 " + text(probeResult.clusterName())
                        + "（版本 " + text(probeResult.version()) + "）");
            }
        } catch (Exception e) {
            String reason = e.getMessage() == null ? "未知原因" : e.getMessage();
            log.warn("[管控] {} 连通性测试失败: {}", kind, reason);
            result.put("success", false);
            result.put("message", CONNECT_FAILED + "：" + reason);
            result.put("error", reason);
        }
        return result;
    }

    /**
     * 连接测试的请求体：一个候选连接，或一份候选 Redis。
     *
     * <p>用"候选值"而不是"已存的值"来测，是因为用户会先填再点测试，此时还没保存。</p>
     */
    public record InfraTestRequest(InfraSettings.Connection connection, InfraSettings.Redis redis) {
    }

    /** 兼容性判读的传输形态（前端只认 tone 上色 + label 展示 + note/hint 展开） */
    private static Map<String, Object> compatBody(EsCompatibility.Verdict verdict) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", verdict.level().name());
        m.put("tone", verdict.tone());
        m.put("label", verdict.label());
        m.put("note", verdict.note());
        m.put("hint", verdict.hint());
        return m;
    }

    /**
     * 两份 Redis 配置是不是同一台机器上的同一个库。
     *
     * <p>持久化自检查的是<b>当前生效</b>那条连接（它拿的是全局 template）；用户改完地址后
     * 点「测试连接」，测的是新地址，此时拿旧连接的自检结论回来比不给更误导。</p>
     */
    private static boolean sameRedisTarget(InfraSettings.Redis a, InfraSettings.Redis b) {
        if (a == null || b == null) {
            return false;
        }
        return String.valueOf(a.getHost()).trim()
                .equalsIgnoreCase(String.valueOf(b.getHost()).trim())
                && a.effectivePort() == b.effectivePort()
                && a.effectiveDatabase() == b.effectiveDatabase();
    }

    /** Redis 拓扑翻成中文；读不到就如实说读不到 */
    private static String modeLabel(String mode) {
        if (mode == null || mode.isBlank()) {
            return "拓扑未知";
        }
        return switch (mode.trim().toLowerCase()) {
            case "standalone" -> "单机";
            case "cluster" -> "集群";
            case "sentinel" -> "哨兵";
            default -> mode;
        };
    }

    // ===== 提示词设定 =====
    //
    // 提示词 = 公共基线 + 域差异，落盘 config/prompts.json（控制台 > yaml）。
    // 域的【存在性】有两个来源：DomainRegistry（内置 default 与人工创建的域）与工具声明的派生域
    // （ToolRegistry）；本页只读不改域本身，增删域在「域空间」。

    /**
     * 读取提示词设定。
     *
     * 没有公共基线：根域 {@code default} 的片段就是基底，生效提示词沿域链从根拼接。
     * 每个域额外带上它的祖先链，管控台要能看出"这段是从哪继承来的"。
     */
    @GetMapping("/prompts")
    public Map<String, Object> prompts() {
        DomainSettings console = domainSettingsStore.load();
        Map<String, Object> body = new LinkedHashMap<>();

        // 编辑框里填"生效值"：控制台没写的用 yaml 兜底，避免用户以为空就是没配
        body.put("prompts", effectivePromptMap(console));

        Set<String> known = new TreeSet<>(toolRouter.knownDomains());
        List<Map<String, Object>> domains = new ArrayList<>();
        DomainSystemPromptResolver resolver =
                new DomainSystemPromptResolver(domainSettingsStore, promptProperties);
        for (String domain : known) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", domain);
            d.put("chain", Domains.chainOf(domain));
            d.put("ancestors", Domains.ancestorsOf(domain));
            // 工具清单带 source（哪个 Bean#方法提供的）："域从哪来"的答案就在这儿
            List<Map<String, Object>> tools = new ArrayList<>();
            toolRouter.toolSpecifications(domain).stream()
                    .map(ToolSpecification::name)
                    .sorted()
                    .forEach(name -> {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("name", name);
                        t.put("source", toolRouter.toolDescriptors().stream()
                                .filter(desc -> desc.name().equals(name))
                                .map(ToolDescriptor::source)
                                .findFirst().orElse("-"));
                        tools.add(t);
                    });
            d.put("tools", tools);
            d.put("providerCount", tools.stream()
                    .map(t -> providerOf(String.valueOf(t.get("source"))))
                    .distinct()
                    .count());
            String own = firstNonBlank(console.domainPrompt(domain),
                    promptProperties.domainPrompt(domain));
            d.put("prompt", own == null ? "" : own);
            d.put("hasPrompt", own != null && !own.isBlank());
            d.put("promptLength", own == null ? 0 : own.trim().length());
            // 预览是沿链拼接后的全文，不是本域片段
            d.put("preview", resolver.preview(domain, console));
            domains.add(d);
        }
        body.put("domains", domains);

        List<String> orphans = new ArrayList<>(console.getPrompts().keySet());
        orphans.removeAll(known);
        Collections.sort(orphans);
        body.put("orphanPrompts", orphans);

        body.put("settingsFile", domainSettingsStore.filePath());
        return body;
    }

    /**
     * 保存提示词设定，立即生效（不重启）
     */
    @PostMapping("/prompts")
    public Map<String, Object> savePrompts(@RequestBody(required = false) DomainSettings incoming) {
        DomainSettings toSave = normalize(incoming);
        // toolCallEcho / planDisplay 不归提示词页管：那页不发这两个字段，而保存是整份覆盖写：
        // 不合并的话，在提示词页点一次保存就会把各域的工具执行信息回写开关冲回默认（关）。
        toSave.setToolCallEcho(domainSettingsStore.load().getToolCallEcho());
        toSave.setPlanDisplay(domainSettingsStore.load().getPlanDisplay());
        // bodyBindings 同理不归提示词页管：整份覆盖写会把内置/派生域的知识体绑定冲掉，
        // 表现为"在提示词页点一次保存，域空间的知识体绑定就没了"
        toSave.setBodyBindings(domainSettingsStore.load().getBodyBindings());
        // 域必须已登记：提示词是域的属性，给树里不存在的域存片段只会变成永远不生效的孤儿
        List<String> unknown = toSave.getPrompts().keySet().stream()
                .filter(domain -> !domainRegistry.contains(domain))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "域不存在：" + unknown + "（已登记的域: " + domainRegistry.ids() + "）；"
                            + "请先在「域空间」把这些域创建出来");
        }
        // 入口回显降 debug：DomainSettingsStore 保存成功时会打"已保存到 …：域片段 N 个"
        log.debug("[管控] 收到域提示词保存请求: {} 个域片段", toSave.getPrompts().size());
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            domainSettingsStore.save(toSave);
            result.put("success", true);
            result.put("message", "已保存并生效：下一次对话即使用新提示词");
            result.put("settingsFile", domainSettingsStore.filePath());
        } catch (Exception e) {
            log.error("[管控] 域提示词保存失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "保存失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 归一化后再落盘。
     */
    private static DomainSettings normalize(DomainSettings incoming) {
        DomainSettings result = new DomainSettings();
        if (incoming == null) {
            return result;
        }
        if (incoming.getPrompts() != null) {
            incoming.getPrompts().forEach((k, v) -> {
                if (k == null || k.isBlank() || v == null || v.isBlank()) {
                    return;
                }
                String domain = k.trim();
                // 键必须是完整路径域：写成短名的片段永远不会命中任何域，
                // 静默丢弃会让用户以为配过了：直接跳过并在返回里列为孤儿键
                String reason = Domains.validatePath(domain);
                if (reason == null) {
                    result.getPrompts().put(domain, v);
                }
            });
        }
        return result;
    }

    /** 控制台值优先，未配置时回落 yaml（编辑框要显示"实际会生效的那份"） */
    private Map<String, String> effectivePromptMap(DomainSettings console) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (promptProperties.getPrompts() != null) {
            merged.putAll(promptProperties.getPrompts());
        }
        console.getPrompts().forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                merged.put(k, v);
            }
        });
        // 存在的域即使没配提示词也要出现，否则前端渲染不出"待补提示词"的行
        for (String domain : toolRouter.knownDomains()) {
            merged.putIfAbsent(domain, "");
        }
        /* 编辑框里不放未登记的域：保存是整份覆盖，放进来等于让前端把孤儿键反复写回去，
           而且保存入口现在会拒绝未登记的域：留着它们会把整个页面卡在"保存失败"上。
           孤儿键仍然通过 orphanPrompts 字段只读地暴露出来。 */
        merged.keySet().removeIf(domain -> !domainRegistry.contains(domain));
        return merged;
    }

    private static String firstNonBlank(String consoleValue, String yamlValue) {
        return consoleValue == null || consoleValue.isBlank() ? yamlValue : consoleValue;
    }

    /** 从 {@code com.foo.Bean#method} 取提供者部分，用于"这个域由谁贡献"的分组展示 */
    private static String providerOf(String source) {
        if (source == null || source.isBlank()) {
            return "-";
        }
        int hash = source.indexOf('#');
        return hash > 0 ? source.substring(0, hash) : source;
    }

    private static String text(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
