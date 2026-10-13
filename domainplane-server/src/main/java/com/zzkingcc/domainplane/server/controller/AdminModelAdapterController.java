package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理面：服务商适配的匹配查询。
 *
 * 两个接口都**只读**：匹配不落盘、不依赖已存档案，纯粹回答「这个 baseUrl + 模型名
 * 有没有适配、适配的调优 schema 是什么」。管控台据此决定参数调优工具栏的显隐与渲染。
 *
 * 「未适配就不能调优」的强制点在档案保存时的复核（{@code AdminModelProfileController#save}），
 * 不在这里 —— 本接口被绕过不影响任何状态。
 *
 * 路径在 {@code /admin/**} 之下，鉴权由凭证拦截器统一处理。
 *
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin")
public class AdminModelAdapterController {

    private final ModelAdapterRegistry registry;

    public AdminModelAdapterController(ModelAdapterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 试匹配：用户一边填表一边问「这家这个模型有没有适配」。
     *
     * 响应形状按设计文档 §4.3：{@code adapter.params} 直接是 chat 族的参数 schema 数组
     * （P0 只消费 chat 族）。不下发内部结构（match / families map）—— 前端不该
     * 依赖服务端存储形态，多族参数进来时（P1）再长响应字段。
     *
     * 请求体两个字段都可空：空 baseUrl + 空模型名 = 必然未命中（前端清空表单时也会调）。
     */
    @PostMapping("/model-adapters/match")
    public Map<String, Object> match(@RequestBody(required = false) MatchBody body) {
        String baseUrl = body == null ? null : body.getBaseUrl();
        String modelName = body == null ? null : body.getModelName();
        ModelAdapterRegistry.MatchResult result = registry.match(baseUrl, modelName);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("matched", result.matched());
        out.put("adapter", result.matched() ? adapterView(result.adapter()) : null);
        return out;
    }

    /**
     * 命中适配的下发形态：id / label / docUrl + 参数 schema（文档 §4.3）。
     *
     * 下发**全部族的并集**而不是只取 chat 族：新增档案时前端还不知道这个模型归哪一族
     * （类型要等保存后读元数据 / 适配声明才定），只给 chat 族的话，向量适配
     * （families 只有 embedding）会下发一个空 params，维度控件就出不来。
     * 一个适配通常只登记一族，并集与该族一致；真出现多族时也只是多摆几项，不影响保存复核。
     */
    private static Map<String, Object> adapterView(ModelAdapterRegistry.Adapter a) {
        Map<String, Object> ad = new LinkedHashMap<>();
        ad.put("id", a.id());
        ad.put("label", a.label());
        ad.put("docUrl", a.docUrl());
        ad.put("params", a.allParams());
        return ad;
    }

    /** 全部适配清单（排障用；管控台不展示） */
    @GetMapping("/model-adapters")
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("adapters", registry.all());
        return out;
    }

    /** 试匹配请求体 */
    @Data
    public static class MatchBody {
        private String baseUrl;
        private String modelName;
    }
}
