package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文档策略匹配工厂
 * @author zzkingcc
 */
@Slf4j
public class DocumentProcessStrategyFactory {

    /** 已注册策略实例 */
    private static final List<DocumentProcessStrategy> ALL_STRATEGIES = List.of(
            new TextDocumentProcessStrategy(),          // .txt
            new MarkdownDocumentProcessStrategy(),      // .md .markdown
            new DocxDocumentProcessStrategy(),          // .docx
            new DocDocumentProcessStrategy(),           // .doc（Word 97-2003）
            new PdfDocumentProcessStrategy(),           // .pdf
            new ExcelDocumentProcessStrategy(),         // .xls .xlsx
            UnknownDocumentProcessStrategy.INSTANCE     // 未知文件类型
    );

    /** 扩展名 → 策略 的映射缓存 */
    private static final Map<String, DocumentProcessStrategy> EXT_TO_STRATEGY;

    static {
        EXT_TO_STRATEGY = new HashMap<>();
        for (DocumentProcessStrategy strategy : ALL_STRATEGIES) {
            for (String ext : strategy.supportedExtensions()) {
                String lowerExt = ext.toLowerCase(Locale.ROOT);
                DocumentProcessStrategy prev = EXT_TO_STRATEGY.put(lowerExt, strategy);
                if (prev != null) {
                    throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_STRATEGY_NOT_FOUND,
                            "[策略工厂] 扩展名冲突: ." + lowerExt + " 同时被 ["
                                    + prev.strategyName() + "] 和 [" + strategy.strategyName() + "] 覆盖，请检查 supportedExtensions() 列表。");
                }
            }
        }
    }

    /**
     * 根据文件名取扩展名匹配策略。
     */
    public static DocumentProcessStrategy resolve(String fileName) {
        //扩展名提取
        String ext = extractExtension(fileName);
        if (ext == null) {
            // 超出扩展名边界，使用 unknown 策略
            return UnknownDocumentProcessStrategy.INSTANCE;
        }
        DocumentProcessStrategy s = EXT_TO_STRATEGY.get(ext.toLowerCase(Locale.ROOT));
        //当有效扩展名未适配策略时，使用 unknown 策略
        if (s != null) return s;
        return UnknownDocumentProcessStrategy.INSTANCE;
    }

    /**
     * 按照文档划分构建各处理器扫描链。
     *
     * @param documents 待分组的文档列表
     * @return
     */
    public static Map<DocumentProcessStrategy, List<IngestSource>> groupByStrategy(List<IngestSource> documents) {
        Map<DocumentProcessStrategy, List<IngestSource>> group = new LinkedHashMap<>();
        for (DocumentProcessStrategy s : ALL_STRATEGIES) {
            group.put(s, new ArrayList<>());
        }

        for (IngestSource doc : documents) {
            group.get(resolve(doc.fileName())).add(doc);
        }

        // 移除空组，减少上层循环输出
        group.entrySet().removeIf(e -> e.getValue().isEmpty());
        return group;
    }

    /**
     * 所有已注册的策略列表
     */
    public static List<DocumentProcessStrategy> allStrategies() {
        return ALL_STRATEGIES;
    }


    //扩展名提取
    private static String extractExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return fileName.substring(dot + 1);
    }
}
