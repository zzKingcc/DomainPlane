package com.zzkingcc.domainplane.runtime.model;

import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.Optional;

/**
 * 按域解析对话模型 —— 内核与「模型档案」之间的唯一接口。
 *
 * 接口放在内核侧、实现放在服务端，好处是内核只依赖这个抽象：
 * 没有实现时（例如单测、或未装配模型档案的部署）内核退回构造期注入的那个模型，
 * 行为与"全局一个模型"完全一致。
 *
 * @author zzkingcc
 */
public interface ModelResolver {

    /**
     * 解析该域本轮应使用的流式对话模型。
     *
     * 解析顺序由实现决定，但语义是固定的：域没有绑定时返回全局默认模型，
     * 而不是返回 {@code null} 让调用方去猜。
     *
     * @param domain 域标识（完整路径；{@code null} / 空白按根域处理）
     * @return 该域应使用的模型；实现无法解析时返回 {@code null}，内核会退回默认模型
     */
    StreamingChatModel streamingChat(String domain);

    /**
     * 解析该域链上<b>第一个</b>视觉模型（输入模态含 {@code image} 的那类档案）。
     *
     * <p>语义固定为"沿绑定顺序取首个可用的视觉模型"，而不是"取一个非主模型的视觉模型"：
     * 后者是调用方的过滤条件，不是解析规则。调用方按 {@link VisionTarget#primary()} 分流：</p>
     *
     * <ul>
     *   <li>{@code primary = true} —— 主模型自己就能看图，本轮图片进它的消息上下文；</li>
     *   <li>{@code primary = false} —— 主模型是纯文本的，图只能交给这个子模型看；</li>
     *   <li>返回空 —— 整条链一个视觉模型都没有。带图请求此时无处可去，
     *       由调用方按"域没配视觉模型"如实拒绝，不静默把图丢掉。</li>
     * </ul>
     *
     * <p>默认返回空：未装配模型档案的部署（单测、最小装配）无从判断视觉能力，
     * 此时调用方按"不知道"处理而不是"不支持"，把"没探测"当成"确定没有"，
     * 会让这些部署下所有带图请求直接失败。</p>
     *
     * @param domain 域标识（完整路径；{@code null} / 空白按根域处理）
     */
    default Optional<VisionTarget> visionModel(String domain) {
        return Optional.empty();
    }

    /**
     * 解析该域本轮主模型声明的分档上限（上下文窗口 / 单次最大输出）。
     *
     * <p><b>必须是 {@link #streamingChat} 会取到的同一个档案</b>，否则会出现
     * "预算按 A 模型收紧、请求实际发给 B 模型"，选小了白白浪费上下文，
     * 选大了则在 B 模型上必然超限。</p>
     *
     * <p>默认返回空：未装配模型档案的部署（单测、最小装配）无从得知上限，此时调用方按
     * "不知道"处理（装配预算维持配置值），而不是当成零去把预算压到下限。</p>
     *
     * <p>与 {@link #streamingChat} 的另一个差别：本方法<b>不抛</b>。它给的是"能不能更保守"
     * 的锦上添花，取不到就不收紧；解析不到模型这件事由 {@code streamingChat} 在真正要用模型时
     * 如实抛出，不该在这里提前炸掉一轮对话。</p>
     *
     * @param domain 域标识（完整路径；{@code null} / 空白按根域处理）
     */
    default Optional<ModelLimits> modelLimits(String domain) {
        return Optional.empty();
    }
}
