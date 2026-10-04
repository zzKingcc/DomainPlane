package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 业务面：<b>可调用单元</b>清单查询。
 *
 * <p>用途是让调用方（尤其是 starter 的启动期）确认自己要用的域能不能当入口 ——
 * 域名写错、或把一个只做装配的父域当入口，都在启动阶段暴露，
 * 而不是等第一次线上调用才收到 10004 / 10010。</p>
 *
 * <p>只返回<b>可调用</b>的域：装配节点（父域）不出现在这里，它们不是入口。</p>
 *
 * <p>路径在 {@code /api/agent/**} 之下，因此与其它业务接口一样需要凭证。</p>
 *
 * @author zzkingcc
 */
@RestController
public class DomainController {

    private final ToolRouter toolRouter;
    private final DomainRegistry domainRegistry;

    public DomainController(ToolRouter toolRouter, DomainRegistry domainRegistry) {
        this.toolRouter = toolRouter;
        this.domainRegistry = domainRegistry;
    }

    /**
     * 列出可调用的域。
     *
     * @return {@code code=0}；{@code domains} 可调用域标识；{@code details} 每个域的来源与可见工具数；
     *         {@code fallback} 根域标识（恒为可调用，供调用方对齐语义）
     */
    @GetMapping("/api/agent/domains")
    public Map<String, Object> list() {
        Set<String> ids = toolRouter.callableDomains();
        List<Map<String, Object>> details = new ArrayList<>();
        for (String id : new TreeSet<>(ids)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("source", sourceOf(id));
            item.put("toolCount", visibleToolCount(id));
            item.put("callable", true);
            details.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("fallback", Domains.DEFAULT);
        result.put("domains", ids);
        result.put("details", details);
        return result;
    }

    /**
     * 域来源：内置 / 人工创建 / 工具派生。
     */
    private String sourceOf(String domainId) {
        DomainRegistry.Source source = domainRegistry.sourceOf(domainId);
        // 未登记的域只可能是尚未被扫描进来的工具声明，按派生计
        return source == null ? DomainRegistry.Source.DERIVED.name() : source.name();
    }

    /**
     * 该域下当前可见的工具数（含通配工具）。
     */
    private long visibleToolCount(String domainId) {
        return toolRouter.toolDescriptors().stream()
                .filter(descriptor -> descriptor.visibleIn(domainId))
                .count();
    }
}
