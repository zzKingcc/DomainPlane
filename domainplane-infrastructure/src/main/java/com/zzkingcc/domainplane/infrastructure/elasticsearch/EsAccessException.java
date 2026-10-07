package com.zzkingcc.domainplane.infrastructure.elasticsearch;

/**
 * ES 不可达 / 查询不可信。
 *
 * 单列此类型是为了让调用方区分「索引确实不存在」与「根本没问到答案」，
 * 两者对破坏性操作含义相反：前者可安全继续，后者必须中止。
 * 若共用 {@code IllegalStateException}，调用方只能靠猜，容易写成"失败也往下走"。
 *
 * 继承 {@link RuntimeException} 仅为不污染调用链签名，
 * 但语义是 fail-closed：捕获它的地方必须当成失败处理。
 *
 * @author zzkingcc
 */
public class EsAccessException extends RuntimeException {

    public EsAccessException(String message) {
        super(message);
    }

    public EsAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}