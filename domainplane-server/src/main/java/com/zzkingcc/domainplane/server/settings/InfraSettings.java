package com.zzkingcc.domainplane.server.settings;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础设施存储配置（Redis + 具名连接 + 知识体，管控台可编辑）。
 *
 * <p><b>为什么不是"三个固定槽"</b>：关键字与向量是<b>两条召回路</b>，不是两种产品。ES 两路
 * 都能干（BM25 + dense_vector），Qdrant 只能做向量。用"ES 槽 / 向量槽"这种按产品分的
 * 固定字段，表达不了"两个 ES 连接指向不同集群"或"关键字在 ES、向量在 Qdrant"。
 * 所以配置分成两层：</p>
 *
 * <ul>
 *   <li>{@link #connections}：<b>具名连接</b>，id → 一台具体实例（ES 或 Qdrant）。
 *       只管"连哪里"（地址、口令、超时），不管"拿来干什么"。</li>
 *   <li>{@link #bodies}：<b>知识体</b>，一个体 = <b>一份可被多个域共用的知识</b>：
 *       它有自己的唯一名（{@link KnowledgeBody#getSlug()}）与由此派生、创建后冻结的索引名
 *       （{@link KnowledgeBody#getIndex()}），再加上两路落点（关键字 / 向量），
 *       每路指向一台连接、可各自留空。</li>
 * </ul>
 *
 * <p><b>体是数据身份，域只是消费者</b>：域绑定一个体，多个域绑同一个体就查同一份数据；
 * 删域只解绑，不删体的索引与原文。v1 的"一域一索引"由迁移器登记成体（索引名沿用旧值），
 * 之后新建的索引名一律按体的唯一名派生。</p>
 *
 * <p>两层分开的直接好处：<b>连接可以复用、知识体可以组合</b>。同一个 ES 连接可以同时
 * 填进一个体的两个槽（一台机器干两件事），也可以被多个体共用；地址一致时由
 * {@link Connection#location()} 判定为同一台实例，不需要用户理解"复用"这回事。</p>
 *
 * <p>"哪个域用哪个知识体"<b>不在这里</b>——那是域自己的声明（{@code DomainStore}），
 * 域与知识体是"多对一、不继承"的关系：没绑的域就是没有知识库，不去找父域借。</p>
 *
 * <p>{@code redis} 不参与这个抽象：它是平台自身的基础设施（会话、检查点、缓存），
 * 只有一个、也不按用途分，硬塞进"连接"里只会多一层无意义的间接。</p>
 *
 * <p>旧配置（{@code {"es":{...},"vectorStore":{...}}}）由
 * {@link InfraSettingsStore#load()} 在读盘时迁移成新结构，不在本类里留兼容字段——
 * 那会让"到底哪个才是真相源"永远说不清。</p>
 *
 * @author zzkingcc
 */
@Data
public class InfraSettings {

    /** Elasticsearch 连接类型 */
    public static final String TYPE_ELASTICSEARCH = "elasticsearch";

    /** Qdrant 连接类型 */
    public static final String TYPE_QDRANT = "qdrant";

    /** 受支持的连接类型白名单 */
    public static final List<String> SUPPORTED_TYPES = List.of(TYPE_ELASTICSEARCH, TYPE_QDRANT);

    /** 召回路：关键字（全文检索） */
    public static final String KIND_KEYWORD = "keyword";

    /** 召回路：向量（kNN 检索） */
    public static final String KIND_VECTOR = "vector";

    /** 知识体可配的两路（一个体里各自可选，互不排斥） */
    public static final List<String> SUPPORTED_KINDS = List.of(KIND_KEYWORD, KIND_VECTOR);

    /** 原文落点：平台内置 BlobStore（当前唯一实现） */
    public static final String RAW_LOCAL = "local";

    /** 受支持的原文落点白名单（S3 / COS / OSS / 文件服务器接入后在此追加，同时补 BlobStore 驱动） */
    public static final List<String> SUPPORTED_RAW_LOCATIONS = List.of(RAW_LOCAL);

    /** 原文落点的人话名字：页面与接口共用一处，免得两边说法漂移 */
    public static String rawLabel(String location) {
        return RAW_LOCAL.equals(location) ? "本地原文存储" : location;
    }

    /** 归属段：关键字（管控台 2-1） */
    public static final String USAGE_KEYWORD = "keyword";

    /** 归属段：向量（管控台 2-2） */
    public static final String USAGE_VECTOR = "vector";

    /** Redis 连接（平台基础设施，单例） */
    private Redis redis = new Redis();

    /** 具名连接：id → 连接定义。只描述"连哪里" */
    private Map<String, Connection> connections = new LinkedHashMap<>();

    /**
     * 知识体：id → <b>一套知识库</b>（最多两路召回，每路可选）。
     *
     * <p>与改造前"存储集"的区别只有一个：<b>谁绑它</b>。存储集是平台级的，
     * 所有域继承同一套；知识体是域自己绑的，<b>不继承</b>——没绑的域就是没有知识库，
     * 不去找父域借（借体会让子域的文档写进父域的库，用户完全看不出来）。</p>
     */
    private Map<String, KnowledgeBody> bodies = new LinkedHashMap<>();

    /**
     * 管控台是否保存过。
     *
     * <p>有了这个位，"一条连接都没配"才有两种可区分的意思：{@code false} = 还没配过
     * （生效值由 yaml 合成，开箱可用）；{@code true} = 用户显式清空了（就该是空的，
     * 不能又把 yaml 那条塞回来——那样用户永远删不掉表里那一行）。</p>
     */
    private boolean configured;

    // ==================== 具名连接 ====================

    /**
     * 一台具体实例。
     *
     * <p>{@code es} 与 {@code vector} 两段是<b>按类型二选一</b>的：ES 连接用前者，
     * Qdrant 连接用后者。留成两个可空字段而不是继承，是为了让 JSON 直观、也让
     * "这台机器是 ES 还是 Qdrant"在配置里一眼可见。</p>
     */
    @Data
    public static class Connection {

        /** 连接 id（用户命名，如 {@code es-main} / {@code qdrant-vec}），也是知识库后端 id */
        private String id;

        /** 展示名（可为空，空则回落到 id） */
        private String name;

        /** 类型：{@code elasticsearch} / {@code qdrant} */
        private String type = TYPE_ELASTICSEARCH;

        /**
         * 归属段：{@code keyword} / {@code vector}。
         *
         * <p>决定两件事：管控台把这一行画在 2-1 还是 2-2 那张表里，以及知识体的槽下拉
         * 能不能选到它——关键字段配的 ES 不会出现在向量下拉里，反之亦然。
         * <b>不参与检索判定</b>：谁干哪活只看知识体绑了哪台连接。</p>
         *
         * <p>同一台 ES 想两槽都用，就在两段各配一条（同地址、不同 id）：{@link #location()}
         * 会判定为同一台实例，写入仍然只落一次，不会双写。</p>
         */
        private String usage;

        /** ES 连接参数（仅 type=elasticsearch 时有意义） */
        private Elasticsearch es;

        /** Qdrant 连接参数（仅 type=qdrant 时有意义） */
        private VectorStore vector;

        @JsonIgnore
        public String effectiveType() {
            return hasText(type) ? type.trim().toLowerCase() : TYPE_ELASTICSEARCH;
        }

        @JsonIgnore
        public boolean isElasticsearch() {
            return TYPE_ELASTICSEARCH.equals(effectiveType());
        }

        @JsonIgnore
        public boolean isQdrant() {
            return TYPE_QDRANT.equals(effectiveType());
        }

        @JsonIgnore
        public String effectiveName() {
            return hasText(name) ? name.trim() : id;
        }

        /**
         * 归属段：{@code keyword} / {@code vector}，只决定画在哪张表里。
         *
         * <p>缺省时按类型给归属（ES → 关键字、Qdrant → 向量），这样老配置
         * （那时还没有这个字段）会落在它原本的位置，而不是从两张表里一起消失。</p>
         */
        @JsonIgnore
        public String effectiveUsage() {
            if (hasText(usage)) {
                String u = usage.trim().toLowerCase();
                if (KIND_KEYWORD.equals(u) || KIND_VECTOR.equals(u)) {
                    return u;
                }
            }
            return isQdrant() ? KIND_VECTOR : KIND_KEYWORD;
        }

        /** 该连接填了连接信息没有（只判可用性，不测连通性） */
        @JsonIgnore
        public boolean isUsable() {
            if (isElasticsearch()) {
                return es != null && es.isUsable();
            }
            if (isQdrant()) {
                return vector != null && vector.isUsable();
            }
            return false;
        }

        /**
         * 物理实例标识：<b>协议 + 主机 + 端口</b>（Qdrant 用规范化后的 uri）。
         *
         * <p>这是"同实例合并"的唯一判据。刻意不含账号与超时：同一个集群换个账号
         * 还是同一个集群、还是同一份索引，写入必须仍然只落一次。</p>
         */
        @JsonIgnore
        public String location() {
            if (isElasticsearch()) {
                return es == null || !es.isUsable()
                        ? null
                        // 主机名大小写不敏感（DNS 语义），统一小写再比，免得 "ES.internal"
                        // 与 "es.internal" 被当成两台机器而重复写
                        : es.effectiveScheme() + "://" + es.getHost().trim().toLowerCase()
                            + ":" + es.effectivePort();
            }
            if (isQdrant()) {
                return vector == null || !vector.isUsable() ? null : trimEndSlash(vector.getUri());
            }
            return null;
        }

        @JsonIgnore
        public String describe() {
            if (isElasticsearch()) {
                return es == null ? "未配置" : es.describe();
            }
            if (isQdrant()) {
                return vector == null ? "未配置" : vector.describe();
            }
            return "不支持的类型 " + effectiveType();
        }

        /** 形态校验；问题文案带上连接 id，多点几个连接时能分清是谁填错了 */
        @JsonIgnore
        public List<String> validate() {
            List<String> problems = new ArrayList<>();
            if (!SUPPORTED_TYPES.contains(effectiveType())) {
                problems.add("连接 " + id + " 的类型「" + effectiveType() + "」不受支持，"
                        + "当前支持 " + String.join(" / ", SUPPORTED_TYPES));
                return problems;
            }
            if (!hasText(id)) {
                problems.add("连接缺少 id（用于被知识体引用）");
            }
            if (isElasticsearch() && es != null) {
                for (String p : es.validate()) {
                    problems.add("连接 " + id + "：" + p);
                }
            }
            if (isQdrant() && vector != null) {
                for (String p : vector.validate()) {
                    problems.add("连接 " + id + "：" + p);
                }
            }
            return problems;
        }
    }

    // ==================== 知识体 ====================

    /**
     * 一个知识体：<b>一套知识库</b>，最多两路召回（关键字 / 向量），每路可选。
     *
     * <p><b>为什么一个体能装两路</b>：混合召回的前提是同一批文档既有关键字索引又有向量索引。
     * 若把"关键字在哪"和"向量在哪"拆成两个体，域就必须绑两次，而"绑了一个、忘了另一个"
     * 的表现是<b>静默退化成单路</b>——召回到了，只是少一半，没人会去查。装在一个体里，
     * "这个域用几条路"由体自己一眼回答：两路都配 = 混合，只配一路 = 单路。</p>
     *
     * <p><b>原文落点也在体上</b>（{@link #raw}）：原文是权威源，换模型 / 换索引落点都要从它重灌，
     * 所以"原文存在哪"是体自己的身份配置，不是平台级开关。当前只有内置 {@code BlobStore}
     * （{@code local}）；文件服务器 / S3 兼容存储（COS、OSS、MinIO）接入后，同一个字段里
     * 会出现对应连接，迁移时只搬原文、索引原地从原文重建。</p>
     *
     * <p>两路可以指向<b>同一条连接</b>（一台 ES 既做 BM25 又做 dense_vector），
     * 那时索引只有一份、写入只写一次：判定按 {@link Connection#location()}，
     * 见 {@code RetrievalPlan.writeStore}。</p>
     */
    @Data
    public static class KnowledgeBody {

        /** 知识体 id（用户命名，如 {@code kb-sales}），域绑定时引用它 */
        private String id;

        private String name;

        /**
         * 知识体唯一名：小写字母 / 数字 / 连字符，全局唯一。
         *
         * <p>它是<b>索引名的来源</b>（{@link #effectiveIndex()}）：创建时派生一次并写进
         * {@link #index}，之后改名不搬数据。唯一性由保存路径校验（{@code AdminBodyController}），
         * 这里只做形态兜底（老数据可能没有这个字段）。</p>
         */
        private String slug;

        /**
         * 冻结的索引名：创建时按唯一名派生（{@code domainplane_kb_<slug>_<id哈希>}），
         * 迁移体的则是旧的域索引名。
         *
         * <p><b>永远不要重新派生它</b>：改名 / 换落点 / 改绑定都不该换索引名，
         * 换名意味着数据搬到另一个集合、旧集合变成永远查不到的孤儿。</p>
         */
        private String index;

        /** 关键字路的连接 id（全文检索，<b>约定</b>用 ES）；不配这一路就留空 */
        private String keyword;

        /** 向量路的连接 id（kNN 检索，ES / Qdrant）；不配这一路就留空 */
        private String vector;

        /**
         * 原文落点：{@link #RAW_LOCAL}（平台内置 BlobStore）或将来接入的文件服务连接 id。
         *
         * <p>原文是<b>权威源</b>（换模型/换落点都从它重灌），所以它和索引落点一样属于知识体的
         * 身份配置。当前只有 {@code local} 一个可选值：S3 / COS / OSS / 自建文件服务器要等
         * {@code BlobStore} 多驱动接入后才会出现在可选列表里（API 的 {@code rawOptions}
         * 如实反映"现在能选什么"，不给假选项）。</p>
         */
        private String raw;

        /** 一句话说明（卡片上显示，可空） */
        private String description;

        @JsonIgnore
        public String effectiveName() {
            return hasText(name) ? name.trim() : id;
        }

        /** 原文落点：留空按 local（老配置没有这个字段） */
        @JsonIgnore
        public String rawLocation() {
            return hasText(raw) ? raw.trim().toLowerCase() : RAW_LOCAL;
        }

        /** 唯一名：没写时按 id 规范化兜底（老数据 / yaml 合成的体没有这个字段） */
        @JsonIgnore
        public String effectiveSlug() {
            String raw = hasText(slug) ? slug.trim() : (hasText(id) ? id : name);
            String normalized = com.zzkingcc.domainplane.api.support.KbIndexes.normalizeSlug(raw);
            return normalized.isBlank() ? "kb" : normalized;
        }

        /**
         * 索引名：有冻结值就用它，否则按唯一名现算（只用于没有身份字段的老体 / yaml 合成体）。
         *
         * <p>现算分支只保证"同一份配置每次算出来一样"，不保证改名后不变——所以凡是
         * 要改名的体，必须先由保存路径把 {@link #index} 写实。</p>
         */
        @JsonIgnore
        public String effectiveIndex() {
            if (hasText(index)) {
                return index.trim();
            }
            return com.zzkingcc.domainplane.api.support.KbIndexes.nameOfBody(
                    effectiveSlug(), hasText(id) ? id.trim() : effectiveSlug());
        }

        @JsonIgnore
        public String keywordId() {
            return hasText(keyword) ? keyword.trim() : null;
        }

        @JsonIgnore
        public String vectorId() {
            return hasText(vector) ? vector.trim() : null;
        }

        @JsonIgnore
        public boolean hasKeyword() {
            return keywordId() != null;
        }

        @JsonIgnore
        public boolean hasVector() {
            return vectorId() != null;
        }

        /** 有没有配到任何一路（一个都没配 = 这个体是空壳，域绑它等于没绑） */
        @JsonIgnore
        public boolean configured() {
            return hasKeyword() || hasVector();
        }

        /** 这个体走哪几路，供管控台与日志展示；顺序固定为关键字在前 */
        @JsonIgnore
        public List<String> modes() {
            List<String> modes = new ArrayList<>(2);
            if (hasKeyword()) {
                modes.add(KIND_KEYWORD);
            }
            if (hasVector()) {
                modes.add(KIND_VECTOR);
            }
            return modes;
        }

        /** 两路都配了吗（混合召回的前提） */
        @JsonIgnore
        public boolean hybrid() {
            return hasKeyword() && hasVector();
        }
    }

    // ==================== 派生取值 ====================

    /** 按 id 取知识体；不存在返回 {@code null} */
    @JsonIgnore
    public KnowledgeBody body(String id) {
        if (bodies == null || !hasText(id)) {
            return null;
        }
        return bodies.get(id.trim());
    }

    /**
     * 平台默认连接：<b>第一个配了这一路的知识体</b>给的那个连接 id。
     *
     * <p>用于"没有域上下文"的场景（未绑域的通配检索、诊断、内部 EX 存在性检查）。
     * 域自己的存储一律走域绑定的知识体，不经过这里——那才是"每个域各用各的"。</p>
     */
    @JsonIgnore
    public String platformConnectionId(String kind) {
        for (KnowledgeBody b : bodiesOrEmpty().values()) {
            if (b == null) {
                continue;
            }
            String id = KIND_KEYWORD.equals(kind) ? b.keywordId() : b.vectorId();
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /** 某个用途是否至少有一个可用知识体（管控台状态行与启动日志用） */
    @JsonIgnore
    public boolean isKindUsable(String kind) {
        String id = platformConnectionId(kind);
        Connection c = connection(id);
        return c != null && c.isUsable();
    }

    /**
     * 按 id 取一个<b>真能干活</b>的知识体：存在、配了至少一路、且那一路的连接存在且可用。
     *
     * <p>"至少一路"而不是"两路都可用"：关键字体那台 ES 被删、向量体还在，这个域就该
     * 继续走向量路，而不是整块知识库消失。哪一路可用由 {@code KnowledgeBodyResolver}
     * 逐槽判断，这里只回答"绑这个体还有没有意义"。</p>
     *
     * @return 可用的体；否则 {@code null}（域绑了它等于没绑，检索工具也不该给这个域）
     */
    @JsonIgnore
    public KnowledgeBody usableBody(String id) {
        KnowledgeBody b = body(id);
        if (b == null || !b.configured()) {
            return null;
        }
        if (usableConnection(b.keywordId()) != null || usableConnection(b.vectorId()) != null) {
            return b;
        }
        return null;
    }

    /** 该连接存在且填了地址（只判可用性，不测连通性） */
    @JsonIgnore
    public Connection usableConnection(String id) {
        Connection c = connection(id);
        return c != null && c.isUsable() ? c : null;
    }

    /** 按 id 取连接；不存在返回 {@code null} */
    @JsonIgnore
    public Connection connection(String id) {
        if (connections == null || !hasText(id)) {
            return null;
        }
        return connections.get(id.trim());
    }

    /**
     * 平台自己的 ES 连接：<b>关键字体</b>那一个。
     *
     * <p>为什么是关键字体而不是"第一个 ES"：平台内部的 ES 用途（索引存在性检查、
     * 切片导出、诊断）都围绕"有文本的那一份"，那正是关键体的语义。没有关键字体时
     * 回落到第一个 ES 连接，再没有就返回空对象——保持"未配置是合法状态"。</p>
     */
    @JsonIgnore
    public Elasticsearch defaultEs() {
        Connection kw = connection(platformConnectionId(KIND_KEYWORD));
        if (kw != null && kw.isElasticsearch() && kw.getEs() != null) {
            return kw.getEs();
        }
        for (Connection c : connectionsOrEmpty().values()) {
            if (c != null && c.isElasticsearch() && c.getEs() != null) {
                return c.getEs();
            }
        }
        return new Elasticsearch();
    }

    /** 平台唯一的 ES 连接 id（{@link #defaultEs()} 对应的那个）；没有返回 {@code null} */
    @JsonIgnore
    public String defaultEsConnectionId() {
        Connection kw = connection(platformConnectionId(KIND_KEYWORD));
        if (kw != null && kw.isElasticsearch()) {
            return kw.getId();
        }
        for (Connection c : connectionsOrEmpty().values()) {
            if (c != null && c.isElasticsearch()) {
                return c.getId();
            }
        }
        return null;
    }

    /** 平台上第一个 Qdrant 连接（向量体优先，回落任意 Qdrant） */
    @JsonIgnore
    public VectorStore defaultQdrant() {
        Connection vec = connection(platformConnectionId(KIND_VECTOR));
        if (vec != null && vec.isQdrant() && vec.getVector() != null) {
            return vec.getVector();
        }
        for (Connection c : connectionsOrEmpty().values()) {
            if (c != null && c.isQdrant() && c.getVector() != null) {
                return c.getVector();
            }
        }
        return new VectorStore();
    }

    @JsonIgnore
    public boolean isEsUsable() {
        return defaultEs().isUsable();
    }

    @JsonIgnore
    public boolean isRedisUsable() {
        return redis != null && redis.isUsable();
    }

    /** 向量用途是否可用（ES 或 Qdrant 都算） */
    @JsonIgnore
    public boolean isVectorKindUsable() {
        return isKindUsable(KIND_VECTOR);
    }

    @JsonIgnore
    public boolean isQdrantUsable() {
        return defaultQdrant().isUsable();
    }

    // ==================== 校验 ====================

    /**
     * 汇总形态问题；空列表表示"没发现问题"。
     *
     * <p>除了"填得像不像"，还查两件只有跨条才能看出来的事：知识体引用的连接存不存在、
     * 用途与连接类型配不配（关键字体绑一台 Qdrant，等于配了一条永远空结果的召回路径，
     * 这种错必须在保存时就点出来，而不是等检索时静默无结果）。</p>
     */
    @JsonIgnore
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (redis != null) {
            problems.addAll(redis.validate());
        }
        for (Connection c : connectionsOrEmpty().values()) {
            if (c == null) {
                continue;
            }
            problems.addAll(c.validate());
        }
        Map<String, String> slugOwners = new LinkedHashMap<>();
        for (KnowledgeBody b : bodiesOrEmpty().values()) {
            if (b == null) {
                continue;
            }
            checkBody(problems, b);
            if (hasText(b.getSlug())) {
                String owner = slugOwners.putIfAbsent(b.getSlug().trim(), b.getId());
                if (owner != null) {
                    problems.add("知识体唯一名「" + b.getSlug() + "」重复（已被 " + owner
                            + " 使用）：唯一名决定索引名，不能共用");
                }
            }
        }
        return problems;
    }

    /**
     * 知识体校验：id / 唯一名 / 首路 / 每一路的连接存不存在。
     *
     * <p>两路各自独立校验，不因为另一路配错就跳过：一个体里关键字配错、向量配对了，
     * 保存后就变成"悄悄只走向量"——用户看着两行都填了，实际少一路。</p>
     */
    private void checkBody(List<String> problems, KnowledgeBody body) {
        String who = "知识体 " + body.effectiveName();
        if (!hasText(body.getId())) {
            problems.add("有个知识体缺少 id（域绑定时要靠它引用）");
        }
        if (hasText(body.getSlug())) {
            String reason = com.zzkingcc.domainplane.api.support.KbIndexes.validateSlug(body.getSlug().trim());
            if (reason != null) {
                problems.add(who + " 的" + reason);
            }
        }
        if (hasText(body.getIndex())
                && !body.getIndex().trim().startsWith(
                        com.zzkingcc.domainplane.api.support.KbIndexes.PREFIX)) {
            problems.add(who + " 的索引名「" + body.getIndex() + "」不合规：必须以 "
                    + com.zzkingcc.domainplane.api.support.KbIndexes.PREFIX + " 开头");
        }
        if (!SUPPORTED_RAW_LOCATIONS.contains(body.rawLocation())) {
            problems.add(who + " 的原文落点「" + body.rawLocation() + "」不受支持，可选值："
                    + SUPPORTED_RAW_LOCATIONS + "（文件服务接入后才会出现新的落点）");
        }
        if (!body.configured()) {
            problems.add(who + " 一路都没配：至少选一个关键字连接（全文检索）或向量连接（语义检索）");
            return;
        }
        checkSlot(problems, who, KIND_KEYWORD, body.keywordId());
        checkSlot(problems, who, KIND_VECTOR, body.vectorId());
    }

    /**
     * 一路的校验：没填放行（可选），填了就必须指向一条<b>存在的</b>连接。
     *
     * <p><b>刻意不判"这种类型干不干得了这活"</b>：那是运行期的事。保存只负责把用户写的
     * 东西如实落盘——拦在这里会让"我想先配好再换机器"这类操作做不下去，而且配置对不对
     * 得连上那台后端才知道，不是读一遍枚举类型就能定论的。类型不匹配的后果是
     * {@code RetrievalPlan.backendFor} 在真正检索时跳过该路并留错误日志，不是保存失败。</p>
     */
    private void checkSlot(List<String> problems, String who, String kind, String connectionId) {
        if (connectionId == null) {
            return;
        }
        if (connection(connectionId) == null) {
            problems.add(who + " 的" + kindLabel(kind) + "连接「" + connectionId + "」不存在");
        }
    }

    /** 召回路的中文名（只用于拼文案） */
    public static String kindLabel(String kind) {
        if (KIND_KEYWORD.equals(kind)) {
            return "关键字";
        }
        if (KIND_VECTOR.equals(kind)) {
            return "向量";
        }
        return kind;
    }

    @JsonIgnore
    public Map<String, Connection> connectionsOrEmpty() {
        return connections == null ? Map.of() : connections;
    }

    @JsonIgnore
    public Map<String, KnowledgeBody> bodiesOrEmpty() {
        return bodies == null ? Map.of() : bodies;
    }

    // ==================== 复用的连接参数段 ====================

    /**
     * Elasticsearch 连接参数。
     *
     * 只留"连接身份"与超时；其余（索引名、分词、灌库开关）仍属平台行为配置，留在 yaml。
     */
    @Data
    public static class Elasticsearch {

        /** 主机地址（IP 或域名）。为空即视为"未配置" */
        private String host;

        /** HTTP 端口（留空用 9200） */
        private Integer port;

        /** 协议（http / https，留空用 http） */
        private String scheme;

        /** 用户名，未启用安全认证时可空 */
        private String username;

        /** 密码，未启用安全认证时可空 */
        private String password;

        /** 连接超时（毫秒，留空用 5000） */
        private Integer connectTimeout;

        /** 读写超时（毫秒，留空用 10000） */
        private Integer socketTimeout;

        /** 是否已配置（只判主机，端口/协议都有默认值） */
        @JsonIgnore
        public boolean isUsable() {
            return hasText(host);
        }

        @JsonIgnore
        public int effectivePort() {
            return port != null && port > 0 ? port : 9200;
        }

        @JsonIgnore
        public String effectiveScheme() {
            return hasText(scheme) ? scheme.trim() : "http";
        }

        @JsonIgnore
        public int effectiveConnectTimeout() {
            return connectTimeout != null && connectTimeout > 0 ? connectTimeout : 5000;
        }

        @JsonIgnore
        public int effectiveSocketTimeout() {
            return socketTimeout != null && socketTimeout > 0 ? socketTimeout : 10000;
        }

        /** 展示用掩码（不落盘、不出接口的明文形式） */
        @JsonIgnore
        public String getMaskedPassword() {
            return mask(password);
        }

        @JsonIgnore
        public String describe() {
            return isUsable() ? effectiveScheme() + "://" + host + ":" + effectivePort() : "未配置";
        }

        /**
         * 形态校验：只判断"填得像不像"，不测连通性
         */
        @JsonIgnore
        public List<String> validate() {
            List<String> problems = new ArrayList<>();
            if (!isUsable()) {
                return problems;
            }
            String scheme = effectiveScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                problems.add("协议只支持 http / https，当前为「" + scheme + "」");
            }
            if (port != null && (port < 1 || port > 65535)) {
                problems.add("端口需在 1~65535，当前为「" + port + "」");
            }
            if (looksLikeUrl(host)) {
                problems.add("主机地址只填主机名或 IP，不要带 http:// 前缀或路径");
            } else if (hasEmbeddedPort(host)) {
                problems.add("主机地址里不要带端口，端口请填在端口字段");
            }
            return problems;
        }
    }

    /**
     * Redis 连接参数。
     */
    @Data
    public static class Redis {

        /** 主机地址（IP 或域名）。为空即视为"未配置" */
        private String host;

        /** 端口（留空用 6379） */
        private Integer port;

        /** 密码，未设置则为空（无密码模式） */
        private String password;

        /**
         * 库号，留空用 0
         */
        private Integer database;

        @JsonIgnore
        public boolean isUsable() {
            return hasText(host);
        }

        @JsonIgnore
        public int effectivePort() {
            return port != null && port > 0 ? port : 6379;
        }

        @JsonIgnore
        public int effectiveDatabase() {
            return database != null && database >= 0 ? database : 0;
        }

        @JsonIgnore
        public String getMaskedPassword() {
            return mask(password);
        }

        @JsonIgnore
        public String describe() {
            return isUsable() ? host + ":" + effectivePort() + "/db" + effectiveDatabase() : "未配置";
        }

        /**
         * 形态校验：只判断"填得像不像"，不测连通性
         */
        @JsonIgnore
        public List<String> validate() {
            List<String> problems = new ArrayList<>();
            if (!isUsable()) {
                return problems;
            }
            if (port != null && (port < 1 || port > 65535)) {
                problems.add("端口需在 1~65535，当前为「" + port + "」");
            }
            if (database != null && database < 0) {
                problems.add("库号不能为负数，当前为「" + database + "」");
            }
            if (looksLikeUrl(host)) {
                problems.add("主机地址只填主机名或 IP，不要带 redis:// 前缀或路径");
            } else if (hasEmbeddedPort(host)) {
                problems.add("主机地址里不要带端口，端口请填在端口字段");
            }
            return problems;
        }
    }

    /**
     * 向量库连接参数（Qdrant）。
     *
     * <p>与 ES 段的差别：<b>uri 是完整地址</b>（含协议与端口），
     * 不是"主机 + 端口"两段——向量库各家（Qdrant / Milvus / pgvector）
     * 的地址形态差异太大，拆成两段反而处处要补协议。校验只查"像不像地址"，
     * 不查能不能连上（连通性走「测试连接」）。</p>
     *
     * <p>{@code collectionPrefix} 是集合名前缀：向量库里集合名与 ES 索引名
     * 可能撞车（同名集合在两套系统里各有一份），加前缀隔开。留空则直接用
     * {@code KbIndexes.nameOf(domain)} 的原名——与 ES 侧索引名一字不差，
     * 存量数据零迁移。</p>
     */
    @Data
    public static class VectorStore {

        /** 完整服务地址，如 {@code http://qdrant.internal:6333}。为空即视为"未配置" */
        private String uri;

        /** API Key / Token，未启用鉴权时可空 */
        private String token;

        /** 集合名前缀，留空表示直接用原名 */
        private String collectionPrefix;

        /** 距离度量：cosine / dot / euclid（留空用 cosine） */
        private String metric;

        @JsonIgnore
        public boolean isUsable() {
            return hasText(uri);
        }

        @JsonIgnore
        public String effectiveMetric() {
            return hasText(metric) ? metric.trim().toLowerCase() : "cosine";
        }

        @JsonIgnore
        public String effectiveCollectionPrefix() {
            return collectionPrefix == null ? "" : collectionPrefix.trim();
        }

        @JsonIgnore
        public String getMaskedToken() {
            return mask(token);
        }

        @JsonIgnore
        public String describe() {
            return isUsable() ? uri : "未配置";
        }

        /**
         * 形态校验：只判断"填得像不像"，不测连通性。
         */
        @JsonIgnore
        public List<String> validate() {
            List<String> problems = new ArrayList<>();
            if (!isUsable()) {
                return problems;
            }
            String u = uri.trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                problems.add("地址需带协议前缀（http:// 或 https://），当前为「" + uri + "」");
            }
            String metric = effectiveMetric();
            if (!"cosine".equals(metric) && !"dot".equals(metric) && !"euclid".equals(metric)) {
                problems.add("距离度量只支持 cosine / dot / euclid，当前为「" + metric + "」");
            }
            return problems;
        }
    }

    // ==================== 工具 ====================

    /** 主机字段里混进了完整 URL（带协议头或路径） */
    private static boolean looksLikeUrl(String host) {
        return host != null && (host.contains("://") || host.contains("/"));
    }

    /** 主机字段里混进了端口：含单个冒号，且不是 IPv6 字面量（{@code [::1]}） */
    private static boolean hasEmbeddedPort(String host) {
        return host != null
                && host.startsWith("[") == false
                && host.indexOf(':') > 0
                && host.indexOf(':') == host.lastIndexOf(':');
    }

    private static String trimEndSlash(String uri) {
        String u = uri == null ? "" : uri.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u.toLowerCase();
    }

    private static String mask(String secret) {
        if (!hasText(secret)) {
            return null;
        }
        String s = secret.trim();
        if (s.length() <= 4) {
            return "******";
        }
        return s.substring(0, 2) + "******" + s.substring(s.length() - 2);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
