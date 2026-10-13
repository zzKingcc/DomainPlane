package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.common.exception.BaseException;
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
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.embedding.EmbeddingModel;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zzkingcc.domainplane.server.knowledge.ChunkExporter;
import com.zzkingcc.domainplane.server.knowledge.KnowledgeBaseService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
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
    private final ModelProfileRegistry modelProfileRegistry;

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
                           DomainRegistry domainRegistry,
                           ModelProfileRegistry modelProfileRegistry) {
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
        this.domainRegistry = domainRegistry;
        this.modelProfileRegistry = modelProfileRegistry;
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
        body.put("settingsFile", settingsStore.filePath());
        return body;
    }

    /**
     * 保存模型设置并立即生效（无需重启）
     *
     * @param rebuildIndex 管控台已二次确认"维度变化、同意重建索引"时为 {@code true}
     */
    @PostMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody LlmSettings settings,
                                            @RequestParam(defaultValue = "false") boolean rebuildIndex) {
        log.info("[管控] 收到模型设置保存请求: chatBaseUrl={}, chatModel={}, embeddingModel={}, rebuildIndex={}",
                settings.getChatBaseUrl(), settings.getChatModelName(),
                settings.getEmbeddingModelName(), rebuildIndex);
        // Key 留空表示"不修改"，用已保存的值补齐——避免管控台不发明文导致 Key 被清空
        fillBlankKeysFromCurrent(settings);

        DimCheck dim = checkEmbeddingDimension(settings);
        if (dim.verdict() == DimVerdict.DECLARED_MISMATCH) {
            // 声明了一个模型不支持的维度：保存下去必然灌库失败，直接拒绝（不受重建开关影响）
            Map<String, Object> fail = failBody(dim.message());
            fail.put("declaredDimension", dim.declaredDims());
            fail.put("newDimension", dim.newDims());
            return fail;
        }
        if (dim.verdict() == DimVerdict.NEEDS_REBUILD && !rebuildIndex) {
            // 维度变了但用户还没确认重建 → 本次保存不生效，配置保持原样
            Map<String, Object> fail = failBody(dim.message());
            fail.put("requiresRebuild", true);
            fail.put("indexDimension", dim.indexDims());
            fail.put("newDimension", dim.newDims());
            return fail;
        }

        try {
            modelHolder.apply(settings);
        } catch (IllegalStateException e) {
            return failBody(e.getMessage());
        }

        // 维度变化且用户已确认 → 紧接着重建索引（embeddingModel 是持有者委派代理，apply 后已指向新模型）。
        boolean rebuilt = false;
        if (dim.verdict() == DimVerdict.NEEDS_REBUILD) {
            log.warn("[管控] 维度 {} → {}，开始重建全部知识库索引", dim.indexDims(), dim.newDims());
            try {
                // 维度必须显式传入：索引维度是 mapping 不可变参数，取 apply 后生效的新模型维度。
                // 重建即删旧建新：维度变了旧索引无用，留着会导致灌库失败。
                List<String> rebuiltIndices =
                        knowledgeBaseService.rebuildAll(modelHolder.effectiveEmbeddingDimension());
                rebuilt = true;
                log.info("[管控] 知识库索引重建完成（dims={}，索引 {}）", dim.newDims(), rebuiltIndices);
            } catch (Exception e) {
                // 配置已落盘并生效，只是索引重建失败 —— 必须如实告知，不能报成功
                log.error("[管控] 知识库索引重建失败（配置已生效）: {}", e.getMessage(), e);
                Map<String, Object> fail = failBody(
                        "配置已保存并生效，但知识库索引重建失败：" + e.getMessage()
                                + "。请到「知识库」页重试「触发重建」，重建成功前灌库与检索不可用。");
                fail.put("saved", true);
                fail.put("rebuilt", false);
                fillKeyState(fail);
                return fail;
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
                ? "已保存并立即生效，知识库索引已按新维度 " + dim.newDims() + " 重建"
                : (dim.note() != null ? "已保存并立即生效（" + dim.note() + "）" : "已保存并立即生效"));
        return result;
    }

    /**
     * 清空内置的某个模型配置（{@code kind} = {@code chat} / {@code embedding}）。
     *
     * 只清目标那一组，另一组原样保留。清空后该模型视为「未配置」；
     * 若 yaml 里也没有兜底值，则未绑定任何档案的域将没有可用模型 —— 管控台需二次确认。
     */
    @DeleteMapping("/settings/{kind}")
    public Map<String, Object> clearSettings(@PathVariable("kind") String kind) {
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
            return failBody(e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("chatConfigured", modelHolder.isConfigured() || modelProfileRegistry.hasChatModel());
        result.put("embeddingConfigured", modelHolder.isEmbeddingConfigured());
        fillKeyState(result);
        result.put("message", (embedding ? "向量模型" : "对话模型") + "配置已清空");
        return result;
    }

    /** 保存失败/需确认时的统一响应体 */
    private static Map<String, Object> failBody(String message) {
        Map<String, Object> fail = new LinkedHashMap<>();
        fail.put("success", false);
        fail.put("error", message);
        return fail;
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
                    "向量维度声明与实测不符：声明 " + probe.declaredDimension() + " 维，实际返回 " + newDims
                            + " 维。上游对不支持的维度是静默忽略（不报错），所以这个声明不会被兑现，"
                            + "按它建索引会导致灌库被 ES 拒绝。请改填该模型真正支持的维度。");
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
    public Map<String, Object> testSettings(@RequestBody(required = false) LlmSettings settings,
                                            @RequestParam(defaultValue = "chat") String type) {
        LlmSettings candidate = settings == null ? new LlmSettings() : settings;
        fillBlankKeysFromCurrent(candidate);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if ("embedding".equalsIgnoreCase(type)) {
                testEmbedding(candidate, result);
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
        }
        return result;
    }

    /**
     * 向量测试的完整裁决：连通性 + 维度契约 + 索引一致性
     */
    private void testEmbedding(LlmSettings candidate, Map<String, Object> result) {
        LlmModelHolder.EmbeddingProbe probe = modelHolder.testEmbeddingConnection(candidate);
        int actual = probe.actualDimension();
        Integer declared = probe.declaredDimension();
        Integer indexDims = knowledgeBaseService.currentVectorDims();

        result.put("type", "embedding");
        result.put("dimension", actual);
        result.put("declaredDimension", declared);
        result.put("indexDimension", indexDims);

        if (probe.mismatched()) {
            String detail = "该模型不支持自定义维度：声明 " + declared + " 维，实际返回 " + actual + " 维";
            log.warn("[管控] 向量维度校验未通过：{}", detail);
            result.put("success", false);
            result.put("message", "已连通，但" + detail + "。请填写该模型真正支持的维度，"
                    + "或改用支持自定义维度的模型（具体支持范围请查服务商文档）。");
            result.put("error", detail);
            return;
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
                tools.add(toolView(d));

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
            // 该域实际生效的 SystemMessage 预览（preview 不打日志，见 DomainSystemPromptResolver）
            d.put("promptPreview", resolver.preview(domain, console));
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
        List<String> orphans = new ArrayList<>(console.getPrompts().keySet());
        orphans.removeAll(known);
        Collections.sort(orphans);
        body.put("orphanPrompts", orphans);
        body.put("settingsFile", domainSettingsStore.filePath());
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
     * 「域空间」里一个工具的统一呈现字段。
     */
    private Map<String, Object> toolView(ToolDescriptor d) {
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
        // 该工具是否"只声明了一个域"——一眼看出域的专属能力，还是全域通用能力
        t.put("exclusive", d.domains() != null && d.domains().size() == 1);
        return t;
    }

    // ===== 知识库文档（列表 / 上传 / 删除 / 状态） =====

    /**
     * 已入库文档列表
     */
    @GetMapping("/kb/documents")
    public Map<String, Object> kbDocuments() {
        List<KnowledgeBaseService.DocumentItem> items = knowledgeBaseService.list();
        List<Map<String, Object>> documents = new ArrayList<>(items.size());
        for (KnowledgeBaseService.DocumentItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docId", item.docId());
            row.put("fileName", item.fileName());
            row.put("chunks", item.chunks());
            row.put("domain", item.domain());
            // 切片预览文件的落盘路径（导出目录固定，管控台只负责显示与复制）
            row.put("exportPath", item.exportPath());
            documents.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("count", documents.size());
        body.put("exportDir", chunkExporter.exportDir().toAbsolutePath().toString());
        body.put("documents", documents);
        return body;
    }

    /**
     * 上传一个知识库文档（multipart，字段名 {@code file}）
     *
     * @param replace {@code true} = 该域索引内已存在同名文档时先删旧再写入；{@code false} = 同名直接拒绝（60005）
     * @param domain  该文档归属的域（完整路径，落到该域的索引里；挂某域则其全部后代可检索；留空 → 根域）
     */
    @PostMapping(value = "/kb/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> kbUpload(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "replace", defaultValue = "false") boolean replace,
                                        @RequestParam(value = "domain", required = false) String domain)
            throws IOException {
        KnowledgeBaseService.UploadResult result =
                knowledgeBaseService.upload(file.getBytes(), file.getOriginalFilename(), replace, domain);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("success", true);
        body.put("docId", result.docId());
        body.put("fileName", result.fileName());
        body.put("size", result.size());
        body.put("chunks", result.chunks());
        body.put("domain", result.domain());
        // 切片质量诊断：批量上传时靠这几个数就能发现"这批文件切坏了"
        body.put("sections", result.sections());
        body.put("droppedLines", result.droppedLines());
        body.put("encoding", result.encoding());
        body.put("exportPath", result.exportPath());
        return body;
    }

    /**
     * 删除一个文档的全部切片，并释放其文件名（删除后同名可以再次上传）
     */
    @DeleteMapping("/kb/documents/{docId}")
    public Map<String, Object> kbDelete(@PathVariable String docId) {
        boolean deleted = knowledgeBaseService.delete(docId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("success", true);
        body.put("docId", docId);
        body.put("deleted", deleted);
        return body;
    }

    /**
     * 知识库状态：索引数、文档数、切片总数，以及每个索引（域）的明细。
     */
    @GetMapping("/kb/status")
    public Map<String, Object> kbStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.putAll(knowledgeBaseService.status());
        return body;
    }

    /**
     * 手动重建全部知识库索引（诊断 + 删旧 + 建 IK mapping + 校验）。
     *
     * 语义是「删掉重建」，因此索引会被清空，文档需重新上传。
     */
    @PostMapping("/kb/rebuild")
    public Map<String, Object> rebuildKnowledgeIndex() {
        try {
            int dimensions = modelHolder.effectiveEmbeddingDimension();
            log.info("[管控] 收到知识库重建请求，向量维度 = {}", dimensions);
            List<String> indices = knowledgeBaseService.rebuildAll(dimensions);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 0);
            result.put("success", true);
            result.put("indices", indices);
            result.put("dimensions", dimensions);
            result.put("message", "已按维度 " + dimensions + " 重建 " + indices.size()
                    + " 个索引；索引已清空，请重新上传知识库文档");
            return result;
        } catch (Exception e) {
            log.error("[管控] 知识库重建失败: {}", e.getMessage(), e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    // ===== 存储配置（ES / Redis） =====
    //
    // 连接信息从 yaml 搬到管控台：先在页面上填 → 「测试连接」验证 → 「保存并生效」热替换。
    // 保存的是"界面那一份"，yaml 留空回落；两者都空即"未配置"（服务照常启动，检索与上传报未配置）。

    /**
     * 读取 ES / Redis 存储配置（口令脱敏）
     */
    @GetMapping("/infra")
    public Map<String, Object> infra() {
        InfraSettings cur = infraHolder.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("es", esBody(cur.getEs()));
        body.put("redis", redisBody(cur.getRedis()));
        body.put("esConfigured", infraHolder.isEsConfigured());
        body.put("redisConfigured", infraHolder.isRedisConfigured());
        body.put("esSource", infraHolder.esSource());
        body.put("redisSource", infraHolder.redisSource());
        body.put("indexPattern", KbIndexes.WILDCARD);
        body.put("settingsFile", infraSettingsStore.filePath());
        return body;
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
     * 保存存储配置并立即生效（无需重启）
     */
    @PostMapping("/infra")
    public Map<String, Object> saveInfra(@RequestBody InfraSettings incoming) {
        log.info("[管控] 收到存储配置保存请求: es={}, redis={}",
                incoming.getEs() == null ? "-" : incoming.getEs().describe(),
                incoming.getRedis() == null ? "-" : incoming.getRedis().describe());
        try {
            infraHolder.apply(incoming);
        } catch (IllegalStateException e) {
            return failBody(e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("esConfigured", infraHolder.isEsConfigured());
        result.put("redisConfigured", infraHolder.isRedisConfigured());
        result.put("esSource", infraHolder.esSource());
        result.put("redisSource", infraHolder.redisSource());
        result.put("message", "存储配置已保存并立即生效");
        fillSecretState(result);
        return result;
    }

    /**
     * 回传口令掩码/是否已设置。
     */
    private void fillSecretState(Map<String, Object> body) {
        InfraSettings cur = infraHolder.current();
        body.put("esPasswordMasked", cur.getEs().getMaskedPassword());
        body.put("esPasswordSet", hasText(cur.getEs().getPassword()));
        body.put("redisPasswordMasked", cur.getRedis().getMaskedPassword());
        body.put("redisPasswordSet", hasText(cur.getRedis().getPassword()));
    }

    /**
     * 连接测试（保存前先验，避免填错后才发现）
     * @param type {@code es}（默认）或 {@code redis}
     */
    @PostMapping("/infra/test")
    public Map<String, Object> testInfra(@RequestBody(required = false) InfraSettings settings,
                                         @RequestParam(defaultValue = "es") String type) {
        InfraSettings candidate = settings == null ? new InfraSettings() : settings;
        infraHolder.fillBlankSecrets(candidate);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type.toLowerCase());
        try {
            if ("redis".equalsIgnoreCase(type)) {
                InfraSettingsHolder.RedisProbe probe =
                        infraHolder.testRedis(candidate.getRedis(), redisProperties);
                result.put("success", true);
                result.put("version", probe.version());
                result.put("mode", probe.mode());
                result.put("database", probe.database());
                result.put("databases", probe.databases());
                result.put("dbSize", probe.dbSize());
                result.put("note", probe.note());
                result.put("message", "Redis 连接正常：版本 " + text(probe.version())
                        + "（" + modeLabel(probe.mode()) + "），库 " + probe.database()
                        + " 当前有 " + text(probe.dbSize()) + " 个 key");
            } else {
                InfraSettingsHolder.EsProbe probe = infraHolder.testEs(candidate.getEs());
                result.put("success", true);
                result.put("clusterName", probe.clusterName());
                result.put("version", probe.version());
                result.put("buildFlavor", probe.buildFlavor());
                result.put("compatibility", compatBody(probe.compatibility()));
                Map<String, Object> ik = new LinkedHashMap<>();
                ik.put("available", probe.ikAvailable());
                ik.put("note", probe.ikNote());
                result.put("ik", ik);
                result.put("indexPattern", KbIndexes.WILDCARD);
                result.put("message", "Elasticsearch 连接正常：集群 " + text(probe.clusterName())
                        + "（版本 " + text(probe.version()) + "）");
            }
        } catch (Exception e) {
            String reason = e.getMessage() == null ? "未知原因" : e.getMessage();
            log.warn("[管控] {} 连通性测试失败: {}", type, reason);
            result.put("success", false);
            result.put("message", CONNECT_FAILED + "：" + reason);
            result.put("error", reason);
        }
        return result;
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
            // 工具清单带 source（哪个 Bean#方法提供的）——"域从哪来"的答案就在这儿
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
        // toolCallEcho 不归提示词页管：那页不发这个字段，而保存是整份覆盖写 ——
        // 不合并的话，在提示词页点一次保存就会把各域的工具执行信息回写开关冲回默认（关）。
        toSave.setToolCallEcho(domainSettingsStore.load().getToolCallEcho());
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
        log.info("[管控] 收到域提示词保存请求: {} 个域片段", toSave.getPrompts().size());
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
                // 静默丢弃会让用户以为配过了 —— 直接跳过并在返回里列为孤儿键
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
           而且保存入口现在会拒绝未登记的域 —— 留着它们会把整个页面卡在"保存失败"上。
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
