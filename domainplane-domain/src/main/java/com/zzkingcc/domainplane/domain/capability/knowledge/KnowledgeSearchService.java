package com.zzkingcc.domainplane.domain.capability.knowledge;

import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.common.util.LogSanitizer;
import com.zzkingcc.domainplane.domain.rag.model.RetrievalScoreKeys;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索能力服务
 *
 * @author zzkingcc
 */
@Component
public class KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);

    private final ContentRetriever contentRetriever;

    public KnowledgeSearchService(@Qualifier("myContentRetriever") ContentRetriever contentRetriever) {
        this.contentRetriever = contentRetriever;
    }

    /**
     * 从知识库检索相关内容
     *
     * @param keyword 检索关键词或问题
     * @return 检索到的知识库内容片段(拼接为纯文本),无结果时返回提示
     */
    public String searchKnowledgeBase(String keyword) {
        // keyword 就是用户提问原文：DESIGN §13 的敏感红线禁止用户消息全文入日志，只记长度
        log.info("[知识库检索] 收到检索请求，关键词 {}", LogSanitizer.describeUserText(keyword));
        try {
            List<Content> contents = contentRetriever.retrieve(new Query(keyword));
            if (contents == null || contents.isEmpty()) {
                log.info("[知识库检索] 未检索到相关内容");
                return "未检索到相关内容";
            }
            // 两道收口：条数上限（可配）+ 正文字符预算。
            // 条数不是真正的瓶颈 —— 同样的条数，切片长短能差几倍；按字符收口才控得住上下文占用。
            int limit = Math.min(contents.size(), RetrievalLimits.injectTopN());
            int budget = RetrievalLimits.maxContextChars();
            StringBuilder sb = new StringBuilder();
            int used = 0;
            int injected = 0;
            for (int i = 0; i < limit; i++) {
                Content c = contents.get(i);
                String body = c.textSegment().text();
                // 首条无论如何都收 —— 否则预算配得偏小时一条都给不出去，检索形同关闭
                if (injected > 0 && used + body.length() > budget) {
                    break;
                }
                used += body.length();
                injected++;

                sb.append("【片段").append(injected);
                Double fused = readScore(c, RetrievalScoreKeys.FUSED_SCORE);
                if (fused != null) {
                    sb.append("｜相关度 ").append(String.format("%.2f", fused));
                }
                sb.append("】\n");
                String fileName = c.textSegment().metadata().getString(ChunkMetadataKeys.FILE_NAME);
                String sectionTitle = c.textSegment().metadata().getString(ChunkMetadataKeys.SECTION_TITLE);
                if (fileName != null) {
                    sb.append("来源：").append(fileName);
                    if (sectionTitle != null && !sectionTitle.isBlank()) {
                        sb.append(" > ").append(sectionTitle);
                    }
                    sb.append("\n");
                }
                sb.append(body).append("\n\n");
            }
            String result = sb.toString();
            log.info("[知识库检索] 检索到 {} 条相关内容，注入 {} 条（约 {} 字，预算 {}）",
                    contents.size(), injected, used, budget);
            return result;
        } catch (Exception e) {
            // 走到这里说明检索链路本身有问题（上面返回空列表那条分支才是"没命中"）。
            // 可能是检索引擎未配置（该去管控台补），也可能是暂时故障——域层拿不到区分二者的错误码
            log.error("[知识库检索] 检索异常：{}", e.getMessage(), e);
            return "知识库检索服务当前不可用：可能是检索服务尚未配置，也可能是暂时故障。"
                    + "请确认知识库配置后再试。";
        }
    }

    /** 读取融合阶段回写的分数 */
    private Double readScore(Content content, String key) {
        try {
            return content.textSegment().metadata().getDouble(key);
        } catch (Exception e) {
            return null;
        }
    }
}
