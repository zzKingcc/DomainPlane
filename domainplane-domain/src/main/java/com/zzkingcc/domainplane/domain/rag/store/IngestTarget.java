package com.zzkingcc.domainplane.domain.rag.store;

/**
 * 一次导入的写入目标：往哪个 collection 写、经哪个 store 写、向哪个后端问去重。
 *
 * 三个字段打成一个 record 而不是三个松散参数，是因为它们必须同源：
 * collection 与 store 分属不同后端时（写 ES、去重问向量库），去重结果对不上，
 * 同内容切片会被重复写入。捆在一起，装配期错配一眼可见。
 *
 * P0 下 store 与 dedup 是同一个 ES 实例（{@code EsKnowledgeStore} 同时实现两个接口）；
 * 混合后端时二者才分开。ingest 侧只认本 record，不认识任何后端类型。
 *
 * @param collection 目标 collection 名（{@code KbIndexes.nameOf(domain)} 的全名）
 * @param store      写入通道（已带向量的切片经它落库）
 * @param dedup      去重查询通道
 *
 * @author zzkingcc
 */
public record IngestTarget(String collection, KnowledgeStore store, DedupLookup dedup) {
}
