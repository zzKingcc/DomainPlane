package com.zzkingcc.domainplane.server.model;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务商适配注册表：三级匹配、host 归一、schema 校验、坏文件隔离。
 *
 * 口径与设计文档 §4 / §9 / §10 逐条对应：
 * 生产适配文件（GPT-5.6 三家 + GPT-6 四家）走真实 classpath 加载；
 * 合成适配走 {@link ModelAdapterRegistry#of}；坏文件走临时目录 + 独立 ClassLoader 灌入。
 *
 * @author zzkingcc
 */
class ModelAdapterRegistryTest {

    // ==================== 生产适配文件 ====================

    private static ModelAdapterRegistry production() {
        return new ModelAdapterRegistry(new DefaultResourceLoader());
    }

    @Test
    void 生产适配文件全部加载且按id排序() {
        ModelAdapterRegistry reg = production();

        List<String> ids = reg.all().stream().map(ModelAdapterRegistry.Adapter::id).toList();
        assertEquals(List.of(
                "anthropic-claude-fable-5-1", "anthropic-claude-haiku-5-5",
                "anthropic-claude-mythos-5-1", "anthropic-claude-opus-5-5", "anthropic-claude-sonnet-5-5",
                "deepseek-flash", "deepseek-v4-pro",
                "openai-gpt-5.6-luna", "openai-gpt-5.6-sol", "openai-gpt-5.6-terra",
                "openai-gpt-6-astra", "openai-gpt-6-luna", "openai-gpt-6-sol", "openai-gpt-6.1-sol",
                "openrouter-anthropic-claude-fable-5-1", "openrouter-anthropic-claude-haiku-5-5",
                "openrouter-anthropic-claude-opus-5-5", "openrouter-google-gemini-3.7-flash",
                "openrouter-google-gemini-3.8-flash", "openrouter-openai-gpt-6-astra",
                "openrouter-openai-gpt-6-luna", "openrouter-openai-gpt-6-sol", "openrouter-openai-gpt-6.1-sol",
                "openrouter-x-ai-grok-4.6", "openrouter-x-ai-grok-4.7",
                "siliconflow-bge-m3", "siliconflow-bge-reranker-v2-m3",
                "siliconflow-deepseek-v4-flash", "siliconflow-kolors", "siliconflow-xingchen-asr-v3-2-ultra"),
                ids,
                "Claude 官方原生五家（chat）+ DeepSeek 两家 + GPT-5.6 三家 + GPT-6 四家（chat）"
                        + " + OpenRouter 十一家（Claude 三家 / GPT-6 四家 / Grok 两家 / Gemini 两家，全 chat）"
                        + " + 硅基流动五家（DeepSeek-V4-Flash=chat、Kolors=images、XingChenASR=asr、"
                        + "bge-reranker-v2-m3=rerank、bge-m3=embedding）都应加载，且 all() 按 id 排序");

        /* 参数面按模型分化（官方文档口径）：
         * GPT-6 起 max 档仅 Responses API 可用，Chat Completions 基底最高 xhigh，一律不登记；
         * Astra / 6.1-Sol 无 none；verbosity 仅 Astra 确认支持，其余三家未确认不登记；
         * DeepSeek 两模型参数面相同：reasoning_effort 四档（none 即关闭思考）。
         * temperature / max_tokens 是标准参数（std=true）：登记它，绿色调优框里才有「温度」「单次最大输出」
         * 两个控件。Astra / 6.1-Sol 例外 —— 官方明确它们的 Chat Completions 不接受 temperature，且本代
         * 无 none 档（思考恒开），登记一个填了就报错、永远不生效的控件只会误人，故两个都不登记。 */
        Map<String, List<String>> expectedWires = new LinkedHashMap<>();
        /* 五个 Claude 官方原生 chat 模型：参数面一致，temperature / max_tokens 是 std */
        for (String id : List.of("anthropic-claude-opus-5-5", "anthropic-claude-sonnet-5-5",
                "anthropic-claude-haiku-5-5", "anthropic-claude-fable-5-1", "anthropic-claude-mythos-5-1")) {
            expectedWires.put(id, List.of("temperature", "max_tokens", "top_p", "top_k", "stop_sequences"));
        }
        expectedWires.put("deepseek-flash", List.of("reasoning_effort", "temperature", "max_tokens", "top_p",
                "stop", "response_format", "logprobs", "top_logprobs"));
        expectedWires.put("deepseek-v4-pro", List.of("reasoning_effort", "temperature", "max_tokens", "top_p",
                "stop", "response_format", "logprobs", "top_logprobs"));
        expectedWires.put("openai-gpt-5.6-sol", List.of("reasoning_effort", "verbosity", "temperature", "max_tokens"));
        expectedWires.put("openai-gpt-5.6-terra", List.of("reasoning_effort", "verbosity", "temperature", "max_tokens"));
        expectedWires.put("openai-gpt-5.6-luna", List.of("reasoning_effort", "verbosity", "temperature", "max_tokens"));
        expectedWires.put("openai-gpt-6-astra", List.of("reasoning_effort", "verbosity"));
        expectedWires.put("openai-gpt-6-sol", List.of("reasoning_effort", "temperature", "max_tokens"));
        expectedWires.put("openai-gpt-6-luna", List.of("reasoning_effort", "temperature", "max_tokens"));
        expectedWires.put("openai-gpt-6.1-sol", List.of("reasoning_effort"));
        expectedWires.put("siliconflow-deepseek-v4-flash",
                List.of("temperature", "max_tokens", "top_p", "top_k", "stop",
                        "response_format", "frequency_penalty", "presence_penalty"));
        /* OpenRouter 聚合平台：按服务商分化参数面（与直接官方适配口径一致）
         * Claude：温度上限 1、含 top_k、不含 frequency/presence_penalty；登记 reasoning_effort
         *   （OpenRouter 映射 Anthropic output_config.effort；5.5 系强制思考、无 none 档，故枚举不含 none）
         * OpenAI / Grok：温度上限 2、含两种 penalty、不含 top_k；均登记 reasoning_effort（OpenRouter 原生支持顶层参数）
         * Gemini Flash：温度上限 2、含 top_k、不含 penalty；登记 reasoning_effort（思考默认开启、无 none 档）
         * 枚举口径（查证 OpenRouter 迁移指南 / reasoning-tokens / Gemini thinkingLevels）：
         *   GPT-6 Astra / 6.1-Sol 与 Claude 5.5 系无 none（下限 low）；Sol / Luna 及 Grok 含 none；Gemini = minimal/low/medium/high
         * verbosity 仍不登记：OpenRouter 透传语义不明确，与直连适配只给 Astra 不同，避免过度承诺 */
        for (String id : List.of("openrouter-anthropic-claude-opus-5-5",
                "openrouter-anthropic-claude-haiku-5-5", "openrouter-anthropic-claude-fable-5-1")) {
            expectedWires.put(id, List.of("reasoning_effort", "temperature", "max_tokens", "top_p", "top_k", "stop", "response_format"));
        }
        for (String id : List.of("openrouter-openai-gpt-6-astra", "openrouter-openai-gpt-6-luna",
                "openrouter-openai-gpt-6-sol", "openrouter-openai-gpt-6.1-sol",
                "openrouter-x-ai-grok-4.6", "openrouter-x-ai-grok-4.7")) {
            expectedWires.put(id, List.of("reasoning_effort", "temperature", "max_tokens", "top_p", "stop",
                    "frequency_penalty", "presence_penalty", "response_format"));
        }
        for (String id : List.of("openrouter-google-gemini-3.7-flash", "openrouter-google-gemini-3.8-flash")) {
            expectedWires.put(id, List.of("reasoning_effort", "temperature", "max_tokens", "top_p", "top_k", "stop", "response_format"));
        }
        for (ModelAdapterRegistry.Adapter a : reg.all()) {
            if (!a.endpoints().contains("chat")) {
                continue; // 向量适配（embedding 族）另行断言，见 硅基流动bgeM3向量适配登记embedding族与维度
            }
            String expectHost = a.id().startsWith("deepseek-") ? "api.deepseek.com"
                    : a.id().startsWith("anthropic-") ? "api.anthropic.com"
                    : a.id().startsWith("siliconflow-") ? "api.siliconflow.cn"
                    : a.id().startsWith("openrouter-") ? "openrouter.ai"
                    : "api.openai.com";
            assertEquals("deepseek".equals(a.provider()) ? "deepseek"
                    : "anthropic".equals(a.provider()) ? "anthropic"
                    : "siliconflow".equals(a.provider()) ? "siliconflow"
                    : "openrouter".equals(a.provider()) ? "openrouter"
                    : "openai", a.provider());
            assertTrue(a.match().hosts().contains(expectHost),
                    a.id() + " 应登记官方 host " + expectHost);
            List<String> wires = a.paramsOf("chat").stream()
                    .map(ModelAdapterRegistry.ParamSpec::wire).toList();
            assertEquals(expectedWires.get(a.id()), wires,
                    "chat 基底参数面与官方文档一致，实际 " + a.id() + "：" + wires);
        }
    }

    @Test
    void 生产适配文件都声明了端点族与文本进文本出() {
        ModelAdapterRegistry reg = production();

        for (ModelAdapterRegistry.Adapter a : reg.all()) {
            if (a.endpoints().contains("embedding")) {
                /* 向量适配：入参文本、产出向量。端点族 = 登记了参数的族名 = embedding */
                assertEquals(List.of("embedding"), a.endpoints(), a.id() + " 的端点族应登记为 embedding");
                assertEquals(List.of("text"), a.input(), a.id() + " 的输入模态应登记为 text");
                assertEquals(List.of("embedding"), a.output(), a.id() + " 的输出模态应登记为 embedding");
                continue;
            }
            if (a.endpoints().contains("images")) {
                assertEquals(List.of("images"), a.endpoints(), a.id() + " 的端点族应登记为 images");
                assertEquals(List.of("text"), a.input(), a.id() + " 的输入模态应登记为 text（提示词）");
                assertEquals(List.of("image"), a.output(), a.id() + " 的输出模态应登记为 image");
                continue;
            }
            if (a.endpoints().contains("asr")) {
                assertEquals(List.of("asr"), a.endpoints(), a.id() + " 的端点族应登记为 asr");
                assertEquals(List.of("audio"), a.input(), a.id() + " 的输入模态应登记为 audio（音频）");
                assertEquals(List.of("text"), a.output(), a.id() + " 的输出模态应登记为 text（转录文本）");
                continue;
            }
            if (a.endpoints().contains("rerank")) {
                assertEquals(List.of("rerank"), a.endpoints(), a.id() + " 的端点族应登记为 rerank");
                assertEquals(List.of("text"), a.input(), a.id() + " 的输入模态应登记为 text（query+documents）");
                assertEquals(List.of("score"), a.output(), a.id() + " 的输出模态应登记为 score（相关性分）");
                continue;
            }
            assertEquals(List.of("chat"), a.endpoints(),
                    a.id() + "：登记了 chat 参数 ⇒ 端点族就是 chat —— 读不出类型的服务商靠这条定类型");
            assertEquals(List.of("text"), a.input(), a.id() + " 的输入模态应登记为 text");
            assertEquals(List.of("text"), a.output(), a.id() + " 的输出模态应登记为 text");
        }
    }

    @Test
    void 硅基流动bgeM3向量适配登记embedding族与维度() {
        ModelAdapterRegistry reg = production();

        ModelAdapterRegistry.Adapter a = reg.all().stream()
                .filter(x -> "siliconflow-bge-m3".equals(x.id())).findFirst().orElseThrow();
        assertEquals("siliconflow", a.provider());
        assertEquals(List.of("embedding"), a.endpoints(), "登记了 embedding 参数 ⇒ 端点族就是 embedding");

        List<ModelAdapterRegistry.ParamSpec> specs = a.paramsOf("embedding");
        assertEquals(List.of("dimensions"), specs.stream().map(ModelAdapterRegistry.ParamSpec::wire).toList(),
                "embedding 族唯一可调项是向量维度");
        ModelAdapterRegistry.ParamSpec dim = specs.get(0);
        assertTrue(dim.std(), "维度是 std 参数：值落 ModelProfile#dimensions 专用字段，不进 params");
        assertEquals("int", dim.type());
        assertEquals(256.0, dim.min());
        assertEquals(4096.0, dim.max());

        /* 声明按模型名命中 —— 保存时零上游请求即可拿到端点族与模态（"向量"标记的来源） */
        ModelAdapterRegistry.Declaration d =
                reg.declaration("https://api.siliconflow.cn/v1", "BAAI/bge-m3").orElseThrow();
        assertEquals("siliconflow-bge-m3", d.adapterId());
        assertEquals(List.of("embedding"), d.endpoints());
        assertEquals(List.of("text"), d.input());
        assertEquals(List.of("embedding"), d.output());
    }

    @Test
    void 适配声明只在按模型名命中时给出host兜底不给() {
        ModelAdapterRegistry reg = production();

        ModelAdapterRegistry.Declaration d =
                reg.declaration("https://api.openai.com/v1", "gpt-5.6-sol").orElseThrow();
        assertEquals("openai-gpt-5.6-sol", d.adapterId());
        assertEquals(List.of("chat"), d.endpoints(), "端点族取自登记了参数的族名");
        assertEquals(List.of("text"), d.input());
        assertEquals(List.of("text"), d.output());

        assertTrue(reg.declaration("https://api.openai.com/v1", "gpt-4o").isEmpty(),
                "host 兜底只说明「这家服务商登记过」，说明不了「这个模型是什么」——"
                        + "照抄同 host 另一个模型的声明会把 text-embedding-3-* 说成 text→text");
        assertTrue(reg.declaration("https://api.openai.com/v1", "  ").isEmpty(), "模型名为空只剩 host 兜底");
        assertTrue(reg.declaration("https://api.example.com/v1", "gpt-4o").isEmpty(),
                "服务商没登记、模型名也没登记 → 三级全不中");
        assertTrue(reg.declaration("https://api.example.com/v1", "gpt-5.6-sol").isPresent(),
                "模型名精确匹配不挑服务商地址（与 match 的既有口径一致），照旧给声明");
    }

    @Test
    void 三个GPT56模型各自命中自己的适配() {
        ModelAdapterRegistry reg = production();

        assertEquals("openai-gpt-5.6-sol",
                reg.match("https://api.openai.com/v1", "gpt-5.6-sol").adapter().id());
        assertEquals("openai-gpt-5.6-terra",
                reg.match("https://api.openai.com/v1", "gpt-5.6-terra").adapter().id());
        assertEquals("openai-gpt-5.6-luna",
                reg.match("https://api.openai.com/v1", "gpt-5.6-luna").adapter().id());
    }

    @Test
    void 四个GPT6模型各自命中自己的适配() {
        ModelAdapterRegistry reg = production();

        assertEquals("openai-gpt-6-astra",
                reg.match("https://api.openai.com/v1", "gpt-6-astra").adapter().id());
        assertEquals("openai-gpt-6.1-sol",
                reg.match("https://api.openai.com/v1", "gpt-6.1-sol").adapter().id());
        assertEquals("openai-gpt-6-sol",
                reg.match("https://api.openai.com/v1", "gpt-6-sol").adapter().id());
        assertEquals("openai-gpt-6-luna",
                reg.match("https://api.openai.com/v1", "gpt-6-luna").adapter().id());
    }

    @Test
    void GPT6思考强度枚举按模型分化() {
        ModelAdapterRegistry reg = production();

        /* Astra / 6.1-Sol：无 none（本代已移除）、无 max（Responses 专属） */
        for (String model : List.of("gpt-6-astra", "gpt-6.1-sol")) {
            List<String> values = effortValues(reg, model);
            assertEquals(List.of("low", "medium", "high", "xhigh"), values, model + " 的 effort 枚举");
        }
        /* Sol / Luna：有 none、无 max */
        for (String model : List.of("gpt-6-sol", "gpt-6-luna")) {
            List<String> values = effortValues(reg, model);
            assertEquals(List.of("none", "low", "medium", "high", "xhigh"), values, model + " 的 effort 枚举");
        }
    }

    private static List<String> effortValues(ModelAdapterRegistry reg, String model) {
        ModelAdapterRegistry.Adapter a = reg.match("https://api.openai.com/v1", model).adapter();
        return a.paramsOf("chat").stream()
                .filter(p -> "reasoning_effort".equals(p.wire()))
                .flatMap(p -> p.options().stream())
                .map(ModelAdapterRegistry.Option::value)
                .toList();
    }

    @Test
    void OpenRouter思考强度枚举按模型分化() {
        ModelAdapterRegistry reg = production();

        /* GPT-6 Astra / 6.1-Sol：无 none（与直连适配一致，下限 low） */
        for (String model : List.of("openai/gpt-6-astra", "openai/gpt-6.1-sol")) {
            List<String> values = openRouterEffortValues(reg, model);
            assertEquals(List.of("low", "medium", "high", "xhigh"), values, "OpenRouter " + model + " 的 effort 枚举");
        }
        /* Claude 5.5 系（Opus / Haiku / Fable）：强制思考，无 none 档（none 在 5.5 上直接 400），枚举同 GPT-6 Astra */
        for (String model : List.of("anthropic/claude-opus-5.5", "anthropic/claude-haiku-5.5",
                "anthropic/claude-fable-5.1")) {
            List<String> values = openRouterEffortValues(reg, model);
            assertEquals(List.of("low", "medium", "high", "xhigh"), values, "OpenRouter " + model + " 的 effort 枚举");
        }
        /* GPT-6 Sol / Luna 及 Grok 4.6 / 4.7：含 none */
        for (String model : List.of("openai/gpt-6-sol", "openai/gpt-6-luna",
                "x-ai/grok-4.6", "x-ai/grok-4.7")) {
            List<String> values = openRouterEffortValues(reg, model);
            assertEquals(List.of("none", "low", "medium", "high", "xhigh"), values,
                    "OpenRouter " + model + " 的 effort 枚举");
        }
        /* Gemini 3.7 / 3.8 Flash：minimal/low/medium/high，无 none（思考默认开启）、无 max（xhigh 映射为 high） */
        for (String model : List.of("google/gemini-3.7-flash", "google/gemini-3.8-flash")) {
            List<String> values = openRouterEffortValues(reg, model);
            assertEquals(List.of("minimal", "low", "medium", "high"), values,
                    "OpenRouter " + model + " 的 effort 枚举");
        }
    }

    private static List<String> openRouterEffortValues(ModelAdapterRegistry reg, String model) {
        ModelAdapterRegistry.Adapter a = reg.match("https://openrouter.ai/api/v1", model).adapter();
        return a.paramsOf("chat").stream()
                .filter(p -> "reasoning_effort".equals(p.wire()))
                .flatMap(p -> p.options().stream())
                .map(ModelAdapterRegistry.Option::value)
                .toList();
    }

    @Test
    void 两个DeepSeek模型各自命中自己的适配() {
        ModelAdapterRegistry reg = production();

        assertEquals("deepseek-flash",
                reg.match("https://api.deepseek.com/v1", "deepseek-flash").adapter().id());
        assertEquals("deepseek-v4-pro",
                reg.match("https://api.deepseek.com", "deepseek-v4-pro").adapter().id(),
                "预设地址不带 /v1 也能归一出 host");
        /* 旧 ID（deepseek-v4-flash / deepseek-chat / deepseek-reasoner）已被官方退役并路由到 Flash：
         * 精确命中不了，落 host 兜底；同 host 两个适配按 id 字典序取 deepseek-flash，路由结果正确 */
        assertEquals("deepseek-flash",
                reg.match("https://api.deepseek.com/v1", "deepseek-v4-flash").adapter().id());
    }

    @Test
    void 五个Claude官方原生模型各自命中自己的适配() {
        ModelAdapterRegistry reg = production();

        assertEquals("anthropic-claude-opus-5-5",
                reg.match("https://api.anthropic.com/v1", "Claude Opus 5.5").adapter().id());
        assertEquals("anthropic-claude-sonnet-5-5",
                reg.match("https://api.anthropic.com/v1", "Claude Sonnet 5.5").adapter().id());
        assertEquals("anthropic-claude-haiku-5-5",
                reg.match("https://api.anthropic.com/v1", "Claude Haiku 5.5").adapter().id());
        assertEquals("anthropic-claude-fable-5-1",
                reg.match("https://api.anthropic.com/v1", "Claude Fable 5.1").adapter().id());
        assertEquals("anthropic-claude-mythos-5-1",
                reg.match("https://api.anthropic.com/v1", "Claude Mythos 5.1").adapter().id());
    }

    @Test
    void 四个硅基流动模型各自命中自己的适配且端点族正确() {
        ModelAdapterRegistry reg = production();

        ModelAdapterRegistry.Adapter ds =
                reg.match("https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V4-Flash").adapter();
        assertEquals("siliconflow-deepseek-v4-flash", ds.id());
        assertEquals(List.of("chat"), ds.endpoints(), "硅基流动 DeepSeek-V4-Flash 是对话模型");

        ModelAdapterRegistry.Adapter kolors =
                reg.match("https://api.siliconflow.cn/v1", "Kwai-Kolors/Kolors").adapter();
        assertEquals("siliconflow-kolors", kolors.id());
        assertEquals(List.of("images"), kolors.endpoints(), "Kolors 是生图模型");

        ModelAdapterRegistry.Adapter asr =
                reg.match("https://api.siliconflow.cn/v1", "XingChenAGI/XingChenASR-V3.2-Ultra").adapter();
        assertEquals("siliconflow-xingchen-asr-v3-2-ultra", asr.id());
        assertEquals(List.of("asr"), asr.endpoints(), "XingChenASR 是语音识别模型");

        ModelAdapterRegistry.Adapter rerank =
                reg.match("https://api.siliconflow.cn/v1", "BAAI/bge-reranker-v2-m3").adapter();
        assertEquals("siliconflow-bge-reranker-v2-m3", rerank.id());
        assertEquals(List.of("rerank"), rerank.endpoints(), "bge-reranker-v2-m3 是重排模型");
    }

    @Test
    void 十一个OpenRouter模型各自命中自己的适配且端点族为chat() {
        ModelAdapterRegistry reg = production();

        /* match.models 必须照 OpenRouter 的 owner/model 原名（带斜杠）填写才能命中 */
        assertEquals("openrouter-anthropic-claude-opus-5-5",
                reg.match("https://openrouter.ai/api/v1", "anthropic/claude-opus-5.5").adapter().id());
        assertEquals("openrouter-anthropic-claude-haiku-5-5",
                reg.match("https://openrouter.ai/api/v1", "anthropic/claude-haiku-5.5").adapter().id());
        assertEquals("openrouter-anthropic-claude-fable-5-1",
                reg.match("https://openrouter.ai/api/v1", "anthropic/claude-fable-5.1").adapter().id());

        assertEquals("openrouter-openai-gpt-6.1-sol",
                reg.match("https://openrouter.ai/api/v1", "openai/gpt-6.1-sol").adapter().id());
        assertEquals("openrouter-openai-gpt-6-luna",
                reg.match("https://openrouter.ai/api/v1", "openai/gpt-6-luna").adapter().id());
        assertEquals("openrouter-openai-gpt-6-sol",
                reg.match("https://openrouter.ai/api/v1", "openai/gpt-6-sol").adapter().id());
        assertEquals("openrouter-openai-gpt-6-astra",
                reg.match("https://openrouter.ai/api/v1", "openai/gpt-6-astra").adapter().id());

        assertEquals("openrouter-x-ai-grok-4.7",
                reg.match("https://openrouter.ai/api/v1", "x-ai/grok-4.7").adapter().id());
        assertEquals("openrouter-x-ai-grok-4.6",
                reg.match("https://openrouter.ai/api/v1", "x-ai/grok-4.6").adapter().id());

        assertEquals("openrouter-google-gemini-3.8-flash",
                reg.match("https://openrouter.ai/api/v1", "google/gemini-3.8-flash").adapter().id());
        assertEquals("openrouter-google-gemini-3.7-flash",
                reg.match("https://openrouter.ai/api/v1", "google/gemini-3.7-flash").adapter().id());

        /* 全部是对话模型：登记了 chat 参数 ⇒ 端点族就是 chat */
        for (String id : List.of(
                "openrouter-anthropic-claude-opus-5-5", "openrouter-anthropic-claude-haiku-5-5",
                "openrouter-anthropic-claude-fable-5-1", "openrouter-openai-gpt-6.1-sol",
                "openrouter-openai-gpt-6-luna", "openrouter-openai-gpt-6-sol", "openrouter-openai-gpt-6-astra",
                "openrouter-x-ai-grok-4.7", "openrouter-x-ai-grok-4.6",
                "openrouter-google-gemini-3.8-flash", "openrouter-google-gemini-3.7-flash")) {
            ModelAdapterRegistry.Adapter a =
                    reg.all().stream().filter(x -> id.equals(x.id())).findFirst().orElseThrow();
            assertEquals(List.of("chat"), a.endpoints(), id + " 应登记为 chat 族");
            assertEquals(List.of("text"), a.input(), id + " 输入模态 text");
            assertEquals(List.of("text"), a.output(), id + " 输出模态 text");
        }
    }

    @Test
    void DeepSeek思考强度枚举四档含none() {
        ModelAdapterRegistry reg = production();

        for (String model : List.of("deepseek-flash", "deepseek-v4-pro")) {
            assertEquals(List.of("none", "low", "high", "max"), effortValues(reg, model),
                    model + " 的 effort 枚举：none 关闭思考，low/high/max 开启并逐级加深");
        }
    }

    @Test
    void 别名gpt56归Sol且大小写与空白不敏感() {
        ModelAdapterRegistry reg = production();

        assertEquals("openai-gpt-5.6-sol",
                reg.match("https://api.openai.com/v1", "gpt-5.6").adapter().id(),
                "官方别名 gpt-5.6 路由到 Sol（旗舰）");
        assertEquals("openai-gpt-5.6-sol",
                reg.match("https://api.openai.com/v1", "  GPT-5.6-SOL ").adapter().id(),
                "模型名 trim 后忽略大小写");
    }

    @Test
    void hosts兜底命中且模型精确匹配不挑服务商地址() {
        ModelAdapterRegistry reg = production();

        /* 七家都登记了 api.openai.com：host 级兜底按 id 字典序取第一个（5.6-luna < 5.6-sol < ... < 6-astra < 6-luna < 6-sol < 6.1-sol） */
        assertEquals("openai-gpt-5.6-luna",
                reg.match("https://api.openai.com/v1", "gpt-4o").adapter().id(),
                "服务商级兜底：同 host 的其它模型落到 id 最小的适配");
        assertFalse(reg.match("https://api.example.com/v1", "gpt-4o").matched(),
                "服务商没登记、模型名也没登记，两头都不沾才不命中");
        assertTrue(reg.match("https://api.example.com/v1", "gpt-5.6-sol").matched(),
                "models 精确匹配只看模型名、不挑服务商地址（modelName 侧独立判定，见设计文档 §9）");
    }

    // ==================== 匹配优先级与语义 ====================

    private static ModelAdapterRegistry.ParamSpec param(String key, String type, String wire) {
        return new ModelAdapterRegistry.ParamSpec(key, key, type, wire, null, false,
                null, null, null, null, List.of(), null, null);
    }

    private static ModelAdapterRegistry.Adapter adapter(String id, List<String> hosts, List<String> models,
                                                        List<String> patterns) {
        return new ModelAdapterRegistry.Adapter(id, "test", id, null,
                new ModelAdapterRegistry.MatchSpec(hosts, models, patterns),
                Map.of("chat", List.of(param("k", "string", "k_wire"))));
    }

    @Test
    void 匹配优先级_精确大于正则大于host() {
        /* 故意让 host 适配的 id 最小：证明优先级不由 id 顺序决定 */
        ModelAdapterRegistry.Adapter hostOnly = adapter("a-host", List.of("api.test.com"), List.of(), List.of());
        ModelAdapterRegistry.Adapter patternOnly = adapter("b-pattern", List.of(), List.of(), List.of("^gpt-.*$"));
        ModelAdapterRegistry.Adapter modelOnly = adapter("c-model", List.of(), List.of("gpt-5.6"), List.of());
        ModelAdapterRegistry reg = ModelAdapterRegistry.of(List.of(hostOnly, patternOnly, modelOnly));

        assertEquals("c-model", reg.match("https://api.test.com/v1", "gpt-5.6").adapter().id(),
                "models 精确命中压过同轮任何 host 命中");
        assertEquals("b-pattern", reg.match("https://other.com/v1", "gpt-9").adapter().id(),
                "没有精确命中时 modelPatterns 压过 host");
        assertEquals("a-host", reg.match("https://api.test.com/v1", "llama").adapter().id(),
                "只剩 host 时走服务商级兜底");
    }

    @Test
    void 同级多个命中按id字典序() {
        ModelAdapterRegistry.Adapter second = adapter("m-2", List.of(), List.of("dup"), List.of());
        ModelAdapterRegistry.Adapter first = adapter("m-1", List.of(), List.of("dup"), List.of());
        /* 乱序给：of() 内部按 id 排序，确定性不依赖调用方的列举顺序 */
        ModelAdapterRegistry reg = ModelAdapterRegistry.of(List.of(second, first));

        assertEquals("m-1", reg.match("https://x/v1", "dup").adapter().id());
    }

    @Test
    void modelPatterns全串匹配且与显式锚点等价() {
        ModelAdapterRegistry anchored = ModelAdapterRegistry.of(List.of(
                adapter("a", List.of(), List.of(), List.of("^qwq-.*$"))));
        ModelAdapterRegistry bare = ModelAdapterRegistry.of(List.of(
                adapter("a", List.of(), List.of(), List.of("qwq-.*"))));

        assertTrue(anchored.match("https://x/v1", "qwq-plus").matched(), "系列前缀命中");
        assertFalse(anchored.match("https://x/v1", "xqwq-plus").matched(),
                "全串语义：开头不沾边不命中（部分匹配会误伤）");
        assertFalse(anchored.match("https://x/v1", "qwq").matched(), "qwq- 的横杠不能少");

        /* String.matches 本就是全串匹配：加不加 ^$ 结果必须一致（写文件的人两种写法都可能） */
        for (String m : List.of("qwq-plus", "qwq-32b", "xqwq", "qwq", "my-qwq-plus", "QWQ-x")) {
            assertEquals(anchored.match("https://x/v1", m).matched(),
                    bare.match("https://x/v1", m).matched(),
                    "锚点写法不应改变全串语义：" + m);
        }
    }

    @Test
    void host归一化_大小写端口尾点无scheme与非法URL() {
        assertEquals("api.openai.com", ModelAdapterRegistry.hostOf("https://api.openai.com/v1"));
        assertEquals("api.openai.com", ModelAdapterRegistry.hostOf("api.openai.com"), "无 scheme 补 https");
        assertEquals("api.openai.com", ModelAdapterRegistry.hostOf("HTTPS://API.OpenAI.COM:443/v1"),
                "大小写不敏感、端口不参与");
        assertEquals("api.openai.com", ModelAdapterRegistry.hostOf("api.openai.com."), "尾点（FQDN）剥掉");
        assertEquals("api.openai.com", ModelAdapterRegistry.hostOf("api.openai.com/v1/chat"),
                "路径忽略，只认 host");
        assertEquals("127.0.0.1", ModelAdapterRegistry.hostOf("http://127.0.0.1:8080"), "端口忽略");

        assertNull(ModelAdapterRegistry.hostOf(null));
        assertNull(ModelAdapterRegistry.hostOf("   "));
        assertNull(ModelAdapterRegistry.hostOf("::::"), "非法 URL 归不出 host");
    }

    @Test
    void baseUrl非法时精确与正则仍可命中而host不命中() {
        ModelAdapterRegistry reg = ModelAdapterRegistry.of(List.of(
                adapter("m", List.of(), List.of("gpt-5.6-sol"), List.of("^gpt-.*$"))));

        assertTrue(reg.match("::::", "gpt-5.6-sol").matched(),
                "host 归不出来不等于模型匹配也废 —— ①② 独立于 baseUrl 判定");
        assertTrue(reg.match("::::", "gpt-9").matched(), "正则同样独立于 baseUrl");
        assertFalse(reg.match("::::", "llama").matched(), "只剩 host 时归不出 host 就不命中");
    }

    @Test
    void 模型名为空时只走host兜底() {
        ModelAdapterRegistry reg = ModelAdapterRegistry.of(List.of(
                adapter("m", List.of("api.test.com"), List.of("gpt-5.6-sol"), List.of())));

        assertTrue(reg.match("https://api.test.com/v1", null).matched());
        assertTrue(reg.match("https://api.test.com/v1", "  ").matched());
        assertFalse(reg.match("https://other.com/v1", null).matched());
    }

    // ==================== 保存复核（validateParams / checkValue） ====================

    @Test
    void 保存复核_未知key拒绝并列出可用键() {
        ModelAdapterRegistry reg = production();
        ModelAdapterRegistry.MatchResult m = reg.match("https://api.openai.com/v1", "gpt-5.6-sol");

        String err = reg.validateParams(m, Map.of("top_p", 0.9));
        assertNotNull(err, "适配里没登记的键必须拒绝");
        assertTrue(err.contains("top_p") && err.contains("reasoningEffort") && err.contains("verbosity"),
                "拒绝原因要带未知键与可用键，用户才知道能填什么，实际：" + err);
    }

    @Test
    void 保存复核_枚举越界拒绝合法值通过() {
        ModelAdapterRegistry reg = production();
        ModelAdapterRegistry.MatchResult m = reg.match("https://api.openai.com/v1", "gpt-5.6-terra");

        assertNotNull(reg.validateParams(m, Map.of("reasoningEffort", "max")),
                "max / ultra / pro 是 Responses API 专属，chat 基底不登记 —— 这里必须拒绝");
        assertNotNull(reg.validateParams(m, Map.of("verbosity", "verbose")),
                "不在 options 里的值一律拒绝");
        assertNotNull(reg.validateParams(m, Map.of("reasoningEffort", 5)),
                "枚举不接收数值形态");
        assertNull(reg.validateParams(m, Map.of("reasoningEffort", "high", "verbosity", "low")),
                "合法值应放行");
        assertNull(reg.validateParams(m, Map.of()), "空 params 无需复核");
        assertNull(reg.validateParams(m, null), "null params 无需复核");
    }

    @Test
    void 保存复核_未命中时registry放行由控制器才拦() {
        ModelAdapterRegistry reg = production();
        ModelAdapterRegistry.MatchResult miss = reg.match("https://api.example.com/v1", "m");

        assertFalse(miss.matched());
        assertNull(miss.adapter());
        assertTrue(miss.chatParams().isEmpty(), "未命中时没有参数 schema");
        assertNull(reg.validateParams(miss, Map.of("x", 1)),
                "未命中时 registry 层不拦（拒绝动作在控制器 checkAdapterTuning，见其测试）");
    }

    @Test
    void 值校验的类型宽容度() {
        ModelAdapterRegistry.ParamSpec boolSpec = param("b", "bool", "w");
        assertNull(ModelAdapterRegistry.checkValue(boolSpec, true));
        assertNull(ModelAdapterRegistry.checkValue(boolSpec, "TRUE"), "布尔容忍字符串形态（前端可能传字符串）");
        assertNotNull(ModelAdapterRegistry.checkValue(boolSpec, "yes"));

        ModelAdapterRegistry.ParamSpec numSpec = new ModelAdapterRegistry.ParamSpec(
                "n", "n", "number", "w", null, false, 0.0, 2.0, null, null, List.of(), null, null);
        assertNull(ModelAdapterRegistry.checkValue(numSpec, 0.5));
        assertNull(ModelAdapterRegistry.checkValue(numSpec, "1.5"), "数值容忍字符串形态");
        assertNotNull(ModelAdapterRegistry.checkValue(numSpec, 3), "越界拒绝");
        assertNotNull(ModelAdapterRegistry.checkValue(numSpec, "abc"), "非数值拒绝");

        ModelAdapterRegistry.ParamSpec intSpec = param("i", "int", "w");
        assertNull(ModelAdapterRegistry.checkValue(intSpec, 8));
        assertNotNull(ModelAdapterRegistry.checkValue(intSpec, 8.5), "int 不接受小数");

        ModelAdapterRegistry.ParamSpec strSpec = param("s", "string", "w");
        assertNull(ModelAdapterRegistry.checkValue(strSpec, "x"));
        assertNotNull(ModelAdapterRegistry.checkValue(strSpec, "  "), "空串拒绝");

        ModelAdapterRegistry.ParamSpec enumSpec = new ModelAdapterRegistry.ParamSpec(
                "e", "e", "enum", "w", null, false, null, null, null, null,
                List.of(new ModelAdapterRegistry.Option("a", "A")), null, null);
        assertNull(ModelAdapterRegistry.checkValue(enumSpec, "a"));
        assertNotNull(ModelAdapterRegistry.checkValue(enumSpec, "b"));
    }

    // ==================== 条件可用性（enabledWhen）与新类型 ====================

    private static ModelAdapterRegistry.ParamSpec byKey(ModelAdapterRegistry.MatchResult m, String key) {
        return m.chatParams().stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("适配里没有登记参数 " + key));
    }

    @Test
    void 条件参数_思考模式与温度topP互为开关() {
        ModelAdapterRegistry reg = production();
        ModelAdapterRegistry.MatchResult m = reg.match("https://api.deepseek.com/v1", "deepseek-flash");

        /* 非思考模式：温度能填、top_p 不能填 */
        assertNull(reg.validateParams(m, Map.of("reasoningEffort", "none", "temperature", 0.3)));
        String p = reg.validateParams(m, Map.of("reasoningEffort", "none", "topP", 0.98));
        assertNotNull(p, "非思考模式下 top_p 固定 1.0，填它必须明确拒绝，不能静默丢弃");
        assertTrue(p.contains("topP") && p.contains("reasoningEffort"),
                "原因要带 key 与它依赖的参数，实际：" + p);

        /* 思考模式：反过来 */
        assertNull(reg.validateParams(m, Map.of("reasoningEffort", "high", "topP", 0.98)));
        String t = reg.validateParams(m, Map.of("reasoningEffort", "high", "temperature", 0.3));
        assertNotNull(t, "思考模式下 temperature 不生效，填它必须拒绝");
        assertTrue(t.contains("temperature"), "原因要带 key，实际：" + t);

        /* 依赖未填 = 服务商默认 high = 思考模式：缺 missing 的温度按"关"算，带 missing 的 top_p 按"开"算 */
        assertNotNull(reg.validateParams(m, Map.of("temperature", 0.3)), "默认思考开启，温度不可填");
        assertNull(reg.validateParams(m, Map.of("topP", 0.98)), "top_p 的 missing=high，默认按思考模式判定");
    }

    @Test
    void 条件参数_topLogprobs依赖logprobs() {
        ModelAdapterRegistry reg = production();
        ModelAdapterRegistry.MatchResult m = reg.match("https://api.deepseek.com/v1", "deepseek-v4-pro");

        assertNotNull(reg.validateParams(m, Map.of("topLogprobs", 5)), "没开 logprobs 就不能填候选个数");
        assertNull(reg.validateParams(m, Map.of("logprobs", true, "topLogprobs", 5)));
        assertNotNull(reg.validateParams(m, Map.of("logprobs", false, "topLogprobs", 5)),
                "logprobs 显式 false 与没开等价");
        assertNotNull(reg.validateParams(m, Map.of("logprobs", true, "topLogprobs", 21)), "上限 20");
    }

    @Test
    void 条件参数开关切回原值时原值不放行() {
        ModelAdapterRegistry reg = production();
        ModelAdapterRegistry.MatchResult m = reg.match("https://api.deepseek.com/v1", "deepseek-flash");

        /* 这条锁的是"整份替换"语义：切到 none 后温度能填了，但那是新的提交，
           上一次的 top_p 不会因为"切回来"而自动合法 —— 服务端只认这一次提交的全部值 */
        assertNotNull(reg.validateParams(m, Map.of("reasoningEffort", "none", "topP", 0.98, "temperature", 0.3)),
                "一次提交里值必须全部对得上当前条件");
    }

    @Test
    void DeepSeek新登记的参数面与官方文档一致() {
        ModelAdapterRegistry reg = production();
        ModelAdapterRegistry.MatchResult m = reg.match("https://api.deepseek.com/v1", "deepseek-flash");

        assertEquals(List.of("reasoningEffort", "temperature", "maxTokens", "topP", "stop",
                        "responseFormat", "logprobs", "topLogprobs"),
                m.chatParams().stream().map(ModelAdapterRegistry.ParamSpec::key).toList(),
                "参数面 = 官方 /chat/completions 里对调优有意义、且填了必生效的那些");

        /* response_format 在线上是嵌套对象：标量经 wireWrap 包成 {type: ...} */
        ModelAdapterRegistry.ParamSpec fmt = byKey(m, "responseFormat");
        assertEquals("response_format", fmt.wire());
        assertEquals("type", fmt.wireWrap());
        assertNull(byKey(m, "temperature").wireWrap(), "标准参数没有包装概念");

        /* 官方已废弃的 frequency_penalty / presence_penalty 一个字都不登记：传了不生效 */
        assertTrue(m.chatParams().stream().noneMatch(s -> s.wire().contains("penalty")),
                "废弃参数不得登记，登记等于骗用户");

        /* top_p 的值域按思考模式收窄：思考模式下低于 0.95 会被按 0.95 处理 */
        assertEquals(0.95, byKey(m, "topP").min());
        assertEquals(1.0, byKey(m, "topP").max());
    }

    @Test
    void 停止序列按条数与元素内容校验() {
        ModelAdapterRegistry reg = production();
        ModelAdapterRegistry.MatchResult m = reg.match("https://api.deepseek.com/v1", "deepseek-flash");

        assertEquals("stringList", byKey(m, "stop").type());
        assertNull(reg.validateParams(m, Map.of("stop", List.of("###", "STOP"))));
        assertNull(reg.validateParams(m, Map.of("stop", "###")), "官方是 string | string[]，单字符串也收");
        assertNotNull(reg.validateParams(m, Map.of("stop", List.of())), "空数组不是有效值（清除 = 不发这个键）");
        assertNotNull(reg.validateParams(m, Map.of("stop", List.of("ok", "  "))), "空白元素拒绝");
        assertNotNull(reg.validateParams(m, Map.of("stop", 3)), "非文本拒绝");

        List<String> sixteen = IntStream.range(0, 16).mapToObj(i -> "s" + i).toList();
        assertNull(reg.validateParams(m, Map.of("stop", sixteen)));
        List<String> seventeen = IntStream.range(0, 17).mapToObj(i -> "s" + i).toList();
        String err = reg.validateParams(m, Map.of("stop", seventeen));
        assertNotNull(err, "stringList 的 max 是条数上限");
        assertTrue(err.contains("16"), "原因要说清上限，实际：" + err);
    }

    @Test
    void 条件判定的标量比较不受包装类型影响() {
        ModelAdapterRegistry.EnabledWhen w = new ModelAdapterRegistry.EnabledWhen(
                "mode", List.of("on"), null);
        ModelAdapterRegistry.ParamSpec spec = new ModelAdapterRegistry.ParamSpec(
                "a", "a", "number", "a", null, false, null, null, null, null, List.of(), null, w);

        assertTrue(ModelAdapterRegistry.enabled(spec, Map.of("mode", "on")));
        assertFalse(ModelAdapterRegistry.enabled(spec, Map.of("mode", "off")));
        assertFalse(ModelAdapterRegistry.enabled(spec, Map.of()), "未填且无 missing = 不命中");
        assertFalse(ModelAdapterRegistry.enabled(spec, Map.of("mode", "  ")), "空白等同未填");

        /* 数值 / 布尔形态也要比得动：JSON 的 0.95 与字符串 "0.95" 是同一个值 */
        ModelAdapterRegistry.EnabledWhen num = new ModelAdapterRegistry.EnabledWhen(
                "top", List.of(0.95, 1), null);
        ModelAdapterRegistry.ParamSpec numSpec = new ModelAdapterRegistry.ParamSpec(
                "a", "a", "number", "a", null, false, null, null, null, null, List.of(), null, num);
        assertTrue(ModelAdapterRegistry.enabled(numSpec, Map.of("top", 0.95)));
        assertTrue(ModelAdapterRegistry.enabled(numSpec, Map.of("top", "0.95")));
        assertTrue(ModelAdapterRegistry.enabled(numSpec, Map.of("top", 1.0)), "1 与 1.0 是同一个值");

        ModelAdapterRegistry.EnabledWhen bool = new ModelAdapterRegistry.EnabledWhen(
                "on", List.of(true), null);
        ModelAdapterRegistry.ParamSpec boolSpec = new ModelAdapterRegistry.ParamSpec(
                "a", "a", "int", "a", null, false, null, null, null, null, List.of(), null, bool);
        assertTrue(ModelAdapterRegistry.enabled(boolSpec, Map.of("on", true)));
        assertTrue(ModelAdapterRegistry.enabled(boolSpec, Map.of("on", "true")));
        assertFalse(ModelAdapterRegistry.enabled(boolSpec, Map.of("on", false)));
    }

    // ==================== 加载隔离与 schema 校验 ====================

    private static final String GOOD_JSON = """
            {
              "id": "t-good",
              "provider": "test",
              "label": "测试适配",
              "match": {"hosts": ["api.test.com"]},
              "families": {"chat": {"params": [
                {"key": "reasoningEffort", "label": "思考强度", "type": "enum",
                 "wire": "reasoning_effort", "std": false,
                 "options": [{"value": "none"}, {"value": "high"}]}
              ]}}
            }
            """;

    /** 把若干文件写进临时目录的 adapters/，用独立 ClassLoader 让 classpath 指向它 */
    private static ModelAdapterRegistry loadFromDisk(LinkedHashMap<String, String> files) throws IOException {
        Path root = Files.createTempDirectory("model-adapters-test");
        Path dir = Files.createDirectories(root.resolve("adapters"));
        files.forEach((name, json) -> {
            try {
                Files.writeString(dir.resolve(name), json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return new ModelAdapterRegistry(new DefaultResourceLoader(
                new URLClassLoader(new URL[]{root.toUri().toURL()}, null)));
    }

    @Test
    void 坏文件隔离_坏JSON与schema不过都不影响其余加载() throws Exception {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        files.put("a-good.json", GOOD_JSON);
        files.put("b-broken.json", "{ this is not json");
        files.put("c-schema.json", """
                {"id":"t-bad","provider":"test","label":"坏 schema","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"temperature","label":"温度","type":"number","wire":"foo","std":true}]}}}
                """);

        ModelAdapterRegistry reg = loadFromDisk(files);

        List<String> ids = reg.all().stream().map(ModelAdapterRegistry.Adapter::id).toList();
        assertEquals(List.of("t-good"), ids,
                "坏 JSON 与 schema 不过的文件只跳过自己，好的那个照常加载，启动不炸");
    }

    @Test
    void schema校验_各类违规文件都被拒绝() throws Exception {
        /* 一条一个违规点；全部应被 parseAdapter 拒绝而加载不进来 */
        Map<String, String> cases = new LinkedHashMap<>();
        // std=false 占用标准 wire
        cases.put("private-std-wire.json", """
                {"id":"a","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"k","label":"k","type":"bool","wire":"temperature","std":false}]}}}
                """);
        // match 全空
        cases.put("empty-match.json", """
                {"id":"b","provider":"p","label":"l","match":{},
                 "families":{"chat":{"params":[
                   {"key":"k","label":"k","type":"string","wire":"k"}]}}}
                """);
        // enum 缺 options
        cases.put("enum-no-options.json", """
                {"id":"c","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"k","label":"k","type":"enum","wire":"k"}]}}}
                """);
        // 重复 key
        cases.put("dup-key.json", """
                {"id":"d","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"k","label":"k","type":"string","wire":"k1"},
                   {"key":"k","label":"k2","type":"string","wire":"k2"}]}}}
                """);
        // 重复 wire
        cases.put("dup-wire.json", """
                {"id":"e","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"k1","label":"k1","type":"string","wire":"w"},
                   {"key":"k2","label":"k2","type":"string","wire":"w"}]}}}
                """);
        // 未知端点族
        cases.put("bad-family.json", """
                {"id":"f","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"video":{"params":[
                   {"key":"k","label":"k","type":"string","wire":"k"}]}}}
                """);
        // 参数 type 不在词表
        cases.put("bad-type.json", """
                {"id":"g","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"k","label":"k","type":"float","wire":"k"}]}}}
                """);
        // 模态不在词表：写错一个词就加载失败，别让卡片上出现谁也不认识的标签
        cases.put("bad-modality.json", """
                {"id":"g2","provider":"p","label":"l","input":["txt"],"match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"k","label":"k","type":"string","wire":"k"}]}}}
                """);
        // default 不合法（加载期就该炸，而不是等用户保存）
        cases.put("bad-default.json", """
                {"id":"h","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"k","label":"k","type":"int","wire":"k","default":"abc"}]}}}
                """);
        // modelPatterns 不是合法正则
        cases.put("bad-pattern.json", """
                {"id":"i","provider":"p","label":"l",
                 "match":{"modelPatterns":["[unclosed"]},
                 "families":{"chat":{"params":[
                   {"key":"k","label":"k","type":"string","wire":"k"}]}}}
                """);
        // wireWrap 用在标准参数上（标准参数的值形状由框架定，没法再套一层）
        cases.put("wirewrap-on-std.json", """
                {"id":"j","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"temperature","label":"温度","type":"number","wire":"temperature",
                    "std":true,"wireWrap":"value"}]}}}
                """);
        // 条件指向不存在的参数
        cases.put("enable-unknown-dep.json", """
                {"id":"k","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"a","label":"a","type":"number","wire":"a",
                    "enabledWhen":{"key":"nope","values":["x"]}}]}}}
                """);
        // 条件指向自己（条件恒不成立）
        cases.put("enable-self.json", """
                {"id":"l","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"a","label":"a","type":"enum","wire":"a","options":[{"value":"x"}],
                    "enabledWhen":{"key":"a","values":["x"]}}]}}}
                """);
        // 链式依赖：条件参数又依赖另一个条件参数
        cases.put("enable-chain.json", """
                {"id":"m","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"a","label":"a","type":"enum","wire":"a","options":[{"value":"x"}]},
                   {"key":"b","label":"b","type":"number","wire":"b",
                    "enabledWhen":{"key":"a","values":["x"]}},
                   {"key":"c","label":"c","type":"number","wire":"c",
                    "enabledWhen":{"key":"b","values":[1]}}]}}}
                """);
        // 条件指向标准参数（标准参数不在 params map 里，判定会飘）
        cases.put("enable-std-dep.json", """
                {"id":"n","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"temperature","label":"温度","type":"number","wire":"temperature","std":true},
                   {"key":"a","label":"a","type":"number","wire":"a",
                    "enabledWhen":{"key":"temperature","values":[0.5]}}]}}}
                """);
        // 条件取值不是被依赖参数的合法选项（写错就永远灰着，只能在加载期拦）
        cases.put("enable-bad-value.json", """
                {"id":"o","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"a","label":"a","type":"enum","wire":"a","options":[{"value":"x"}]},
                   {"key":"b","label":"b","type":"number","wire":"b",
                    "enabledWhen":{"key":"a","values":["y"]}}]}}}
                """);
        // 条件 values 为空数组
        cases.put("enable-empty-values.json", """
                {"id":"r","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"a","label":"a","type":"enum","wire":"a","options":[{"value":"x"}]},
                   {"key":"b","label":"b","type":"number","wire":"b",
                    "enabledWhen":{"key":"a","values":[]}}]}}}
                """);
        // display 用在非枚举上（分档的前提是取值有序，只有枚举自带这个顺序）
        cases.put("display-on-number.json", """
                {"id":"s","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"k","label":"k","type":"number","wire":"k","display":"bar"}]}}}
                """);
        // display 取值不在词表（curated 文件，不静默吞）
        cases.put("display-unknown.json", """
                {"id":"t","provider":"p","label":"l","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"k","label":"k","type":"enum","wire":"k","display":"pie",
                    "options":[{"value":"x"}]}]}}}
                """);

        for (Map.Entry<String, String> c : cases.entrySet()) {
            LinkedHashMap<String, String> one = new LinkedHashMap<>();
            one.put("x.json", c.getValue());
            ModelAdapterRegistry reg = loadFromDisk(one);
            assertTrue(reg.all().isEmpty(), c.getKey() + " 应被 schema 校验拒绝，实际加载出 " + reg.all().size() + " 条");
        }
    }

    @Test
    void 合法条件与包装声明能正常加载() throws Exception {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        files.put("ok.json", """
                {"id":"t-cond","provider":"test","label":"条件适配","match":{"models":["m"]},
                 "families":{"chat":{"params":[
                   {"key":"mode","label":"模式","type":"enum","wire":"mode","display":"bar",
                    "options":[{"value":"on"},{"value":"off"}]},
                   {"key":"a","label":"a","type":"number","wire":"a","min":0,"max":1,"default":0.5,
                    "enabledWhen":{"key":"mode","values":["on"],"missing":"off"}},
                   {"key":"b","label":"b","type":"enum","wire":"b","wireWrap":"type",
                    "options":[{"value":"x"}]}]}}}
                """);

        ModelAdapterRegistry reg = loadFromDisk(files);

        assertEquals(1, reg.all().size(), "合法的条件 / 包装声明不该被加载期拦掉");
        ModelAdapterRegistry.MatchResult m = reg.match("https://x/v1", "m");
        assertEquals("on", byKey(m, "a").enabledWhen().values().get(0));
        assertEquals("off", byKey(m, "a").enabledWhen().missing());
        assertEquals(0.5, ((Number) byKey(m, "a").defaultValue()).doubleValue(), 1e-9,
                "条件参数可以声明 default，且解析成 Java 标量（补缺值同样要过条件门）");
        assertEquals("type", byKey(m, "b").wireWrap());
        assertEquals("bar", byKey(m, "mode").display(), "display 是纯呈现提示，原样透传给前端");
        assertNull(byKey(m, "a").display(), "没写 display 的参数按 type 走默认控件");
        assertNull(byKey(m, "a").wireWrap(), "没写 wireWrap 的参数不带包装");
    }
}
