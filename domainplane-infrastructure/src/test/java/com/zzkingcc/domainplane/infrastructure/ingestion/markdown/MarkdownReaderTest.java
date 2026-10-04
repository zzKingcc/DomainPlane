package com.zzkingcc.domainplane.infrastructure.ingestion.markdown;

import com.zzkingcc.domainplane.infrastructure.ingestion.block.Block;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * md 解析器的回归样例：md 的标题是「读」出来的，不是猜出来的 —— 这条别再退回txt 那套正则。
 */
class MarkdownReaderTest {

    private static MarkdownReader.MarkdownParseResult result(String md) {
        return MarkdownReader.read(md);
    }

    private static List<Block> blocks(String md) {
        return result(md).blocks();
    }

    private static List<BlockKind> kinds(String md) {
        return blocks(md).stream().map(Block::kind).toList();
    }

    // ==================== 标题 ====================

    @Test
    void 标题层级_井号个数即层级() {
        MarkdownReader.MarkdownParseResult r = result("# 一\n## 二\n### 三\n#### 四\n##### 五\n###### 六\n");

        List<Block> titles = r.blocks().stream().filter(Block::isTitle).toList();
        assertEquals(6, titles.size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), titles.stream().map(Block::level).toList());
    }

    @Test
    void 井号后必须有空白_否则不是标题() {
        List<Block> got = blocks("#tag\n####### 七\n");

        assertEquals(List.of(BlockKind.PARAGRAPH, BlockKind.PARAGRAPH), got.stream().map(Block::kind).toList());
        assertEquals("#tag", got.get(0).text());
    }

    @Test
    void 标题的可选闭合井号被去掉() {
        List<Block> got = blocks("## 会员规则 ##\n");

        assertEquals(List.of(BlockKind.TITLE), got.stream().map(Block::kind).toList());
        assertEquals("会员规则", got.get(0).text());
    }

    // ==================== 代码块 ====================

    @Test
    void 围栏代码块_整块保留且块内井号不当标题() {
        String md = "# 安装\n\n```bash\n# 这不是标题\nsudo apt install -y git\n```\n";

        List<Block> codes = blocks(md).stream().filter(b -> b.kind() == BlockKind.CODE).toList();
        assertEquals(1, codes.size());
        assertEquals("```bash\n# 这不是标题\nsudo apt install -y git\n```", codes.get(0).text());
        assertEquals(1, blocks(md).stream().filter(Block::isTitle).count());
    }

    @Test
    void 未闭合围栏_补上围栏而不是吞掉后面所有内容() {
        String md = "```\necho hi\n";

        List<Block> codes = blocks(md).stream().filter(b -> b.kind() == BlockKind.CODE).toList();
        assertEquals(1, codes.size());
        assertEquals("```\necho hi\n```", codes.get(0).text());
    }

    @Test
    void 缩进代码块_只有紧跟空行才认() {
        String md = "正文\n\n    echo hello\n    echo world\n";

        List<Block> codes = blocks(md).stream().filter(b -> b.kind() == BlockKind.CODE).toList();
        assertEquals(1, codes.size());
        assertEquals("```\necho hello\necho world\n```", codes.get(0).text());
    }

    // ==================== 表格 ====================

    @Test
    void 表格_靠分隔行确认_整块成表() {
        String table = "| 等级 | 折扣 |\n| --- | --- |\n| 黄金 | 9折 |\n| 白金 | 8折 |\n";

        List<Block> tables = blocks(table).stream().filter(b -> b.kind() == BlockKind.TABLE).toList();
        assertEquals(1, tables.size());
        assertEquals("| 等级 | 折扣 |\n| --- | --- |\n| 黄金 | 9折 |\n| 白金 | 8折 |", tables.get(0).text());
    }

    @Test
    void 含竖线但没有分隔行_不是表格() {
        List<Block> got = blocks("路径 src|main|java 下面放着源码。\n");

        assertEquals(List.of(BlockKind.PARAGRAPH), got.stream().map(Block::kind).toList());
    }

    // ==================== 列表 / 引用 ====================

    @Test
    void 列表项与引用_保留标记() {
        List<Block> got = blocks("- 第一条\n* 第二条\n1. 第三条\n> 注意：未成年人需监护人同意。\n");

        assertEquals(List.of(BlockKind.LIST_ITEM, BlockKind.LIST_ITEM, BlockKind.LIST_ITEM, BlockKind.QUOTE),
                got.stream().map(Block::kind).toList());
        assertEquals("- 第一条", got.get(0).text());
        assertEquals("> 注意：未成年人需监护人同意。", got.get(3).text());
    }

    // ==================== 图片 / 链接 / 标记 ====================

    @Test
    void 图片留占位_链接只留文字() {
        List<Block> got = blocks("![流程图](http://x/a.png)\n\n参见[退款政策](http://x/refund)。\n");

        assertEquals(BlockKind.IMAGE, got.get(0).kind());
        assertEquals("[图片: 流程图]", got.get(0).text());
        assertEquals("参见退款政策。", got.get(1).text());
    }

    @Test
    void 图片没有alt时_占位退化成通用描述() {
        assertEquals("[图片]", blocks("![](http://x/b.png)\n").get(0).text());
    }

    @Test
    void 行内HTML标签被剥掉_文字保留() {
        assertEquals("加粗的标题", blocks("<b>加粗的标题</b>\n").get(0).text());
    }

    @Test
    void 注释与分隔线被丢掉() {
        MarkdownReader.MarkdownParseResult r = result("<!--\n草稿草稿\n-->\n\n正文一。\n\n---\n\n正文二。\n");

        assertEquals(List.of("正文一。", "正文二。"), r.blocks().stream().map(Block::text).toList());
        assertEquals(4, r.dropped());
    }

    @Test
    void front_matter整段跳过() {
        MarkdownReader.MarkdownParseResult r = result("---\ntitle: 会员手册\ndraft: true\n---\n\n# 第一章\n\n正文。\n");

        assertEquals(List.of(BlockKind.TITLE, BlockKind.PARAGRAPH), kindsOf(r.blocks()));
        assertEquals(4, r.dropped());
    }

    private static List<BlockKind> kindsOf(List<Block> blocks) {
        return blocks.stream().map(Block::kind).toList();
    }

    @Test
    void 空文本产出空结果() {
        assertTrue(blocks("").isEmpty());
        assertTrue(blocks(null).isEmpty());
    }
}
