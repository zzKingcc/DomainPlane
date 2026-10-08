package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.server.model.ModelPresets;
import com.zzkingcc.domainplane.server.model.ProviderType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理面：内置服务商预设 —— 「添加模型」表单顶部那份快速填充清单。
 *
 * 与 {@code /admin/provider-connections} 的分工：那边是用户自己的连接（可增删改、落盘），
 * 这边是系统内置的平台清单（只读、不落盘）。两者不重叠，预设也不代替连接库。
 *
 * 预设真正不可替代的一点是携带 {@code providerType}：地址用户能查，
 * "这份档案该交给哪个适配器"没有记忆成本，漏填就只能走 OpenAI 兼容——
 * 对 Anthropic / Ollama 这类非兼容协议会直接跑不通。
 *
 * 返回体刻意把"协议类型"与"预设项"分开给：
 * 协议类型列表供表单渲染那个独立的下拉（用户可能不用预设、只手动改协议），
 * 预设列表供顶部快速填充。合成一份会让"换协议但保留地址"变成做不到的事。
 *
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin/provider-presets")
public class AdminProviderPresetController {

    /** 可选协议类型（已过滤掉尚未接入的） */
    @GetMapping("/types")
    public Map<String, Object> types() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProviderType type : ModelPresets.supportedProviderTypes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", type.name());
            item.put("label", type.label());
            out.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("types", out);
        return body;
    }

    /** 全部预设项：协议类型 + 名称 + 默认地址 + 是否需要密钥 + 备注 */
    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ModelPresets.Preset preset : ModelPresets.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", preset.id());
            item.put("label", preset.label());
            item.put("baseUrl", preset.baseUrl());
            item.put("needApiKey", preset.needApiKey());
            item.put("note", preset.note() == null ? "" : preset.note());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("presets", items);
        return body;
    }
}