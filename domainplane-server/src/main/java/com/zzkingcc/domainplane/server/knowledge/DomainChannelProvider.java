package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.support.RetrievalScope;
import com.zzkingcc.domainplane.domain.rag.retriever.CompositeRetriever;
import com.zzkingcc.domainplane.domain.rag.retriever.CompositeRetriever.Channel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 域通道提供者：把 {@link RetrievalPlan} 开出的通道规格包成编排层认识的检索器。
 *
 * <p>本类只做两件与"查什么"无关的事：</p>
 * <ul>
 *   <li><b>取当前检索域</b>（{@link RetrievalScope}，对话上下文里的域；未绑域传 null 走通配），
 *       交给计划解析；</li>
 *   <li><b>检索器缓存</b>：规格 → 检索器实例按 {@code locationId} 缓存复用（检索器无状态），
 *       避免每轮重建。</li>
 * </ul>
 *
 * <p>通道解析（域链 → collection × 模态、通配降级、策略门控）全在 {@link RetrievalPlan}，
 * 那里不依赖 Spring、可单测；本类是它在编排层的适配壳。</p>
 *
 * <p>提成独立 bean 而不是内联 new 进 {@link CompositeRetriever}：删域流程要用它失效
 * 检索器缓存（索引删了但还在缓存里会被旧检索器继续命中），那是与"查询"无关的另一条
 * 使用路径。内联构造时它拿不到 bean 引用，{@code AdminDomainController} 就只能声明一个
 * 根本不存在类型的依赖：于是 ES 未配置、检索器未装配的场景下整个应用起不来。</p>
 *
 * @author zzkingcc
 */
public class DomainChannelProvider implements CompositeRetriever.ChannelProvider {

    private final RetrievalPlan plan;

    /** locationId → 检索器（检索器无状态，可安全复用；避免每轮重建） */
    private final Map<String, ContentRetriever> retrieverCache = new ConcurrentHashMap<>();

    public DomainChannelProvider(RetrievalPlan plan) {
        this.plan = plan;
    }

    @Override
    public List<Channel> channels() {
        List<RetrievalPlan.ChannelSpec> specs = plan.channels(RetrievalScope.current());
        List<Channel> channels = new ArrayList<>(specs.size());
        for (RetrievalPlan.ChannelSpec spec : specs) {
            channels.add(new Channel(
                    spec.sourceId(), spec.locationId(), spec.modality(),
                    retrieverOf(spec), spec.depth()));
        }
        return channels;
    }

    private ContentRetriever retrieverOf(RetrievalPlan.ChannelSpec spec) {
        // 缓存 key = locationId（backendId|collection|modality）：同一物理位置只有
        // 一个检索器实例；规格里的 searcher 引用不变（后端实例是单例），可安全复用
        return retrieverCache.computeIfAbsent(spec.locationId(), key ->
                spec.vectorSearcher() != null
                        ? new VectorChannelRetriever(spec.vectorSearcher(), spec.collection(), spec.topK())
                        : new KeywordChannelRetriever(spec.keywordSearcher(), spec.collection(), spec.topK()));
    }

    /**
     * 后端集合被整份替换时清空全部检索器缓存。
     *
     * <p>缓存里的检索器抓着<b>旧后端实例</b>（旧客户端、旧连接参数）。后端集合一换，
     * 那些实例就指向已经不生效的配置了——不清就会出现"页面改了地址、检索还在打旧机器"，
     * 而且这种错不会报任何异常，只会表现为结果不对。</p>
     */
    public void evictAll() {
        retrieverCache.clear();
    }

    /**
     * 删域时清理：失效这些 collection 对应的检索器缓存。
     *
     * 不失效的话，删除的 collection 还会被缓存里的旧检索器继续命中（ES 层报索引缺失），
     * 且同路径域将来重建时会复用指向旧 mapping 的检索器。
     *
     * 缓存 key 是 {@code backendId|collection|modality}，这里按"中一段含 collection"匹配清理——
     * 同一 collection 可能挂在多个后端下（混合后端），只按 key 头部的 backendId 匹配会漏。
     *
     * @param collections 域链上被删除的 collection 名（{@code KbIndexes.nameOf} 的全名）
     */
    public void evict(Collection<String> collections) {
        if (collections == null || collections.isEmpty()) {
            return;
        }
        for (String collection : collections) {
            String middle = "|" + collection + "|";
            retrieverCache.keySet().removeIf(key -> key.contains(middle));
        }
    }
}
