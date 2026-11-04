package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.domain.rag.model.Modality;
import com.zzkingcc.domainplane.domain.rag.store.FanOutStore;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackendRegistry;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.domain.rag.store.KeywordSearcher;
import com.zzkingcc.domainplane.domain.rag.store.VectorSearcher;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties.Strategy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索计划：召回策略 + <b>按域解析的知识体</b> → "这一轮该查哪些 collection、每个 collection 走哪几路"。
 *
 * <p>计划本身不碰 Spring、不建检索器实例——它只回答"查什么"，检索器的构造与缓存在通道层。
 * 域绑了哪个知识体、那个体连的是哪台机器，都由 {@link BodyLookup} 现取：管控台改完
 * 域绑定或知识体，下一次检索就按新配置走，不需要重启。</p>
 *
 * <p><b>为什么每个域名要单独解析，而不是全局面量</b>：知识体是"一种用途 + 一台连接"，
 * 一个域只挂一个。于是同一棵域树上，父域可能是关键字体、子域是向量体——查子域时
 * 要按父域自己的体去查父域的索引，而不是拿子域的体去套父域的索引名（那样查的
 * 那台机器上根本没有那份数据，结果为空且不报错）。</p>
 *
 * <p>域链 = {@code Domains.chainOf(当前域)}，即自身 + 全部祖先（含根域）：挂在父域的
 * 内容子域天然可用，这条语义不变。每条通道带 depth（距查询域的距离，0 = 自身），
 * 供融合阶段做层级衰减。<b>域链与"绑定继承"是两件事</b>：链让子域<b>查得到</b>父域的数据，
 * 但不会让子域<b>借用</b>父域的知识体——没绑知识体的域，它自己那份索引就是空的。</p>
 *
 * <p>{@code sourceId} 是<b>知识来源（域）</b>，不是索引名：融合层按它统计"有命中的来源数"，
 * 同一域两路必须仍算 1 个来源，否则分数制融合会静默退化成 RRF。物理位置走
 * {@code locationId}（{@code backendId|collection|modality}），只用于日志、缓存与排障。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class RetrievalPlan {

    /**
     * 一条待开出的召回通道（尚未包成检索器）。
     *
     * @param vectorSearcher  向量路的后端检索实现；关键词路为 {@code null}
     * @param keywordSearcher 关键词路的后端检索实现；向量路为 {@code null}
     * @param topK            该路的每索引召回条数（来自对应后端配置，通用参数）
     */
    public record ChannelSpec(String sourceId, String locationId, Modality modality,
                              String backendId, String collection, int depth, int topK,
                              VectorSearcher vectorSearcher, KeywordSearcher keywordSearcher) {
    }

    /**
     * 一个域当前生效的存储绑定：用途 + 连接 + 该路的召回条数。
     *
     * @param bodyId       知识体 id（日志与排障用）
     * @param kind         {@code keyword} / {@code vector} / {@code text}
     * @param connectionId 连接 id，同时也是后端 id（注册表按它取后端）
     * @param topK         该路的每索引召回条数
     * @param index        该知识体冻结的索引名（<b>不再由域派生</b>：多域共用一个体就共用一个索引）
     */
    public record BodyBinding(String bodyId, String kind, String connectionId, int topK, String index) {
    }

    /**
     * 域 → 知识体的解析入口。
     *
     * <p>返回列表而不是单个：一个体里两路各自可选，所以一个域可能开出两路；体本身是唯一的
     * （v1 域只绑一个体）。{@link #bodyIdOf} 与 {@link #bodiesOfBody} 是给"以知识体为中心"的
     * 管理链路用的——重建 / 上传 / 预览都不再问域，只问体。</p>
     */
    public interface BodyLookup {

        /** 该域绑定的知识体；没绑返回空列表（<b>不继承父域</b>） */
        List<BodyBinding> bodiesOf(String domain);

        /** 没有域上下文时的平台默认（未绑域的通配检索 / 诊断）；该用途没配返回 {@code null} */
        BodyBinding platformBody(String kind);

        /** 该域绑定的知识体 id；没绑返回 {@code null} */
        default String bodyIdOf(String domain) {
            return null;
        }

        /** 某个知识体开出的全部绑定（两路各自可选）；体不存在返回空列表 */
        default List<BodyBinding> bodiesOfBody(String bodyId) {
            return List.of();
        }
    }

    /**
     * 固定绑定版（测试与"配置不可变"的场景）：所有域都拿到同一对角色。
     *
     * <p>顺序刻意是<b>向量在前、关键字在后</b>：与改造前 {@code channels} 的开通道顺序
     * 逐字一致（向量路先开）。顺序本身不影响召回结果，但它是可观测行为——通道列表是
     * 排障时第一眼看的东西，没必要因为一次重构让它换个样子。</p>
     *
     * <p><b>两个角色共用同一个合成体 id</b>{@value #FIXED_BODY_ID}：它们是"同一个体的两路"，
     * 不是两个体。若各自拿连接 id 当体 id，{@code bodiesOfBody} 只会返回一路——读侧于是
     * 静默漏掉另一台机器上的数据（这条曾把"两台 ES 要合并读"的用例打红过）。</p>
     */
    private static BodyLookup fixed(BodyBinding keyword, BodyBinding vector) {
        List<BodyBinding> both = new ArrayList<>(2);
        if (vector != null) {
            both.add(new BodyBinding(FIXED_BODY_ID, vector.kind(), vector.connectionId(),
                    vector.topK(), vector.index()));
        }
        if (keyword != null) {
            both.add(new BodyBinding(FIXED_BODY_ID, keyword.kind(), keyword.connectionId(),
                    keyword.topK(), keyword.index()));
        }
        List<BodyBinding> fixedList = List.copyOf(both);
        return new BodyLookup() {
            @Override
            public List<BodyBinding> bodiesOf(String domain) {
                return fixedList;
            }

            @Override
            public BodyBinding platformBody(String kind) {
                if ("keyword".equals(kind)) {
                    return keyword;
                }
                return "vector".equals(kind) ? vector : null;
            }

            @Override
            public List<BodyBinding> bodiesOfBody(String bodyId) {
                return fixedList.stream().filter(b -> b.bodyId().equals(bodyId)).toList();
            }

            @Override
            public String bodyIdOf(String domain) {
                return fixedList.isEmpty() ? null : fixedList.get(0).bodyId();
            }
        };
    }

    private final Strategy strategy;
    private final KnowledgeBackendRegistry registry;
    private final BodyLookup lookup;

    /** 固定绑定版使用的合成体 id：两路是同一个体，读侧必须合起来看 */
    public static final String FIXED_BODY_ID = "platform";

    /** 固定绑定版：两个角色 id 写死，所有域共用（与改造前的行为逐字一致） */
    public RetrievalPlan(Strategy strategy,
                         KnowledgeBackendRegistry registry,
                         String keywordBackendId, int keywordTopK,
                         String vectorBackendId, int vectorTopK) {
        this(strategy, registry, fixed(
                keywordBackendId == null ? null
                        : new BodyBinding(keywordBackendId, "keyword", keywordBackendId, keywordTopK,
                                KbIndexes.WILDCARD),
                vectorBackendId == null ? null
                        : new BodyBinding(vectorBackendId, "vector", vectorBackendId, vectorTopK,
                                KbIndexes.WILDCARD)));
    }

    /**
     * 按域解析版：每个域用它自己绑的知识体。
     *
     * <p>为什么不让装配期把连接定死——知识体与域绑定都是管控台可改的，而计划是单例。
     * 定死意味着"改完要重启"，改成现取之后保存即生效。</p>
     */
    public RetrievalPlan(Strategy strategy, KnowledgeBackendRegistry registry, BodyLookup lookup) {
        this.strategy = strategy;
        this.registry = registry;
        this.lookup = lookup == null ? fixed(null, null) : lookup;
    }

    public Strategy strategy() {
        return strategy;
    }

    /** 该知识体开出的全部绑定（两路各自可选）；不存在返回空列表 */
    public List<BodyBinding> bodiesOfBody(String bodyId) {
        if (bodyId == null || bodyId.isBlank()) {
            return List.of();
        }
        List<BodyBinding> bodies = lookup.bodiesOfBody(bodyId.trim());
        return bodies == null ? List.of() : bodies;
    }

    /** 该域绑定的知识体 id；没绑返回 {@code null}（页面展示与排障用） */
    public String bodyIdOf(String domain) {
        return lookup.bodyIdOf(Domains.normalize(domain));
    }

    /** 关键词路的平台默认后端 id（校验与排障用；不等于"所有域都用它"） */
    public String keywordBackendId() {
        BodyBinding b = lookup.platformBody("keyword");
        return b == null ? null : b.connectionId();
    }

    /** 向量路的平台默认后端 id（同上） */
    public String vectorBackendId() {
        BodyBinding b = lookup.platformBody("vector");
        return b == null ? null : b.connectionId();
    }

    /**
     * 关键词路的平台默认后端实体。
     *
     * @throws IllegalArgumentException 未注册（信息里列出全部可用 id）——由校验器包成
     *                                  60007，调用方不应直接吞
     */
    public KnowledgeBackend keywordBackend() {
        return registry.require(keywordBackendId());
    }

    /** 向量路的平台默认后端实体（异常语义同 {@link #keywordBackend()}） */
    public KnowledgeBackend vectorBackend() {
        return registry.require(vectorBackendId());
    }

    /** 是否一个后端都没配（{@code backends} 为空）：校验第一条就查它 */
    public boolean backendsEmpty() {
        return registry.isEmpty();
    }

    /**
     * 开出这一轮的召回通道。
     *
     * @param domain 当前检索域（完整路径）；{@code null} = 未绑域（管控台预览 / 诊断），走通配
     */
    public List<ChannelSpec> channels(String domain) {
        if (domain == null) {
            return wildcardChannels();
        }
        // chainOf 是「由根向下」的顺序：[root, ..., 查询域自身]。
        // depth 是"距查询域的距离"，0 = 自身，所以要反向算：末位是 0，越靠前越大。
        List<String> chain = Domains.chainOf(Domains.normalize(domain));
        int last = chain.size() - 1;
        List<ChannelSpec> specs = new ArrayList<>(chain.size() * 2);
        for (int i = 0; i < chain.size(); i++) {
            String sourceId = chain.get(i);
            int depth = last - i;
            // 这个域自己绑的体：没绑就什么都不开（不拿父域的体去查它的索引）
            for (BodyBinding binding : bodiesOf(sourceId)) {
                String collection = binding.index();
                if (collection == null || collection.isBlank()) {
                    continue;
                }
                addChannel(specs, binding, sourceId, collection, depth);
            }
        }
        return dedupeByLocation(specs);
    }

    /**
     * 同一条物理通道只留一条：<b>多域绑同一个体就查同一个索引</b>，不去重会把它查两遍。
     *
     * <p>保留 distance 最近的那条（depth 小者优先，相同时先到先得），这样融合层的
     * depth 衰减算的是"离查询域最近的那次引用"，与共用前逐域查询的观感一致。</p>
     */
    private static List<ChannelSpec> dedupeByLocation(List<ChannelSpec> specs) {
        java.util.LinkedHashMap<String, ChannelSpec> byLocation = new java.util.LinkedHashMap<>();
        for (ChannelSpec spec : specs) {
            ChannelSpec existing = byLocation.get(spec.locationId());
            if (existing == null || spec.depth() < existing.depth()) {
                byLocation.put(spec.locationId(), spec);
            }
        }
        return List.copyOf(byLocation.values());
    }

    /**
     * 未绑域的通配检索（管控台预览 / 诊断）。
     *
     * sourceId 用 {@link KbIndexes#WILDCARD}：与旧实现一致，跨库通配算一个来源。
     */
    private List<ChannelSpec> wildcardChannels() {
        List<ChannelSpec> specs = new ArrayList<>(2);
        BodyBinding keyword = lookup.platformBody("keyword");
        if (keyword != null) {
            addChannel(specs, keyword, KbIndexes.WILDCARD, KbIndexes.WILDCARD, 0);
        }
        BodyBinding vector = lookup.platformBody("vector");
        if (vector != null) {
            addChannel(specs, vector, KbIndexes.WILDCARD, KbIndexes.WILDCARD, 0);
        }
        return specs;
    }

    private List<BodyBinding> bodiesOf(String domain) {
        List<BodyBinding> bodies = lookup.bodiesOf(domain);
        return bodies == null ? List.of() : bodies;
    }

    /** 按绑定开一路通道：用途决定开哪路，原文体不开（它不参与召回） */
    private void addChannel(List<ChannelSpec> specs, BodyBinding binding,
                            String sourceId, String collection, int depth) {
        if (binding == null || binding.connectionId() == null || binding.connectionId().isBlank()) {
            return;
        }
        if ("keyword".equals(binding.kind())) {
            addKeywordChannel(specs, binding, sourceId, collection, depth);
        } else if ("vector".equals(binding.kind())) {
            addVectorChannel(specs, binding, sourceId, collection, depth);
        }
        // text：原文体只登记与回溯，没有召回能力，这里安静跳过
    }

    private void addVectorChannel(List<ChannelSpec> specs, BodyBinding binding,
                                  String sourceId, String collection, int depth) {
        if (strategy == Strategy.KEYWORD) {
            return; // 策略只要关键词路：向量通道根本不开（不是开了不用）
        }
        KnowledgeBackend backend = backendFor(binding.connectionId(), "vector");
        if (backend == null) {
            return;
        }
        if (KbIndexes.WILDCARD.equals(collection) && !backend.capabilities().wildcard()) {
            // 设计第 12 节：向量后端不支持通配时该路跳过（不枚举 collection 逐路查向量，
            // 那是把"全库检索"摊成 N 次检索，延迟与 ES 通配不在一个量级）。
            log.warn("[检索计划] 后端 {} 不支持通配检索，未绑域的向量召回本次跳过", backend.id());
            return;
        }
        backend.vector().ifPresent(searcher -> specs.add(new ChannelSpec(
                sourceId, locationOf(backend.id(), collection, Modality.VECTOR), Modality.VECTOR,
                backend.id(), collection, depth, binding.topK(), searcher, null)));
    }

    private void addKeywordChannel(List<ChannelSpec> specs, BodyBinding binding,
                                   String sourceId, String collection, int depth) {
        if (strategy == Strategy.VECTOR) {
            return; // 策略只要向量路：关键词通道根本不开
        }
        KnowledgeBackend backend = backendFor(binding.connectionId(), "keyword");
        if (backend == null) {
            return;
        }
        if (KbIndexes.WILDCARD.equals(collection) && !backend.capabilities().wildcard()) {
            // 通配降级（设计第 12 节）：关键词路枚举 collection 逐路查，行为与"全库检索"等价，
            // 只是从一次通配查询摊成 N 次单 collection 查询。
            backend.store().ifPresent(store -> {
                List<String> collections = store.listCollections();
                if (collections.isEmpty()) {
                    return;
                }
                log.warn("[检索计划] 后端 {} 不支持通配检索，未绑域关键词路降级为枚举 {} 个 collection",
                        backend.id(), collections.size());
                for (String c : collections) {
                    backend.keyword().ifPresent(searcher -> specs.add(new ChannelSpec(
                            sourceId, locationOf(backend.id(), c, Modality.KEYWORD), Modality.KEYWORD,
                            backend.id(), c, depth, binding.topK(), null, searcher)));
                }
            });
            return;
        }
        backend.keyword().ifPresent(searcher -> specs.add(new ChannelSpec(
                sourceId, locationOf(backend.id(), collection, Modality.KEYWORD), Modality.KEYWORD,
                backend.id(), collection, depth, binding.topK(), null, searcher)));
    }

    private KnowledgeBackend backendFor(String backendId, String capability) {
        try {
            KnowledgeBackend backend = registry.require(backendId);
            boolean has = "vector".equals(capability)
                    ? backend.capabilities().vector()
                    : backend.capabilities().keyword();
            if (!has) {
                // 能力缺失本该在保存存储配置时被 KnowledgeBackendValidator 拦下（那里只给
                // warning、不拒保存），所以运行期仍可能走到这里：降级该路并留痕，不抛——
                // 抛会把"配错了"演变成"整轮检索失败"，与 A2 的降级口径相反。
                log.error("[检索计划] 后端 {} 缺 {} 能力（保存存储配置时应已给过警告），该路本次跳过",
                        backendId, capability);
                return null;
            }
            return backend;
        } catch (IllegalArgumentException e) {
            log.error("[检索计划] 后端 {} 未注册（连接可能刚被删或尚未装配完），该路本次跳过: {}",
                    backendId, e.getMessage());
            return null;
        }
    }

    private static String locationOf(String backendId, String collection, Modality modality) {
        return backendId + "|" + collection + "|" + modality;
    }

    // ==================== 写入侧取值（B4：多后端下的取值源纪律） ====================

    /**
     * 关键词路后端：写入侧的行为权威（ping / 诊断 / 建集合时的维度校验看它）。
     *
     * @throws KnowledgeBaseException 后端未注册或不提供存储能力（60007）
     */
    public KnowledgeStore keywordStore() {
        return requireStore(keywordBackendId());
    }

    /**
     * 写入用的 store：<b>写当前域自己绑的知识体</b>。
     *
     * <p>新模型下一个域只有一个体，所以这里通常是"取那个体连的 store"就完事。
     * 扇出分支覆盖的正是"关键字槽与向量槽绑了不同实例"：可能是两台 ES，也可能是
     * ES + 向量库。那种情况下同一份切片必须两边都写，否则另一条路静默召回不到
     * （不报错，只是没结果）。</p>
     *
     * <p><b>判"是不是同一份"看物理地址，不看对象引用</b>：关键字体与向量体可以指向
     * 同一个地址（一台 ES 干两件事），那两个后端是不同对象、却是同一台机器上的同一个索引。
     * 按引用比会扇出成两路，同一批切片被写两遍，计数翻倍、去重键撞自己。
     * 所以比 {@link KnowledgeBackend#location()}。</p>
     *
     * @param domain 要写入的域；该域没绑知识体时抛错（不回落平台默认——
     *               那会把 A 域的知识写进 B 域的库里）
     */
    public KnowledgeStore writeStore(String domain) {
        String bodyId = lookup.bodyIdOf(Domains.normalize(domain));
        if (bodyId == null || bodyId.isBlank()) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                    "域 " + domain + " 未绑定知识体：请先在管控台「知识库」页选一个知识体，"
                            + "再到「域空间」把它绑到这个域");
        }
        return writeStoreOfBody(bodyId);
    }

    /**
     * 写入用的 store：<b>写这个知识体</b>（不经过域——管理链路只认体）。
     *
     * <p>扇出分支覆盖"关键字槽与向量槽绑了不同实例"：同一份切片必须两边都写，
     * 否则另一条路静默召回不到（不报错，只是没结果）。判同机看
     * {@link KnowledgeBackend#location()}，不看对象引用。</p>
     *
     * @param bodyId 知识体 id；体不存在 / 两路都没配时抛错
     */
    public KnowledgeStore writeStoreOfBody(String bodyId) {
        List<BodyBinding> bodies = bodiesOfBody(bodyId);
        BodyBinding keywordBinding = null;
        BodyBinding vectorBinding = null;
        for (BodyBinding binding : bodies) {
            if ("keyword".equals(binding.kind()) && keywordBinding == null) {
                keywordBinding = binding;
            } else if ("vector".equals(binding.kind()) && vectorBinding == null) {
                vectorBinding = binding;
            }
        }
        boolean hasKeyword = keywordBinding != null;
        boolean hasVector = vectorBinding != null;
        if (!hasKeyword && !hasVector) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                    "知识体 " + bodyId + " 没有可用的一路：它未配置，或配置的连接都不可用");
        }
        if (!hasKeyword) {
            return storeOfBinding(vectorBinding);
        }
        KnowledgeStore keyword = storeOfBinding(keywordBinding);
        if (!hasVector) {
            return keyword;
        }
        KnowledgeStore vector = storeOfBinding(vectorBinding);
        if (samePhysicalStore(keywordBinding, vectorBinding, keyword == vector)) {
            return keyword;
        }
        return new FanOutStore(keyword, List.of(keyword, vector));
    }

    /**
     * 读该域自己那份存储（列表 / 计数 / 写入后回读）。
     *
     * <p><b>两路在时返回扇出 store，不是只看关键字那台</b>：关键字体与向量体可以分别绑在
     * 两台 ES、或 ES + 向量库上，同一份切片各写一份。只读其中一台的前提是"那台一定全有"，
     * 而这个前提在扇出写入 + 域换过知识体之后不成立——另一台可能持有它没有的文档，
     * 页面于是显示"没有这份"（看着空、其实有）。{@link FanOutStore} 的读路径按后端
     * 合并：文档按 docId 并集、集合名取并集、计数取最大值（不求和，避免双写翻倍）。</p>
     *
     * <p>同实例（{@link #samePhysicalStore}）时仍直接给那一个 store：本来就是同一份数据，
     * 包一层扇出只是多绕一次。</p>
     *
     * <p>域没绑知识体时回落平台默认：读是幂等操作，读错一台最多是"看到空列表"；
     * 写入侧不允许这样回落（见 {@link #writeStore(String)}）。</p>
     */
    public KnowledgeStore readStore(String domain) {
        String bodyId = lookup.bodyIdOf(Domains.normalize(domain));
        if (bodyId == null || bodyId.isBlank()) {
            return writeStore();
        }
        List<BodyBinding> bodies = bodiesOfBody(bodyId);
        if (bodies.isEmpty()) {
            return writeStore();
        }
        return readStoreOfBody(bodyId);
    }

    /**
     * 读某个知识体自己那份存储（列表 / 计数 / 写入后回读）。
     *
     * <p><b>两路在时返回扇出 store，不是只看关键字那台</b>：两路可以分别绑在两台 ES、
     * 或 ES + 向量库上，同一份切片各写一份。只读其中一台的前提是"那台一定全有"，
     * 而这个前提在扇出写入 + 换过落点之后不成立——另一台可能持有它没有的文档，
     * 页面于是显示"没有这份"（看着空、其实有）。{@link FanOutStore} 的读路径按后端
     * 合并：文档按 docId 并集、集合名取并集、计数取最大值（不求和，避免双写翻倍）。</p>
     *
     * <p>同实例（{@link #samePhysicalStore}）时仍直接给那一个 store：本来就是同一份数据。</p>
     */
    public KnowledgeStore readStoreOfBody(String bodyId) {
        BodyBinding keyword = null;
        BodyBinding vector = null;
        for (BodyBinding binding : bodiesOfBody(bodyId)) {
            if ("keyword".equals(binding.kind()) && keyword == null) {
                keyword = binding;
            } else if ("vector".equals(binding.kind()) && vector == null) {
                vector = binding;
            }
        }
        if (keyword == null) {
            return vector == null ? writeStoreOfBody(bodyId) : storeOfBinding(vector);
        }
        KnowledgeStore keywordStore = storeOfBinding(keyword);
        if (vector == null) {
            return keywordStore;
        }
        KnowledgeStore vectorStore = storeOfBinding(vector);
        if (samePhysicalStore(keyword, vector, keywordStore == vectorStore)) {
            return keywordStore;
        }
        return new FanOutStore(keywordStore, List.of(keywordStore, vectorStore));
    }

    /**
     * 平台默认写入 store（没有域上下文的场景：对账、诊断、内部维护）。
     *
     * <p>业务写入一律走 {@link #writeStore(String)}：拿平台默认去写某个域，等于把
     * 那个域的知识塞进"第一个知识体那台机器"，看起来成功、实际查不到。</p>
     */
    public KnowledgeStore writeStore() {
        BodyBinding keyword = lookup.platformBody("keyword");
        BodyBinding vector = lookup.platformBody("vector");
        if (keyword == null && vector == null) {
            throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                    "还没有任何可用的知识体：请先在管控台「存储配置」建一个");
        }
        if (keyword == null) {
            return storeOfBinding(vector);
        }
        KnowledgeStore keywordStore = storeOfBinding(keyword);
        if (vector == null) {
            return keywordStore;
        }
        // 两路都在：同一台机器只写一次，不同机器必须两边都写（判据与 writeStore(domain) 同一套）
        KnowledgeStore vectorStore = storeOfBinding(vector);
        if (samePhysicalStore(keyword, vector, keywordStore == vectorStore)) {
            return keywordStore;
        }
        return new FanOutStore(keywordStore, List.of(keywordStore, vectorStore));
    }

    /**
     * 两个绑定是不是同一份物理数据。
     *
     * <p>比的是后端 {@link KnowledgeBackend#location()}（协议+主机+端口），不是 store 对象：
     * 一台 ES 既当关键字体又当向量体时，两个绑定的连接不同、后端对象也不同，
     * 但落在同一个索引上。任一侧取不到 location 时按"不同"处理——真去扇出也只会在
     * 写入时失败并报得明明白白，比静默合并且只写一半好。</p>
     */
    private boolean samePhysicalStore(BodyBinding keyword, BodyBinding vector, boolean sameStoreObject) {
        if (sameStoreObject) {
            return true;
        }
        String left = locationOf(keyword.connectionId());
        String right = locationOf(vector.connectionId());
        return left != null && left.equals(right);
    }

    private String locationOf(String connectionId) {
        try {
            return registry.require(connectionId).location();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 向量路后端：向量维度的唯一读数来源（keyword-only 后端没有维度概念，
     * 遍历全部后端会把第一个有值的当全局值——一审修订 B4）。
     */
    public KnowledgeStore vectorStore() {
        return requireStore(vectorBackendId());
    }

    /**
     * 按 id 取<b>任意</b>已注册后端的 store（跨后端重灌用：目标后端不一定是
     * 当前生效的 keyword / vector 那两个）。
     *
     * @throws KnowledgeBaseException 后端未注册或不提供存储能力（60007）
     */
    public KnowledgeStore storeOf(String backendId) {
        return requireStore(backendId);
    }

    /**
     * 全部已注册后端（含能力声明）：管控台展示与端点参数校验用。
     *
     * <p>刻意返回后端实体而不是 id 列表：光有 id 的话，调用方无从判断"这个后端
     * 能不能作为重灌目标"——能不能写、能不能存向量都在 capabilities 里，
     * 而这些正是选目标时要看的。</p>
     */
    public List<KnowledgeBackend> backends() {
        return registry.all();
    }

    private KnowledgeStore storeOfBinding(BodyBinding binding) {
        return requireStore(binding.connectionId());
    }

    private KnowledgeStore requireStore(String backendId) {
        try {
            return registry.require(backendId).store()
                    .orElseThrow(() -> new KnowledgeBaseException(
                            ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                            "知识库后端 " + backendId + " 未提供存储能力（应被启动期校验拦截）"));
        } catch (IllegalArgumentException e) {
            throw new KnowledgeBaseException(
                    ErrorCode.KNOWLEDGE_BACKEND_MISCONFIGURED,
                    "知识库后端 " + backendId + " 未注册：" + e.getMessage(), e);
        }
    }
}
