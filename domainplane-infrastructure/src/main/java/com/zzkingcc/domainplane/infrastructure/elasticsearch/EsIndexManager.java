package com.zzkingcc.domainplane.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.json.JsonData;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量索引操作工具
 * @author zzkingcc
 */
@Slf4j
public class EsIndexManager {

    /**
     * 切片元数据里"所属域"的字段名（完整路径域，单个值）。
     *
     * 索引本身已经是一域一索引，检索不再靠这个字段过滤；它只用于管控台列表展示，
     * 以及索引名无法反查域路径（名里是哈希）时仍能读出归属。必须是 {@code keyword}。
     */
    public static final String DOMAIN_FIELD = "domain";

    /**
     * 允许拼进 Painless 脚本的字段名形态（无引号、无括号、无分号）。
     *
     * 脚本里的字段名来自 {@code _source} 的键，直接字符串拼接等于把任意内容交给脚本引擎解析。
     * 只放行常规标识符（允许点号，兼容嵌套字段），其余一律拒绝。
     */
    private static final java.util.regex.Pattern SAFE_FIELD_NAME =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    private EsIndexManager() {}

    /**
     * 索引是否存在。查询本身失败时抛异常，不返回 {@code false}。
     *
     * 为什么不能 fail-open：调用方拿这个结果做的是破坏性决策，
     * {@link #deleteIndex} 会先问"存在吗"，答"不"就直接跳过删除。
     * 若把"查不到"当成"不存在"，那么 ES 一抖动（超时、连接被拒、集群 red），
     * 删域就会静默跳过索引删除并继续往下走，域没了索引还在：
     * 而同一路径域重建后索引名相同（{@link KbIndexes#nameOf} 是路径哈希），
     * 这份"本该删掉的知识内容"会原样复活，且没有任何报错。
     *
     * 失败时抛而不是返回，是这一类的通用判据：存疑时宁可让操作失败，
     * 不能给一个看起来正常的错答案。
     *
     * @throws EsAccessException ES 不可达或查询失败
     */
    public static boolean exists(ElasticsearchClient esClient, String indexName) {
        try {
            return esClient.indices().exists(e -> e.index(indexName)).value();
        } catch (Exception e) {
            throw new EsAccessException("查询索引[" + indexName + "]是否存在失败（无法确认，不等同于不存在）："
                    + e.getMessage(), e);
        }
    }

