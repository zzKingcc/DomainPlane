package com.zzkingcc.domainplane.server.tool;

import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.runtime.tool.InstanceEndpoint;
import com.zzkingcc.domainplane.runtime.tool.ParamSchemaRenderer;
import com.zzkingcc.domainplane.runtime.tool.ParamTextTree;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置工具文案覆盖的唯一写入口。
 *
 * <p><b>为什么要在启动时留一份注解基线</b>：覆盖是写进 {@link ToolRegistry} 的条目里的，
 * 写完原始注解值就不在注册表里了。若下一次保存以「当前值」当基线，第二次覆盖会把第一次
 * 的文案当成原文；清除覆盖也回不去。所以启动时（任何覆盖生效之前）先把每个本地工具的
 * description 与 Param 树快照下来，之后所有生效值都是「基线 + 覆盖」实时算出的。</p>
 *
 * <p><b>非文字字段一律取注册表当前值</b>：{@code declareDomains} 会在运行期改可用域，
 * 若按快照重建 descriptor 会把域绑回启动时的状态。文字取基线，其它取当前，互不干扰。</p>
 */
@Component
public class ToolTextOverlay implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolTextOverlay.class);

    /** 启动期采集的注解原文；只在 run() 里填充一次 */
    public record CodeBaseline(String description, List<ToolDescriptor.Param> params) {
    }

    private final ToolRegistry registry;
    private final ToolTextStore store;
    private final Map<String, CodeBaseline> baselines = new ConcurrentHashMap<>();

    public ToolTextOverlay(ToolRegistry registry, ToolTextStore store) {
        this.registry = registry;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        captureBaselines();
        replay();
    }

    /** 采集必须在任何覆盖生效之前；容器就绪时本地 Bean 已全部注册完 */
    public void captureBaselines() {
        baselines.clear();
        for (ToolRegistry.Registered registered : registry.all()) {
            if (!isLocal(registered)) {
                continue;
            }
            ToolDescriptor d = registered.descriptor();
            baselines.put(d.name(), new CodeBaseline(d.description(), d.params()));
        }
        log.info("[工具文案] 采集到 {} 个内置工具的注解基线", baselines.size());
    }

    /** 重启后把已保存的覆盖重新套上；路径已失效的项会被过滤，不会带坏整个启动 */
    public void replay() {
        ToolTextStore.ToolTextFile saved = store.load();
        for (Map.Entry<String, ToolTextStore.Entry> entry : saved.tools().entrySet()) {
            apply(entry.getKey(), entry.getValue());
        }
        if (!saved.tools().isEmpty()) {
            log.info("[工具文案] 已重放 {} 个工具的文案覆盖", saved.tools().size());
        }
    }

    /** 是否本地 Bean 工具：副本地址里有 local 项，与注册时的同名保护用同一判据 */
    public static boolean isLocal(ToolRegistry.Registered registered) {
        return registered.endpoints().stream().anyMatch(InstanceEndpoint::isLocal);
    }

    /** 来源分类：本地 / MCP / 远程。判据是副本身份，不是 source 字符串（后者只作排障标识） */
    public static String kind(ToolRegistry.Registered registered) {
        if (isLocal(registered)) {
            return "LOCAL";
        }
        if (registered.endpoints().stream()
                .anyMatch(e -> e.instanceId().startsWith("mcp:"))) {
            return "MCP";
        }
        return "REMOTE";
    }

    public CodeBaseline baseline(String name) {
        return baselines.get(name);
    }

    /**
     * 保存一个工具的全部覆盖项（全量替换语义）。
     * 空白的工具说明 = 清除该项；空白的参数说明 = 清除该路径；两者都空 = 整条删除。
     *
     * @throws IllegalArgumentException 工具不是内置工具，或参数路径在当前注解里不存在
     */
    public synchronized void save(String name, String description,
                                  Map<String, String> paramDescriptions) {
        requireEditable(name);
        CodeBaseline base = requireBaseline(name);
        Map<String, String> raw = paramDescriptions == null ? Map.of() : paramDescriptions;
        List<String> unknown = ParamTextTree.unknownPaths(base.params(), raw.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("参数路径不存在：" + String.join("、", unknown)
                    + "；该工具的参数结构可能已变化");
        }
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                clean.put(e.getKey(), e.getValue().trim());
            }
        }
        String desc = description == null || description.isBlank() ? null : description.trim();
        if (desc == null && clean.isEmpty()) {
            reset(name);
            return;
        }
        ToolTextStore.Baseline snapshot = new ToolTextStore.Baseline(base.description(),
                ParamTextTree.baselineDescriptions(base.params()));
        ToolTextStore.Entry entry = new ToolTextStore.Entry(desc, clean, snapshot);
        store.update(name, entry);
        apply(name, entry);
    }

    /** 整条恢复默认 */
    public synchronized void reset(String name) {
        requireEditable(name);
        store.update(name, null);
        apply(name, null);
    }

    /**
     * 把覆盖套到注册表上：文字取「注解基线 + 覆盖」，其余字段取当前条目。
     * 传入 {@code null} 即恢复默认。
     */
    public void apply(String name, ToolTextStore.Entry entry) {
        ToolRegistry.Registered current = registry.find(name).orElse(null);
        if (current == null || !isLocal(current)) {
            return;
        }
        CodeBaseline base = baselines.get(name);
        if (base == null) {
            log.warn("[工具文案] {} 没有注解基线，跳过覆盖", name);
            return;
        }
        String desc = entry == null || entry.description() == null || entry.description().isBlank()
                ? base.description() : entry.description();
        Map<String, String> overrides = entry == null ? Map.of()
                : cleanPaths(entry.paramDescriptions(), base.params());
        List<ToolDescriptor.Param> params = ParamTextTree.withDescriptions(base.params(), overrides);

        ToolDescriptor old = current.descriptor();
        ToolDescriptor updated = new ToolDescriptor(old.name(), desc, old.category(), old.version(),
                old.sideEffect(), old.idempotent(), old.toModel(), params, old.domains(),
                old.approval(), old.source());
        ToolSpecification specification = ToolSpecification.builder()
                .name(old.name())
                .description(desc)
                .parameters(ParamSchemaRenderer.toJsonSchema(params))
                .build();
        registry.replaceLocalText(name, updated, specification);
    }

    /** 管控台工具目录：一次读盘 + 一次遍历，把生效值、基线、覆盖状态一起算出来 */
    public List<Map<String, Object>> catalog() {
        ToolTextStore.ToolTextFile saved = store.load();
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolRegistry.Registered registered : registry.all()) {
            ToolDescriptor d = registered.descriptor();
            String kind = kind(registered);
            CodeBaseline base = baselines.get(d.name());
            List<ToolDescriptor.Param> baseParams = base == null ? d.params() : base.params();
            String baseDescription = base == null ? d.description() : base.description();
            Map<String, String> effectiveParams = ParamTextTree.baselineDescriptions(d.params());
            ToolTextStore.Entry entry = saved.tools().get(d.name());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", d.name());
            item.put("kind", kind);
            item.put("editable", "LOCAL".equals(kind));
            item.put("description", d.description());
            item.put("baselineDescription", baseDescription);
            item.put("overridden", entry != null);
            item.put("drift", drift(entry, base));
            item.put("params", paramViews(baseParams, effectiveParams));
            item.put("source", d.source());
            item.put("domains", d.domains());
            item.put("replicaCount", registered.endpoints().size());
            item.put("sideEffect", d.sideEffect() == null ? null : d.sideEffect().name());
            item.put("requiresApproval", d.requiresApproval());
            items.add(item);
        }
        items.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return items;
    }

    private List<Map<String, Object>> paramViews(List<ToolDescriptor.Param> baseParams,
                                                 Map<String, String> effectiveParams) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (ParamTextTree.Node node : ParamTextTree.flatten(baseParams)) {
            String effective = effectiveParams.getOrDefault(node.path(), node.description());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("path", node.path());
            view.put("name", node.name());
            view.put("type", node.type());
            view.put("depth", node.depth());
            view.put("description", effective);
            view.put("baselineDescription", node.description());
            view.put("overridden", !Objects.equals(effective, node.description()));
            views.add(view);
        }
        return views;
    }

    /** 代码注解改过说明后，旧覆盖仍盖着新文案：只提示，不自动清 */
    private boolean drift(ToolTextStore.Entry entry, CodeBaseline base) {
        if (entry == null || entry.baseline() == null || base == null) {
            return false;
        }
        return !Objects.equals(entry.baseline().description(), base.description())
                || !Objects.equals(entry.baseline().paramDescriptions(),
                ParamTextTree.baselineDescriptions(base.params()));
    }

    /** 覆盖文件里的路径可能因注解升级而失效：静默丢弃，不能让它带坏启动 */
    private Map<String, String> cleanPaths(Map<String, String> raw,
                                           List<ToolDescriptor.Param> baseParams) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> clean = new LinkedHashMap<>();
        Map<String, String> known = ParamTextTree.baselineDescriptions(baseParams);
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (known.containsKey(e.getKey()) && e.getValue() != null && !e.getValue().isBlank()) {
                clean.put(e.getKey(), e.getValue());
            }
        }
        return clean;
    }

    private void requireEditable(String name) {
        ToolRegistry.Registered registered = registry.find(name).orElse(null);
        if (registered == null) {
            throw new IllegalArgumentException("工具不存在：" + name);
        }
        if (!isLocal(registered)) {
            throw new IllegalArgumentException("只有内置工具可以修改文案；" + name
                    + " 的声明来自" + kind(registered) + "，管控台只读");
        }
    }

    private CodeBaseline requireBaseline(String name) {
        CodeBaseline base = baselines.get(name);
        if (base == null) {
            throw new IllegalStateException("工具 " + name + " 的注解基线未采集，服务可能仍在启动中");
        }
        return base;
    }
}
