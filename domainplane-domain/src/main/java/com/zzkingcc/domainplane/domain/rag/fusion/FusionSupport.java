package com.zzkingcc.domainplane.domain.rag.fusion;

import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.common.util.CjkWidth;
import com.zzkingcc.domainplane.domain.rag.model.RetrievalScoreKeys;
import dev.langchain4j.rag.content.Content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 融合策略共用的辅助函数（去重哈希、取分、标题 / 文件名命中判定、命中通道命名）。
 *
 * 抽出来是为了让分数制（{@link DefaultFusionStrategy}）与排名制（{@link RrfFusionStrategy}）
 * 共用同一套判定，避免两处各写一份而慢慢走偏。
 *
 * @author zzkingcc
 */
final class FusionSupport {

    private FusionSupport() {
    }

    /**
     * 去重身份 = 文件名 + 切片序号 + 正文，取 SHA-256。
     *
     * <p>只取正文哈希会把"不同切片但正文相同"的内容判成同一条并丢弃：PDF 每页的页眉页脚、
     * 表格里重复的行、合同的标准条款都是这种形态。用户看到的是"明明有 10 处依据，
     * 检索只回了 1 处"。加上文件与序号之后，同一条切片在不同通道（向量 / 关键词）命中时
     * 仍然折叠成一条，重复正文的不同切片则各自保留。</p>
     */
    static String hashContent(Content content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String identity = meta(content, ChunkMetadataKeys.FILE_NAME)
                    + "\u0000" + meta(content, ChunkMetadataKeys.CHUNK_SEQ)
                    + "\u0000" + content.textSegment().text();
            byte[] hash = md.digest(identity.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return content.textSegment().text();
        }
    }

