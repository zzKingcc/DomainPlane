package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.api.agent.Domains;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型档案的落盘结构（{@code config/models.json}）。
 *
 * 三条规则体现在结构里：
 *
 *   • 档案不自动绑定任何域 —— {@code domainBindings} 只能由管控台手工填写；
 *   • 一个域可绑一组模型 —— {@code domainBindings} 是 {@code 域 → 别名列表} 的有序映射，
 *       整体覆盖；列表首个是当前使用的模型，其余留给多 agent / 降级；空列表即解绑；
 *   • 向量模型全局只有一个 —— {@link #embeddingAlias} 是单选，不参与域绑定
 *       （{@code domainBindings} 只管对话模型）。向量检索是"全树共用一套向量空间"，
 *       让它按域切换会导致同一索引里混入不同模型的向量，维度对不上、检索结果无意义。
 *
 *
 * @author zzkingcc
 */
@Data
public class ModelProfileSettings {

    /** 域 → 可调用对话模型别名列表（有序；首个为当前使用；空 = 未绑定 / 无可调用） */
    private Map<String, java.util.List<String>> domainBindings = new LinkedHashMap<>();

    /** 别名 → 档案（不含纯向量档案 —— 那批在 {@link #embeddingProfiles} 里单独存放） */
    private Map<String, ProfileData> profiles = new LinkedHashMap<>();

    /**
     * 别名 → 纯向量档案，与 {@link #profiles} 分开存放。
     *
     * 分开是刻意的：向量模型是全局唯一单选、不参与域绑定，与"可以绑到域上按需调用"
     * 的对话 / 生图 / 语音那类档案根本是两种东西。混在一个 map 里时，管控台的「模型」段只能
     * 靠 endpoints 过滤才不至于把向量模型画进去 —— 漏掉一处就会冒出"怎么还有一个向量模型"。
     */
    private Map<String, ProfileData> embeddingProfiles = new LinkedHashMap<>();

    /**
     * 当前启用的向量模型档案别名（全局唯一，不参与域绑定）。
     *
     * 为空 = 未配置向量模型，知识库的灌库与向量检索不可用（BM25 通道仍可用）。
     * 换向量模型会让已灌库的向量全部失效，必须先做二次确认（见管理面接口）。
     */
    private String embeddingAlias;

    /**
     * 是不是「纯向量」档案 —— 只做向量、不接对话。
     *
     * 同时具备 chat 与 embedding 的档案不算：它要能被绑到域上当对话模型用。
     * 若一律按 {@code isEmbedding} 分出去，这种档案会从「模型」段消失、且再也没法绑域。
     */
    private static boolean isVectorOnly(ProfileData data, String alias) {
        if (data == null) {
            return false;
        }
        ModelProfile profile = data.toProfile(alias);
        return profile.isEmbedding() && !profile.isChat();
    }

    /** 在两类档案里查同一个别名（写操作请分别走 {@link #put} / {@link #remove}） */
    public ProfileData find(String alias) {
        if (alias == null) {
            return null;
        }
        ProfileData data = profiles.get(alias);
        return data != null ? data : embeddingProfiles.get(alias);
    }

    /** 两类档案合并后的视图（全量统计 / 诊断用），顺序为「先普通、后向量」 */
    public Map<String, ProfileData> allProfiles() {
        Map<String, ProfileData> all = new LinkedHashMap<>(profiles);
        all.putAll(embeddingProfiles);
        return all;
    }

    /**
     * 把一份档案放进对应的桶，并确保它不会同时留在另一个桶里。
     *
     * 必须清另一侧：档案类型会被重新探测改写 —— 一个纯向量模型换个模型名重探可能变成
     * 对话模型，此时若不从向量桶摘掉，它会在两个桶里各留一份，语义直接矛盾。
     */
    public void put(String alias, ProfileData data) {
        if (isVectorOnly(data, alias)) {
            profiles.remove(alias);
            embeddingProfiles.put(alias, data);
        } else {
            embeddingProfiles.remove(alias);
            profiles.put(alias, data);
        }
    }

    /** 从两类档案里都摘掉该别名 */
    public boolean remove(String alias) {
        boolean removed = profiles.remove(alias) != null;
        return embeddingProfiles.remove(alias) != null || removed;
    }

    /**
     * 把历史上混在 {@link #profiles} 里的纯向量档案挪到 {@link #embeddingProfiles}。
     *
     * 旧布局是"一个 profiles 装所有档案"、只靠 endpoints 区分。读盘时做一次归一，
     * 返回值表示是否真有改动 —— 调用方据此决定要不要把新布局写回文件。
     */
    public boolean splitVectorProfiles() {
        boolean moved = false;
        java.util.Iterator<Map.Entry<String, ProfileData>> it = profiles.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ProfileData> entry = it.next();
            if (isVectorOnly(entry.getValue(), entry.getKey())) {
                it.remove();
                embeddingProfiles.putIfAbsent(entry.getKey(), entry.getValue());
                moved = true;
            }
        }
        return moved;
    }

    /**
     * 沿链解析结果
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
     * 沿域链解析绑定：由自身向根找最近一份非空绑定。
     *
     * 纯函数（不依赖 Spring 容器），因此可被单元测试直接覆盖。回落的是域链本身，
     * 不含任何内置默认值 —— 根域也没绑就是真的没有。
     */
    public DomainBinding resolveAlong(String domain) {
        List<String> chain = Domains.chainOf(Domains.normalize(domain));
        for (int i = chain.size() - 1; i >= 0; i--) {
            String step = chain.get(i);
            List<String> bound = domainBindings.get(step);
            if (bound != null && !bound.isEmpty()) {
                return new DomainBinding(bound, step);
            }
        }
        return new DomainBinding(List.of(), null);
    }

    /**
     * 一条档案的落盘形态（与 {@link ModelProfile} 一一对应）。
     */
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
        /**
         * 服务商协议类型名（存枚举 {@code name()}）。
         *
         * 存字符串而不是枚举本身：{@code models.json} 允许手工编辑，
         * 出现认不出来的值时 {@link ProviderType#parse} 会退回归 OpenAI 兼容，
         * 而不会让整个反序列化过程炸掉。
         */
        private String providerType;
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
        /** 是否开启思考模式（null = 不声明，由服务商默认决定） */
        private Boolean thinking;
        /** 是否把思考内容回传给平台（null = 不声明） */
        private Boolean returnThinking;
        /** 向量维度（仅 embedding 用） */
        private Integer dimensions;
        /** 布尔能力：streaming / tools / reasoning */
        private java.util.List<String> capabilities = new java.util.ArrayList<>();
        /** 降级链（暂只存不生效） */
        private java.util.List<String> fallbacks = new java.util.ArrayList<>();
        /** 调优参数（前缀键，如 chat.topP）；只存用户显式填过的项 */
        private Map<String, Object> params = new LinkedHashMap<>();

        public ModelProfile toProfile(String alias) {
            return new ModelProfile(alias, endpoints, input, output, baseUrl, apiKey,
                    ProviderType.parse(providerType), modelName,
                    temperature, maxTokens, thinking, returnThinking, dimensions, capabilities, fallbacks,
                    params);
        }

        public static ProfileData from(ModelProfile profile) {
            ProfileData data = new ProfileData();
            data.setEndpoints(new java.util.ArrayList<>(profile.endpoints()));
            data.setInput(new java.util.ArrayList<>(profile.input()));
            data.setOutput(new java.util.ArrayList<>(profile.output()));
            data.setBaseUrl(profile.baseUrl());
            data.setApiKey(profile.apiKey());
            data.setProviderType(profile.providerType().name());
            data.setModelName(profile.modelName());
            data.setTemperature(profile.temperature());
            data.setMaxTokens(profile.maxTokens());
            data.setThinking(profile.thinking());
            data.setReturnThinking(profile.returnThinking());
            data.setDimensions(profile.dimensions());
            data.setCapabilities(new java.util.ArrayList<>(profile.capabilities()));
            data.setFallbacks(new java.util.ArrayList<>(profile.fallbacks()));
            data.setParams(new LinkedHashMap<>(profile.params()));
            return data;
        }
    }
}
