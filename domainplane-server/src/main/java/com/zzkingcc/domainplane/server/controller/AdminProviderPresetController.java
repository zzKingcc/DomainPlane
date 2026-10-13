package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.server.model.ModelPresets;
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
 * 连接基底统一为 OpenAI 兼容，预设只携带默认地址：选一条预设等于把该厂商的
 * OpenAI 兼容入口填进表单，密钥仍由用户自己填。
 *
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin/provider-presets")
public class AdminProviderPresetController {

    /** 全部预设项：名称 + 默认地址 + 是否需要密钥 + 备注 */
    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ModelPresets.Preset preset : ModelPresets.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
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
