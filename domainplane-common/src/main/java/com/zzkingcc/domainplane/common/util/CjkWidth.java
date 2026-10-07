package com.zzkingcc.domainplane.common.util;

/**
 * 字符宽度估算（CJK 判定）—— 全项目唯一实现，避免各处重复且漏判。
 *
 * 此前散落的实现漏判日文假名（区间止于中文标点 {@code \u303F}，假名从 {@code \u3040} 起），
 * 导致日文按 0.25/字 计、记忆 token 严重低估，会话超预算仍被放行。统一收敛于此。
 *
 * 注意：{@code char} 是 UTF-16 单元，{@code \uD800-\uDFFF} 是代理对的一半。
 * 生僻汉字（𠮷 等）需按 code point 成对判断，见 {@link #isCjk(String, int)}。
 */
public final class CjkWidth {

    /**
     * 按绝对码位索引的 CJK 字符表（BMP 全域，{@code char} 能表示的最大值）。
     *
     * 不按相对偏移建表：建表与读取的偏移基数一旦不一致就会越界，且表现为
     * {@code ArrayIndexOutOfBoundsException} 而非算错，属最坏的一类故障。
     */
    private static final boolean[] CJK_TABLE = buildTable(
            // CJK 表意文字：基本区 + 扩展 A + 兼容表意
            0x4E00, 0x9FFF, 0x3400, 0x4DBF, 0xF900, 0xFAFF,
            // 中文标点与全角符号
            0x3000, 0x303F, 0xFF00, 0xFFEF,
            // 假名：平假名 + 片假名（此前漏掉的就是这一段 —— 注释声称覆盖日文，区间却止于中文标点）
            0x3040, 0x309F, 0x30A0, 0x30FF,
            // 韩文：音节 + 字母 + 兼容字母
            0xAC00, 0xD7AF, 0x1100, 0x11FF, 0x3130, 0x318F);

    /** 单个 CJK 字符折算的 token 数 */
    private static final double CJK_TOKENS = 1.5;

    /** 单个非 CJK 字符折算的 token 数 */
    private static final double OTHER_TOKENS = 0.25;

    private CjkWidth() {
    }

    private static boolean[] buildTable(int... bounds) {
        boolean[] table = new boolean[0x10000];
        for (int i = 0; i < bounds.length; i += 2) {
            for (int c = bounds[i]; c <= bounds[i + 1]; c++) {
                table[c] = true;
            }
        }
        return table;
    }

    /**
     * 单个 {@code char} 是否按 CJK 宽度计。
     *
     * 代理对的高位/低位单独看都不算 —— 单个 {@code char} 承载不了一个 code point，
     * 拿它判定没有意义，用 {@link #isCjk(String, int)}。
     */
    public static boolean isCjk(char c) {
        if (c < 0x3000) {
            return false;
        }
        // 代理对区间（D800-DFFF）：单 char 无意义，不算
        return c < 0xD800 && CJK_TABLE[c];
    }

    /**
     * 按 code point 判定 —— 生僻字（扩展 B 及以上）以代理对存储，
     * 按 char 看会落进 {@code \uD800-\uDFFF} 而被判成"非 CJK"，导致再度低估。
     */
    public static boolean isCjk(String text, int index) {
        int cp = text.codePointAt(index);
        if (cp > 0xFFFF) {
            // 扩展 B 及以上（生僻汉字区）：一律按 CJK 计
            return true;
        }
        return isCjk((char) cp);
    }

    /**
     * 估算一段文本的 token 数（字符级启发式）。
     *
     * 用 code point 迭代而非 char 迭代：代理对按 1 个字符算才是对的。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            tokens += (cp > 0xFFFF || isCjk((char) cp)) ? CJK_TOKENS : OTHER_TOKENS;
            i += Character.charCount(cp);
        }
        return (int) Math.ceil(tokens);
    }

    /**
     * 文本的「字符数」，按 code point 计 —— emoji 与生僻字算 1 个而不是 2 个。
     *
     * 切片长度判断用它才对：{@code String.length()} 会把代理对算成 2。
     */
    public static int countChars(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }
}