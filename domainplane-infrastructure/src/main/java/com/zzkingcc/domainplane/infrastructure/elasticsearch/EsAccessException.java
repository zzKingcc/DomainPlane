package com.zzkingcc.domainplane.infrastructure.elasticsearch;

/**
 * ES <b>不可达 / 查询不可信</b>。
 *
 * <p>为什么单列一个类型，而不是继续用 {@code IllegalStateException}：
 * 调用方要区分「索引确实不存在」与「我根本没问到答案」，这两者对
 * <b>破坏性操作</b>的含义完全相反 —— 前者可以安全继续，后者必须中止。
 * 它们若都是 {@code false} 或同一种 unchecked 异常，调用方就只能靠猜，
 * 于是会写成"失败了也往下走"。（这正是 {@link EsIndexManager#exists} 原来的样子。）</p>
 *
 * <p>继承 {@link RuntimeException} 是为了不污染现有调用链的签名，
 * 但它的语义是<b>fail-closed</b>：捕获它的地方必须当成失败。</p>
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