package com.zzkingcc.domainplane.runtime.model;

import dev.langchain4j.model.chat.ChatModel;

/**
 * 域链上解析到的视觉模型 —— 能力路由器与 seeImage 委派共用的一份描述。
 *
 * <p>为什么要带 {@code primary}：同一个"能看图的模型"在两条路上角色不同。</p>
 *
 * <ul>
 *   <li>{@code primary = true} —— 它就是本域链的<b>主模型</b>。本轮带图时图片直接进
 *       {@code UserMessage} 的内容块，由它自己看，没有第二次调用；</li>
 *   <li>{@code primary = false} —— 它是主模型之外的<b>视觉子模型</b>。主模型看不到图片像素，
 *       只能由 {@code seeImage} 把图委派给它做一次 completion 拿回文本。</li>
 * </ul>
 *
 * <p>这两件事必须在同一次解析里一次定死：分开解析会出现"判定说主模型能看图、
 * 委派却又选中主模型自己"的自调用，而那种错误在运行期只表现为一次莫名其妙的重复请求。</p>
 *
 * <p>{@code model} 是<b>非流式</b> {@link ChatModel}：委派要的是"一次拿完整结果"，
 * 既不需要增量回调，也天然不会往父轮的 SSE 流里塞事件（流式模型需要 handler，
 * 而在工具线程上合成的增量会被父轮的事件通道拒收或串号）。</p>
 *
 * @param alias     档案别名（管控台上看到的那个名字，用于日志与排障）
 * @param modelName 模型名（同上）
 * @param model     已构建好的非流式对话模型
 * @param primary   是否就是本域链的主模型
 * @author zzkingcc
 */
public record VisionTarget(String alias, String modelName, ChatModel model, boolean primary) {
}
