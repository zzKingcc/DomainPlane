package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockChunking;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockSplitter;
import com.zzkingcc.domainplane.infrastructure.ingestion.markdown.MarkdownReader;
import com.zzkingcc.domainplane.infrastructure.ingestion.txt.TxtNormalizer;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 文档处理策略（{@code .md} / {@code .markdown}）。
 *
 * 管线只有两步：md 解析（读结构）→ 通用切片层。与 txt 的差别就在这第一步，
 * txt 用正则「猜」标题，md 的 {@code #} 前缀是确定信息；
 * 后面的句子打包、重叠、去重、向量化由 {@link AbstractDocumentProcessStrategy} 统一封装。
 *
 * md 不走 {@code TxtCleaner}：那套规则是给「不知道哪里是结构」的纯文本用的，
 * 它会把重复出现的短正文行当成页眉页脚删掉；而 md 里的重复短行往往是列表项。
 *
 * @author zzkingcc
 */
@Slf4j
public class MarkdownDocumentProcessStrategy extends AbstractDocumentProcessStrategy {

    public static final List<String> MARKDOWN_EXTENSIONS = List.of("md", "markdown");

    @Override
    protected SplitResult splitDocuments(List<IngestSource> documents) {
        BlockSplitter splitter = BlockChunking.newSplitter();
        List<TextSegment> all = new ArrayList<>();
        int titles = 0;
        int dropped = 0;
        for (IngestSource source : documents) {
            String text = TxtNormalizer.normalize(source.text());
            if (text.isBlank()) {
                log.warn("[markdown切片] 文件[{}]正文为空，跳过", safeFileName(source));
                continue;
            }
            MarkdownReader.MarkdownParseResult parsed = MarkdownReader.read(text);
            if (parsed.blocks().isEmpty()) {
                log.warn("[markdown切片] 文件[{}]没有解析出任何内容块，跳过", safeFileName(source));
                continue;
            }
            SplitResult one = splitter.splitWithStats(source, parsed.blocks(), parsed.dropped());
            all.addAll(one.segments());
            titles += one.sections();
            dropped += one.droppedLines();
        }
        return new SplitResult(all, titles, dropped);
    }

    @Override
    public List<String> supportedExtensions() {
        return MARKDOWN_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "Markdown类型";
    }
}
