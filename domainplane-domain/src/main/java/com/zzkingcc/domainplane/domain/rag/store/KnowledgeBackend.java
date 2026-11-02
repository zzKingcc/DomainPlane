package com.zzkingcc.domainplane.domain.rag.store;

import java.util.Optional;

/**
 * 注册单元：一个后端（如 ES）及其提供的能力子集。
 *
 * 三个能力接口用 {@link Optional} 表达"这个后端不提供这条路"：keyword-only 的
 * 关键词库、vector-only 的向量库都是合法后端，hybrid 策略可以由两个后端拼成
 * （keyword 走一家、vector 走另一家）。用 {@code null} 或抛异常表达"不支持"
 * 都会让调用方在运行期才发现配错。
 *
 * @author zzkingcc
 */
public interface KnowledgeBackend {

    /** 后端唯一标识（配置里 {@code keyword-backend} / {@code vector-backend} 引用的就是它）。 */
    String id();

    /**
     * 物理实例标识：两个后端 {@code location()} 相同 = 同一台服务器上的同一份数据。
     *
     * <p>写入侧用它判"关键字路和向量路是不是同一份"（{@code RetrievalPlan.writeStore()}）：
     * 管控台里关键字连接与向量连接是两条独立配置，但只要地址一致就是同一台 ES、同一个索引，
     * 扇出写必须收敛成一路——否则同一批切片会被写两遍，去重与计数全部失真。</p>
     *
     * <p>缺省回落到 {@link #id()}：对"一个后端就是一个库"的实现（内存桩、单租户向量库）
     * 这是正确语义，也不需要每个适配器都去想这件事。</p>
     */
    default String location() {
        return id();
    }

    /** 能力声明：与 {@link #store()} / {@link #keyword()} / {@link #vector()} 必须自洽。 */
    StoreCapabilities capabilities();

    /** 写入与生命周期实现；不提供存储能力的后端（纯检索服务）为 {@link Optional#empty()}。 */
    Optional<KnowledgeStore> store();

    /** 关键词检索实现；{@link StoreCapabilities#keyword()} 为 {@code false} 时为空。 */
    Optional<KeywordSearcher> keyword();

    /** 向量检索实现；{@link StoreCapabilities#vector()} 为 {@code false} 时为空。 */
    Optional<VectorSearcher> vector();
}
