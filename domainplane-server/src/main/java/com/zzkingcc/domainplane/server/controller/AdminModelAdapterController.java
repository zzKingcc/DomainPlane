package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.server.model.ModelAdapterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理面：适配声明的排障入口（只读）。
 *
 * <p>适配目录现在只剩两件事：<b>不发上游就能定端点族 / 模态</b>（{@code declaration}，
 * 建档案时用），以及向量族的 {@code dimensions} schema。对话族的参数 schema 已下架，
 * 原来那个「边填表边问 schema」的试匹配接口（{@code POST /model-adapters/match}）随之删除 ——
 * 它唯一的下游是对话卡片的绿色调优框，那个框已经不存在了。</p>
 *
 * <p>留下清单接口是为了排障：档案「未声明类型」或端点族判错时，第一件要确认的事是
 * 「这条 baseUrl + 模型名到底命中了哪份适配」，没有它就只能翻日志。</p>
 *
 * <p>路径在 {@code /admin/**} 之下，鉴权由凭证拦截器统一处理。</p>
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

    /** 全部已加载的适配（含 match 声明与各族参数；管控台不展示，仅排障） */
    @GetMapping("/model-adapters")
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("adapters", registry.all());
        return out;
    }
}
