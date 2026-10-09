package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.model.ModelPresets;
import com.zzkingcc.domainplane.server.model.ProviderConnectionStore;
import lombok.Data;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理面：自定义配置项 —— 模型设置页「预设配置信息」按钮背后的四个接口。
 *
 * 一条自定义配置 = 名称 + 协议 + 服务商地址 +（可选）密钥。它与内置配置项一起出现在
 * 模型表单顶部的预设下拉里，分两段展示：上「内置配置项」、下「自定义配置项」。
 * 选中一条即把协议、地址、密钥一起填好 —— 不用自己对齐"这条地址配哪把 Key、走哪套协议"。
 *
 * 名称在「内置 + 自定义」整体内唯一：两段共用一个选择框，重名就分不清点的是哪一条。
 * 内置名由 {@link ModelPresets} 固定，所以这里在写之前先做一次跨段查重。
 *
 * 凭据面比档案面更严，体现在只有一处：{@link #resolve(String)}。
 * 列表接口只回地址与脱敏密钥，明文只在用户于下拉里明确点了某个名称时才单独取一次 ——
 * 页面每次打开就把全库明文拉进浏览器内存没有必要，而档案列表里的 Key 本来就是脱敏展示的。
 *
 * 删除不牵连档案：库里存的是"快捷填充"，档案里存的是各自的明文副本，
 * 两者本就无关联。删除接口会照实说明，避免用户以为删了就等于从档案里撤掉了凭据。
 *
 * 路径在 {@code /admin/**} 之下，鉴权由凭证拦截器统一处理。
 *
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin/provider-connections")
public class AdminProviderConnectionController {

    private final ProviderConnectionStore store;

    public AdminProviderConnectionController(ProviderConnectionStore store) {
        this.store = store;
    }

    /** 列表：名称 + 地址（原样）+ 脱敏密钥（不含明文）+ 落盘位置 */
    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> connections = new ArrayList<>();
        for (ProviderConnectionStore.ProviderConnection connection : store.entries()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", connection.name());
            /* 地址不是机密，原样回显：排障时要能一眼看到真实地址，
               而不是把它当成凭据一样遮起来。 */
            item.put("baseUrl", connection.baseUrl());
            item.put("protocol", connection.protocol());
            item.put("hasApiKey", connection.hasApiKey());
            item.put("maskedApiKey", connection.maskedApiKey());
            connections.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("connections", connections);
        body.put("count", connections.size());
        body.put("settingsFile", store.filePath());
        return body;
    }

    /**
     * 新增一条。名称不能重复，重名的失败原因由
     * {@link ProviderConnectionStore#add(String, String, String)} 给出并点名已存在的那条。
     */
    @PostMapping
    public Map<String, Object> add(@RequestBody(required = false) ConnectionBody body) {
        if (body == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "请求体不能为空");
        }
        String name = body.getName() == null ? "" : body.getName().trim();
        /* 内置配置项的名字是保留字：下拉里两段共用一个选择框，重名就分不清点的是哪一条。
           只查名字，不查地址 —— 同一家服务商配两条不同密钥是合理需求。 */
        for (ModelPresets.Preset preset : ModelPresets.all()) {
            if (preset.label().equalsIgnoreCase(name)) {
                throw new BaseException(ErrorCode.INVALID_PARAMETER,
                        "「" + name + "」是内置配置项的名字，自定义配置项不能与它重名");
            }
        }
        String failure = store.add(name, body.getBaseUrl(), body.getApiKey(), body.getProtocol());
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("name", name);
        out.put("settingsFile", store.filePath());
        out.put("message", "已添加配置「" + name + "」→ " + body.getBaseUrl().trim()
                + "（模型表单顶部的预设下拉里「自定义配置项」下可以选用；列表只显示脱敏密钥）");
        return out;
    }

    /**
     * 按名称删除。
     *
     * 刻意不级联改档案：档案里的地址与密钥是各自独立的明文副本，没有指向本库的引用 ——
     * 若在这里去"清理"档案，改的是用户没要求改的配置。返回文案把这一点说清楚即可。
     */
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam("name") String name) {
        if (!store.delete(name)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "自定义配置项里没有名为「" + name + "」的条目");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("name", name.trim());
        out.put("settingsFile", store.filePath());
        out.put("message", "已删除自定义配置「" + name.trim() + "」。"
                + "已保存的模型档案不受影响：它们各自保存的是独立副本，不是对这一条的引用。");
        return out;
    }

    /**
     * 取一条连接（含明文密钥）—— 下拉里选中某个名称时调用一次。
     *
     * 刻意做成按名单取而不是列表里一并返回：明文只在用户点了具体某个名称之后才离开磁盘，
     * 而不是每次页面加载都把全库拉进浏览器。该条目没配密钥时返回 {@code apiKey} 空串，
     * 前端据此只填地址、不去动已经填好的 Key。
     */
    @PostMapping("/resolve")
    public Map<String, Object> resolve(@RequestParam("name") String name) {
        ProviderConnectionStore.ProviderConnection connection = store.resolve(name)
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_PARAMETER,
                        "自定义配置项里没有名为「" + name + "」的条目（可能已被删除，请重新展开下拉）"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("name", connection.name());
        out.put("baseUrl", connection.baseUrl());
        out.put("protocol", connection.protocol());
        out.put("apiKey", connection.apiKey());
        out.put("hasApiKey", connection.hasApiKey());
        return out;
    }

    /** 新增的请求体（{@code apiKey} 可空） */
    @Data
    public static class ConnectionBody {
        private String name;
        /** 协议类型名；留空 = OpenAI 兼容 */
        private String protocol;
        private String baseUrl;
        private String apiKey;
    }
}