package com.zzkingcc.domainplane.infrastructure.ingestion.block;

import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.processor.SplitResult;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化切片层的回归样例：md / docx / pdf 共用这一份，定的规则别再被改回去。
 */
class BlockSplitterTest {

    private static IngestSource doc(String fileName) {
        return IngestSource.ofText("(md 由 MarkdownReader 负责解析)", new byte[0],
                fileName, Metadata.from("file_name", fileName), "UTF-8");
    }

    private static SplitResult split(List<Block> blocks) {
        return new BlockSplitter(400, 1, 60).splitWithStats(doc("会员手册.md"), blocks, 0);
    }

    private static List<String> paths(SplitResult r) {
        return r.segments().stream().map(s -> s.metadata().getString("section_path")).toList();
    }

    private static List<String> bodies(SplitResult r) {
        return r.segments().stream().map(TextSegment::text).toList();
    }

    // ==================== 设计文档第八节的例子 ====================

    @Test
    void 示例全程_标题路径与不可切块_() {
        List<Block> blocks = List.of(
                Block.title(1, "第一章 会员规则"),
                Block.paragraph("会员体系分为三个等级。"),
                Block.title(2, "一、注册条件"),
                Block.paragraph("会员注册需提供手机号。"),
                Block.quote("> 注意：未成年人需监护人同意。"),
                Block.table("| 等级 | 折扣 |\n| --- | --- |\n| 黄金 | 9折 |\n| 白金 | 8折 |"),
                Block.title(2, "二、权益"),
                Block.code("```bash\ncurl -X POST /api/member/register\n```"),
                Block.paragraph("会员可享受专属客服。"),
                Block.title(1, "第二章 退款政策"),
                Block.paragraph("签收后七日内可申请退款。"));

        SplitResult result = split(blocks);

        assertEquals(5, result.segments().size());
        assertEquals(List.of(
                "第一章 会员规则",
                "第一章 会员规则 > 一、注册条件",
                "第一章 会员规则 > 一、注册条件",
                "第一章 会员规则 > 二、权益",
                "第二章 退款政策"), paths(result));
        assertEquals(4, result.sections());

        // 同一节内的段落与引用打包到一片
        assertTrue(bodies(result).get(1).contains("会员注册需提供手机号。"));
        assertTrue(bodies(result).get(1).contains("> 注意：未成年人需监护人同意。"));
        // 表格独占一片，不与前后段落合并
        assertTrue(bodies(result).get(2).contains("| 黄金 | 9折 |"));
        assertFalse(bodies(result).get(1).contains("| 黄金"));
        // 代码块整块保留，其后段落与它同片
        assertTrue(bodies(result).get(3).contains("curl -X POST /api/member/register"));
        assertTrue(bodies(result).get(3).contains("会员可享受专属客服。"));
    }

    // ==================== 标题路径栈 ====================

    @Test
    void 六级标题_路径完整拼接() {
        List<Block> blocks = List.of(
                Block.title(1, "一"), Block.title(2, "二"), Block.title(3, "三"),
                Block.title(4, "四"), Block.title(5, "五"), Block.title(6, "六"),
                Block.paragraph("正文。"));

        SplitResult result = split(blocks);

        assertEquals("一 > 二 > 三 > 四 > 五 > 六",
                result.segments().get(0).metadata().getString("section_path"));
        assertEquals("六", result.segments().get(0).metadata().getString("section_title"));
    }

    @Test
    void 一级标题出现时_深层路径被清掉() {
        List<Block> blocks = List.of(
                Block.title(1, "第一章"), Block.title(2, "第一节"), Block.paragraph("甲。"),
                Block.title(1, "第二章"), Block.paragraph("乙。"));

        assertEquals(List.of("第一章 > 第一节", "第二章"), paths(split(blocks)));
    }

    @Test
    void 开篇正文没有标题时_路径取文件名() {
        SplitResult result = split(List.of(
                Block.paragraph("开篇。"),
                Block.title(1, "第一章"),
                Block.paragraph("甲。")));

        assertEquals(List.of("会员手册", "第一章"), paths(result));
    }

    // ==================== 不可切块 ====================

    @Test
    void 表格独占一片_不与前后段落合并() {
        SplitResult result = split(List.of(
                Block.title(1, "会员"),
                Block.paragraph("下面是会员等级。"),
                Block.table("| 等级 | 折扣 |\n| --- | --- |\n| 黄金 | 9折 |"),
                Block.paragraph("表格后面的说明。")));

        assertEquals(3, result.segments().size());
        assertTrue(bodies(result).get(1).contains("| 黄金 | 9折 |"));
        assertFalse(bodies(result).get(1).contains("下面是会员等级。"));
        assertFalse(bodies(result).get(1).contains("表格后面的说明。"));
    }

    @Test
    void 表格超长_每片都重复表头() {
        String table = "| 等级 | 折扣 | 有效期 |\n| --- | --- | --- |\n| 黄金 | 9折 | 一年 |\n| 白金 | 8折 | 两年 |";

        SplitResult result = new BlockSplitter(40, 0, 0)
                .splitWithStats(doc("a.md"), List.of(Block.table(table)), 0);

        assertEquals(2, result.segments().size());
        for (TextSegment segment : result.segments()) {
            assertTrue(segment.text().contains("| 等级 | 折扣 | 有效期 |"), segment.text());
        }
    }

    @Test
    void 代码块超长_按行切且每片重新包围栏() {
        String code = "```bash\necho one\necho two\necho three\n```";

        SplitResult result = new BlockSplitter(30, 0, 0)
                .splitWithStats(doc("a.md"), List.of(Block.code(code)), 0);

        assertEquals(2, result.segments().size());
        // 首片保留语言标记
        assertTrue(bodies(result).get(0).contains("```bash\necho one"));
        // 后续片只有围栏
        assertTrue(bodies(result).get(1).contains("```\necho three\n```"));
    }

    // ==================== 元数据 ====================

    @Test
    void 元数据_seq与total连续() {
        SplitResult result = split(List.of(
                Block.title(1, "第一章"), Block.paragraph("甲。"),
                Block.title(2, "第一节"), Block.paragraph("乙。"),
                Block.title(2, "第二节"), Block.paragraph("丙。")));

        // chunk_seq / chunk_total 存的是整数（与 txt 管线一致，检索侧按 getInteger 读）
        List<Integer> seq = result.segments().stream()
                .map(s -> s.metadata().getInteger("chunk_seq")).toList();
        List<Integer> total = result.segments().stream()
                .map(s -> s.metadata().getInteger("chunk_total")).toList();
        assertEquals(List.of(1, 2, 3), seq);
        assertEquals(List.of(3, 3, 3), total);
    }
}
