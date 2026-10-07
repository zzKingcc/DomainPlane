package com.zzkingcc.domainplane.infrastructure.ingestion.markdown;

import com.zzkingcc.domainplane.infrastructure.ingestion.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 解析器（md 入库的格式适配层）：{@code String → List<Block>}。
 *
 * 为什么不复用 txt 的「猜标题」：md 的 {@code #} 前缀是确定信息，
 * 比正则猜准得多 —— 层级从 1 到 6 都认得出，也不会把 {@code 2024 年新增 12800 人} 这种数据行当成标题。
 * 反过来，{@code OutlineReader} 那套硬约束（数字编号必须带分隔符、层级只分两级）在这里一条都用不上。
 *
 * 几条容易改错的取舍：
 *
 *   • {@code #} 后必须有空白才算标题 —— {@code #tag} 是话题标签，不是标题。
 *   • 缩进代码块必须紧跟空行才认 —— 否则列表项的缩进续行会被整段误判成代码。
 *   • 表格要靠分隔行确认：只有「下一行是 {@code ---} 分隔区」时才把当前行当表头，
 *       否则含竖线的正文行会被拼成伪表格。
 *   • 图片只留占位、链接只留文字 —— url 对检索是纯噪音。
 *
 *
 * @author zzkingcc
 */
public final class MarkdownReader {

    /** ATX 标题：{@code #} 后必须有空白才算（{@code #tag} 不是标题） */
    private static final Pattern ATX = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*)$");

    /** ATX 标题的可选闭合序列，如 {@code ## 标题 ##} */
    private static final Pattern ATX_CLOSING = Pattern.compile("[ \\t]+#+[ \\t]*$");

    /** 围栏起始行：``` 或 ~~~，后面可跟语言 */
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})[ \\t]*(\\S.*)?$");

    /** GFM 表格分隔行：{@code | --- | :---: |} */
    private static final Pattern TABLE_DELIM =
            Pattern.compile("^\\|?[ \\t]*:?-{1,}:?[ \\t]*(\\|[ \\t]*:?-{1,}:?[ \\t]*)*\\|?[ \\t]*$");

    /** 主题分隔线：{@code ---} / {@code ***} / {@code ___} */
    private static final Pattern THEMATIC = Pattern.compile(
            "^ {0,3}(?:\\*[ \\t]*){3,}$|^ {0,3}(?:-[ \\t]*){3,}$|^ {0,3}(?:_[ \\t]*){3,}$");

    /** 缩进代码块：4 个空格或 1 个 tab */
    private static final Pattern INDENT_CODE = Pattern.compile("^(?: {4}|\\t)(.*)$");

    /** 无序列表项 */
    private static final Pattern UL_ITEM = Pattern.compile("^ {0,3}[-*+][ \\t]+(.*)$");

    /** 有序列表项：{@code 1.} / {@code 1)} */
    private static final Pattern OL_ITEM = Pattern.compile("^ {0,3}\\d{1,9}[.)][ \\t]+(.*)$");

    /** 引用行 */
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}>[ \\t]?(.*)$");

    /** 独立成行的图片 */
    private static final Pattern IMAGE_LINE = Pattern.compile("^ {0,3}!\\[([^\\]]*)\\]\\(([^)]*)\\)[ \\t]*$");

    /** 行内图片 */
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]*)\\)");

    /** 行内链接 */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");

    /** 行内 HTML 标签 */
    private static final Pattern HTML_TAG = Pattern.compile("</?[A-Za-z][^>]*>");

    private MarkdownReader() {
    }

    /** 解析结果：块序列 + 被当作标记丢掉的行数 */
    public record MarkdownParseResult(List<Block> blocks, int dropped) {
    }

    public static MarkdownParseResult read(String text) {
        if (text == null || text.isBlank()) {
            return new MarkdownParseResult(List.of(), 0);
        }
        String[] raw = text.split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        int dropped = 0;
        boolean prevBlank = true;
        int i = 0;

        // 1) YAML front matter（静态站点 / 文档站常见）：整段跳过，它对检索是纯噪音
        if (raw.length > 1 && raw[0].strip().equals("---")) {
            int j = 1;
            while (j < raw.length && !raw[j].strip().equals("---")) {
                j++;
            }
            if (j < raw.length) {
                dropped += j + 1;
                i = j + 1;
            }
        }

        while (i < raw.length) {
            String line = raw[i];
            String trimmed = line.strip();

            if (trimmed.isEmpty()) {
                prevBlank = true;
                i++;
                continue;
            }

            // 2) HTML 注释：跨行的整段丢掉，单行的剥掉注释留剩余文本
            if (trimmed.contains("<!--")) {
                boolean closedHere = trimmed.contains("-->");
                if (!closedHere) {
                    dropped++;
                    i++;
                    while (i < raw.length && !raw[i].contains("-->")) {
                        dropped++;
                        i++;
                    }
                    if (i < raw.length) {
                        dropped++;
                        i++;
                    }
                    continue;
                }
                String kept = stripCommentsInline(trimmed);
                if (kept.isEmpty()) {
                    dropped++;
                    i++;
                    continue;
                }
                line = kept;
                trimmed = kept.strip();
            }

            // 3) 主题分隔线
            if (THEMATIC.matcher(trimmed).matches()) {
                dropped++;
                i++;
                continue;
            }

            // 4) 围栏代码块
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                List<String> body = new ArrayList<>();
                body.add(line.strip());
                int j = i + 1;
                boolean closed = false;
                while (j < raw.length) {
                    String cur = raw[j];
                    j++;
                    body.add(cur);
                    if (isFenceClose(cur, marker)) {
                        closed = true;
                        break;
                    }
                }
                if (!closed) {
                    // 没收尾就把后面的内容全包进来会吞掉整篇文档 —— 补上围栏收口，尾部空行一并去掉
                    while (body.size() > 1 && body.get(body.size() - 1).isBlank()) {
                        body.remove(body.size() - 1);
                    }
                    body.add(marker);
                }
                blocks.add(Block.code(String.join("\n", body)));
                i = j;
                prevBlank = true;
                continue;
            }

            // 5) 缩进代码块（必须紧跟空行，否则列表项的缩进续行会被误判）
            if (prevBlank && INDENT_CODE.matcher(line).matches()) {
                StringBuilder sb = new StringBuilder("```");
                int j = i;
                while (j < raw.length && !raw[j].isBlank() && INDENT_CODE.matcher(raw[j]).matches()) {
                    sb.append('\n').append(dedent(raw[j]));
                    j++;
                }
                sb.append("\n```");
                blocks.add(Block.code(sb.toString()));
                i = j;
                prevBlank = true;
                continue;
            }

            // 6) GFM 表格：当前行含竖线，且下一行是分隔区
            if (trimmed.contains("|") && i + 1 < raw.length
                    && TABLE_DELIM.matcher(raw[i + 1].strip()).matches()) {
                StringBuilder sb = new StringBuilder(cleanInline(trimmed));
                int j = i + 1;
                sb.append('\n').append(raw[j].strip());
                j++;
                while (j < raw.length && !raw[j].isBlank() && raw[j].contains("|")) {
                    sb.append('\n').append(cleanInline(raw[j].strip()));
                    j++;
                }
                blocks.add(Block.table(sb.toString()));
                i = j;
                prevBlank = false;
                continue;
            }

            // 7) 标题
            Matcher atx = ATX.matcher(line);
            if (atx.matches()) {
                String body = cleanInline(ATX_CLOSING.matcher(atx.group(2)).replaceAll(""));
                if (!body.isEmpty()) {
                    blocks.add(Block.title(atx.group(1).length(), body));
                }
                i++;
                prevBlank = false;
                continue;
            }

            // 8) 独立成行的图片
            Matcher single = IMAGE_LINE.matcher(line);
            if (single.matches()) {
                blocks.add(Block.image(single.group(1)));
                i++;
                prevBlank = false;
                continue;
            }

            // 9) 普通行：剥标记后按形态归类
            String body = cleanInline(trimmed);
            if (body.isEmpty()) {
                dropped++;
                i++;
                continue;
            }
            if (QUOTE.matcher(line).matches()) {
                blocks.add(Block.quote(body));
            } else if (UL_ITEM.matcher(line).matches() || OL_ITEM.matcher(line).matches()) {
                blocks.add(Block.listItem(body));
            } else {
                blocks.add(Block.paragraph(body));
            }
            i++;
            prevBlank = false;
        }

        return new MarkdownParseResult(List.copyOf(blocks), dropped);
    }

    // ==================== 工具 ====================

    /** 图片 → 占位；链接 → 只留文字；HTML 标签 → 留内容丢标签 */
    private static String cleanInline(String raw) {
        String s = IMAGE.matcher(raw).replaceAll(m -> Block.imagePlaceholder(m.group(1)));
        s = LINK.matcher(s).replaceAll(m -> m.group(1).isBlank() ? "" : m.group(1));
        s = HTML_TAG.matcher(s).replaceAll(" ");
        return s.replaceAll("[ \\t]{2,}", " ").strip();
    }

    /** 去掉行内 HTML 注释，注释之外的内容保留 */
    private static String stripCommentsInline(String trimmed) {
        int start = trimmed.indexOf("<!--");
        if (start < 0) {
            return trimmed;
        }
        int end = trimmed.indexOf("-->", start + 4);
        if (end < 0) {
            return trimmed.substring(0, start).strip();
        }
        return (trimmed.substring(0, start) + " " + trimmed.substring(end + 3)).strip();
    }

    /** 围栏闭合行：同字符、长度不短于起始标记、后面没有别的东西 */
    private static boolean isFenceClose(String line, String marker) {
        String t = line.strip();
        if (!t.startsWith(marker)) {
            return false;
        }
        return t.substring(marker.length()).chars().allMatch(c -> c == marker.charAt(0));
    }

    /** 去掉一层缩进（4 个空格或 1 个 tab） */
    private static String dedent(String line) {
        if (line.startsWith("\t")) {
            return line.substring(1);
        }
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return line.substring(Math.min(4, spaces));
    }
}