    /**
     * 列出匹配 {@code pattern}（支持通配）的索引名，按字典序返回；一个都没有时返回空列表。
     *
     * 与 {@link #exists} 不同，这里保持 fail-open：它只用于管控台列表展示
     * 与"当前有哪些域有索引"的展示口径，返回空列表的代价是多显示一行空，
     * 而抛异常的代价是页面整个打不开。
     */
    public static List<String> listIndices(ElasticsearchClient esClient, String pattern) {
        try {
            var resp = esClient.indices().get(g -> g.index(pattern).ignoreUnavailable(true).allowNoIndices(true));
            return resp.indices().keySet().stream().sorted().toList();
        } catch (Exception e) {
            log.warn("[ES] 枚举索引[{}]失败: {}", pattern, e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除单个索引；确实不存在时返回 {@code false}（没有东西可删）。
     *
     * @return 是否确实删掉了一个已存在的索引
     * @throws EsAccessException 查询或删除失败：调用方必须当成失败处理，
     *                              不能继续往下删域
     */
    public static boolean deleteIndex(ElasticsearchClient esClient, String indexName) {
        // 这里不能再包一层 try-catch 转 IllegalStateException：exists 现在会抛
        // EsAccessException，而"查询失败"和"删除失败"在上层要区别对待：
        // 前者索引可能还在（不该删域），后者是删操作本身没成功（同样不该删域）
        if (!exists(esClient, indexName)) {
            return false;
        }
        try {
            esClient.indices().delete(d -> d.index(indexName));
        } catch (Exception e) {
            throw new EsAccessException("删除索引[" + indexName + "]失败：" + e.getMessage(), e);
        }
        log.info("[ES] 已删除索引: {}", indexName);
        return true;
    }

    /** 启动诊断：输出 ES 版本、集群健康、索引存在性及字段结构 */
    public static void diagnoseElasticsearch(ElasticsearchClient esClient, String indexName) {
        try {
            var info = esClient.info();
            String version = info.version().number();
            String clusterName = info.clusterName();
            log.info("=================== ES 诊断开始 ===================");
            log.info("[ES] 集群: {}  |  服务器版本: {}", clusterName, version);

            HealthResponse h = esClient.cluster().health();
            log.info("[ES] 集群健康: status={}, 节点数={}, 活跃分片={}",
                    h.status(), h.numberOfNodes(), h.activeShards());

            boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();
            log.info("[ES] 索引[{}] 是否存在: {}", indexName, exists);
            if (exists) {
                try {
                    SearchResponse<Map> s = esClient.search(q -> q.index(indexName).size(0), Map.class);
                    log.info("[ES] 索引文档数: {}", s.hits().total() != null ? s.hits().total().value() : "N/A");
                } catch (Exception ignored) {
                }
                try {
                    IndexState state = esClient.indices().get(g -> g.index(indexName)).indices().get(indexName);
                    if (state != null && state.mappings() != null && state.mappings().properties() != null) {
                        Map<String, Property> properties = state.mappings().properties();
                        log.info("[ES] Mapping 字段:");
                        properties.forEach((name, prop) -> {
                            String extra = "";
                            if (prop.isDenseVector()) {
                                extra = " (dims=" + prop.denseVector().dims() + ")";
                            } else if (prop.isObject()) {
                                extra = " (enabled=" + (prop.object().enabled() != null && Boolean.TRUE.equals(prop.object().enabled())) + ")";
                            }
                            log.info("[ES]      - {} -> {}{}", name, propertyTypeName(prop), extra);
                        });
                    }
                } catch (Exception ignored) {
                }
            }
            log.info("=================== ES 诊断结束 ===================");
        } catch (Exception e) {
            log.error("[ES] 诊断失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 读取索引当前的向量维度。
     */
    public static Integer currentVectorDims(ElasticsearchClient esClient, String indexName) {
        try {
            IndexState state = esClient.indices().get(g -> g.index(indexName)).indices().get(indexName);
            if (state == null || state.mappings() == null) {
                return null;
            }
            Property vector = state.mappings().properties().get("vector");
            if (vector == null || !vector.isDenseVector()) {
                return null;
            }
            return vector.denseVector().dims();
        } catch (Exception e) {
            log.debug("[ES] 读取索引[{}]向量维度失败: {}", indexName, e.getMessage());
            return null;
        }
    }

    /**
     * 构造 IK 分词器版本的索引 mapping JSON。
     *
     * metadata 用 {@code dynamic:false} + 全部字段显式声明：
     *
     *   • 防字段污染（切片器将来加字段不会悄悄变成 text 把检索带偏）；
     *   • {@code doc_id} 必须显式声明为可精确查询的类型：它一旦被动态映射成 text，
     *       删除/统计用的 {@code term(metadata.doc_id)} 就会失效：UUID 被分词后基本查不中。
     *
     *
     * text 挂两个子字段：{@code keyword} 精确等值（留档）；
     * {@code standard} 标准分词器，救 ik 会把英文词/数字/订单号切碎的场景
     * （{@code ik_smart} 对 "SKU-10086"、"v2.1" 这类标识符切出来的词往往对不上）。
     *
     * 字段名必须与 {@link ChunkMetadataKeys} 一致。这里是 JSON 字面量，
     * 用不了常量，只能靠约定：所以 {@code EsMetadataMappingTest} 会把两边逐字比对，
     * 改名只改一处时那条测试会红。
     */
    static String buildIkMapping(int dims) {
        return """
                {
                  "mappings": {
                    "properties": {
                      "vector": {
                        "type": "dense_vector",
                        "dims": %d,
                        "index": true,
                        "similarity": "cosine"
                      },
                      "text": {
                        "type": "text",
                        "analyzer": "ik_max_word",
                        "search_analyzer": "ik_smart",
                        "fields": {
                          "keyword": {
                            "type": "keyword",
                            "ignore_above": 256
                          },
                          "standard": {
                            "type": "text",
                            "analyzer": "standard"
                          }
                        }
                      },
                      "metadata": {
                        "type": "object",
                        "dynamic": false,
                        "properties": {
                          "doc_id":          { "type": "keyword" },
                          "file_name":       { "type": "keyword" },
                          "file_name_lower": { "type": "keyword" },
                          "upload_time":     { "type": "keyword" },
                          "domain":          { "type": "keyword" },
                          "content_hash":    { "type": "keyword" },
                          "chunk_seq":       { "type": "integer" },
                          "chunk_total":     { "type": "integer" },
                          "page_from":       { "type": "integer" },
                          "section_title":   { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                          "section_path": {
                            "type": "text",
                            "analyzer": "ik_max_word",
                            "search_analyzer": "ik_smart",
                            "fields": {
                              "keyword": { "type": "keyword", "ignore_above": 512 }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(dims);
    }

    /**
     * 创建带 IK 分词器的索引 mapping。
     *
     * @param esClient  ES 客户端
     * @param indexName 索引名
     * @param dims      向量维度（来自用户声明或实测，不由调用方写死）
     */
    public static void createIndexWithIkMapping(ElasticsearchClient esClient, String indexName, int dims) {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                Integer current = currentVectorDims(esClient, indexName);
                if (current != null && current != dims) {
                    log.error("[ES] 索引[{}]已存在且向量维度不一致：现有 mapping dims={}，当前配置需要 {}。"
                                    + "写入会因维度不符被拒绝，需删除索引后重建"
                                    + "（管控台「知识库」页点「触发重建」）。",
                            indexName, current, dims);
                } else {
                    log.info("[ES] 索引[{}]已存在（mapping dims={}），跳过创建IK mapping", indexName, current);
                }
                return;
            }

            // 使用原始 JSON 构建 mapping（Java API Builder 对 analyzer 支持不直观）
            String mappingJson = buildIkMapping(dims);

            esClient.indices().create(c -> c
                    .index(indexName)
                    .withJson(new java.io.StringReader(mappingJson)));

            log.info("[ES] 索引[{}]创建成功（IK分词器 mapping，dims={}）", indexName, dims);
        } catch (Exception e) {
            // 这里刻意不按报文关键字去分类异常（"是不是 IK 没装"）。
            // 判据靠关键字在多版本、多发行版的 ES 上并不可靠，而错误分类会把用户引向错误方向。
            // 如实说明降级已发生，并指出一条确定的确认途径：
            // 管控台「存储配置」页点一次「测试连接」，会直接告诉你 IK 是否可用。
            log.warn("""
                    ================ [ES] 索引创建降级 ================
                    索引[{}] 未能按 IK 分词器 mapping 创建，将由上层回退到默认创建方式。
                    影响：text 字段改用 ES 默认分词器，中文检索召回质量下降。
                          注意这不会报错、不会失败——索引照样建、灌库照样成功，只是结果变差。
                    最常见原因：该 ES 未安装 analysis-ik 插件（IK 是插件，任何版本都需单独安装）。
                    确认方式：管控台 http://localhost:9527/admin.html「存储配置」页点一次
                              「测试连接」，它会明确告知 IK 是否可用。
                    原始原因：{}
                    ===============================================
                    """, indexName, e.getMessage());
        }
    }

    public static void writeAfterVerify(ElasticsearchClient esClient, String indexName) {
        try {
            esClient.indices().refresh(r -> r.index(indexName));

            long count = esClient.count(c -> c.index(indexName)).count();
            log.info("================ [ES] 写入后校验 ================");
            log.info("[ES] 文档总数: {}", count);

            try {
                IndexState state = esClient.indices().get(g -> g.index(indexName)).indices().get(indexName);
                if (state != null && state.mappings() != null && state.mappings().properties() != null) {
                    Map<String, Property> properties = state.mappings().properties();
                    log.info("[ES] Mapping 字段:");
                    properties.forEach((name, prop) -> {
                        String type = propertyTypeName(prop);
                        String dims = prop.isDenseVector() ? String.valueOf(prop.denseVector().dims()) : "";
                        log.info("[ES]      - {} -> type={}, dims={}", name, type, dims);
                    });
                }
            } catch (Exception ignored) {
            }

            SearchResponse<Map> searchResp = esClient.search(s -> s.index(indexName).size(1), Map.class);
            Map<String, Object> firstSource = null;
            if (searchResp.hits().hits() != null && !searchResp.hits().hits().isEmpty()) {
                firstSource = searchResp.hits().hits().get(0).source();
            }

            if (firstSource != null) {
                log.info("[ES] 首条文档字段: {}", firstSource.keySet());
                String vecField = null;
                String txtField = null;
                int vecLen = 0;
                Object[] vecArr = null;

                for (Map.Entry<String, Object> e : firstSource.entrySet()) {
                    Object v = e.getValue();
                    if (v == null) continue;
                    if (v.getClass().isArray()) {
                        vecField = e.getKey();
                        if (v instanceof double[]) {
                            vecLen = ((double[]) v).length;
                            vecArr = toObjectArray((double[]) v);
                        } else if (v instanceof float[]) {
                            vecLen = ((float[]) v).length;
                            vecArr = toObjectArray((float[]) v);
                        } else if (v instanceof int[]) {
                            vecLen = ((int[]) v).length;
                        }
                        log.info("[ES]      向量字段 = {}，长度 = {}", vecField, vecLen);
                    } else if (v instanceof java.util.List) {
                        vecField = e.getKey();
                        vecLen = ((java.util.List<?>) v).size();
                        vecArr = ((java.util.List<?>) v).toArray();
                        log.info("[ES]      向量字段 = {}，长度 = {}", vecField, vecLen);
                    } else if (v instanceof String) {
                        String sv = (String) v;
                        String curTxt = (String) firstSource.get(txtField);
                        if (txtField == null || (curTxt != null && sv.length() > curTxt.length())) {
                            txtField = e.getKey();
                        }
                    }
                }

                if (txtField != null) {
                    String t = (String) firstSource.get(txtField);
                    if (t != null) {
                        log.info("[ES]      文本字段 = {}，前100字 = {}", txtField,
                                t.length() > 100 ? t.substring(0, 100) + "..." : t);
                    }
                }

                if (vecField != null && vecArr != null && count > 0) {
                    try {
                        String finalVecField = vecField;
                        String finalTxtField = txtField == null ? "text" : txtField;

                        // 字段名来自 _source 的键，直接拼进 Painless 源码等于把一段任意字符串
                        // 交给脚本引擎解析。只放行常规字段名形态（字母/数字/下划线/点），
                        // 其余一律拒绝：否则一条带引号或括号的键就能改写脚本语义。
                        if (!SAFE_FIELD_NAME.matcher(finalVecField).matches()
                                || !SAFE_FIELD_NAME.matcher(finalTxtField).matches()) {
                            throw new IllegalStateException("索引内出现非法字段名，拒绝生成检索脚本：vec="
                                    + finalVecField + ", txt=" + finalTxtField);
                        }

                        Map<String, JsonData> params = new HashMap<>();
                        params.put("q", JsonData.of(vecArr));

                        co.elastic.clients.elasticsearch._types.Script script =
                                new co.elastic.clients.elasticsearch._types.Script.Builder()
                                        .source(ss -> ss.scriptString(
                                                "cosineSimilarity(params.q, '" + finalVecField + "') + 1.0"))
                                        .params(params)
                                        .build();

                        co.elastic.clients.elasticsearch._types.query_dsl.Query scriptScoreQuery =
                                new co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder()
                                        .scriptScore(ss -> ss
                                                .query(q -> q.matchAll(m -> m))
                                                .script(script))
                                        .build();

                        SearchResponse<Map> exampleResp = esClient.search(s -> s
                                        .index(indexName)
                                        .size(2)
                                        .source(src -> src.filter(f -> f.includes(finalTxtField)))
                                        .query(scriptScoreQuery),
                                Map.class);
                        long total = exampleResp.hits().total() != null ? exampleResp.hits().total().value() : 0;
                        log.info("[ES] script_score 示例查询通过，命中 = {}", total);
                    } catch (Exception e) {
                        log.warn("[ES] script_score 示例查询失败: {}", e.getMessage());
                    }
                }
            } else {
                log.info("[ES] 未查询到首条文档，size(0) total = {}",
                        searchResp.hits().total() != null ? searchResp.hits().total().value() : 0);
            }
            log.info("================ [ES] 写入后校验结束 ================");
        } catch (Exception e) {
            log.error("[ES] 写入后校验异常: {}", e.getMessage(), e);
        }
    }

    private static Object[] toObjectArray(double[] arr) {
        Object[] out = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = arr[i];
        return out;
    }

    private static Object[] toObjectArray(float[] arr) {
        Object[] out = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = (double) arr[i];
        return out;
    }

    /** 将 ES Property 转为可读的字段类型名称 */
    private static String propertyTypeName(Property p) {
        if (p == null) return "null";
        if (p.isText()) return "text";
        if (p.isKeyword()) return "keyword";
        if (p.isDenseVector()) return "dense_vector";
        if (p.isObject()) return "object";
        if (p.isNested()) return "nested";
        if (p.isInteger() || p.isLong()) return "long";
        if (p.isFloat()) return "float";
        if (p.isDouble()) return "double";
        if (p.isBoolean()) return "boolean";
        if (p.isIp()) return "ip";
        if (p.isDate()) return "date";
        if (p.isGeoPoint()) return "geo_point";
        return p._kind().name().toLowerCase();
    }
}
