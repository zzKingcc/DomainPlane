package com.zzkingcc.domainplane.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 服务商适配注册表 —— 「OpenAI 兼容基底 + 声明式适配」的匹配权威。
 *
 * 适配文件是 {@code classpath:adapters/*.json}（一服务商一文件），登记
 * 「哪个服务商 + 哪个模型 → 暴露哪些调优参数」。未登记的服务商 / 模型
 * 一律按纯 OpenAI 直连处理，不给参数调优。
 *
 * 匹配三级降级（第一个命中即用）：
 *
 *  ① {@code match.models} 模型名精确（忽略大小写）—— 模型个体差异在此层表达；
 *  ② {@code match.modelPatterns} 模型名正则（**全串匹配**，语义等同 {@code Pattern.matches}）—— 系列模型在此层表达；
 *  ③ {@code match.hosts} 服务商 host（归一化后）—— 服务商级兜底。
 *
 * 同级多个适配命中按 {@code id} 字典序取第一个 —— 确定性强，不依赖文件列举顺序。
 *
 * 加载策略：坏文件只记 ERROR 并跳过，**不影响启动与其余文件**；适配文件是 curated 数据，
 * 一个手误不该让整个服务起不来。
 *
 * @author zzkingcc
 */
@Slf4j
public class ModelAdapterRegistry {

    /** 支持的端点族； families 里出现别的键视为 schema 错误（curated 文件，不静默吞） */
    public static final Set<String> FAMILIES = Set.of("chat", "embedding", "images", "rerank", "tts", "asr");

    /**
     * 模态词表（与 {@link ModelProfile} 的 {@code MOD_*} 一一对应）。
     * 适配文件里写错一个词就加载失败 —— curated 文件，宁可炸在启动期，也别让卡片上出现一个
     * 谁也不认识的模态标签。
     */
    private static final Set<String> MODALITIES =
            Set.of("text", "image", "audio", "video", "file", "embedding", "score");

    /** 参数类型词表；{@code stringList} 的值是字符串数组（如 stop），其 min / max 语义是**元素条数** */
    private static final Set<String> PARAM_TYPES =
            Set.of("number", "int", "bool", "enum", "string", "stringList");

    /**
     * std=true 只允许绑这几个老字段（与 ModelProfile 既有字段一一对应）。
     *
     * {@code dimensions} 也在其列：向量维度落的是 {@link ModelProfile#dimensions()} 专用字段，
     * 不进 params 通用表 —— 它要参与 ES 索引契约与灌库校验，混进"服务商私有参数"里没人把关。
     */
    private static final Set<String> STD_WIRES = Set.of("temperature", "max_tokens", "dimensions");

    /**
     * 参数控件的可选呈现方式；缺省（null）= 按 type 走默认控件。
     * {@code bar} = 把有序枚举渲染成分档进度条（如「思考强度」none→low→high→max）。
     * 只对 enum 成立 —— 分档的前提是取值有序，只有枚举自带这个顺序（options 数组顺序即档位顺序）。
     */
    private static final Set<String> PARAM_DISPLAYS = Set.of("bar");

    private final List<Adapter> adapters;

    /** 生产构造：启动时从 classpath 加载全部适配文件 */
    public ModelAdapterRegistry(ResourceLoader resourceLoader) {
        this.adapters = load(resourceLoader);
    }

    private ModelAdapterRegistry(List<Adapter> adapters) {
        /* 两个入口都排序：同级命中按 id 字典序的确定性不能依赖"恰好从 load 进来" */
        List<Adapter> sorted = new ArrayList<>(adapters);
        sorted.sort(Comparator.comparing(Adapter::id));
        this.adapters = List.copyOf(sorted);
    }

    /** 测试用：直接给适配列表，不走 classpath */
    public static ModelAdapterRegistry of(List<Adapter> adapters) {
        return new ModelAdapterRegistry(adapters);
    }

    /** 全部适配（按 id 排序，不可变） */
    public List<Adapter> all() {
        return adapters;
    }

    // ==================== 匹配 ====================

    /**
     * 按 baseUrl + modelName 匹配适配；三级全不中返回 {@link MatchResult#miss()}。
     *
     * @param baseUrl    服务商地址（可缺 scheme、可带路径与端口，host 归一化后参与 ③）
     * @param modelName  模型名（trim 后参与 ①②）
     */
    public MatchResult match(String baseUrl, String modelName) {
        String model = modelName == null ? "" : modelName.trim();
        // ① 模型名精确
        for (Adapter a : adapters) {
            for (String m : a.match().models()) {
                if (!m.isBlank() && m.trim().equalsIgnoreCase(model)) {
                    return new MatchResult(true, a, MatchLevel.MODEL);
                }
            }
        }
        // ② 模型名正则（全串匹配）
        if (!model.isEmpty()) {
            for (Adapter a : adapters) {
                for (String p : a.match().modelPatterns()) {
                    if (!p.isBlank() && model.matches(p)) {
                        return new MatchResult(true, a, MatchLevel.PATTERN);
                    }
                }
            }
        }
        // ③ 服务商 host
        String host = hostOf(baseUrl);
        if (host != null) {
            for (Adapter a : adapters) {
                for (String h : a.match().hosts()) {
                    if (!h.isBlank() && h.trim().toLowerCase(Locale.ROOT).equals(host)) {
                        return new MatchResult(true, a, MatchLevel.HOST);
                    }
                }
            }
        }
        return MatchResult.MISS;
    }

    /**
     * 适配文件里对这个**模型**的声明：端点族 + 输入 / 输出模态。
     *
     * 存在的理由：只有 OpenRouter 那类综合提供商才会在接口里说明模型吃什么、产出什么；
     * OpenAI / DeepSeek 官方的清单只有 id/object/created/owned_by，读不出来。而这些内置模型
     * 我们本来就是**按官方文档登记过**的 —— 端点族就是登记了参数的族名，模态就在同一个文件里。
     * 于是"先看适配文件有没有，再去读元数据"：内置模型一次上游请求都不用发。
     *
     * 只有**按模型名命中**（① 精确 / ② 正则）才返回：host 兜底命中说明"这家服务商我们登记过，
     * 但这个模型没登记"，拿同 host 另一个模型的声明往它头上套就是张冠李戴 ——
     * {@code api.openai.com} 上的 {@code text-embedding-3-*} 会被套上 GPT 的 text → text。
     *
     * @return 未按模型名命中时为空
     */
    public Optional<Declaration> declaration(String baseUrl, String modelName) {
        MatchResult m = match(baseUrl, modelName);
        if (!m.byModelName()) {
            return Optional.empty();
        }
        Adapter a = m.adapter();
        return Optional.of(new Declaration(a.id(), a.endpoints(), a.input(), a.output()));
    }

    /**
     * baseUrl → 归一化 host：无 scheme 补 {@code https://}；转小写；去尾点；
     * **端口不参与匹配**（{@code URI.getHost()} 本就不含端口），路径忽略。
     * 解析失败返回 {@code null}（该输入不可能命中 ③，但 ①② 仍可命中）。
     */
    static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String s = baseUrl.trim();
        if (!s.contains("://")) {
            s = "https://" + s;
        }
        try {
            String host = URI.create(s).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            while (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            return host.isBlank() ? null : host;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 保存复核 ====================

    /**
     * 校验档案 params 是否与命中适配的 schema 相符。
     *
     * @return {@code null} = 通过；否则为可直接展示给用户的拒绝原因
     */
    public String validateParams(MatchResult match, Map<String, Object> params) {
        if (!match.matched() || params == null || params.isEmpty()) {
            return null;
        }
        Map<String, ParamSpec> byKey = new LinkedHashMap<>();
        /* 全部族的参数都进索引：values 里既有 chat 族的私有 / 标准参数，也有 embedding 族的
           dimensions —— 只认 chat 族会让向量档案的维度绕过 min / max 校验 */
        for (ParamSpec spec : match.allParams()) {
            byKey.put(spec.key(), spec);
        }
        for (Map.Entry<String, Object> e : params.entrySet()) {
            ParamSpec spec = byKey.get(e.getKey());
            if (spec == null) {
                return "调优参数「" + e.getKey() + "」未在适配 " + match.adapter().id()
                        + " 中登记（可用：" + String.join("、", byKey.keySet()) + "）";
            }
            /* 条件参数只在依赖取到指定值时可填：值存在但条件不成立一律拒绝，不静默丢弃 ——
               丢弃会让用户以为"保存成功了、填的值在生效"，而实际一个字都没发出去 */
            if (!enabled(spec, params)) {
                return "调优参数「" + spec.key() + "」当前不可填（依赖「" + spec.enabledWhen().key()
                        + "」取值为 " + joinValues(spec.enabledWhen().values()) + "）";
            }
            String err = checkValue(spec, e.getValue());
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    /**
     * 条件参数此刻是否可填（无条件的参数恒为 true）。
     *
     * 语义与前端 {@code models.html} 的 {@code tuneParamEnabled} 必须一致 —— 两份实现同一套规则，
     * 前端管"控件灰不灰"，这里管"收不收"。判定口径：取依赖参数的当前值，未填则取
     * {@link EnabledWhen#missing()}（再为空即不命中）。
     *
     * @param values 摊平后的全部调优值（私有参数 + 标准参数），键为适配 key
     */
    static boolean enabled(ParamSpec spec, Map<String, Object> values) {
        EnabledWhen w = spec.enabledWhen();
        if (w == null) {
            return true;
        }
        Object v = values == null ? null : values.get(w.key());
        if (v == null || (v instanceof String s && s.isBlank())) {
            v = w.missing();
        }
        if (v == null) {
            return false;
        }
        String want = scalar(v);
        for (Object allowed : w.values()) {
            if (scalar(allowed).equals(want)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 标量的比较形态：数值取「整数值去掉小数点」的写法，使 {@code 1} 与 {@code 1.0}、
     * JSON 的 {@code true} 与前端回读的 {@code "true"} 落在同一个签名上。
     */
    private static String scalar(Object v) {
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return String.valueOf(v).trim();
    }

    /** 按 schema 校验单个参数值；{@code null} = 通过 */
    static String checkValue(ParamSpec spec, Object value) {
        String where = "调优参数「" + spec.key() + "」";
        switch (spec.type()) {
            case "bool":
                if (value instanceof Boolean) {
                    return null;
                }
                if (value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
                    return null;
                }
                return where + "只能是 true / false";
            case "number":
            case "int": {
                Double d = toDouble(value);
                if (d == null) {
                    return where + "需要一个数值";
                }
                if (d.isNaN() || d.isInfinite()) {
                    return where + "数值非法";
                }
                if ("int".equals(spec.type()) && d != Math.floor(d)) {
                    return where + "只能是整数";
                }
                if (spec.min() != null && d < spec.min()) {
                    return where + "不能小于 " + trimNum(spec.min());
                }
                if (spec.max() != null && d > spec.max()) {
                    return where + "不能大于 " + trimNum(spec.max());
                }
                return null;
            }
            case "enum": {
                String s = value == null ? null : String.valueOf(value);
                for (Option o : spec.options()) {
                    if (o.value().equals(s)) {
                        return null;
                    }
                }
                return where + "只能是：" + spec.options().stream()
                        .map(Option::value).reduce((a, b) -> a + " / " + b).orElse("");
            }
            case "stringList": {
                /* 上游多数是 string | string[]（如 stop），两种都收；空数组不视为"有效值"：
                   清除语义是"不发这个键"，发一个空数组只是把一条无意义字段塞进请求体 */
                List<?> items;
                if (value instanceof List<?> l) {
                    items = l;
                } else if (value instanceof String s && !s.isBlank()) {
                    items = List.of(s);
                } else {
                    return where + "只能是字符串数组（或单个非空字符串）";
                }
                if (items.isEmpty()) {
                    return where + "不能是空数组";
                }
                for (Object item : items) {
                    if (!(item instanceof String s) || s.isBlank()) {
                        return where + "的元素只能是非空文本";
                    }
                }
                if (spec.min() != null && items.size() < spec.min()) {
                    return where + "至少 " + trimNum(spec.min()) + " 条";
                }
                if (spec.max() != null && items.size() > spec.max()) {
                    return where + "最多 " + trimNum(spec.max()) + " 条";
                }
                return null;
            }
            default:
                String s = value == null ? null : String.valueOf(value);
                if (s == null || s.isBlank()) {
                    return where + "不能为空";
                }
                return null;
        }
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String trimNum(double d) {
        return d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** 条件取值列表 → 一行可读文案（只用在拒绝原因里） */
    private static String joinValues(List<Object> values) {
        return values.stream().map(String::valueOf).reduce((a, b) -> a + " / " + b).orElse("");
    }

    // ==================== 加载 ====================

    private static List<Adapter> load(ResourceLoader resourceLoader) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(resourceLoader);
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath:adapters/*.json");
        } catch (IOException e) {
            log.error("[服务商适配] 扫描 classpath:adapters/*.json 失败：{}", e.getMessage());
            return List.of();
        }
        /* 按文件名排序：跨文件 id 重复时"先到先得"的判定因此确定 */
        Arrays.sort(resources, Comparator.comparing(Resource::getFilename,
                Comparator.nullsLast(Comparator.naturalOrder())));
        ObjectMapper mapper = new ObjectMapper();
        List<Adapter> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Resource r : resources) {
            String name = r.getFilename();
            try (InputStream in = r.getInputStream()) {
                Adapter a = parseAdapter(mapper.readTree(in), name);
                if (!seen.add(a.id())) {
                    log.error("[服务商适配] 适配 id 重复：{}（{} 已加载，后者跳过）", a.id(), name);
                    continue;
                }
                out.add(a);
            } catch (Exception e) {
                /* 单个文件坏只跳过它：适配是 curated 数据，一处手误不该让服务起不来 */
                log.error("[服务商适配] 适配文件 {} 加载失败，已跳过（不影响其余适配与启动）：{}",
                        name, e.getMessage());
            }
        }
        out.sort(Comparator.comparing(Adapter::id));
        if (!out.isEmpty()) {
            log.info("[服务商适配] 已加载 {} 条适配：{}", out.size(),
                    out.stream().map(Adapter::id).reduce((a, b) -> a + ", " + b).orElse(""));
        }
        return List.copyOf(out);
    }

    // ==================== 解析与 schema 校验 ====================

    private static Adapter parseAdapter(JsonNode root, String filename) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("不是 JSON 对象");
        }
        String id = reqText(root, "id", filename);
        String provider = reqText(root, "provider", filename);
        String label = reqText(root, "label", filename);
        String docUrl = optText(root, "docUrl", filename);

        JsonNode matchNode = root.get("match");
        if (matchNode == null || !matchNode.isObject()) {
            throw new IllegalArgumentException(filename + ": 缺少 match 对象");
        }
        MatchSpec match = new MatchSpec(
                textList(matchNode, "hosts", filename),
                textList(matchNode, "models", filename),
                textList(matchNode, "modelPatterns", filename));
        if (match.empty()) {
            throw new IllegalArgumentException(filename + ": match 的 hosts / models / modelPatterns 不能全为空");
        }
        for (String p : match.modelPatterns()) {
            try {
                Pattern.compile(p);
            } catch (Exception e) {
                throw new IllegalArgumentException(filename + ": modelPatterns 不是合法正则：" + p);
            }
        }

        JsonNode familiesNode = root.get("families");
        if (familiesNode == null || !familiesNode.isObject() || familiesNode.isEmpty()) {
            throw new IllegalArgumentException(filename + ": 缺少 families（至少一个端点族一段）");
        }
        Map<String, List<ParamSpec>> families = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        Set<String> wires = new LinkedHashSet<>();
        familiesNode.fieldNames().forEachRemaining(family -> {
            if (!FAMILIES.contains(family)) {
                throw new IllegalArgumentException(filename + ": 未知端点族「" + family + "」（允许："
                        + String.join("、", FAMILIES) + "）");
            }
            /* 结构是 families.<族>.params（族名下再套一层 params 数组，见设计文档 §3.2） */
            JsonNode familyNode = familiesNode.get(family);
            JsonNode paramsNode = familyNode == null ? null : familyNode.get("params");
            if (paramsNode == null || !paramsNode.isArray() || paramsNode.isEmpty()) {
                throw new IllegalArgumentException(filename + ": families." + family + ".params 不能为空");
            }
            List<ParamSpec> specs = new ArrayList<>();
            for (JsonNode pn : paramsNode) {
                ParamSpec spec = parseParam(pn, filename + ": families." + family);
                if (!keys.add(spec.key())) {
                    throw new IllegalArgumentException(filename + ": 参数 key 重复：" + spec.key());
                }
                if (!wires.add(spec.wire())) {
                    throw new IllegalArgumentException(filename + ": 参数 wire 重复：" + spec.wire());
                }
                specs.add(spec);
            }
            /* 单条参数自身合法不等于整族自洽（条件指向谁、包不包对象都跨参数）—— 解析完再查一遍 */
            crossCheckFamily(specs, filename + ": families." + family);
            families.put(family, List.copyOf(specs));
        });
        return new Adapter(id, provider, label, docUrl,
                modalityList(root, "input", filename), modalityList(root, "output", filename),
                match, families);
    }

    /**
     * 读一个模态列表并校验词表。
     *
     * 模态写错一个词就加载失败，而不是静默丢弃 —— 丢弃的后果是卡片上少一格标签，
     * 而没人会去翻日志找"为什么 input 少了一项"。
     */
    private static List<String> modalityList(JsonNode root, String field, String filename) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return List.of();
        }
        if (!n.isArray()) {
            throw new IllegalArgumentException(filename + ": " + field + " 只能是数组");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException(filename + ": " + field + " 只能装非空文本");
            }
            String v = item.asText().trim();
            if (!MODALITIES.contains(v)) {
                throw new IllegalArgumentException(filename + ": " + field + " 里的模态「" + v
                        + "」不在词表内（允许：" + String.join("、", MODALITIES) + "）");
            }
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    private static ParamSpec parseParam(JsonNode node, String where) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(where + ": 参数不是 JSON 对象");
        }
        String key = reqText(node, "key", where);
        String label = reqText(node, "label", where);
        String type = reqText(node, "type", where);
        if (!PARAM_TYPES.contains(type)) {
            throw new IllegalArgumentException(where + ": 参数 " + key + " 的 type「" + type + "」不合法（允许："
                    + String.join("、", PARAM_TYPES) + "）");
        }
        String wire = reqText(node, "wire", where);
        boolean std = node.path("std").asBoolean(false);
        if (std && !STD_WIRES.contains(wire)) {
            throw new IllegalArgumentException(where + ": 参数 " + key + " std=true 时 wire 只能是 "
                    + String.join(" / ", STD_WIRES) + "（当前：" + wire + "）");
        }
        if (!std && STD_WIRES.contains(wire)) {
            throw new IllegalArgumentException(where + ": 参数 " + key + " 的 wire「" + wire + "」保留给标准参数"
                    + "（std=true），私有参数不得占用");
        }
        /* 包装只对私有参数成立：标准参数落的是 langchain4j 老字段，值形状由框架定，没法再套一层 */
        String wireWrap = optText(node, "wireWrap", where);
        if (wireWrap != null && std) {
            throw new IllegalArgumentException(where + ": 标准参数 " + key + " 不能有 wireWrap");
        }
        /* 呈现方式只对枚举成立：分档进度条靠的就是 options 的先后顺序，非枚举没有"档"可言 */
        String display = optText(node, "display", where);
        if (display != null) {
            if (!"enum".equals(type)) {
                throw new IllegalArgumentException(where + ": 参数 " + key + " 的 display 只对 enum 成立（当前 type："
                        + type + "）");
            }
            if (!PARAM_DISPLAYS.contains(display)) {
                throw new IllegalArgumentException(where + ": 参数 " + key + " 的 display「" + display + "」不合法（允许："
                        + String.join("、", PARAM_DISPLAYS) + "）");
            }
        }
        Double min = optDouble(node, "min", where, key);
        Double max = optDouble(node, "max", where, key);
        Double step = optDouble(node, "step", where, key);
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException(where + ": 参数 " + key + " 的 min 大于 max");
        }
        /* default 先转成 Java 标量再存：直接留 JsonNode 会让 checkValue 两头都认不出它 ——
           TextNode.toString() 带引号（枚举比对必然不中），NumberNode 又不是 Number（toDouble 返回 null），
           于是文件里写得好好的默认值会被判成"不合法"。数组 / 对象一律拒：默认值就是单个标量。 */
        Object defaultValue = null;
        JsonNode defaultNode = node.get("default");
        if (defaultNode != null && !defaultNode.isNull()) {
            if (!defaultNode.isValueNode()) {
                throw new IllegalArgumentException(where + ": 参数 " + key + " 的 default 只能是标量");
            }
            defaultValue = scalarOf(defaultNode, where + ": 参数 " + key);
        }

        List<Option> options = new ArrayList<>();
        if ("enum".equals(type)) {
            JsonNode optionsNode = node.get("options");
            if (optionsNode == null || !optionsNode.isArray() || optionsNode.isEmpty()) {
                throw new IllegalArgumentException(where + ": 枚举参数 " + key + " 缺少 options");
            }
            Set<String> seen = new HashSet<>();
            for (JsonNode on : optionsNode) {
                String v = reqText(on, "value", where + ": " + key + ".options");
                String l = optText(on, "label", where + ": " + key + ".options");
                if (!seen.add(v)) {
                    throw new IllegalArgumentException(where + ": 枚举参数 " + key + " 的选项值重复：" + v);
                }
                options.add(new Option(v, l == null ? v : l));
            }
        }

        ParamSpec spec = new ParamSpec(key, label, type, wire, wireWrap, std, min, max, step, defaultValue,
                List.copyOf(options), display,
                parseEnabledWhen(node.get("enabledWhen"), where, key));
        /* default 也要过同一套校验：文件里写坏的默认值必须在加载期暴露，而不是等用户保存才炸 */
        if (defaultValue != null) {
            String err = checkValue(spec, defaultValue);
            if (err != null) {
                throw new IllegalArgumentException(where + ": 参数 " + key + " 的 default 不合法 —— " + err);
            }
        }
        return spec;
    }

    /**
     * 解析条件可用性。写成对象而不是一句表达式，因为"依赖谁 / 取哪些值 / 没填怎么算"
     * 三件事缺一件就会让控件灰得没道理，三件都摆在同一处才审得动。
     */
    private static EnabledWhen parseEnabledWhen(JsonNode node, String where, String key) {
        if (node == null || node.isNull()) {
            return null;
        }
        String at = where + ": 参数 " + key + ".enabledWhen";
        if (!node.isObject()) {
            throw new IllegalArgumentException(at + " 只能是对象");
        }
        String depKey = reqText(node, "key", at);
        JsonNode valuesNode = node.get("values");
        if (valuesNode == null || !valuesNode.isArray() || valuesNode.isEmpty()) {
            throw new IllegalArgumentException(at + ".values 不能为空");
        }
        List<Object> values = new ArrayList<>();
        for (JsonNode vn : valuesNode) {
            values.add(scalarOf(vn, at));
        }
        Object missing = node.get("missing") == null || node.get("missing").isNull()
                ? null : scalarOf(node.get("missing"), at);
        return new EnabledWhen(depKey, List.copyOf(values), missing);
    }

    /** JSON 标量 → Java 标量；条件取值只可能是标量，对象 / 数组一律拒 */
    private static Object scalarOf(JsonNode n, String at) {
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isNumber()) {
            return n.numberValue();
        }
        throw new IllegalArgumentException(at + " 的取值只能是文本 / 数值 / 布尔");
    }

    /**
     * 族内交叉校验：条件参数只能指向**同族的普通私有参数**。
     *
     * 三条禁令都是为了让"这个框为什么是灰的"能一句话说清：
     *   • 不能指向自己（自指条件恒不成立，等于把自己永久禁掉）；
     *   • 不能指向另一个条件参数（链式依赖会叠出两级禁用态，用户无从判断到底缺哪个）；
     *   • 不能指向标准参数（temperature / maxTokens 落的是 ModelProfile 老字段，
     *     注入路径看到的是另一份 map，条件判定会飘）。
     *
     * 条件参数**可以**声明 default：补缺值也要过 {@link #enabled} 这道门（见
     * {@code ModelClientFactory#effectiveParams}），条件不成立时连默认值都不发射，
     * 两者不会打架。允许它之后，官方写明默认值但只在特定档位生效的参数（如 DeepSeek 的
     * temperature，默认 1、仅非思考模式生效）也能在界面上把默认值说清楚，而不是笼统一句"服务商默认"。
     */
    static void crossCheckFamily(List<ParamSpec> specs, String where) {
        Map<String, ParamSpec> byKey = new LinkedHashMap<>();
        for (ParamSpec s : specs) {
            byKey.put(s.key(), s);
        }
        for (ParamSpec s : specs) {
            EnabledWhen w = s.enabledWhen();
            if (w == null) {
                continue;
            }
            String at = where + ": 参数 " + s.key();
            ParamSpec dep = byKey.get(w.key());
            if (dep == null) {
                throw new IllegalArgumentException(at + " 的 enabledWhen.key「" + w.key()
                        + "」不是同族参数（可用：" + String.join("、", byKey.keySet()) + "）");
            }
            if (dep == s) {
                throw new IllegalArgumentException(at + " 的 enabledWhen 不能指向自己（条件恒不成立）");
            }
            if (dep.enabledWhen() != null) {
                throw new IllegalArgumentException(at + " 的 enabledWhen 指向了条件参数 " + dep.key()
                        + "（不允许链式依赖）");
            }
            if (dep.std()) {
                throw new IllegalArgumentException(at + " 的 enabledWhen 指向了标准参数 " + dep.key()
                        + "（标准参数不在 params map 里，条件判定会飘）");
            }
            for (Object v : w.values()) {
                checkAllowedValue(dep, v, at);
            }
            if (w.missing() != null) {
                checkAllowedValue(dep, w.missing(), at);
            }
        }
    }

    /** 条件取值必须落在被依赖参数自己的取值域里：写错一个字母就永远灰着的框，加载期就得拦住 */
    private static void checkAllowedValue(ParamSpec dep, Object v, String at) {
        String prefix = at + " 的 enabledWhen 取值「" + v + "」";
        switch (dep.type()) {
            case "enum": {
                for (Option o : dep.options()) {
                    if (o.value().equals(String.valueOf(v))) {
                        return;
                    }
                }
                throw new IllegalArgumentException(prefix + "不是参数 " + dep.key() + " 的合法选项");
            }
            case "bool": {
                if (v instanceof Boolean
                        || (v instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")))) {
                    return;
                }
                throw new IllegalArgumentException(prefix + "不是布尔值");
            }
            case "number":
            case "int": {
                if (toDouble(v) != null) {
                    return;
                }
                throw new IllegalArgumentException(prefix + "不是数值");
            }
            default: {
                if (v instanceof String s && !s.isBlank()) {
                    return;
                }
                throw new IllegalArgumentException(prefix + "不是非空文本");
            }
        }
    }

    private static String reqText(JsonNode node, String field, String where) {
        JsonNode n = node.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException(where + ": 缺少非空文本字段 " + field);
        }
        return n.asText();
    }

    private static String optText(JsonNode node, String field, String where) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException(where + ": 字段 " + field + " 只能是非空文本");
        }
        return n.asText();
    }

    private static Double optDouble(JsonNode node, String field, String where, String key) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isNumber()) {
            throw new IllegalArgumentException(where + ": 参数 " + key + " 的 " + field + " 只能是数值");
        }
        return n.doubleValue();
    }

    private static List<String> textList(JsonNode node, String field, String where) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return List.of();
        }
        if (!n.isArray()) {
            throw new IllegalArgumentException(where + ": " + field + " 只能是数组");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException(where + ": " + field + " 只能装非空文本");
            }
            out.add(item.asText());
        }
        return List.copyOf(out);
    }

    // ==================== DTO ====================

    /** 枚举选项 */
    public record Option(String value, String label) {
    }

    /**
     * 一个可调优参数的 schema。
     *
     * @param std         true = 标准参数（wire 限 temperature / max_tokens，值存 ModelProfile 老字段）；
     *                    false = 私有参数（值存 params map，注入走 customParameters）
     * @param min / max   数值类型的值域；在 {@code stringList} 上是**元素条数**区间（如 stop 最多 16 条）
     * @param wireWrap    非空时注入的值要包一层单键对象 {@code {wireWrap: value}} ——
     *                    厂商把标量塞进子对象的标准写法（如 {@code response_format.type}）。
     *                    只对私有参数成立：标准参数的值形状由框架定，没法再套一层
     * @param display     非空时指定控件呈现方式（仅 enum 可用）：{@code bar} = 分档进度条。
     *                    只是呈现提示，不参与取值与注入 —— 值仍是 options 里那个枚举字符串
     * @param defaultValue 官方文档写明的默认值。用户没填时它同时管两件事：控件空态上显示
     *                    「默认 X」（没写 default 才显示「服务商默认」），以及注入时补缺。
     *                    条件参数也可以声明 default —— 补缺值同样要过 {@link #enabled} 那道门，
     *                    条件不成立时连默认值都不发射
     * @param enabledWhen 非空时本参数是**条件参数**：依赖参数取到指定值才可填，见 {@link EnabledWhen}
     */
    public record ParamSpec(String key, String label, String type, String wire, String wireWrap,
                            boolean std, Double min, Double max, Double step,
                            /* 下发的 JSON 键就是适配文件里那个 "default"：记录组件叫 defaultValue，
                               Jackson 默认按组件名序列化，前端读 spec.default 会永远拿到 undefined */
                            @JsonProperty("default") Object defaultValue,
                            List<Option> options, String display, EnabledWhen enabledWhen) {
        public ParamSpec {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * 条件可用性：本参数只在调优值 {@code values[key]} 落在 {@code values} 里时才可填。
     *
     * 存在的理由是"有参数填了不生效"这件事必须被表达出来 —— 与其让用户填完一个
     * 服务商根本不看的字段，不如把控件灰掉。
     *
     * @param key     被依赖参数的 key（同族、私有、且自身无条件的参数）
     * @param values  允许值集合，取值必须落在被依赖参数自己的取值域里
     * @param missing 被依赖参数**未填**时按这个值参与判定；为空 = 未填即不命中（默认关）
     */
    public record EnabledWhen(String key, List<Object> values, Object missing) {
        public EnabledWhen {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    /** 匹配键 */
    public record MatchSpec(List<String> hosts, List<String> models, List<String> modelPatterns) {
        public MatchSpec {
            hosts = hosts == null ? List.of() : List.copyOf(hosts);
            models = models == null ? List.of() : List.copyOf(models);
            modelPatterns = modelPatterns == null ? List.of() : List.copyOf(modelPatterns);
        }

        public boolean empty() {
            return hosts.isEmpty() && models.isEmpty() && modelPatterns.isEmpty();
        }
    }

    /**
     * 一条适配。
     *
     * @param input  该模型能吃什么（模态词表，见 {@link #MODALITIES}）；空 = 没登记（不是"只能吃文本"）
     * @param output 该模型产出什么；空 = 没登记
     * @param families 端点族 → 该族暴露的调优参数。族的**键名**同时就是这份模型的端点族
     *                 （{@link #endpoints()}）—— 登记了 chat 参数，就说明它走对话端点
     */
    public record Adapter(String id, String provider, String label, String docUrl,
                          List<String> input, List<String> output,
                          MatchSpec match, Map<String, List<ParamSpec>> families) {

        /** 兼容构造：模态未登记的适配（测试与只关心参数的场景） */
        public Adapter(String id, String provider, String label, String docUrl,
                       MatchSpec match, Map<String, List<ParamSpec>> families) {
            this(id, provider, label, docUrl, List.of(), List.of(), match, families);
        }

        public Adapter {
            input = input == null ? List.of() : List.copyOf(input);
            output = output == null ? List.of() : List.copyOf(output);
            /* 用 LinkedHashMap 保序再包不可变：Map.copyOf 是无序的，
               而端点族要按登记顺序显示在卡片上（多族适配时顺序必须确定） */
            families = families == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(families));
        }

        /** 端点族 —— 登记了参数的族名，顺序同文件里的书写顺序 */
        public List<String> endpoints() {
            return List.copyOf(families.keySet());
        }

        public List<ParamSpec> paramsOf(String family) {
            return families.getOrDefault(family, List.of());
        }

        /**
         * 全部端点族的参数并集（按 families 的登记顺序）。
         *
         * 用于"这个适配对当前表单暴露哪些可调参数"这类**不看族**的场景：新增档案时前端
         * 还不知道模型归哪一族（要靠保存后读元数据 / 适配声明才定），只能把该适配登记过的
         * 参数整体摆出来；而档案列表页则按 {@code isEmbedding()} 精确取对应族。
         */
        public List<ParamSpec> allParams() {
            List<ParamSpec> out = new ArrayList<>();
            for (List<ParamSpec> specs : families.values()) {
                out.addAll(specs);
            }
            return List.copyOf(out);
        }
    }

    /**
     * 匹配命中的级别 —— 决定"这份适配的声明能不能用在**这个模型**身上"。
     *
     * 前两级是"我们登记过这个模型"；{@link #HOST} 只是"这家服务商我们登记过"，
     * 拿它去回答"这个模型是什么类型"会张冠李戴（见 {@link #declaration}）。
     */
    public enum MatchLevel {

        /** ① {@code match.models} 模型名精确 */
        MODEL("模型名精确"),
        /** ② {@code match.modelPatterns} 模型名正则 */
        PATTERN("模型名正则"),
        /** ③ {@code match.hosts} 服务商 host 兜底 */
        HOST("服务商 host 兜底");

        private final String label;

        MatchLevel(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 匹配结果；未命中时 adapter 与 level 均为 null */
    public record MatchResult(boolean matched, Adapter adapter, MatchLevel level) {

        public static final MatchResult MISS = new MatchResult(false, null, null);

        /** 是否按模型名命中（精确 / 正则）—— 只有这两级代表"我们登记过这个模型" */
        public boolean byModelName() {
            return matched && level != MatchLevel.HOST;
        }

        /** 对话端点族的参数 schema（P0 唯一被消费的族） */
        public List<ParamSpec> chatParams() {
            return matched ? adapter.paramsOf("chat") : List.of();
        }

        /** 向量端点族的参数 schema（目前只有 {@code dimensions} 一项） */
        public List<ParamSpec> embeddingParams() {
            return matched ? adapter.paramsOf("embedding") : List.of();
        }

        /** 全部族的参数并集；匹配校验与"这个适配有哪些可调项"用它 */
        public List<ParamSpec> allParams() {
            return matched ? adapter.allParams() : List.of();
        }

        /**
         * 按档案类型挑出该档案真正可调的那一族参数。
         *
         * 向量档案看 embedding 族（dimensions），其余（含端点族未声明的 OpenAI / DeepSeek 官方）
         * 看 chat 族 —— 与 {@code AdminModelProfileController} 下发调优框、复核调优值两处共用同一判据，
         * 避免"列表页画出维度框、复核时却不认"的漂移。
         */
        public List<ParamSpec> tuningParams(ModelProfile profile) {
            return profile != null && profile.isEmbedding() ? embeddingParams() : chatParams();
        }
    }

    /**
     * 适配文件对某个模型的声明 —— 端点族与模态。
     *
     * @param adapterId 命中哪条适配（日志与排障用）
     */
    public record Declaration(String adapterId, List<String> endpoints,
                              List<String> input, List<String> output) {
        public Declaration {
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
            input = input == null ? List.of() : List.copyOf(input);
            output = output == null ? List.of() : List.copyOf(output);
        }
    }
}
