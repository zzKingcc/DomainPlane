package com.zzkingcc.domainplane.runtime.tool;

import com.zzkingcc.domainplane.api.tool.ToolDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 参数说明的路径规则 —— 只有这一份定义。
 *
 * <p>工具说明的覆盖按「工具名」存，参数说明按「工具名 + 参数路径」存。路径要同时被
 * 三处使用：管控台展示参数树、保存时定位要替换的节点、启动时校验覆盖是否还挂得住。
 * 各写一份必然漂移，漂移的样子是「改了说明但没生效，页面还不报错」。</p>
 *
 * <pre>
 *   prompt                 顶层参数
 *   filter.status          object 子字段
 *   tags[]                 数组元素本身（元素在 Param 树里没有名字）
 *   tags[].sku             数组元素是对象时，元素的子字段
 * </pre>
 *
 * <p>数组元素那一层也允许改说明：{@code JsonArraySchema.items()} 里的元素对象带自己的
 * description，它就是模型判断「数组里该放什么」的依据。</p>
 */
public final class ParamTextTree {

    private ParamTextTree() {
    }

    /** 参数树上的一个可编辑节点；depth 供管控台做缩进，path 是存储键 */
    public record Node(String path, String name, String type, String description, int depth) {
    }

    /** 展开成线性列表：管控台按 depth 缩进画参数树 */
    public static List<Node> flatten(List<ToolDescriptor.Param> params) {
        List<Node> nodes = new ArrayList<>();
        walkNamed(params, "", 0, nodes);
        return List.copyOf(nodes);
    }

    private static void walkNamed(List<ToolDescriptor.Param> params, String prefix,
                                  int depth, List<Node> nodes) {
        if (params == null || params.isEmpty()) {
            return;
        }
        for (ToolDescriptor.Param param : params) {
            if (param.name() == null || param.name().isBlank()) {
                continue;
            }
            String path = prefix.isEmpty() ? param.name() : prefix + "." + param.name();
            nodes.add(new Node(path, param.name(), param.type(), param.description(), depth));
            walkNamed(param.properties(), path, depth + 1, nodes);
            walkItems(param.items(), path, depth + 1, nodes);
        }
    }

    private static void walkItems(List<ToolDescriptor.Param> items, String parentPath,
                                  int depth, List<Node> nodes) {
        if (items == null || items.isEmpty()) {
            return;
        }
        ToolDescriptor.Param item = items.get(0);
        String path = parentPath + "[]";
        nodes.add(new Node(path, "[]", item.type(), item.description(), depth));
        walkNamed(item.properties(), path, depth + 1, nodes);
        walkItems(item.items(), path, depth + 1, nodes);
    }

    /** 基线说明：path → 注解里原本的 description。存进覆盖文件用于检测上游文案变化 */
    public static Map<String, String> baselineDescriptions(List<ToolDescriptor.Param> params) {
        Map<String, String> baseline = new LinkedHashMap<>();
        for (Node node : flatten(params)) {
            baseline.put(node.path(), node.description());
        }
        return baseline;
    }

    /**
     * 按路径替换说明，返回一棵新的 Param 树；原树不变。
     * 覆盖值不存在或为空白时保留基线值 —— 空白在保存口已被解释成「删除该项覆盖」，
     * 这里再兜一层，避免把空字符串写进发给模型的 schema。
     */
    public static List<ToolDescriptor.Param> withDescriptions(
            List<ToolDescriptor.Param> params, Map<String, String> overrides) {
        Map<String, String> effective = overrides == null ? Map.of() : overrides;
        return rewriteNamed(params, "", effective);
    }

    /**
     * 覆盖表里挂不住的路径：工具注解改了参数结构后，旧覆盖会静默失效。
     * 保存时用来拒绝未知路径，列表页用来标「参数结构已变化」。
     */
    public static List<String> unknownPaths(List<ToolDescriptor.Param> params,
                                            Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        Set<String> known = baselineDescriptions(params).keySet();
        List<String> unknown = new ArrayList<>();
        for (String path : paths) {
            if (!known.contains(path)) {
                unknown.add(path);
            }
        }
        return List.copyOf(unknown);
    }

    private static List<ToolDescriptor.Param> rewriteNamed(
            List<ToolDescriptor.Param> params, String prefix, Map<String, String> overrides) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        List<ToolDescriptor.Param> result = new ArrayList<>(params.size());
        for (ToolDescriptor.Param param : params) {
            if (param.name() == null || param.name().isBlank()) {
                continue;
            }
            String path = prefix.isEmpty() ? param.name() : prefix + "." + param.name();
            result.add(rebuild(param, path, overrides,
                    rewriteNamed(param.properties(), path, overrides),
                    rewriteItems(param.items(), path, overrides)));
        }
        return List.copyOf(result);
    }

    private static List<ToolDescriptor.Param> rewriteItems(
            List<ToolDescriptor.Param> items, String parentPath, Map<String, String> overrides) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        ToolDescriptor.Param item = items.get(0);
        String path = parentPath + "[]";
        return List.of(rebuild(item, path, overrides,
                rewriteNamed(item.properties(), path, overrides),
                rewriteItems(item.items(), path, overrides)));
    }

    private static ToolDescriptor.Param rebuild(ToolDescriptor.Param param, String path,
                                                Map<String, String> overrides,
                                                List<ToolDescriptor.Param> properties,
                                                List<ToolDescriptor.Param> items) {
        String override = overrides.get(path);
        String description = override == null || override.isBlank()
                ? param.description() : override;
        return new ToolDescriptor.Param(param.name(), param.type(), description,
                param.required(), param.allowValues(), param.example(), param.sensitive(),
                properties, items);
    }
}
