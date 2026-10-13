package com.zzkingcc.domainplane.infrastructure.ingestion.doc;

import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.Block;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockKind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧版 {@code .doc} 解析器的回归样例。
 *
 * <p>样例文档<b>只能是真的</b>（见 src/test/resources/ingestion/doc/README.md）：
 * HWPF 的样式表、表格遍历、控制符这几处行为与实际二进制布局强相关，用代码现造不出来，
 * 靠推理也必错，这几个样例是把实测结论钉下来的地方。</p>
 */
class DocReaderTest {

    private static List<BlockKind> kinds(DocReader.DocParseResult result) {
        return result.blocks().stream().map(Block::kind).toList();
    }

    // ==================== 表格 ====================

    @Test
    void 表格转markdown_且单元格标记不泄漏进正文() {
        DocReader.DocParseResult result = DocReader.read(DocFixtures.load("simple-table.doc"));

        List<Block> tables = result.blocks().stream().filter(b -> b.kind() == BlockKind.TABLE).toList();
        assertEquals(1, tables.size(), "simple-table.doc 里就一张 3 列 2 行的表");
        assertEquals("| Cell 1,1 | Cell 1,2 | Cell 1,3 |\n"
                + "| --- | --- | --- |\n"
                + "| Cell 2,1 | Cell 2,2 | Cell 2,3 |", tables.get(0).text());

        // 表格段落是按"整张表一次跳过"消费掉的：不能既出表格块、又把单元格当正文再来一遍
        for (Block block : result.blocks()) {
            assertFalse(block.text().contains("\u0007"), "单元格标记 \\u0007 泄漏: " + block.text());
            assertFalse(block.text().contains("\r"), "段落标记 \\r 泄漏: " + block.text());
        }
    }

    @Test
    void 表格前后的段落都保住_且顺序是原文顺序() {
        DocReader.DocParseResult result = DocReader.read(DocFixtures.load("simple-table.doc"));

        assertEquals(List.of(BlockKind.PARAGRAPH, BlockKind.TABLE, BlockKind.PARAGRAPH), kinds(result));
        assertTrue(result.blocks().get(0).text().startsWith("This is a Word document"), result.blocks().get(0).text());
        assertEquals("This text is below the table.", result.blocks().get(2).text());
    }

    // ==================== 标题层级 ====================

    @Test
    void 标题层级_样式名认不出来时靠大纲级别兜底() {
        DocReader.DocParseResult result = DocReader.read(DocFixtures.load("Lists.doc"));

        // Lists.doc 第 1 段的样式是 Heading 1，实测 getLvl() = 0
        Block first = result.blocks().get(0);
        assertEquals(BlockKind.TITLE, first.kind());
        assertEquals(1, first.level());
        assertEquals("Heading Level 1", first.text());
    }

    @Test
    void 列表项不算标题_一个标题都没有的文档不会凭空造出章节() {
        DocReader.DocParseResult result = DocReader.read(DocFixtures.load("Lists.doc"));

        // Lists.doc 里有 28 个 List Paragraph（ilfo != 0），它们的 getLvl() 都是 9
        assertEquals(1, result.blocks().stream().filter(Block::isTitle).count(),
                "只有那个 Heading 1 是标题，列表项不是");
        assertTrue(result.blocks().stream()
                        .anyMatch(b -> b.kind() == BlockKind.PARAGRAPH && b.text().equals("UL 2")),
                "列表项应当作为普通段落保留");
    }

    // ==================== 报错话术 ====================

    @Test
    void Word6的旧格式_报错要说清怎么解决() {
        KnowledgeBaseException ex = assertThrows(KnowledgeBaseException.class,
                () -> DocReader.read(DocFixtures.load("Word6.doc")));

        assertTrue(ex.getMessage().contains("Word 95 或更早"), ex.getMessage());
        assertTrue(ex.getMessage().contains("另存为 .docx"), ex.getMessage());
    }

    @Test
    void 不是doc时_给出明确错误而不是静默空结果() {
        KnowledgeBaseException ex = assertThrows(KnowledgeBaseException.class,
                () -> DocReader.read("这不是 doc".getBytes(StandardCharsets.UTF_8)));

        assertTrue(ex.getMessage().contains("doc 解析失败"), ex.getMessage());
    }

    @Test
    void 内容为空_直接拒绝() {
        KnowledgeBaseException ex = assertThrows(KnowledgeBaseException.class, () -> DocReader.read(new byte[0]));
        assertTrue(ex.getMessage().contains("内容为空"), ex.getMessage());
    }
}
