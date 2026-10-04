package com.zzkingcc.domainplane.common.util;

/**
 * 日志里的用户内容处理。
 *
 * <p><b>为什么要有这一份</b>：用户提问原文进日志违反 {@code DESIGN.md §13} 的敏感红线，
 * 但"别打原文"这条规则靠自觉是守不住的 —— 写日志的人当时只想着排障，
 * 加上"截断 50 字"就觉得自己已经处理过了。真正需要的是<b>一个能写进代码的既定动作</b>：
 * 调 {@link #describeUserText(String)} 而不是把原文塞进 {@code {}}。</p>
 *
 * <p><b>为什么不放各模块自己实现</b>：至少检索链路上有两处需要（domain 的检索入口、
 * infrastructure 的关键词检索器），复制两份就会各自漂移 —— 一份退化成打前 50 字，
 * 一份变成打哈希。这个工具没有依赖、纯函数、可单测，所以收在 common。</p>
 *
 * @author zzkingcc
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /**
     * 把用户原文转成**可安全落日志**的描述：只保留长度，内容一字不出。
     *
     * <p>刻意不提供"截断后输出"的形式。截断版在实践中等于全文入库 ——
     * 用户提问没有固定长度，而多数有意义的提问都短于任何人随手定的截断阈值，
     * 于是"截断 50 字"实际就是"整条都记下来了"。</p>
     *
     * @param userText 用户原文，允许为 null
     * @return 形如 {@code 「128 字符」} 的描述，永不返回原文的任何片段
     */
    public static String describeUserText(String userText) {
        return userText == null ? "「空」" : "「" + userText.length() + " 字符」";
    }
}
