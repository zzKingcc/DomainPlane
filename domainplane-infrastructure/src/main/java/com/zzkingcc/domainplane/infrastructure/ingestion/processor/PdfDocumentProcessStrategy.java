package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockChunking;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockSplitter;
import com.zzkingcc.domainplane.infrastructure.ingestion.pdf.PdfReader;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF（{@code .pdf}）文档处理策略。
 *
 * 格式适配用 PDFBox 抽行：拿得到「文本 + 字号 + 坐标 + 页码」，这是 Java 生态里唯一做得到的成熟库
 * （Tika 的输出里字号与坐标全丢）。抽完行之后删页眉页脚、拼跨页段落，再交给通用的 {@link BlockSplitter}。
 *
 * 本轮不做字号推断标题（设计里的阶段 5），所以分节按「页」走：
 * {@code section_path = 文件名 > 第N页}，切片同时带 {@code metadata.page_from}。
 *
 * 抽不出文字必须报错：扫描件（整页是图片）在文本层里什么都没有，
 * 这时要给一句"没有可抽取的文本"，而不是"导入成功、0 个切片"，后者会让用户以为入库了。
 *
 * @author zzkingcc
 */
@Slf4j
public class PdfDocumentProcessStrategy extends AbstractDocumentProcessStrategy {

    public static final List<String> PDF_EXTENSIONS = List.of("pdf");

    @Override
    protected SplitResult splitDocuments(List<IngestSource> documents) {
        BlockSplitter splitter = BlockChunking.newSplitter();
        List<TextSegment> all = new ArrayList<>();
        int titles = 0;
        int dropped = 0;
        for (IngestSource source : documents) {
            byte[] raw = source.content();
            if (raw == null || raw.length == 0) {
                log.warn("[pdf切片] 文件[{}]内容为空，跳过", safeFileName(source));
                continue;
            }
            PdfReader.PdfParseResult parsed = PdfReader.read(raw, safeFileName(source));
            if (parsed.paragraphs() == 0) {
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                        "文件 " + safeFileName(source) + " 没有可抽取的文本"
                                + "（共 " + parsed.pages() + " 页，可能是扫描件或纯图片），请提供文字版 pdf");
            }
            log.info("[pdf切片] 文件[{}]：{} 页，正文 {} 段，删页眉页脚/页码 {} 行",
                    safeFileName(source), parsed.pages(), parsed.paragraphs(), parsed.dropped());
            SplitResult one = splitter.splitWithStats(source, parsed.blocks(), parsed.dropped());
            all.addAll(one.segments());
            titles += one.sections();
            dropped += one.droppedLines();
        }
        return new SplitResult(all, titles, dropped);
    }

    /** pdf 是二进制：入口不解码，原始字节交给 PDFBox */
    @Override
    public boolean binary() {
        return true;
    }

    @Override
    public List<String> supportedExtensions() {
        return PDF_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "PDF类型";
    }
}
