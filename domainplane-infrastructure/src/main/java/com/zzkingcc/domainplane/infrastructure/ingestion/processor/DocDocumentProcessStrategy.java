package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockChunking;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockSplitter;
import com.zzkingcc.domainplane.infrastructure.ingestion.doc.DocReader;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 旧版 Word（{@code .doc}，Word 97–2003）文档处理策略。
 *
 * 格式适配用 POI 的 HWPF 直读，之后交给通用的 {@link BlockSplitter} —— 与 docx 策略同形，
 * 差别只在"标题层级从哪来"：docx 读 {@code Heading N} 样式，doc 读样式名 + 大纲级别。
 *
 * 为什么接这个格式：HWPF 的段落样式名不可靠（是本地化的：俄文文档里
 * {@code Normal} 叫 {@code Базовый}），但 {@code Paragraph.getLvl()} 给的是语言无关的大纲级别，
 * {@code Heading 1} 的段落返回 {@code 0}、正文返回 {@code 9}。加上"样式名能用就用"这一路，
 * 标题层级是拿得到的，所以 {@code .doc} 也能接进来。
 *
 * 代价要说清楚：老 .doc 绝大多数段落是 {@code Normal}（7 个真实样例里只有 1 个带标题样式），
 * 这类文档整篇落进一个 {@code section_path}（= 文件名），靠 {@code chunk_seq} 保序 ——
 * 与"没有标题的 docx"表现完全一致。
 *
 * @author zzkingcc
 */
@Slf4j
public class DocDocumentProcessStrategy extends AbstractDocumentProcessStrategy {

    public static final List<String> DOC_EXTENSIONS = List.of("doc");

    @Override
    protected SplitResult splitDocuments(List<IngestSource> documents) {
        BlockSplitter splitter = BlockChunking.newSplitter();
        List<TextSegment> all = new ArrayList<>();
        int titles = 0;
        int dropped = 0;
        for (IngestSource source : documents) {
            byte[] raw = source.content();
            if (raw == null || raw.length == 0) {
                log.warn("[doc切片] 文件[{}]内容为空，跳过", safeFileName(source));
                continue;
            }
            DocReader.DocParseResult parsed = DocReader.read(raw);
            if (parsed.blocks().isEmpty()) {
                // 整篇只有图片 / 文本框的 .doc 落到这里 —— 明确报错，不要"导入成功、0 片"
                throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                        "文件 " + safeFileName(source) + " 没有解析出任何文字内容"
                                + "（可能整篇是图片，或正文都在文本框里），请提供文字版");
            }
            SplitResult one = splitter.splitWithStats(source, parsed.blocks(), parsed.dropped());
            all.addAll(one.segments());
            titles += one.sections();
            dropped += one.droppedLines();
        }
        return new SplitResult(all, titles, dropped);
    }

    /** 旧版 doc 是 OLE2 二进制：入口不解码，原始字节交给 HWPF */
    @Override
    public boolean binary() {
        return true;
    }

    @Override
    public List<String> supportedExtensions() {
        return DOC_EXTENSIONS;
    }

    @Override
    public String strategyName() {
        return "Word旧版类型";
    }
}
