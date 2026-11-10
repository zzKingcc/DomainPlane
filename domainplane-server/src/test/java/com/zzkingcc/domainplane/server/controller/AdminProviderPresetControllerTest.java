package com.zzkingcc.domainplane.server.controller;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内置服务商预设接口 —— 「添加模型」表单顶部那份快速填充清单。
 *
 * 口径：只内置 DeepSeek 与 OpenAI 两家的官方 OpenAI 兼容入口地址（不代填密钥）。
 * 多余厂商不设预设：地址栏本就是自由输入，用户手填即可。
 *
 * <p>这两个只是<b>地址快捷方式</b>：地址栏本就是自由输入，预设只省一次手填，
 * 不附带任何能力声明，也不依赖任何服务商登记信息。</p>
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
}
