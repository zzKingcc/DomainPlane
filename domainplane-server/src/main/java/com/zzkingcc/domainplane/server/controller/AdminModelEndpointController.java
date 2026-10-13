package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.model.ModelEndpointClient;
import com.zzkingcc.domainplane.server.model.ModelEndpointException;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import com.zzkingcc.domainplane.server.model.ModelProfileRegistry;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理面：多模态端点（生图 / 重排 / 语音合成 / 语音识别）的调用入口。
 *
 * 与对话 / 向量不同，这四族没有 langchain4j 抽象，由 {@link ModelEndpointClient} 直接调。
 * 这里只负责「取档案 + 校验端点族 + 把请求体转成客户端能吃的入参 + 把结果翻成管控台能用的 JSON」，
 * 不持有任何协议知识。
 *
 * 每个入口都先验档案：档案不存在、或没声明对应端点族（endpoints 不含该族），
 * 直接抛 {@link ModelEndpointException}（{@link ErrorCode#MODEL_ENDPOINT_UNSUPPORTED}），
 * 不让参数流到根本不认它的地址上。
 *
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin/model-endpoints")
public class AdminModelEndpointController {

    private static final Logger log = LoggerFactory.getLogger(AdminModelEndpointController.class);

    private final ModelProfileRegistry registry;
    private final ModelEndpointClient client;

    public AdminModelEndpointController(ModelProfileRegistry registry, ModelEndpointClient client) {
        this.registry = registry;
        this.client = client;
    }

    /**
     * 生图：{@code POST /admin/model-endpoints/image?alias=...}
     */
    @PostMapping("/image")
    public Map<String, Object> generateImage(@RequestParam("alias") String alias,
                                             @RequestBody ImageBody body) {
        ModelProfile profile = resolve(alias, ModelProfile.EP_IMAGES);
        ModelEndpointClient.ImageResult result = client.generateImage(profile,
                new ModelEndpointClient.ImageRequest(body.getPrompt(), body.getSize(), body.getN(),
                        body.getResponseFormat()));
        Map<String, Object> out = base(alias, ModelProfile.EP_IMAGES);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ModelEndpointClient.GeneratedImage img : result.items()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", img.url());
            item.put("b64Json", img.b64Json());
            items.add(item);
        }
        out.put("images", items);
        out.put("count", items.size());
        return out;
    }

    /**
     * 重排：{@code POST /admin/model-endpoints/rerank?alias=...}
     */
    @PostMapping("/rerank")
    public Map<String, Object> rerank(@RequestParam("alias") String alias,
                                     @RequestBody RerankBody body) {
        ModelProfile profile = resolve(alias, ModelProfile.EP_RERANK);
        ModelEndpointClient.RerankResult result = client.rerank(profile,
                new ModelEndpointClient.RerankRequest(body.getQuery(), body.getDocuments(),
                        body.getTopN(), body.getReturnDocuments()));
        Map<String, Object> out = base(alias, ModelProfile.EP_RERANK);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ModelEndpointClient.RerankItem r : result.results()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", r.index());
            item.put("score", r.score());
            item.put("document", r.document());
            items.add(item);
        }
        out.put("results", items);
        return out;
    }

    /**
     * 语音合成：{@code POST /admin/model-endpoints/speech?alias=...}
     *
     * 音频以 base64 随结果返回（管控台可直接预览 / 下载）；二进制直传交给后续文件接口。
     */
    @PostMapping("/speech")
    public Map<String, Object> textToSpeech(@RequestParam("alias") String alias,
                                           @RequestBody SpeechBody body) {
        ModelProfile profile = resolve(alias, ModelProfile.EP_TTS);
        ModelEndpointClient.SpeechResult result = client.textToSpeech(profile,
                new ModelEndpointClient.SpeechRequest(body.getInput(), body.getVoice(),
                        body.getSpeed(), body.getResponseFormat()));
        Map<String, Object> out = base(alias, ModelProfile.EP_TTS);
        out.put("format", result.format());
        out.put("size", result.audio() == null ? 0 : result.audio().length);
        out.put("audioBase64", result.audio() == null ? null
                : Base64.getEncoder().encodeToString(result.audio()));
        return out;
    }

    /**
     * 语音识别：{@code POST /admin/model-endpoints/transcribe?alias=...}
     *
     * 音频以 base64 随请求体传入（与合成对称，便于管控台走 JSON 调用）。
     */
    @PostMapping("/transcribe")
    public Map<String, Object> transcribe(@RequestParam("alias") String alias,
                                         @RequestBody TranscribeBody body) {
        ModelProfile profile = resolve(alias, ModelProfile.EP_ASR);
        if (body.getAudioBase64() == null || body.getAudioBase64().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "语音识别请求缺少 audioBase64");
        }
        byte[] audio;
        try {
            audio = Base64.getDecoder().decode(body.getAudioBase64());
        } catch (IllegalArgumentException e) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "audioBase64 不是合法的 base64");
        }
        ModelEndpointClient.TranscriptionResult result = client.transcribe(profile,
                new ModelEndpointClient.TranscriptionRequest(audio, body.getFilename(),
                        body.getLanguage(), body.getPrompt(), body.getResponseFormat()));
        Map<String, Object> out = base(alias, ModelProfile.EP_ASR);
        out.put("text", result.text());
        out.put("language", result.language());
        return out;
    }

    /**
     * 生视频（提交任务）：{@code POST /admin/model-endpoints/video?alias=...}
     *
     * <p>只提交、不等待：视频生成动辄几十秒到几分钟，管控台点一下不该把连接挂住。
     * 返回任务号，由 {@code /video/status} 查进度。</p>
     */
    @PostMapping("/video")
    public Map<String, Object> createVideo(@RequestParam("alias") String alias,
                                          @RequestBody VideoBody body) {
        ModelProfile profile = resolve(alias, ModelProfile.EP_VIDEO);
        ModelEndpointClient.VideoTask task = client.createVideo(profile,
                new ModelEndpointClient.VideoRequest(body.getPrompt(), body.getSize(), body.getSeconds()));
        Map<String, Object> out = base(alias, ModelProfile.EP_VIDEO);
        putTask(out, task);
        return out;
    }

    /**
     * 查生视频任务：{@code POST /admin/model-endpoints/video/status?alias=...}
     */
    @PostMapping("/video/status")
    public Map<String, Object> videoStatus(@RequestParam("alias") String alias,
                                          @RequestBody VideoStatusBody body) {
        ModelProfile profile = resolve(alias, ModelProfile.EP_VIDEO);
        ModelEndpointClient.VideoTask task = client.videoStatus(profile, body.getTaskId());
        Map<String, Object> out = base(alias, ModelProfile.EP_VIDEO);
        putTask(out, task);
        return out;
    }

    private static void putTask(Map<String, Object> out, ModelEndpointClient.VideoTask task) {
        out.put("taskId", task.taskId());
        out.put("status", task.status());
        out.put("url", task.url());
        out.put("error", task.error());
    }

    // ==================== 内部 ====================
    /**
     * 取档案并校验它声明了要调用的端点族。
     *
     * 端点族是"这份档案能不能干这事"的唯一判据：没声明 images 族的档案，即使底层服务商
     * 支持生图，也不该被拿去发生图请求，那会把"没配置"伪装成"服务商报错"。
     */
    private ModelProfile resolve(String alias, String family) {
        if (alias == null || alias.isBlank()) {
            throw new ModelEndpointException(ErrorCode.INVALID_PARAMETER, "别名不能为空");
        }
        ModelProfile profile = registry.profile(alias.trim())
                .orElseThrow(() -> new ModelEndpointException(ErrorCode.INVALID_PARAMETER,
                        "档案不存在：" + alias.trim()));
        if (!profile.endpoints().contains(family)) {
            throw new ModelEndpointException(ErrorCode.MODEL_ENDPOINT_UNSUPPORTED,
                    "档案 " + profile.alias() + "（" + profile.modelName() + "）未声明「" + family
                            + "」端点族，不能调用该模态。请先在「读取」或表单里把它标记为支持该端点。");
        }
        return profile;
    }

    private static Map<String, Object> base(String alias, String family) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("alias", alias);
        out.put("endpoint", family);
        return out;
    }

    // ==================== 请求体 ====================

    /** 生图请求体（prompt 必填） */
    @Data
    public static class ImageBody {
        private String prompt;
        private String size;
        private Integer n;
        private String responseFormat;
    }

    /** 重排请求体（query / documents 必填） */
    @Data
    public static class RerankBody {
        private String query;
        private List<String> documents;
        private Integer topN;
        private Boolean returnDocuments;
    }

    /** 语音合成请求体（input 必填） */
    @Data
    public static class SpeechBody {
        private String input;
        private String voice;
        private Double speed;
        private String responseFormat;
    }

    /** 语音识别请求体（audioBase64 必填） */
    @Data
    public static class TranscribeBody {
        private String audioBase64;
        private String filename;
        private String language;
        private String prompt;
        private String responseFormat;
    }

    /** 生视频请求体（prompt 必填） */
    @Data
    public static class VideoBody {
        private String prompt;
        private String size;
        private Integer seconds;
    }

    /** 生视频查状态请求体（taskId 必填） */
    @Data
    public static class VideoStatusBody {
        private String taskId;
    }
}
