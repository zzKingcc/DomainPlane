package com.zzkingcc.domainplane.domain.rag.store;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 知识库存储 SPI：写入与生命周期。一个实例对应一个后端（ES / 向量库…）。
 *
 * 异常姿态逐条钉死（设计 4.6）——写错姿态不会编译失败，只会在生产上把
 * "链路坏了"演成"知识没了"或"知识复活了"：
 *
 * | 方法 | 姿态 | 理由 |
 * | --- | --- | --- |
 * | {@link #exists} | **fail-closed（抛）** | 调用方拿它做破坏性决策。写成"查不到=false"，ES 一抖动删域就静默跳过索引删除，同路径域重建后知识原样复活且无报错 |
 * | {@link #deleteCollection} | fail-closed | 同上 |
 * | {@link #write} / {@link #deleteByDocId} | fail-closed（抛） | 上传接口靠异常回滚"先写后删"；吞掉会让半个文档留在索引里，还占住文件名 |
 * | {@link #listCollections} / {@link #count} / {@link #documents} | **fail-open（空/0）** | 管控台只读展示，抛异常整页打不开 |
 * | {@link #existingDedupKeys} | fail-open + **error 级日志** | 后果是重复写入而不是错数据；warn 会被淹没 |
 * | {@link #scan} | 迭代中失败抛 | 对账补写与重灌都不能半途而废：读到一半停住会把"缺档"误判成"文档只有这些切片" |
 *
 * @author zzkingcc
 */
public interface KnowledgeStore {

    /** 本后端的能力声明（装配校验与通配降级的唯一依据）。 */
    StoreCapabilities capabilities();

    // ---- 生命周期 ----

    /**
     * collection 是否存在。**fail-closed**：查询失败抛 {@link StoreAccessException}，
     * 不返回 {@code false}——"查不到"与"不存在"对破坏性操作含义相反。
     */
    boolean exists(String collection) throws StoreAccessException;

    /**
     * 确保 collection 存在（不存在则按 {@code dims} 创建）。
     *
     * 已存在且维度不符时记录错误日志但不抛（维持现状）：写入会被后端拒绝，
     * 由写入路径如实报错；维度变更是显式操作（重灌），不在这里隐式重建。
     */
    void ensureCollection(String collection, int dims) throws StoreAccessException;

    /**
     * 删除整个 collection。**fail-closed**：查询或删除失败抛
     * {@link StoreAccessException}——调用方（删域）必须当成失败处理，不能继续往下走。
     *
     * @return 是否确实删掉了一个已存在的 collection
     */
    boolean deleteCollection(String collection) throws StoreAccessException;

    /**
     * 列出全部 collection 名（字典序）。**fail-open**：失败返回空列表 + warn 日志——
     * 只用于管控台展示与"当前有哪些域有数据"的展示口径，抛异常的代价是页面整个打不开。
     */
    List<String> listCollections();

    /**
     * 读取 collection 当前的向量维度；读不到（不存在 / keyword-only / 后端不支持）
     * 返回 {@code null}。
     */
    Integer currentVectorDims(String collection);

    /** 连通性探测（管控台「测试连接」用）。 */
    boolean ping();

    /** 启动 / 重建时的诊断日志（后端版本、集群健康、collection 存在性与字段结构）。 */
    void diagnose(String collection);

    // ---- 写入 ----

    /**
     * 写入一批切片（已带向量；keyword-only 后端向量为 {@code null}）。
     * **fail-closed**：失败抛 {@link StoreAccessException}，让调用方回滚。
     */
    void write(String collection, List<KbChunk> chunks) throws StoreAccessException;

    /**
     * 按 docId 删除该文档的全部切片（覆盖更新先清旧）。**fail-closed**。
     *
     * @return 删除条数
     */
    int deleteByDocId(String collection, String docId) throws StoreAccessException;

    /**
     * 批量查询已存在的去重身份。**fail-open**：失败返回空集 + **error 级日志**——
     * 后果是同内容切片被重复写入（不是错数据），warn 混在常规告警里没人会注意。
     *
     * @param contentHashes 待查的正文哈希（不是去重键）
     * @return 已存在的 {@link DedupKey} 集合
     */
    Set<DedupKey> existingDedupKeys(String collection, Set<String> contentHashes);

    // ---- 读取（管控台 / 重灌 / 对账）----

    /** 切片总数。**fail-open**：失败返回 0。 */
    long count(String collection);

    /**
     * 某文档的切片数。**fail-open**：失败返回 0。
     */
    long countByDocId(String collection, String docId);

    /**
     * 读回一个文档的全部切片（含 id / text / metadata / vector）。
     *
     * 存在的理由是原文档案（{@code ChunkArchive}）：ingest 成功后要把"刚写进去的
     * 切片"归档，缺档补写也要按 docId 精确读回——两者都不该为拿一个文档的切片
     * 而全 collection 扫描。
     *
     * **fail-open**：读不到（文档不存在 / 后端故障）返回空列表。调用方按
     * "缺档"处置（对账补写、archive 记 missing），而不是把整条链路 fail 掉。
     */
    List<KbChunk> readByDocId(String collection, String docId);

    /**
     * 文档级聚合列表（管控台文档列表）。**fail-open**：失败返回空列表。
     *
     * @param limit 最多返回多少行
     */
    List<DocumentAgg> documents(String collection, int limit);

    /**
     * 全量扫描：按 {@code batch} 分批产出切片，用于 ChunkArchive 缺档补写（对账）
     * 与 archive 全失时的兜底重灌。
     *
     * 迭代过程中失败抛 {@link StoreAccessException}：读到一半停住会把"缺档"
     * 误判成"文档只有这些切片"，静默丢内容。
     */
    Iterator<List<KbChunk>> scan(String collection, int batch);
}
