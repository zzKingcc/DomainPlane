package com.zzkingcc.domainplane.runtime.model;

import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * 按域解析对话模型 —— 内核与「模型档案」之间的唯一接口。
 *
 * <p>接口放在内核侧、实现放在服务端，好处是内核只依赖这个抽象：
 * 没有实现时（例如单测、或未装配模型档案的部署）内核退回构造期注入的那个模型，
 * 行为与"全局一个模型"完全一致。</p>
 *
 * @author zzkingcc
 */
public interface ModelResolver {

    /**
     * 解析该域本轮应使用的流式对话模型。
     *
     * <p>解析顺序由实现决定，但语义是固定的：<b>域没有绑定时返回全局默认模型</b>，
     * 而不是返回 {@code null} 让调用方去猜。</p>
     *
     * @param domain 域标识（完整路径；{@code null} / 空白按根域处理）
     * @return 该域应使用的模型；实现无法解析时返回 {@code null}，内核会退回默认模型
     */
    StreamingChatModel streamingChat(String domain);
}
