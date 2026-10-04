package com.zzkingcc.domainplane.infrastructure.ingestion.block;

import java.util.List;

/**
 * 表格 → markdown 表格的<b>唯一实现</b>：docx / 旧版 doc / excel 三个格式适配层共用。
 *
 * <p>为什么三个格式都落到 markdown 表格、而不是各自的纯文本：纯文本会丢掉「哪一列是哪一列」。
 * 表头与数据行的对应关系一断，这条知识就废了；markdown 表格同时让 BM25（表头词）
 * 和向量（表头 + 数据同现）都吃得到。</p>
 *
 * <p>docx 与 excel 的合并单元格处理方式不同（docx 由 POI 直接给出合并后的格子、excel 要自己按
 * 合并区域回填），但<b>落地形态完全一致</b> —— 这里只负责最后那一步渲染。</p>
 *
 * @author zzkingcc
 */
public final class MarkdownTable {

    /** 单元格文本上限（超过就截断，避免一张畸形表撑爆切片） */
    public static final int MAX_CELL_CHARS = 200;

    private MarkdownTable() {
    }

    /**
     * 渲染成 markdown 表格：<b>第一行当表头</b>，列数取所有行的最大值，短行补空列。
     *
     * <p>各格式的表头来源都只有一个「第一行」：docx / doc 没有可靠的表头标记
     * （实测 HWPF 的 {@code TableRow.isTableHeader()} 在真实表格里也是 false），
     * Excel 也没有「这行是标题」这种语义。补空列是因为合并单元格会让某一行的格子数变少。</p>
     *
     * @return markdown 表格文本；没有任何可用行时返回空串
     */
    public static String render(List<List<String>> rows) {
        int columns = 0;
        for (List<String> row : rows) {
            columns = Math.max(columns, row.size());
        }
        if (rows.isEmpty() || columns == 0) {
            return "";
        }
        StringBuilder md = new StringBuilder();
        md.append(textRow(rows.get(0), columns));
        md.append('\n').append(delimiterRow(columns));
        for (int i = 1; i < rows.size(); i++) {
            md.append('\n').append(textRow(rows.get(i), columns));
        }
        return md.toString();
    }

    /**
     * 单元格文本归一：控制符折叠成空格 → 压缩连续空白 → 截断 → 转义竖线。
     *
     * <p>控制符必须处理，三个格式都会往里塞：docx 的软回车 {@code \u000B}、
     * 旧版 doc 的单元格标记 {@code \u0007} 与段落标记 {@code \r}（实测一个单元格内可含多段）。</p>
     */
    public static String cell(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder folded = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            folded.append(c < 0x20 ? ' ' : c);
        }
        String text = folded.toString().replaceAll("\\s{2,}", " ").strip();
        if (text.length() > MAX_CELL_CHARS) {
            text = text.substring(0, MAX_CELL_CHARS);
        }
        // 单元格里的竖线会破坏表格结构，必须最后转义（转义后再截断就会把反斜杠截断）
        return text.replace("|", "\\|");
    }

    private static String textRow(List<String> cells, int columns) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < columns; i++) {
            String cell = i < cells.size() ? cells.get(i) : "";
            sb.append(' ').append(cell).append(" |");
        }
        return sb.toString();
    }

    private static String delimiterRow(int columns) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < columns; i++) {
            sb.append(" --- |");
        }
        return sb.toString();
    }
}
