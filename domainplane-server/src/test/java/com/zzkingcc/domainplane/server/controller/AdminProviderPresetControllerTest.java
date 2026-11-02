package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内置服务商预设接口 —— 「添加模型」表单顶部那份快速填充清单。
 *
 * 口径：只内置 DeepSeek 与 OpenAI 两家的官方 OpenAI 兼容入口地址（不代填密钥）。
 * 多余厂商不设预设：地址栏本就是自由输入，用户手填即可。
 *
 * <p>这两个是<b>地址快捷方式</b>，与"这家有没有适配"是两件事 —— F10 定线之后
 * （只留一份 DeepSeek 样板，其余 29 份归档）OpenAI 官方已无适配，但它的地址仍是
 * 最常被填的那一个，不能因为内部清理适配就把入口一起撤掉。覆盖情况在下面那条用例里
 * 分开断言，不再让两件事共用一个前提。</p>
 *
 * @author zzkingcc
 */
class AdminProviderPresetControllerTest {

    private final AdminProviderPresetController controller = new AdminProviderPresetController();

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> presets() {
        Map<String, Object> body = controller.list();
        assertEquals(0, body.get("code"));
        return (List<Map<String, Object>>) body.get("presets");
    }

    @Test
    void 内置项只保留DeepSeek与OpenAI两家() {
        List<Map<String, Object>> presets = presets();

        assertEquals(List.of("DeepSeek 官方", "OpenAI 官方"),
                presets.stream().map(p -> String.valueOf(p.get("label"))).toList(),
                "内置项只剩这两家，实际：" + presets);
    }

    @Test
    void 预设带官方兼容入口地址且都要求密钥() {
        List<Map<String, Object>> presets = presets();

        Map<String, Object> deepseek = presets.get(0);
        assertEquals("https://api.deepseek.com/v1", deepseek.get("baseUrl"));
        assertEquals(Boolean.TRUE, deepseek.get("needApiKey"), "官方入口需要密钥，预设只填地址不代填");

        Map<String, Object> openai = presets.get(1);
        assertEquals("https://api.openai.com/v1", openai.get("baseUrl"));
        assertEquals(Boolean.TRUE, openai.get("needApiKey"));
    }

    @Test
    void 预设地址与适配覆盖是两件事() {
        ModelAdapterRegistry registry = new ModelAdapterRegistry(new DefaultResourceLoader());

        /* 用一个该家不存在的模型名：只能走 host 级兜底。命中即说明这家有不带型号的适配。 */
        String deepseek = String.valueOf(presets().get(0).get("baseUrl"));
        assertTrue(registry.match(deepseek, "some-unregistered-model").matched(),
                "DeepSeek 是留下的那份样板适配所在，它的地址应当有 host 兜底：" + deepseek);

        String openai = String.valueOf(presets().get(1).get("baseUrl"));
        assertFalse(registry.match(openai, "some-unregistered-model").matched(),
                "OpenAI 官方的适配已随其余 29 份归档，这里不该命中；"
                        + "预设仍在，是因为它只是地址快捷方式，与适配无关：" + openai);
    }
}
