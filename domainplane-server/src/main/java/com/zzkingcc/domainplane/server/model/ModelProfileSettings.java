package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.api.agent.Domains;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型档案的落盘结构（{@code config/models.json}）。
 *
 * <p>三条规则体现在结构里：</p>
 * <ul>
 *   <li><b>档案不自动绑定任何域</b> —— {@code domainBindings} 只能由管控台手工填写；</li>
 *   <li><b>一个域可绑一组模型</b> —— {@code domainBindings} 是 {@code 域 → 别名列表} 的<b>有序</b>映射，
 *       整体覆盖；列表首个是当前使用的模型，其余留给多 agent / 降级；空列表即解绑；</li>
 *   <li><b>向量模型全局只有一个</b> —— {@link #embeddingAlias} 是<b>单选</b>，不参与域绑定
 *       （{@code domainBindings} 只管对话模型）。向量检索是"全树共用一套向量空间"，
 *       让它按域切换会导致同一索引里混入不同模型的向量，维度对不上、检索结果无意义。</li>
 * </ul>
 *
 * @author zzkingcc
 */
@Data
public class ModelProfileSettings {

    /** 域 → 可调用<b>对话</b>模型别名列表（有序；首个为当前使用；空 = 未绑定 / 无可调用） */
    private Map<String, java.util.List<String>> domainBindings = new LinkedHashMap<>();

    /** 别名 → 档案（<b>不含</b>纯向量档案 —— 那批在 {@link #embeddingProfiles} 里单独存放） */
    private Map<String, ProfileData> profiles = new LinkedHashMap<>();

    /**
     * 别名 → <b>纯向量</b>档案，与 {@link #profiles} 分开存放。
     *
     * <p>分开是刻意的：向量模型是<b>全局唯一单选</b>、不参与域绑定，与"可以绑到域上按需调用"
     * 的对话 / 生图 / 语音那类档案根本是两种东西。混在一个 map 里时，管控台的「模型」段只能
     * 靠 endpoints 过滤才不至于把向量模型画进去 —— 漏掉一处就会冒出"怎么还有一个向量模型"。</p>
     */
    private Map<String, ProfileData> embeddingProfiles = new LinkedHashMap<>();

    /**
     * 当前启用的<b>向量</b>模型档案别名（全局唯一，不参与域绑定）。
     *
     * <p>为空 = 未配置向量模型，知识库的灌库与向量检索不可用（BM25 通道仍可用）。
     * 换向量模型会让已灌库的向量全部失效，必须先做二次确认（见管理面接口）。</p>
     */
    private String embeddingAlias;

    /**
     * 是不是「纯向量」档案 —— 只做向量、不接对话。
     *
     * <p>同时具备 chat 与 embedding 的档案<b>不算</b>：它要能被绑到域上当对话模型用。
     * 若一律按 {@code isEmbedding} 分出去，这种档案会从「模型」段消失、且再也没法绑域。</p>
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
     * 把一份档案放进对应的桶，并确保它<b>不会同时留在另一个桶里</b>。
     *
     * <p>必须清另一侧：档案类型会被重新探测改写 —— 一个纯向量模型换个模型名重探可能变成
     * 对话模型，此时若不从向量桶摘掉，它会在两个桶里各留一份，语义直接矛盾。</p>
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
     * <p>旧布局是"一个 profiles 装所有档案"、只靠 endpoints 区分。读盘时做一次归一，
     * 返回值表示是否真有改动 —— 调用方据此决定要不要把新布局写回文件。</p>
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
     * 沿域链解析绑定：<b>由自身向根</b>找最近一份非空绑定。
     *
     * <p>纯函数（不依赖 Spring 容器），因此可被单元测试直接覆盖。回落的是域链本身，
     * 不含任何内置默认值 —— 根域也没绑就是真的没有。</p>
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

        /** 端点族（可多选；空 = chat） */
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
        /** 向量维度（仅 embedding 用） */
        private Integer dimensions;
        /** 布尔能力：streaming / tools */
        private java.util.List<String> capabilities = new java.util.ArrayList<>();
        /** 降级链（暂只存不生效） */
        private java.util.List<String> fallbacks = new java.util.ArrayList<>();

        public ModelProfile toProfile(String alias) {
            return new ModelProfile(alias, endpoints, input, output, baseUrl, apiKey, modelName,
                    temperature, maxTokens, dimensions, capabilities, fallbacks);
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
            data.setDimensions(profile.dimensions());
            data.setCapabilities(new java.util.ArrayList<>(profile.capabilities()));
            data.setFallbacks(new java.util.ArrayList<>(profile.fallbacks()));
            return data;
        }
    }
}
