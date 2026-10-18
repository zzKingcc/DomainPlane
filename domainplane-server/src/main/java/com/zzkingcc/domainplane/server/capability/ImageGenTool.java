package com.zzkingcc.domainplane.server.capability;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore.Kind;
import com.zzkingcc.domainplane.server.model.DefaultModelResolver;
import com.zzkingcc.domainplane.server.model.ModelEndpointClient;
import com.zzkingcc.domainplane.server.model.ModelProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * generateImage —— 把"画一张图"交给同域绑定的生图模型。
 *
 * <p><b>域从哪来、模型是谁</b>：域读本轮上下文，模型沿域链解析 {@code images} 族
 * （见 {@code DefaultModelResolver#endpointModel}）。域没绑生图模型就如实回一句
 * "请到管控台「域空间」绑定"，而不是静默换一个模型画。</p>
 *
 * <p><b>产出落暂存区、返回值只有引用名</b>：生成的图片以 {@code img_xxxxxxxx} 存进
 * {@link MediaAttachmentStore}，与用户上行图片走同一套引用名机制。于是主模型手里
 * 就有了一个可以继续用的东西：可以直接把它写进给用户的回答里（由归档渲染），
 * 也可以调 {@code seeImage} 让视觉模型复核它自己刚生成的图。</p>
 *
 * <p><b>绝不把 base64 拼进返回值</b>：返回值会进工具调用事件与会话记忆，两者都会落库：
 * 一张 1024×1024 的图 base64 上兆，塞进去等于把整段图像数据写进对话历史。</p>
 *
 * <p><b>可见性</b>：挂根域（全树可见），执行时按本轮域校验有没有可用的生图模型。
 * 与 {@code seeImage} 同一口径：让所有域都看见这个工具，比"只有配了生图模型的域才看见"
 * 更好排障：后者在没配的域上表现为"模型根本不知道有这条路"，用户看到的是答非所问。</p>
 *
 * @author zzkingcc
 */
@Component
public class ImageGenTool extends CapabilityToolSupport {

    private static final String TOOL_NAME = "generateImage";

    /** 服务商没给内容类型时的兜底 —— 绝大多数 OpenAI 兼容生图端点的 b64 就是 PNG */
    private static final String DEFAULT_MIME = "image/png";

    public ImageGenTool(DefaultModelResolver resolver,
                        ModelEndpointClient client,
                        com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore store,
                        CapabilityCallExecutor executor) {
        super(resolver, client, store, executor);
    }

    @Tool(value = TOOL_NAME,
            desc = "根据文字描述生成一张或多张图片。"
                    + "当用户要求画图、作图、生成图片 / 插画 / 海报 / 配图，或需要一张图来回答时调用。"
                    + "参数 prompt 填图片内容描述（越具体越好：主体、风格、构图、色调）。"
                    + "生成结果会以图片编号返回，你可以在回答里直接提到这张图，或用 seeImage 复核。"
                    + "本工具依赖当前域已绑定的生图模型；没有时会明确告知，此时不要凭空描述图片内容。",
            effect = Tool.Effect.WRITE)
    public String generate(@ToolParam("图片内容描述：想画什么、什么风格、什么构图。"
            + "例如'一只戴墨镜的柴犬，扁平插画风格，暖色调背景'") String prompt,
                           @ToolParam(value = "图片尺寸，形如 1024x1024 / 1792x1024；"
                                   + "不确定就留空，由服务商默认", required = false) String size) {

        if (isBlank(prompt)) {
            return "没有说明要画什么。请把图片内容描述写在 prompt 参数里。";
        }
        String trimmedSize = isBlank(size) ? null : size.trim();

        return run(TOOL_NAME, "生成图片", ModelProfile.EP_IMAGES, (profile, domain) -> {
            ModelEndpointClient.ImageResult result = client.generateImage(profile,
                    new ModelEndpointClient.ImageRequest(prompt.trim(), trimmedSize, null, null));
            if (result.items().isEmpty()) {
                return "生图模型没有返回任何图片。请如实告知用户这一步没产出结果。";
            }
            List<String> references = new ArrayList<>();
            for (ModelEndpointClient.GeneratedImage image : result.items()) {
                String reference = storeGenerated(image);
                if (reference != null) {
                    references.add(reference);
                }
            }
            if (references.isEmpty()) {
                return "生图模型返回了结果，但内容为空（既没有地址也没有字节），无法使用。";
            }
            return "已生成 " + references.size() + " 张图片，编号为 " + String.join("、", references)
                    + "。这些编号就是图片本身，可以直接告诉用户；"
                    + "若需要确认图里画了什么，用 seeImage 查看对应编号。";
        });
    }

    /** 一张生成的图 → 引用名；既没有地址也没有字节时返回 null（上游异常，不该造一个取不到的引用名） */
    private String storeGenerated(ModelEndpointClient.GeneratedImage image) {
        if (image == null) {
            return null;
        }
        if (!isBlank(image.b64Json())) {
            return store.put(Kind.IMAGE, DEFAULT_MIME, image.b64Json(), null);
        }
        if (!isBlank(image.url())) {
            // URL 引用的内容类型由服务商那边决定，此处不猜一个 mime 出来：
            // 猜错会让下游按错的类型拼 data URL
            return store.put(Kind.IMAGE, null, null, image.url());
        }
        return null;
    }
}
