package com.zzkingcc.domainplane.common.util;

/**
 * 日志里的用户内容处理工具。
 *
 * 用户提问原文进日志违反敏感红线，但"别打原文"靠自觉守不住。
 * 统一要求调 {@link #describeUserText(String)} 而非把原文塞进 {}，把规则固化进代码。
 * 检索链路上多处需要，故收在 common：复制两份会各自漂移。
 *
 * @author zzkingcc
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /**
     * 把用户原文转成可安全落日志的描述：只保留长度，内容一字不出。
     *
     * 刻意不提供"截断后输出"，截断版在实践中约等于全文入库。
     *
     * @param userText 用户原文，允许为 null
     * @return 形如 {@code 「128 字符」} 的描述，永不返回原文片段
     */
    public static String describeUserText(String userText) {
        return userText == null ? "「空」" : "「" + userText.length() + " 字符」";
    }
}
