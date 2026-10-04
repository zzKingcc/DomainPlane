package com.zzkingcc.domainplane.server.settings;

import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.server.config.AiProperties;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LlmModelHolder 的纯逻辑单元测试（不依赖 Spring 上下文、不联网）。
 *
 * <p>重点覆盖 {@code promote()}：langchain4j 1.18.1 的 OpenAI 模型在 doChat 里把
 * request.parameters() 强转为 OpenAiChatRequestParameters，若请求只填了工具声明
 * （用通用 ChatRequestParameters 构建）就会抛 ClassCastException。promote 在委派层
 * 做一次类型提升，让底层模型拿到它认得的参数类型。</p>
 */
class LlmModelHolderTest {

    /** 内存版设置存储：load 返回最近一次 apply 的值，save 不落盘 */
    static final class StubStore extends LlmSettingsStore {
        private LlmSettings current = new LlmSettings();

        StubStore() {
            super(new StorageLocations(new StandardEnvironment()));
        }

        @Override
        public LlmSettings load() {
            return current;
        }

        @Override
        public void save(LlmSettings settings) {
            this.current = settings;
        }
    }

    /** 构造一个"未配置"（设置全空）的 holder，模型为 null、不会触发任何网络调用 */
    private LlmModelHolder unconfiguredHolder() {
        return new LlmModelHolder(new AiProperties(), new StubStore());
    }

    /** 用一组可用的对话设置 apply 到 holder，使其 settings 携带指定的温度 / 上限 token */
    private LlmModelHolder holderWithSettings(Double temperature, Integer maxTokens) {
        LlmModelHolder holder = unconfiguredHolder();
        LlmSettings s = new LlmSettings();
        s.setChatBaseUrl("https://example.test/v1");
        s.setChatApiKey("test-key");
        s.setChatModelName("test-model");
        s.setChatTemperature(temperature);
        s.setChatMaxTokens(maxTokens);
        holder.apply(s);
        return holder;
    }

    private static ToolSpecification sampleTool() {
        return ToolSpecification.builder()
                .name("get_weather")
                .description("查询天气")
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    private static ChatRequest genericRequest(ToolSpecification... tools) {
        ChatRequestParameters params = ChatRequestParameters.builder()
                .toolSpecifications(tools.length == 0 ? null : List.of(tools))
                .build();
        return ChatRequest.builder()
                .messages(List.of(UserMessage.from("你好")))
                .parameters(params)
                .build();
    }

    /** 只实现两个抽象方法；默认方法足以应对"未触发实际回调"的场景 */
    private static StreamingChatResponseHandler noopHandler() {
        return new StreamingChatResponseHandler() {
            @Override
            public void onCompleteResponse(ChatResponse r) {
            }

            @Override
            public void onError(Throwable t) {
            }
        };
    }

    /**
     * 核心回归：携带通用 ChatRequestParameters（仅工具声明）的请求，
     * 经 promote 后参数必须升级为 OpenAiChatRequestParameters，否则底层强转会抛 ClassCastException。
     */
    @Test
    void promote_upgradesGenericParamsToOpenAiAndCarriesToolsAndSettings() {
        LlmModelHolder holder = holderWithSettings(0.7, 512);
        ToolSpecification tool = sampleTool();

        ChatRequest promoted = holder.promote(genericRequest(tool));

        OpenAiChatRequestParameters p = assertInstanceOf(OpenAiChatRequestParameters.class, promoted.parameters());
        assertEquals(0.7, p.temperature());
        assertEquals(512, p.maxOutputTokens());
        assertEquals(List.of(tool), p.toolSpecifications());
        // 消息原样透传，不得丢失
        assertEquals(1, promoted.messages().size());
    }

    /**
     * 回归：提升后的参数必须带上 modelName。OpenAI 模型在 doChat 里只认 request.parameters()，
     * 不再兜自己的默认值，这里丢了它，上游就会报 Required parameter "model" missing。
     */
    @Test
    void promote_carriesModelNameFromModelDefaults() {
        LlmModelHolder holder = holderWithSettings(0.7, 512);

        ChatRequest promoted = holder.promote(genericRequest());

        OpenAiChatRequestParameters p = assertInstanceOf(OpenAiChatRequestParameters.class, promoted.parameters());
        assertEquals("test-model", p.modelName());
    }

    /**
     * 幂等：已经是 OpenAiChatRequestParameters 的请求应原样返回，
     * 不再包一层，避免重复提升或丢失调用方自己设置的 OpenAI 专属字段。
     */
    @Test
    void promote_isIdempotentForOpenAiParams() {
        LlmModelHolder holder = holderWithSettings(0.5, 2048);
        OpenAiChatRequestParameters oa = OpenAiChatRequestParameters.builder()
                .modelName("test-model")
                .build();
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(UserMessage.from("你好")))
                .parameters(oa)
                .build();

        assertSame(req, holder.promote(req));
    }

    /**
     * 回归：UI 未填写温度 / 上限时，promote 仍须把参数提升为 OpenAiChatRequestParameters（否则底层强转抛 ClassCastException），
     * 并带上 modelName；温度 / 上限回落到模型（服务商）默认，不再由项目写死 yaml 默认值。
     */
    @Test
    void promote_upgradesGenericParamsWhenUiUnset() {
        LlmModelHolder holder = holderWithSettings(null, null);

        ChatRequest promoted = holder.promote(genericRequest());
        OpenAiChatRequestParameters p = assertInstanceOf(OpenAiChatRequestParameters.class, promoted.parameters());
        assertEquals("test-model", p.modelName());
    }

    /** 未配置时，流式委派代理的 doChat 必须抛 NotConfiguredException（fail-fast，不联网） */
    @Test
    void streamingDelegation_throwsWhenModelNotConfigured() {
        LlmModelHolder holder = unconfiguredHolder();
        StreamingChatModel model = holder.streamingChatModel();

        assertThrows(NotConfiguredException.class,
                () -> model.doChat(genericRequest(), noopHandler()));
    }

    /** 未配置时，同步委派代理的 doChat 同样 fail-fast */
    @Test
    void chatDelegation_throwsWhenModelNotConfigured() {
        LlmModelHolder holder = unconfiguredHolder();
        ChatModel model = holder.chatModel();

        assertThrows(NotConfiguredException.class, () -> model.doChat(genericRequest()));
    }

    /** 未配置时，向量委派代理的 doEmbed 同样 fail-fast */
    @Test
    void embeddingDelegation_throwsWhenModelNotConfigured() {
        LlmModelHolder holder = unconfiguredHolder();
        dev.langchain4j.model.embedding.EmbeddingModel model = holder.embeddingModel();

        assertThrows(NotConfiguredException.class,
                () -> model.embed("anything"));
    }
}
