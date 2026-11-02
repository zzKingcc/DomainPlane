package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeStore;
import com.zzkingcc.domainplane.domain.rag.store.KeywordSearcher;
import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
import com.zzkingcc.domainplane.domain.rag.store.VectorSearcher;

import java.util.Optional;

/**
 * Qdrant 后端注册单元。
 *
 * <p>{@code capabilities = (keyword=true, vector=true, wildcard=false,
 * storesText=true, reindexInPlace=true)}：</p>
 *
 * <p><b>纯 keyword 变体</b>（P2：{@code vectorEnabled=false}，配置
 * {@code domainplane.knowledge.backends.<id>.vector: false}）：
 * {@code capabilities = (keyword=true, vector=false, wildcard=false, storesText=true,
 * reindexInPlace=false)}——不接向量检索器、写入只落 payload、不能作为重灌目标。
 * 这才是 SPI 注释里说的"keyword-only 是合法后端"：与 vector 后端拼成 hybrid
 * （关键词走它、向量走另一家），或 {@code strategy: keyword} 下独立承担全部召回。</p>
 *
 * <ul>
 *   <li>keyword —— payload 全文索引（Qdrant 的 match 查询）；</li>
 *   <li>vector —— 距离度量由集合创建时定（cosine / dot）；</li>
 *   <li><b>wildcard=false</b> —— Qdrant 没有索引模式，{@code domainplane_kb_*}
 *       这种通配查不了。管控台的"未绑域全库预览"因此会降级成
 *       "枚举集合逐个检索关键词路 + 向量路跳过并告警"（由 RetrievalPlan 统一处理）；
 *   </li>
 *   <li>storesText —— payload 里存着切片正文；</li>
 *   <li>reindexInPlace —— delete + ensure 同一后端完成，重灌第二阶段的前提。</li>
 * </ul>
 *
 * <p>ES 适配器是本项目 P0 唯一实现；这个类是 P1 的第二个实现，
 * 证明"换后端"只需要新增一个适配器 + 一行 {@code type: qdrant} 配置。</p>
 *
 * @author zzkingcc
 */
public class QdrantKnowledgeBackend implements KnowledgeBackend {

    private final String id;
    private final String location;
    private final QdrantKnowledgeStore store;
    private final QdrantKeywordSearcher keyword;
    private final QdrantVectorSearcher vector;

    public QdrantKnowledgeBackend(String id, QdrantHttpClient client, double vectorMinScore) {
        this(id, client, vectorMinScore, true);
    }

    /**
     * @param vectorEnabled {@code false} = 纯 keyword 后端：不接向量检索器
     *                      （{@link #vector()} 为空），store 只写 payload
     */
    public QdrantKnowledgeBackend(String id, QdrantHttpClient client, double vectorMinScore,
                                  boolean vectorEnabled) {
        this(id, id, client, vectorMinScore, vectorEnabled);
    }

    /**
     * @param location 物理实例标识（规范化后的服务地址）。两个 Qdrant 连接指向同一个实例时
     *                 location 相同，写入扇出据此收敛成一路
     */
    public QdrantKnowledgeBackend(String id, String location, QdrantHttpClient client,
                                  double vectorMinScore, boolean vectorEnabled) {
        this.id = id;
        this.location = location == null || location.isBlank() ? id : location;
        this.store = new QdrantKnowledgeStore(client, vectorEnabled);
        this.keyword = new QdrantKeywordSearcher(client);
        this.vector = vectorEnabled ? new QdrantVectorSearcher(client, vectorMinScore) : null;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String location() {
        return location;
    }

    @Override
    public StoreCapabilities capabilities() {
        return store.capabilities();
    }

    @Override
    public Optional<KnowledgeStore> store() {
        return Optional.of(store);
    }

    @Override
    public Optional<KeywordSearcher> keyword() {
        return Optional.of(keyword);
    }

    @Override
    public Optional<VectorSearcher> vector() {
        // 纯 keyword 后端没有向量检索器：返回空而不是抛——SPI 用 Optional 表达
        // "这个后端不提供这条路"，调用方（RetrievalPlan）据此降级该路
        return Optional.ofNullable(vector);
    }
}