    /** 从 Content metadata 中提取 ES 检索分数 */
    static double extractScore(Content content) {
        try {
            return content.textSegment().metadata().getFloat(RetrievalScoreKeys.RAW_SCORE);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** 命中通道命名：vector / keyword / both */
    static String channel(boolean vectorHit, boolean keywordHit) {
        if (vectorHit && keywordHit) {
            return "both";
        }
        return vectorHit ? "vector" : "keyword";
    }

    /**
     * 切片序号（同一文档内的顺序）。
     *
     * 只用于同分时的稳定排序：融合分完全相同时按原文顺序排，
     * 让结果可复现（否则 ES 返回顺序抖动会让同一问题两次答案不一致）。
     */
    static int chunkSeq(Content content) {
        try {
            Integer seq = content.textSegment().metadata().getInteger(ChunkMetadataKeys.CHUNK_SEQ);
            return seq == null ? Integer.MAX_VALUE : seq;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 标题是否命中查询词。
     *
     * @param queryKeywords 由 {@link #keywords(String)} 预先切好的候选词：
     *        切分结果与 query 一一对应且在整轮融合内不变，不能在每条 content 上重算：
     *        2000 字的中文查询会展开出上千个 bigram 候选，重算一次就是上千次字符串分配 × 条数。
     */
    static boolean titleHit(Content content, Set<String> queryKeywords) {
        String title = meta(content, ChunkMetadataKeys.SECTION_TITLE);
        return title != null && !title.isBlank() && containsAnyKeyword(title, queryKeywords);
    }

    /** 文件名是否命中查询词。参数含义同 {@link #titleHit}。 */
    static boolean fileNameHit(Content content, Set<String> queryKeywords) {
        String fileName = meta(content, ChunkMetadataKeys.FILE_NAME);
        return fileName != null && !fileName.isBlank() && containsAnyKeyword(fileName, queryKeywords);
    }

    private static String meta(Content content, String key) {
        try {
            return content.textSegment().metadata().getString(key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 切出查询的候选关键词。整轮融合只应调一次，结果复用于每条 content 的标题/文件名判定。
     *
     * 中文无词边界，只按整段匹配会让 {@code section_title} 几乎不可能命中，
     * {@code title-boost} / {@code file-name-boost} 形同空转。
     * 故对含 CJK 的段额外生成二元组（bigram）候选；短查询与纯拉丁/数字段仍靠整段精确命中。
     */
    static Set<String> keywords(String query) {
        Set<String> keywords = new LinkedHashSet<>();
        if (query == null) {
            return keywords;
        }
        // 明确用 ROOT：默认 locale 下 "I" 在土耳其语环境会变成 "ı"，关键词集合随环境漂移，
        // 同一查询在两地跑出两种排序
        for (String segment : query.toLowerCase(java.util.Locale.ROOT).split(SEGMENT_SPLIT)) {
            if (segment.isEmpty()) {
                continue;
            }
            keywords.add(segment);
            if (hasCjk(segment)) {
                addBigrams(segment, keywords);
            }
        }
        return keywords;
    }

    /** 用原始 query 判定的便捷入口（内部切一次词）。批量场景请走 {@link #keywords(String)}。 */
    static boolean containsAnyKeyword(String text, String query) {
        return containsAnyKeyword(text, keywords(query));
    }

    /**
     * 用预先切好的候选词判定文本是否命中。
     *
     * 单个 CJK 字符不作候选（{@code kw.length() >= 2} 这道闸）：单字命中率接近 100%，
     * 放进来会让标题增益变成常量、彻底失去区分度。
     */
    static boolean containsAnyKeyword(String text, Set<String> queryKeywords) {
        if (text == null || queryKeywords == null || queryKeywords.isEmpty()) {
            return false;
        }
        String lowerText = text.toLowerCase(java.util.Locale.ROOT);
        for (String kw : queryKeywords) {
            if (kw.length() >= 2 && lowerText.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 查询与空白/标点的分隔符：中英文标点各一套，外加空白 */
    private static final String SEGMENT_SPLIT = "[\\s，。！？、；：\"'（）《》【】〔〕,.!?;:()]+";

    /**
     * 高频虚字（疑问词、助词、连词、介词、指示词、数词、量词），只用于过滤 bigram，不过滤整段。
     * 若不拦，无区分度的组合（如「如何」）会让标题被几乎任何问句命中，增益变成常量。
     * 判据为「两字全是虚字才丢」，保留「申请」等含一个实义字的组合：
     * 少丢只是增益略高（可控），多丢会吞掉真命中（查得到却排不上去）。
     */
    private static final String FUNCTION_CHARS =
            "的了着过是在和与或也就都而及又但却吗呢吧啊哪什怎样么如何请这那之其被把对从到于为以"
                    + "会能要可个们你我他她它不没无非上下中里一二三四五六七八九十";

    private static boolean hasCjk(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjkCodePoint(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * 展开二元组，按 code point 走（生僻字是代理对，按 char 切会切出半个字符）。
     * 非 CJK 的相邻字符不生成，避免跨文种碎片（如「api设计规范」切出 {@code pi}）让
     * 标题「api」被中文查询误命中；拉丁部分交给整段匹配。
     */
    private static void addBigrams(String segment, Set<String> keywords) {
        int[] cps = segment.codePoints().toArray();
        for (int i = 0; i + 1 < cps.length; i++) {
            if (!isCjkCodePoint(cps[i]) || !isCjkCodePoint(cps[i + 1])) {
                continue;
            }
            String bigram = new String(cps, i, 2);
            if (!allFunctionChars(bigram)) {
                keywords.add(bigram);
            }
        }
    }

    /**
     * code point 版 CJK 判定。
     *
     * 不能直接 {@code CjkWidth.isCjk((char) cp)}：生僻字（扩展 B 及以上）是代理对，
     * 截成 char 会拿到代理项（落在 D800-DFFF）而被判成非 CJK，这类字就再也不会产出 bigram。
     */
    private static boolean isCjkCodePoint(int cp) {
        return cp > 0xFFFF || CjkWidth.isCjk((char) cp);
    }

    private static boolean allFunctionChars(String bigram) {
        for (int i = 0; i < bigram.length(); i++) {
            if (FUNCTION_CHARS.indexOf(bigram.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
