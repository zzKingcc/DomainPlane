package com.zzkingcc.domainplane.common.util;

/**
 * 文本截断：压平换行 + 按字符数截断。
 *
 * <p>归档、摘要、召回卡片都要把任意长文本压成一两行有限长度的字串，
 * 而"换行必须压成空格"这一条不能各写各的：卡片与摘要是单行结构，
 * 夹一个换行进去，下游拼装出来的提示词就会多出一行莫名的断句。</p>
 *
 * <p>与 {@link CjkWidth#estimateTokens} 的分工：那边算"多少 token"，
 * 这里管"截到多少字符"，两侧口径都由调用方按场景定（存档 8K/元素、卡片 60~140 字）。</p>
 *
 * @author zzkingcc
 */
public final class TextClip {

    private TextClip() {
    }

    /**
     * 压平换行并截断到 {@code max} 个字符。
     *
     * @param text 原文；为 {@code null} / 空时返回空串
     * @param max  上限（字符）；超长时保留 {@code max} 个字符并追加省略号
     */
    public static String clip(String text, int max) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String flat = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
        if (max <= 0) {
            return "…";
        }
        // 按码点而不是 UTF-16 code unit 计数：切点落在代理对中间时，
        // 后半截孤立 surrogate 序列化成 UTF-8 会变成 U+FFFD，
        // 卡片 / 摘要里就此多出一个"�"。emoji 与扩展区汉字都是这种形态。
        int codePoints = flat.codePointCount(0, flat.length());
        if (codePoints <= max) {
            return flat;
        }
        return flat.substring(0, flat.offsetByCodePoints(0, max)) + "…";
    }
}
