package com.zzkingcc.domainplane.server.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.zzkingcc.domainplane.api.agent.Domains;
import lombok.Data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型档案的落盘结构（{@code config/models.json}）。
 *
 * 三条规则体现在结构里：
 *
 *   • 档案不自动绑定任何域，{@code domainBindings} 只能由管控台手工填写；
 *   • 一个域绑一组模型，<b>按族分桶</b>，{@code domainBindings} 是
 *       {@code 域 → {族 → 别名列表}} 的有序映射，整体覆盖；每个族内的列表首个是当前使用的，
 *       其余留给多 agent / 降级；某族空列表即该族解绑；
 *   • 向量模型全局只有一个，{@link #embeddingAlias} 是单选槽位，不参与域绑定
 *       （{@code domainBindings} 管对话与四个能力族）。向量检索是"全树共用一套向量空间"，
 *       让它按域切换会导致同一索引里混入不同模型的向量，维度对不上、检索结果无意义。
 *
 * <p><b>档案只有一张表</b>：早期按「是不是纯向量」把档案分到 {@code profiles} 与
 * {@code embeddingProfiles} 两个 Map，但消费侧又按 {@code isEmbedding()} 从全量重新过滤，
 * 分桶依据与实际使用依据不是同一个东西 —— 双类型档案两边都出现、前端还得再过滤一次。
 * 现在 {@link #profiles} 是唯一真相，"这份档案用来干什么"由用户声明的 {@code endpoints}
 * 和消费侧的槽位决定，不再由它存在哪个 Map 决定。旧文件里那个键读盘时合回来，见
 * {@link #mergeLegacyProfiles()}。</p>
 *
 * <p><b>族的划分</b>：{@code chat} 是主模型（本轮由它回答），其余四个
 * （{@code images} / {@code tts} / {@code asr} / {@code video}，见
 * {@link ModelProfile#CAPABILITY_FAMILIES}）是供主模型调用的能力。主模型桶只收能当对话模型用的档案
 * （{@link ModelProfile#domainBindable()}），能力桶只要求档案确实声明了那个端点族，
 * 生图档案当不了主模型，但它恰恰是生图桶里唯一合格的东西。</p>
 *
 * <p><b>兼容旧格式</b>：早期落盘是 {@code {"default": ["a","b"]}}（只有一个列表，等价于对话模型），
 * 读盘时按 {@code chat} 收进新结构（见 {@link DomainModels.Deserializer}）；写出永远是新的对象形态，
 * 因此首次写入即完成迁移，不需要额外的迁移步骤。</p>
 *
 * @author zzkingcc
 */
@Data
public class ModelProfileSettings {

    /**
     * 域 → 该域绑定的模型（主模型 + 各能力族）。
     *
     * <p>落盘形态：{@code {"default": {"chat": ["a"], "images": ["b"]}}}。</p>
     */
    private Map<String, DomainModels> domainBindings = new LinkedHashMap<>();

    /** 别名 → 档案。全平台唯一一张表，向量 / 对话 / 生图档案都在这里 */
    private Map<String, ProfileData> profiles = new LinkedHashMap<>();

    /**
     * 旧格式的纯向量档案桶，<b>只为读兼容保留</b>。
     *
     * <p>{@code WRITE_ONLY} = Jackson 反序列化时收这个键、序列化时不再输出。
     * 少了这个注解，旧 {@code models.json} 里那批档案会在读盘时被静默丢弃；
     * 让它照常输出，则"合桶"永远完成不了，文件里会一直留着两份真相。</p>
     *
     * <p>{@link ModelProfileStore#load()} 读入后立刻调 {@link #mergeLegacyProfiles()}
     * 把内容并回 {@link #profiles} 并清空这里，内存态里它恒为空。</p>
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Map<String, ProfileData> embeddingProfiles = new LinkedHashMap<>();

    /**
     * 当前启用的向量模型档案别名（全局唯一槽位，不参与域绑定）。
     *
     * 为空 = 未配置向量模型，知识库的灌库与向量检索不可用（BM25 通道仍可用）。
     * 换向量模型会让已灌库的向量全部失效，必须先做二次确认（见管理面接口）。
     */
    private String embeddingAlias;

    /** 在唯一一张表里按别名取档案 */
    public ProfileData find(String alias) {
        return alias == null ? null : profiles.get(alias);
    }

    /** 全量档案的副本（诊断 / 全量统计用） */
    public Map<String, ProfileData> allProfiles() {
        return new LinkedHashMap<>(profiles);
    }

    /** 新建或整体覆盖一份档案 */
    public void put(String alias, ProfileData data) {
        profiles.put(alias, data);
    }

    /** 摘掉该别名 */
    public boolean remove(String alias) {
        return profiles.remove(alias) != null;
    }

    /**
     * 把旧格式 {@code embeddingProfiles} 里的档案并回唯一一张表。
     *
     * <p>同名以 {@link #profiles} 里的为准：旧布局曾允许同一别名在两个桶里各留一份，
     * 而"普通桶"是后写的那份，保留它更接近用户最后一次编辑的结果。</p>
     *
     * <p>调用方（{@code ModelProfileStore.load()}）据返回值决定是否把新布局写回文件，
     * 与 {@link #normalizeChatParams()} 同一套"改了才写回"的约定。</p>
     *
     * @return 是否真的合并过条目
     */
    public boolean mergeLegacyProfiles() {
        if (embeddingProfiles == null || embeddingProfiles.isEmpty()) {
            return false;
        }
        if (profiles == null) {
            profiles = new LinkedHashMap<>();
        }
        boolean moved = false;
        for (Map.Entry<String, ProfileData> entry : embeddingProfiles.entrySet()) {
            if (entry.getValue() != null && profiles.putIfAbsent(entry.getKey(), entry.getValue()) == null) {
                moved = true;
            }
        }
        /* 清空而不是置 null：copyOf / 序列化都按"这是个可读字段"处理，null 会让 @Data 的
           getter 到处要空判。WRITE_ONLY 保证它不会回到磁盘上。 */
        embeddingProfiles = new LinkedHashMap<>();
        return moved;
    }

    /**
     * 把存量对话档案的 {@code params} 键从适配 UI 名归一成线上字段名。
     *
     * <p>对话的适配 schema 下架后，注入前那一步「key → wire」翻译没了（见
     * {@link ChatParamMigration}）；不归一就会把 {@code reasoningEffort} 原样发出去，
     * 上游静默忽略而界面显示"已配置"。读盘时做一次，与 {@link #mergeLegacyProfiles()}
     * 同一位置、同一套"改了才写回"的约定。</p>
     *
     * <p>合桶后扫的是全量表：向量档案的 {@code params} 里没有对话的 UI 键，
     * {@code ChatParamMigration.migrate} 对无旧键的 map 返回 null，不会误伤。</p>
     *
     * @return 是否真的改写过（调用方据此决定要不要落盘）
     */
    public boolean normalizeChatParams() {
        boolean changed = false;
        for (ProfileData data : profiles.values()) {
            if (data == null) {
                continue;
            }
            Map<String, Object> migrated = ChatParamMigration.migrate(data.getParams());
            if (migrated == null) {
                continue;
            }
            data.setParams(migrated.isEmpty() ? null : migrated);
            changed = true;
        }
        return changed;
    }

    /**
     * 主模型（对话）的沿链解析结果
     *
     * @param aliases      生效的别名列表；空 = 整条链都没有绑定
     * @param sourceDomain 这份绑定所在的域（自身，或最近的祖先）；全链未绑时为 {@code null}
     */
    public record DomainBinding(List<String> aliases, String sourceDomain) {

        public DomainBinding {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        public boolean empty() {
            return aliases.isEmpty();
        }
    }

    /**
     * 某个能力族的沿链解析结果
     *
     * @param family       族（{@code images} / {@code tts} / {@code asr} / {@code video}）
     * @param aliases      生效的别名列表；空 = 整条链都没有为该族绑定模型
     * @param sourceDomain 这份绑定所在的域；全链未绑时为 {@code null}
     */
    public record CapabilityBinding(String family, List<String> aliases, String sourceDomain) {

        public CapabilityBinding {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        public boolean empty() {
            return aliases.isEmpty();
        }
    }

    /**
     * 沿域链解析主模型绑定：由自身向根找最近一份非空绑定。
     *
     * 纯函数（不依赖 Spring 容器），因此可被单元测试直接覆盖。回落的是域链本身，
     * 不含任何内置默认值：根域也没绑就是真的没有。
     */
    public DomainBinding resolveAlong(String domain) {
        List<String> chain = Domains.chainOf(Domains.normalize(domain));
        for (int i = chain.size() - 1; i >= 0; i--) {
            String step = chain.get(i);
            DomainModels models = domainBindings.get(step);
            if (models == null) {
                continue;
            }
            List<String> bound = models.family(ModelProfile.EP_CHAT);
            if (!bound.isEmpty()) {
                return new DomainBinding(bound, step);
            }
        }
        return new DomainBinding(List.of(), null);
    }

    /**
     * 沿域链解析某个能力族的绑定，与 {@link #resolveAlong} 同一套回落规则。
     *
     * <p>按族分开回落而不是"取整份绑定再过滤"：一个域可以为生图绑了模型、却没为自己绑语音，
     * 此时语音该继续向祖先找，而不是被"本域已有绑定"截断。</p>
     *
     * <p>族不在白名单（{@link ModelProfile#CAPABILITY_FAMILIES}）时直接返回空，
     * {@code embedding} / {@code rerank} 不参与域绑定，传进来也不该解析出任何东西。</p>
     */
    public CapabilityBinding resolveCapabilityAlong(String domain, String family) {
        if (family == null || !ModelProfile.CAPABILITY_FAMILIES.contains(family)) {
            return new CapabilityBinding(family, List.of(), null);
        }
        List<String> chain = Domains.chainOf(Domains.normalize(domain));
        for (int i = chain.size() - 1; i >= 0; i--) {
            String step = chain.get(i);
            DomainModels models = domainBindings.get(step);
            if (models == null) {
                continue;
            }
            List<String> bound = models.family(family);
            if (!bound.isEmpty()) {
                return new CapabilityBinding(family, bound, step);
            }
        }
        return new CapabilityBinding(family, List.of(), null);
    }

    /**
     * 一个域绑定的模型 —— {@code 族 → 别名列表}。
     *
     * <p>用"族"当 key 而不是分成"主模型字段 + 能力 map"两个结构：族本来就是一个层级，
     * 平铺后落盘就是 {@code {"chat": [...], "images": [...]}}，比再套一层
     * {@code capabilities} 少一层缩进，校验与遍历也只有一条路径。</p>
     *
     * <p>只保留 {@link ModelProfile#BINDABLE_FAMILIES} 里的族：落盘被手工改坏时
     * （例如塞了个 {@code rerank}）静默丢弃并在读盘处告警，而不是让它流进内存态。</p>
     */
    @JsonDeserialize(using = DomainModels.Deserializer.class)
    public static class DomainModels {

        /** 族 → 别名列表（保序；空列表不留在 map 里） */
        private final Map<String, List<String>> families = new LinkedHashMap<>();

        /** 某族绑定的别名列表；未绑为空 */
        public List<String> family(String family) {
            if (family == null) {
                return List.of();
            }
            List<String> bound = families.get(family);
            return bound == null ? List.of() : bound;
        }

        /** 写入某族（空列表 = 解绑该族，条目直接摘掉，落盘不留 {@code "images": []} 噪声） */
        public void putFamily(String family, List<String> aliases) {
            if (family == null || !ModelProfile.BINDABLE_FAMILIES.contains(family)) {
                return;
            }
            if (aliases == null || aliases.isEmpty()) {
                families.remove(family);
                return;
            }
            families.put(family, List.copyOf(aliases));
        }

        /** 全部族的视图（保序，供清理 / 管控台遍历） */
        public Map<String, List<String>> families() {
            return Collections.unmodifiableMap(families);
        }

        /** 该域什么都没绑 */
        public boolean isEmpty() {
            return families.isEmpty();
        }

        /** 只绑主模型的便捷构造 —— 旧格式（单个数组）的等价物 */
        public static DomainModels ofChat(List<String> chatAliases) {
            DomainModels models = new DomainModels();
            models.putFamily(ModelProfile.EP_CHAT, chatAliases);
            return models;
        }

        public DomainModels copy() {
            DomainModels out = new DomainModels();
            families.forEach((family, aliases) -> out.families.put(family, List.copyOf(aliases)));
            return out;
        }

        /** 序列化形态就是族表本身（见类注释的落盘示例） */
        @JsonValue
        public Map<String, List<String>> json() {
            return families;
        }

        /**
         * 兼容读：旧格式是数组（等价于 chat），新格式是族对象；两者都收。
         *
         * <p>写出永远是新的对象形态，所以旧文件读一次、写一次就自动迁移完了，
         * 不需要单独的迁移工具。</p>
         */
        public static class Deserializer extends JsonDeserializer<DomainModels> {

            @Override
            public DomainModels deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                if (p.currentToken() == JsonToken.START_ARRAY) {
                    JsonNode array = ctx.readTree(p);
                    return DomainModels.ofChat(toAliases(array));
                }
                JsonNode node = ctx.readTree(p);
                DomainModels models = new DomainModels();
                if (node != null && node.isObject()) {
                    node.fields().forEachRemaining(entry ->
                            models.putFamily(entry.getKey(), toAliases(entry.getValue())));
                }
                return models;
            }

            private static List<String> toAliases(JsonNode node) {
                List<String> out = new ArrayList<>();
                if (node == null || !node.isArray()) {
                    return out;
                }
                for (JsonNode item : node) {
                    if (item != null && item.isTextual()) {
                        String alias = item.asText();
                        if (alias != null && !alias.isBlank() && !out.contains(alias.trim())) {
                            out.add(alias.trim());
                        }
                    }
                }
                return out;
            }
        }
    }

    /**
     * 一条档案的落盘形态（与 {@link ModelProfile} 一一对应）。
     */
    /** 旧版 models.json 里带 capabilities / params / supportedParams 字段，读盘时忽略而不是炸掉 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class ProfileData {

        /** 端点族（可多选；空 = 未声明，不再默认按 chat 处理） */
        private java.util.List<String> endpoints = new java.util.ArrayList<>();
        /** 输入模态（可多选） */
        private java.util.List<String> input = new java.util.ArrayList<>();
        /** 输出模态（可多选） */
        private java.util.List<String> output = new java.util.ArrayList<>();
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
        /**
         * 上下文窗口（token，含输入与输出）。
         *
         * 用户按服务商文档手填，可空 = 未声明。老文件没有这一字段 → 反序列化为 null，
         * 行为与该字段引入前完全一致（装配预算维持配置值）。
         */
        private Integer contextWindow;
        /** 是否开启思考模式（null = 不声明，由服务商默认决定） */
        private Boolean thinking;
        /** 是否把思考内容回传给平台（null = 不声明） */
        private Boolean returnThinking;
        /** 向量维度（仅 embedding 用） */
        private Integer dimensions;
        /** 降级链（暂只存不生效） */
        private java.util.List<String> fallbacks = new java.util.ArrayList<>();
        /**
         * 服务商适配的私有调优参数（如 {@code enable_thinking}）。
         *
         * 老文件没有这一字段 → 反序列化为 null → {@link ModelProfile} 归一为空 map，行为零变化。
         */
        private Map<String, Object> params;
        /**
         * 连接协议（{@code openai} / {@code anthropic}）。
         *
         * <p>老文件没有这一字段 → 反序列化为 null → {@link ModelProfile} 归一为
         * {@code openai}，行为与该字段引入前完全一致（历史上有过这个字段、读盘时被忽略，
         * 这里属于字段回归）。</p>
         */
        private String protocol;

        public ModelProfile toProfile(String alias) {
            return new ModelProfile(alias, endpoints, input, output, baseUrl, apiKey,
                    modelName, temperature, maxTokens, contextWindow, thinking, returnThinking, dimensions,
                    fallbacks, params, protocol);
        }

        public static ProfileData from(ModelProfile profile) {
            ProfileData data = new ProfileData();
            data.setEndpoints(new java.util.ArrayList<>(profile.endpoints()));
            data.setInput(new java.util.ArrayList<>(profile.input()));
            data.setOutput(new java.util.ArrayList<>(profile.output()));
            data.setBaseUrl(profile.baseUrl());
            data.setApiKey(profile.apiKey());
            data.setModelName(profile.modelName());
            data.setTemperature(profile.temperature());
            data.setMaxTokens(profile.maxTokens());
            data.setContextWindow(profile.contextWindow());
            data.setThinking(profile.thinking());
            data.setReturnThinking(profile.returnThinking());
            data.setDimensions(profile.dimensions());
            data.setFallbacks(new java.util.ArrayList<>(profile.fallbacks()));
            /* 空 map 写 null：不在 models.json 里留 params:{} 噪声 */
            data.setParams(profile.params().isEmpty() ? null : new LinkedHashMap<>(profile.params()));
            /* 同 params：默认值不落盘，避免给所有老文件刷一遍新键 */
            data.setProtocol(ModelProfile.PROTOCOL_OPENAI.equals(profile.protocol()) ? null : profile.protocol());
            return data;
        }
    }
}
