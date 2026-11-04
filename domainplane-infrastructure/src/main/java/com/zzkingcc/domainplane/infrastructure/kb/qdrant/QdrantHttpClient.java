package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Qdrant REST 客户端（薄封装）。
 *
 * <p>刻意不引第三方 SDK：本机 maven 仓库离线且没有向量库客户端，
 * 引入新依赖要么走不通、要么把部署方的依赖树搅浑。Qdrant 的 REST 面足够薄，
 * 用 JDK 自带的 {@link HttpClient} 直接打就够——这也让"换后端"这件事
 * 少一个可能的失败点（依赖冲突）。</p>
 *
 * <p>协议口径（Qdrant v1.x REST）：</p>
 * <ul>
 *   <li>根探活：{@code GET /} → <b>顶层</b> {@code {title, version, commit}}（此端点无 {@code result} 包装）</li>
 *   <li>集合：{@code PUT /collections/{name}}（创建，已存在返回 409 → 视为已存在）</li>
 *   <li>集合列表：{@code GET /collections} → {@code result.collections[].name}</li>
 *   <li>集合信息：{@code GET /collections/{name}} → {@code result.config.params.vectors.size}</li>
 *   <li>写入：{@code PUT /collections/{name}/points?wait=true}（upsert 点位）</li>
 *   <li>向量检索：{@code POST /collections/{name}/points/search}</li>
 *   <li>全文检索：{@code POST /collections/{name}/points/query}（filter + match）</li>
 *   <li>按 docId 删：{@code POST /collections/{name}/points/delete}（filter）</li>
 *   <li>计数：{@code POST /collections/{name}/points/count}（filter 可选）</li>
 *   <li>滚动扫描：{@code POST /collections/{name}/points/scroll}</li>
 * </ul>
 *
 * <p><b>方言关在类内</b>：点位的 payload 键名、向量名、距离度量名都不外泄到 SPI
 * 与配置层——与 ES 适配器同一条纪律（ES 的 {@code +1.0} 偏移也是这样关的）。</p>
 *
 * <p><b>连接参数现取（provider 模式）</b>：本类不持有"当前地址"，
 * 而是持有一个 {@link Supplier}{@code <}{@link VectorTarget}{@code >}，
 * 每次操作开头取一次快照用完即弃。好处是<b>不需要任何热替换接线</b>——
 * 管控台保存后新地址自动生效，也不可能出现"地址改了但客户端还指着旧地址"。
 * 快照<b>整个操作内只用一份</b>：地址、度量、集合名前缀必须来自同一次读取，
 * 否则前缀取自新配置、地址取自旧配置，物理集合名会算到另一台服务上去。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class QdrantHttpClient {

    /** 点位 payload 里的向量字段名（Qdrant 的命名向量机制）。 */
    public static final String VECTOR_NAME = "";

    /** 未配置时的指引文案（ES 侧同款：{@code SwappableElasticsearchTransport}）。 */
    private static final String UNCONFIGURED_HINT =
            "向量库尚未配置，请先在管控台「存储配置」页填写向量库地址并保存"
                    + "（保存后立即生效，无需重启）";

    /** 当前生效的目标（地址 / 口令 / 度量 / 集合前缀） */
    private final Supplier<VectorTarget> targets;

    private final HttpClient http;
    private final ObjectMapper mapper;

    private final Duration timeout;

    /**
     * 连接参数现取的构造器（生产用）。
     *
     * @param targets 当前目标供应器；返回未配置的目标时，首次操作会给出明确指引
     * @param timeout 单次请求超时
     */
    public QdrantHttpClient(Supplier<VectorTarget> targets, Duration timeout) {
        this.targets = targets == null ? () -> VectorTarget.UNCONFIGURED : targets;
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(10) : timeout;
        this.mapper = new ObjectMapper();
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 固定目标的构造器（探活 / 单元测试用）：目标不随外部变化。
     */
    public static QdrantHttpClient fixed(VectorTarget target, Duration timeout) {
        VectorTarget t = target == null ? VectorTarget.UNCONFIGURED : target;
        return new QdrantHttpClient(() -> t, timeout);
    }

    /**
     * 取当前目标；未配置则抛带指引的异常。
     *
     * <p><b>不在构造期抛</b>：向量库地址只能从管控台填，而管控台的可达性依赖
     * 服务能启动——装配期就抛"未配置"等于把用户锁在门外（服务起不来 → 进不去
     * 管控台 → 填不了地址 → 永远起不来）。改成首次使用时抛，指向明确。</p>
     */
    private VectorTarget requireTarget() {
        VectorTarget t = targets.get();
        if (t == null || !t.isConfigured()) {
            throw new NotConfiguredException(UNCONFIGURED_HINT);
        }
        return t;
    }

    /** 供 store / searcher 复用的 JSON 编解码器（保证读写同一套序列化规则）。 */
    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * 当前生效的距离度量（未配置时按 cosine）。
     *
     * <p>只用于<b>创建集合</b>（Qdrant 的 distance 在集合创建时定死）与
     * <b>分数方向判定</b>（euclid 是距离、其余是相似度），不是请求参数。</p>
     */
    public String metric() {
        VectorTarget t = targets.get();
        return t == null ? "cosine" : t.effectiveMetric();
    }

    /** 当前生效的集合名前缀（未配置时为空串）。 */
    public String collectionPrefix() {
        VectorTarget t = targets.get();
        return t == null ? "" : t.effectiveCollectionPrefix();
    }

    /**
     * 规范 collection 名 → 向量库里的物理集合名。
     *
     * <p>所有出网路径都走它，别直接拼 collection——漏一处就会出现
     * "写进 A 集合、读的是 B 集合"这种不报错的错位。</p>
     */
    private static String physical(String collection, VectorTarget target) {
        return target.effectiveCollectionPrefix() + collection;
    }

    /**
     * 配置词表（小写）→ Qdrant REST 的 {@code distance} 枚举（PascalCase）。
     *
     * <p>这两个词表<b>不一样</b>：配置与页面用 {@code cosine / dot / euclid}（小写），
     * 而 Qdrant 的 {@code CreateCollection.vectors.distance} 是 Rust 枚举，
     * 只认 {@code Cosine / Dot / Euclid / Manhattan}。透传小写会让服务端 serde
     * 匹配不上 {@code VectorsConfig}，回 HTTP 400：
     * {@code data did not match any variant of untagged enum VectorsConfig}——
     * 上传阶段就表现为"知识库暂时不可用"（60000），而日志里只有这句反直觉的 serde 报错。</p>
     */
    static String distanceOf(String metric) {
        String m = metric == null ? "" : metric.trim().toLowerCase();
        return switch (m) {
            case "dot" -> "Dot";
            case "euclid", "l2" -> "Euclid";
            case "manhattan", "l1" -> "Manhattan";
            default -> "Cosine";
        };
    }

    // ==================== 集合生命周期 ====================

    /**
     * 创建集合（幂等：已存在视为成功）。
     *
     * <p>维度不一致时<b>不</b>抛——与 ES 适配器同口径：维度变更是显式操作（重灌），
     * 这里只记错误日志，写入侧会如实报错。</p>
     */
    public void ensureCollection(String collection, int dims) throws StoreAccessException {
        VectorTarget t = requireTarget();
        Integer current = currentVectorDims(collection, t);
        if (current != null) {
            if (current != dims) {
                log.error("[Qdrant] 集合[{}]已存在且向量维度不一致：现有 size={}，当前配置需要 {}。"
                                + "写入会因维度不符被拒绝，需删除后重建（管控台「知识库」页点「触发重建」）。",
                        collection, current, dims);
            } else {
                log.info("[Qdrant] 集合[{}]已存在（size={}），跳过创建", collection, current);
            }
            return;
        }
        String metric = t.effectiveMetric();
        ObjectNode body = mapper.createObjectNode();
        ObjectNode vectors = body.putObject("vectors");
        vectors.put("size", dims);
        vectors.put("distance", distanceOf(metric));
        try {
            HttpResponse<String> resp = send(t, "PUT", "/collections/" + enc(physical(collection, t)), body.toString());
            if (resp.statusCode() == 409) {
                // 并发创建：另一个线程刚建好，视为成功
                log.info("[Qdrant] 集合[{}]已由并发请求创建（HTTP 409）", collection);
                return;
            }
            requireSuccess(resp, "创建集合[" + collection + "]");
            log.info("[Qdrant] 集合[{}]创建成功（distance={}, size={}）", collection, distanceOf(metric), dims);
        } catch (StoreAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new StoreAccessException("创建集合[" + collection + "]失败：" + e.getMessage(), e);
        }
    }

    /**
     * 创建<b>无向量</b>集合（纯 keyword 后端用）：不写 {@code vectors} 段，
     * 集合只承载 payload（文本 + 元数据），点位用 {@link QdrantPoint#vector()} 为
     * {@code null} 的 payload-only 形态写入。
     *
     * <p>幂等与带向量版本同口径（已存在视为成功、并发 409 视为成功）。维度与纯 keyword
     * 后端无关，这里不比较——keyword-only 后端本就不存向量，跨"无向量集合"与"带向量集合"
     * 复用同一规范名也不会冲突（payload-only 点位可落在带向量集合里）。</p>
     */
    public void ensureCollection(String collection) throws StoreAccessException {
        VectorTarget t = requireTarget();
        if (exists(collection)) {
            log.info("[Qdrant] 集合[{}]已存在（无向量），跳过创建", collection);
            return;
        }
        // 关键差异：body 不含 vectors 段 → Qdrant 创建无向量集合
        ObjectNode body = mapper.createObjectNode();
        try {
            HttpResponse<String> resp = send(t, "PUT", "/collections/" + enc(physical(collection, t)), body.toString());
            if (resp.statusCode() == 409) {
                log.info("[Qdrant] 集合[{}]已由并发请求创建（HTTP 409，无向量）", collection);
                return;
            }
            requireSuccess(resp, "创建集合[" + collection + "]（无向量）");
            log.info("[Qdrant] 集合[{}]创建成功（无向量，纯 keyword）", collection);
        } catch (StoreAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new StoreAccessException("创建集合[" + collection + "]（无向量）失败：" + e.getMessage(), e);
        }
    }

    public boolean exists(String collection) throws StoreAccessException {
        VectorTarget t = requireTarget();
        try {
            HttpResponse<String> resp = send(t, "GET", "/collections/" + enc(physical(collection, t)), null);
            if (resp.statusCode() == 404) {
                return false;
            }
            requireSuccess(resp, "查询集合[" + collection + "]是否存在");
            return true;
        } catch (StoreAccessException e) {
            throw e;
        } catch (Exception e) {
            // fail-closed：查不到不等同于不存在
            throw new StoreAccessException("查询集合[" + collection + "]是否存在失败"
                    + "（无法确认，不等同于不存在）：" + e.getMessage(), e);
        }
    }

    public boolean deleteCollection(String collection) throws StoreAccessException {
        VectorTarget t = requireTarget();
        if (!exists(collection)) {
            return false;
        }
        try {
            HttpResponse<String> resp = send(t, "DELETE", "/collections/" + enc(physical(collection, t)), null);
            requireSuccess(resp, "删除集合[" + collection + "]");
            log.info("[Qdrant] 已删除集合: {}", collection);
            return true;
        } catch (StoreAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new StoreAccessException("删除集合[" + collection + "]失败：" + e.getMessage(), e);
        }
    }

    public List<String> listCollections() {
        VectorTarget t;
        try {
            t = requireTarget();
        } catch (NotConfiguredException e) {
            log.warn("[Qdrant] 枚举集合失败: {}", e.getMessage());
            return List.of();
        }
        try {
            HttpResponse<String> resp = send(t, "GET", "/collections", null);
            requireSuccess(resp, "枚举集合");
            JsonNode root = mapper.readTree(resp.body());
            List<String> names = new java.util.ArrayList<>();
            JsonNode cols = root.path("result").path("collections");
            if (cols.isArray()) {
                for (JsonNode c : cols) {
                    String name = c.path("name").asText(null);
                    if (name != null && !name.isBlank()) {
                        // 剥掉前缀回规范名：SPI 层看到的 collection 名必须与
                        // KbIndexes.nameOf(domain) 一致，否则上层拿物理名去查别的后端
                        String prefix = t.effectiveCollectionPrefix();
                        if (!prefix.isEmpty() && name.startsWith(prefix)) {
                            name = name.substring(prefix.length());
                        }
                        names.add(name);
                    }
                }
            }
            names.sort(String::compareTo);
            return names;
        } catch (Exception e) {
            log.warn("[Qdrant] 枚举集合失败: {}", e.getMessage());
            return List.of();
        }
    }

    public Integer currentVectorDims(String collection) {
        VectorTarget t;
        try {
            t = requireTarget();
        } catch (NotConfiguredException e) {
            return null;
        }
        return currentVectorDims(collection, t);
    }

    private Integer currentVectorDims(String collection, VectorTarget t) {
        try {
            HttpResponse<String> resp = send(t, "GET", "/collections/" + enc(physical(collection, t)), null);
            if (resp.statusCode() == 404) {
                return null;
            }
            requireSuccess(resp, "读取集合[" + collection + "]维度");
            JsonNode root = mapper.readTree(resp.body());
            JsonNode vectors = root.path("result").path("config").path("params").path("vectors");
            if (vectors.has("size")) {
                return vectors.get("size").asInt();
            }
            // 命名向量形态：{"vectors": {"<name>": {"size": 128, "distance": "Cosine"}}}
            if (vectors.isObject() && vectors.size() > 0) {
                JsonNode first = vectors.elements().next();
                if (first.has("size")) {
                    return first.get("size").asInt();
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("[Qdrant] 读取集合[{}]向量维度失败: {}", collection, e.getMessage());
            return null;
        }
    }

    /** 探活：Qdrant 的根路径返回版本信息。 */
    public boolean ping() {
        VectorTarget t;
        try {
            t = requireTarget();
        } catch (NotConfiguredException e) {
            return false;
        }
        try {
            HttpResponse<String> resp = send(t, "GET", "/", null);
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取服务版本（诊断与管控台探活用）。 */
    public String version() {
        VectorTarget t;
        try {
            t = requireTarget();
        } catch (NotConfiguredException e) {
            return null;
        }
        try {
            HttpResponse<String> resp = send(t, "GET", "/", null);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return null;
            }
            JsonNode root = mapper.readTree(resp.body());
            // Qdrant 根端点直接返回 {"title","version","commit"}，没有 result 包装；
            // 仅在前置网关/代理改写过响应时可能出现 result 层，故做回退兼容。
            String v = text(root, "version");
            if (v == null) {
                v = text(root.path("result"), "version");
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return s == null || s.isBlank() ? null : s;
    }

    // ==================== 点位读写 ====================

    /**
     * 批量 upsert 点位。
     *
     * <p>点位 id 用切片 id；payload 放 text + metadata（扁平成字符串键值），
     * 向量单独放 {@code vector} 字段（无名向量形态）。</p>
     */
    public void upsert(String collection, List<QdrantPoint> points) throws StoreAccessException {
        if (points == null || points.isEmpty()) {
            return;
        }
        VectorTarget t = requireTarget();
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("points");
        for (QdrantPoint p : points) {
            ObjectNode node = arr.addObject();
            node.put("id", p.id());
            ObjectNode payload = node.putObject("payload");
            payload.put("text", p.text());
            for (Map.Entry<String, String> e : p.metadata().entrySet()) {
                payload.put(e.getKey(), e.getValue());
            }
            if (p.vector() != null) {
                ArrayNode vec = node.putArray("vector");
                for (float v : p.vector()) {
                    vec.add(v);
                }
            }
        }
        try {
            HttpResponse<String> resp = send(t, "PUT",
                    "/collections/" + enc(physical(collection, t)) + "/points?wait=true", body.toString());
            requireSuccess(resp, "写入集合[" + collection + "]");
        } catch (StoreAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new StoreAccessException("写入集合[" + collection + "]失败：" + e.getMessage(), e);
        }
    }

    /** 向量检索。 */
    public List<QdrantScored> searchByVector(String collection, float[] queryVector, int topK)
            throws StoreAccessException {
        VectorTarget t = requireTarget();
        ObjectNode body = mapper.createObjectNode();
        body.put("limit", Math.max(1, topK));
        body.put("with_payload", true);
        body.put("with_vector", false);
        ArrayNode vec = body.putArray("vector");
        for (float v : queryVector) {
            vec.add(v);
        }
        return postAndReadHits(t, collection, "/points/search", body, "向量检索");
    }

    /**
     * 全文检索（Qdrant 的 match 查询，命中 payload 里的 text 字段）。
     *
     * <p>这是 Qdrant 侧的<b>关键词路</b>：与向量路分开成两个能力，
     * 与 ES 适配器的 BM25 路同层。分数是 Qdrant 的 BM25 分，
     * 只承诺"同一后端同一模态内可比"（SPI 分数契约）。</p>
     */
    public List<QdrantScored> searchByText(String collection, String queryText, int topK)
            throws StoreAccessException {
        VectorTarget t = requireTarget();
        ObjectNode body = mapper.createObjectNode();
        body.put("limit", Math.max(1, topK));
        body.put("with_payload", true);
        body.put("with_vector", false);
        ObjectNode query = body.putObject("query");
        query.put("text", queryText);
        ObjectNode match = query.putObject("match");
        match.put("text", "text");
        return postAndReadHits(t, collection, "/points/query", body, "全文检索");
    }

    /** 按 docId 删除该文档的全部点位。 */
    public int deleteByDocId(String collection, String docId) throws StoreAccessException {
        VectorTarget t = requireTarget();
        ObjectNode body = mapper.createObjectNode();
        body.put("wait", true);
        applyDocIdFilter(body, docId);
        try {
            HttpResponse<String> resp = send(t, "POST",
                    "/collections/" + enc(physical(collection, t)) + "/points/delete", body.toString());
            requireSuccess(resp, "删除集合[" + collection + "]中文档[" + docId + "]");
            return 1;
        } catch (StoreAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new StoreAccessException("删除集合[" + collection + "]中文档[" + docId + "]失败："
                    + e.getMessage(), e);
        }
    }

    /** 计数（filter 可空 = 全集计数）。 */
    public long count(String collection, String docId) {
        VectorTarget t;
        try {
            t = requireTarget();
        } catch (NotConfiguredException e) {
            return 0;
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("exact", true);
            if (docId != null && !docId.isBlank()) {
                applyDocIdFilter(body, docId);
            }
            HttpResponse<String> resp = send(t, "POST",
                    "/collections/" + enc(physical(collection, t)) + "/points/count", body.toString());
            requireSuccess(resp, "统计集合[" + collection + "]");
            JsonNode root = mapper.readTree(resp.body());
            return root.path("result").path("count").asLong(0);
        } catch (Exception e) {
            log.warn("[Qdrant] 统计集合[{}]失败: {}", collection, e.getMessage());
            return 0;
        }
    }

    /**
     * 写入 docId 过滤条件。
     *
     * <p>Qdrant 的 filter 形态是 {@code {"must": [{"key": "...", "match": {"value": "..."}}]}}——
     * {@code must} 是数组（可多个条件 AND），字段条件是 {@code {key, match}} 结构，
     * 不是 ES 那种 {@code {term: {...}}}。方言差异关在本类内。</p>
     */
    private void applyDocIdFilter(ObjectNode body, String docId) {
        ArrayNode must = body.putObject("filter").putArray("must");
        ObjectNode cond = must.addObject();
        cond.put("key", "doc_id");
        cond.putObject("match").put("value", docId);
    }

    /**
     * 一页滚动结果：点位 + <b>同一次响应</b>里的下一页游标。
     *
     * <p>为什么不分开取：Qdrant scroll 的 {@code result.next_page_offset} 就在响应里，
     * 为读它再发一次 {@code limit:1} 的请求，等于把每页的往返次数翻倍；更糟的是
     * 那个"探针"请求传的是旧 offset，语义上并不等于"本页之后还有没有下一页"，
     * 一旦服务端据此回了个非空游标，翻页就会原地打转、扫满上限才停——
     * 管控台"知识库页加载慢"就是这么来的（8 个切片扫了 125 页、250 次请求、约 29 秒）。</p>
     */
    public record ScrollPage(List<QdrantScored> points, Object nextOffset) {

        public boolean hasMore() {
            return nextOffset != null;
        }
    }

    /**
     * 滚动扫描：按 offset 翻页取点位，游标取自同一次响应。
     *
     * <p>与 ES 适配器的 {@code search_after} 同职责（对账补写 / 重灌 / 文档清单）。</p>
     *
     * @param offset      上一页返回的 {@code nextOffset}；首页传 {@code null}
     * @param withVectors 是否要回向量：只列文档/查去重时关掉，省掉整页 1024 维浮点数的传输
     */
    public ScrollPage scrollPage(String collection, int limit, Object offset, boolean withVectors) {
        VectorTarget t;
        try {
            t = requireTarget();
        } catch (NotConfiguredException e) {
            return new ScrollPage(List.of(), null);
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("limit", Math.max(1, limit));
            body.put("with_payload", true);
            body.put("with_vector", withVectors);
            if (offset != null) {
                body.set("offset", mapper.valueToTree(offset));
            }
            HttpResponse<String> resp = send(t, "POST",
                    "/collections/" + enc(physical(collection, t)) + "/points/scroll", body.toString());
            requireSuccess(resp, "扫描集合[" + collection + "]");
            JsonNode result = mapper.readTree(resp.body()).path("result");
            List<QdrantScored> out = new java.util.ArrayList<>();
            for (JsonNode p : result.path("points")) {
                QdrantScored scored = toScored(p);
                if (scored != null) {
                    out.add(scored);
                }
            }
            JsonNode next = result.path("next_page_offset");
            Object nextOffset = next.isMissingNode() || next.isNull() ? null : next.asText();
            return new ScrollPage(out, nextOffset);
        } catch (Exception e) {
            log.warn("[Qdrant] 扫描集合[{}]失败: {}", collection, e.getMessage());
            return new ScrollPage(List.of(), null);
        }
    }

    // ==================== 内部 ====================

    private List<QdrantScored> postAndReadHits(VectorTarget t, String collection, String path, ObjectNode body,
                                               String what) throws StoreAccessException {
        try {
            HttpResponse<String> resp = send(t, "POST", "/collections/" + enc(physical(collection, t)) + path,
                    body.toString());
            requireSuccess(resp, what + "（集合[" + collection + "]）");
            JsonNode root = mapper.readTree(resp.body());
            List<QdrantScored> out = new java.util.ArrayList<>();
            JsonNode result = root.path("result");
            if (result.isArray()) {
                for (JsonNode p : result) {
                    QdrantScored scored = toScored(p);
                    if (scored != null) {
                        out.add(scored);
                    }
                }
            }
            return out;
        } catch (StoreAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new StoreAccessException(what + "失败（集合[" + collection + "]）：" + e.getMessage(), e);
        }
    }

    /** Qdrant 点位 → 内部结构。 */
    private QdrantScored toScored(JsonNode point) {
        JsonNode payload = point.path("payload");
        String text = payload.path("text").asText("");
        if (text.isBlank()) {
            // 与 ES 适配器同口径：正文空白的命中不进融合
            return null;
        }
        java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> payloadFields = payload.fields();
        while (payloadFields.hasNext()) {
            java.util.Map.Entry<String, JsonNode> e = payloadFields.next();
            if ("text".equals(e.getKey())) {
                continue;
            }
            JsonNode v = e.getValue();
            if (v.isValueNode()) {
                metadata.put(e.getKey(), v.asText());
            }
        }
        float[] vector = null;
        JsonNode vectorNode = point.path("vector");
        if (vectorNode.isArray() && !vectorNode.isEmpty()) {
            vector = new float[vectorNode.size()];
            for (int i = 0; i < vectorNode.size(); i++) {
                vector[i] = vectorNode.get(i).floatValue();
            }
        }
        double score = point.path("score").asDouble(0.0);
        String id = point.path("id").asText(null);
        if (id == null) {
            return null;
        }
        return new QdrantScored(id, text, metadata, score, vector);
    }

    HttpResponse<String> send(VectorTarget t, String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(t.baseUrl() + path))
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if (t.apiKey() != null && !t.apiKey().isBlank()) {
            builder.header("api-key", t.apiKey());
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        builder.method(method, publisher);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void requireSuccess(HttpResponse<String> resp, String what) {
        int code = resp.statusCode();
        if (code >= 200 && code < 300) {
            return;
        }
        String body = resp.body();
        if (body != null && body.length() > 500) {
            body = body.substring(0, 500) + "...";
        }
        throw new StoreAccessException(what + "失败（HTTP " + code + "）："
                + (body == null || body.isBlank() ? "（无响应体）" : body));
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
