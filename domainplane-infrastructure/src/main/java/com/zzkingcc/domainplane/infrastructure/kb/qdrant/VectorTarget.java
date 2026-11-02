package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

/**
 * 向量库目标快照：地址 + API Key + 集合定义（度量 / 集合名前缀）。
 *
 * <p>与 {@code InfraSettings.VectorStore} 同形但<b>不依赖它</b>：
 * infrastructure 模块不认识 server 模块的配置类（依赖方向是 server → infrastructure），
 * 所以这里定义一个自足的载体，由 server 侧每次操作时现取当前值喂进来。</p>
 *
 * <p><b>为什么是"每次操作现取"而不是"构造时定死"</b>：向量库地址在管控台可改，
 * 改完要立刻生效（与 ES 的 {@code SwappableElasticsearchTransport} 同一个目的）。
 * 载体是不可变 record，取当前值这件事交给 {@code Supplier}——客户端每次出网前
 * 取一次快照用完即弃，于是"地址改了但客户端还指着旧地址"这件事不可能发生，
 * 也不需要任何"重建 bean / 重新注册热替换"的接线。</p>
 *
 * <p><b>一次操作只用一份快照</b>：地址、度量、集合名前缀必须来自同一次读取——
 * 否则前缀取自新配置、地址取自旧配置，物理集合名会算到另一台服务上去
 * （报错还算走运，最坏是写进了"看起来对"的集合）。</p>
 *
 * <p><b>集合定义档的语义</b>（{@code metric} / {@code collectionPrefix}）：
 * 改这两项等于换了一套集合——Qdrant 的 distance 在集合创建时定死、不可改，
 * 集合名前缀则直接决定物理集合名。所以它们虽然也是现取，但改完必须
 * <b>重建集合并重新灌一遍知识库</b>才一致（管控台在这两项旁有明示）。</p>
 *
 * @param baseUrl          服务地址（完整 URL，含协议与端口）；为空即"未配置"
 * @param apiKey           API Key / Token，未启用鉴权时为空
 * @param metric           距离度量（cosine / dot）；为空按 cosine
 * @param collectionPrefix 集合名前缀；为空表示直接用规范名
 * @author zzkingcc
 */
public record VectorTarget(String baseUrl, String apiKey, String metric, String collectionPrefix) {

    /** 未配置：地址为空。 */
    public static final VectorTarget UNCONFIGURED = new VectorTarget(null, null, null, null);

    /** 规范构造：去掉地址尾部斜杠、其余字段去空白。 */
    public VectorTarget {
        baseUrl = normalizeBaseUrl(baseUrl);
        apiKey = apiKey == null ? null : apiKey.trim();
        metric = metric == null || metric.isBlank() ? null : metric.trim().toLowerCase();
        collectionPrefix = collectionPrefix == null ? null : collectionPrefix.trim();
    }

    /**
     * 是否已配置（地址非空）。
     *
     * <p>"未配置"不是一个错误状态，而是一个<b>可行动的状态</b>：
     * 地址只能从管控台「存储配置」页填，而那个页面的可达性依赖服务能启动——
     * 所以服务必须能在未配置时照常启动，由调用点在第一次用时给出明确指引。</p>
     */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /** 距离度量；未设按 cosine（与 {@code InfraSettings.VectorStore} 同口径）。 */
    public String effectiveMetric() {
        return metric == null || metric.isBlank() ? "cosine" : metric;
    }

    /** 集合名前缀；未设即空串（直接用规范名）。 */
    public String effectiveCollectionPrefix() {
        return collectionPrefix == null ? "" : collectionPrefix;
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u.isEmpty() ? null : u;
    }
}
