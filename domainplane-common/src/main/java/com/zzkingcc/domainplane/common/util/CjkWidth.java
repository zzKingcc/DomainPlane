package com.zzkingcc.domainplane.common.util;

/**
 * 字符宽度估算（CJK 判定）—— <b>全项目唯一实现</b>。
 *
 * <p>抽出来的理由：这份判定此前在 {@code TokenUsageRecorder} 与 {@code DualConstraintChatMemory}
 * 里各写了一份，且<b>两份都漏了日文假名</b> —— 区间写成 {@code \u3000-\u303F}（中文标点），
 * 而假名从 {@code \u3040} 开始，中间正好断开。后果是日文按 0.25/字 计（应约 1.5），
 * 记忆 token 估算低估约 6 倍：会话实际早已超出预算却继续放行，直到撞上条数上限才被拦，
 * 而且报错原因指向"条数"而非真实原因，排障方向被带偏。</p>
 *
 * <p>注意：{@code char} 是 UTF-16 单元，{@code \uD800-\uDFFF} 是代理对的一半。
 * 生僻汉字（𠮷 等）需要成对判断 code point 才能算对，见 {@link #isCjk(String, int)}。</p>
 */
public final class CjkWidth {

    /**
     * 按<b>绝对码位</b>索引的 CJK 字符表（BMP 全域，{@code char} 能表示的最大值）。
     *
     * <p>刻意不按"相对偏移"建表：那样读的时候得写 {@code table[c - 0x4E00]}，
     * 一旦某个 {@code if} 分支的偏移基数与建表基数不一致就越界 ——
     * 越界点恰好在 {@code FULLWIDTH} 分支上（假名/汉字都从 {@code 0x3000} 以上进这里），
     * 表现为 {@code ArrayIndexOutOfBoundsException} 而不是算错，属最坏的一类故障。</p>
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
     * <p>代理对的高位/低位单独看都不算 —— 单个 {@code char} 承载不了一个 code point，
     * 拿它判定没有意义，用 {@link #isCjk(String, int)}。</p>
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
     * 按 char 看会落进 {@code \uD800-\uDFFF} 而被判成"非 CJK"，导致又一轮低估。
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
     * <p>用 code point 迭代而非 char 迭代：代理对按 1 个字符算才是对的。</p>
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
     * <p>切片长度判断用它才对：{@code String.length()} 会把代理对算成 2。</p>
     */
    public static int countChars(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }
}