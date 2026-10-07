package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.txt.TxtChunking;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 纯文本类型文档处理策略（只认 {@code .txt}）。
 *
 * 分片交给 {@code TxtSplitter}（统一字符集 → 清洗 → 认标题 → 按句子切片），
 * 去重与向量化流程由 {@link AbstractDocumentProcessStrategy} 统一封装。
 *
 * md 走 {@code MarkdownDocumentProcessStrategy}（md 有原生结构，#{@code #} 前缀是确定信息，
 * 不该退化成"按纯文本猜标题"）；docx / doc 走 POI、pdf 走 PDFBox、xls/xlsx 走 POI，
 * 都是按同一接口新增的策略，工厂与现有策略一行都不用改。
 *
 * @author zzkingcc
 */
@Slf4j
public class TextDocumentProcessStrategy extends AbstractDocumentProcessStrategy {

    /** 适用类型：本轮只支持纯文本 */
    public static final List<String> TEXT_EXTENSIONS = List.of("txt");

    @Override
    protected SplitResult splitDocuments(List<IngestSource> documents) {
        return TxtChunking.newSplitter().splitAllWithStats(asDocuments(documents));
    }

    @Override
    public List<String> supportedExtensions() {
        return TEXT_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "纯文本类型";
    }
}
