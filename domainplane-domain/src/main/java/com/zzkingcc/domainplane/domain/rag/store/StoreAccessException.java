package com.zzkingcc.domainplane.domain.rag.store;

/**
 * 存储后端不可达 / 操作不可信。
 *
 * 单列此类型是为了让调用方区分「collection 确实不存在」与「根本没问到答案」，
 * 两者对破坏性操作含义相反：前者可安全继续，后者必须中止。若共用
 * {@code IllegalStateException}，调用方只能靠猜，容易写成"失败也往下走"。
 *
 * 继承 {@link RuntimeException} 仅为不污染调用链签名，但语义是 fail-closed：
 * 捕获它的地方必须当成失败处理。
 *
 * 适配器映射纪律：后端自己的 IO 异常（ES 的 {@code IOException}）与运行时异常
 * （ES 的 {@code ElasticsearchException}，4xx/5xx 走这条路）都要接住转成本类型，
 * 否则会绕过 ErrorCode 分类，上层给不出可行动提示。
 *
 * @author zzkingcc
 */
public class StoreAccessException extends RuntimeException {

    public StoreAccessException(String message) {
        super(message);
    }

    public StoreAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
