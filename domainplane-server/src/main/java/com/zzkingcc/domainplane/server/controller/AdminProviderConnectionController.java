package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
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
 * 管理面：服务商连接库 —— 模型设置页顶部「连接库」按钮背后的四个接口。
 *
 * 一条连接 = 名称 + 服务商地址 +（可选）密钥，所以选中一次就把档案表单的地址与 Key 一起填好，
 * 不用在两个库之间自己对齐"这条地址配哪把 Key"。
 *
 * 凭据面比档案面更严，体现在只有一处：{@link #resolve(String)}。
 * 列表接口只回地址与脱敏密钥，明文只在用户于下拉里明确点了某个名称时才单独取一次 ——
 * 页面每次打开就把全库明文拉进浏览器内存没有必要，而档案列表里的 Key 本来就是脱敏展示的。
 *
 * 删除不牵连档案：库里存的是"取连接快捷方式"，档案里存的是各自的明文副本，
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
        String failure = store.add(body.getName(), body.getBaseUrl(), body.getApiKey());
        if (failure != null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, failure);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("name", body.getName().trim());
        out.put("settingsFile", store.filePath());
        out.put("message", "已添加连接「" + body.getName().trim() + "」→ " + body.getBaseUrl().trim()
                + "（档案表单的地址右侧下拉里可以一键选用；列表只显示脱敏密钥）");
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
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "连接库里没有名为「" + name + "」的条目");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("name", name.trim());
        out.put("settingsFile", store.filePath());
        out.put("message", "已从连接库删除「" + name.trim() + "」。"
                + "已保存的模型档案不受影响：它们各自保存的是独立副本，不是对库中这一条的引用。");
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
                        "连接库里没有名为「" + name + "」的条目（可能已被删除，请重新展开下拉）"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("success", true);
        out.put("name", connection.name());
        out.put("baseUrl", connection.baseUrl());
        out.put("apiKey", connection.apiKey());
        out.put("hasApiKey", connection.hasApiKey());
        return out;
    }

    /** 新增的请求体（{@code apiKey} 可空） */
    @Data
    public static class ConnectionBody {
        private String name;
        private String baseUrl;
        private String apiKey;
    }
}