package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockChunking;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockSplitter;
import com.zzkingcc.domainplane.infrastructure.ingestion.excel.ExcelReader;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel 表格（{@code .xls} / {@code .xlsx}）文档处理策略。
 *
 * <p>{@code .xls}（HSSF）与 {@code .xlsx}（XSSF）由同一个读取器处理 ——
 * {@code WorkbookFactory} 按文件头判类型，不需要按扩展名分叉，所以这里<b>一条策略覆盖两个扩展名</b>。
 * 这与"加格式 = 加一套适配器"不冲突：适配器只有一个（Excel 表格），扩展名只是它的入口。</p>
 *
 * <p>分节按<b>工作表</b>走：{@code section_path = 文件名 > 工作表名}。每个工作表整体渲染成一张
 * markdown 表格交给 {@link BlockSplitter}；单表超长时由切片层按数据行切、每片重复表头。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class ExcelDocumentProcessStrategy extends AbstractDocumentProcessStrategy {

    public static final List<String> EXCEL_EXTENSIONS = List.of("xls", "xlsx");

    @Override
    protected SplitResult splitDocuments(List<IngestSource> documents) {
        BlockSplitter splitter = BlockChunking.newSplitter();
        List<TextSegment> all = new ArrayList<>();
        int titles = 0;
        int dropped = 0;
        for (IngestSource source : documents) {
            byte[] raw = source.content();
            if (raw == null || raw.length == 0) {
                log.warn("[excel切片] 文件[{}]内容为空，跳过", safeFileName(source));
                continue;
            }
            ExcelReader.ExcelParseResult parsed = ExcelReader.read(raw, safeFileName(source));
            if (parsed.blocks().isEmpty()) {
                // 所有工作表都是空的 —— 明确报错，不要"导入成功、0 片"
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                        "文件 " + safeFileName(source) + " 没有解析出任何表格内容"
                                + "（共 " + parsed.skippedSheets() + " 个工作表，全部为空），请检查后再上传");
            }
            log.info("[excel切片] 文件[{}]：有内容工作表 {} 个，空工作表 {} 个",
                    safeFileName(source), parsed.sheets(), parsed.skippedSheets());
            SplitResult one = splitter.splitWithStats(source, parsed.blocks(), 0);
            all.addAll(one.segments());
            titles += one.sections();
            dropped += one.droppedLines();
        }
        return new SplitResult(all, titles, dropped);
    }

    /** excel 是二进制：入口<b>不解码</b>，原始字节交给 POI */
    @Override
    public boolean binary() {
        return true;
    }

    @Override
    public List<String> supportedExtensions() {
        return EXCEL_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "Excel类型";
    }
}
