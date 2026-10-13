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
 * 口径：只内置有适配的 OpenAI / DeepSeek 两家，且只携带该厂商的 OpenAI 兼容入口地址
 * （不代填密钥）。多余厂商不设预设：地址栏本就是自由输入，用户手填即可。
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
    void 每个预设的地址都有适配兜底() {
        ModelAdapterRegistry registry = new ModelAdapterRegistry(new DefaultResourceLoader());

        for (Map<String, Object> p : presets()) {
            String baseUrl = String.valueOf(p.get("baseUrl"));
            /* 用一个该家不存在的模型名：只能走 host 级兜底。命中即说明这家有不带型号的适配，
               与「只保留有适配的两家」这条口径对得上。 */
            assertTrue(registry.match(baseUrl, "some-unregistered-model").matched(),
                    "预设 " + p.get("label") + " 的地址应有适配兜底：" + baseUrl);
        }
    }
}
