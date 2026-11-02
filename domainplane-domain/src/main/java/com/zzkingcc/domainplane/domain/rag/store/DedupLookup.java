package com.zzkingcc.domainplane.domain.rag.store;

import java.util.Set;

/**
 * 去重查询：导入链路的窄依赖。
 *
 * 为什么不让摄取链路直接拿 {@link KnowledgeStore}：{@code DocumentIngestor} 与策略层
 * 只需要"问一句这些正文哈希有没有入库过"，把整个 store（写入 / 删除 / 生命周期）交出去，
 * 等于给摄取链路开了它根本用不到的能力面——而能力面越大，将来"某处顺手调了删除"
 * 这类越界越多。窄接口同时让测试好桩：一个 {@code Set} 工厂就能扮完一个后端。
 *
 * 异常姿态（与 {@link KnowledgeStore#existingDedupKeys} 同一条，fail-open）：
 * 查询失败返回空集 + error 级日志——后果是同内容切片被重复写入（不是错数据），
 * warn 混在常规告警里没人会注意。
 *
 * @author zzkingcc
 */
public interface DedupLookup {

    /**
     * @param collection    目标 collection（域）
     * @param contentHashes 待查的正文哈希（不是去重键）
     * @return 已存在的 {@link DedupKey} 集合；查询失败返回空集
     */
    Set<DedupKey> existingDedupKeys(String collection, Set<String> contentHashes);
}
