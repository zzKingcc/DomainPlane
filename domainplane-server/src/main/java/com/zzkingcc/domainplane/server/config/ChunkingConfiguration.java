package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.infrastructure.ingestion.IngestLimits;
import com.zzkingcc.domainplane.infrastructure.ingestion.block.BlockChunking;
import com.zzkingcc.domainplane.infrastructure.ingestion.txt.SemanticChunking;
import com.zzkingcc.domainplane.infrastructure.ingestion.txt.TxtChunking;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code domainplane.rag.chunking.*} 与导入限制注入切片器的全局取值点。
 *
 * 需要这一层是因为策略工厂是静态的、策略实例是无参构造的单例，拿不到 Spring 的配置对象。
 *
 * 两套切片器取同一份参数：{@code TxtChunking}（纯文本管线）与
 * {@code BlockChunking}（md / docx / pdf 的结构化管线）；语义分块器（F05 P2）复用同一
 * {@link EmbeddingModel} bean，由 {@link SemanticChunking} 持有。
 *
 * @author zzkingcc
 */
@Slf4j
@Configuration
public class ChunkingConfiguration {

    public ChunkingConfiguration(RagProperties ragProperties, EmbeddingModel embeddingModel) {
        RagProperties.Chunking chunking = ragProperties.getChunking();
        TxtChunking.configure(chunking.getMaxChars(), chunking.getOverlapSentences(), chunking.getMinChars());
        BlockChunking.configure(chunking.getMaxChars(), chunking.getOverlapSentences(), chunking.getMinChars());
        IngestLimits.configure(ragProperties.getMaxChunksPerDocument());
        SemanticChunking.configure(embeddingModel, chunking.isSemanticEnabled(),
                chunking.getSemanticSimilarityThreshold(), chunking.getSemanticMinSentences());
        log.info("[知识库] 切片参数已就绪：max-chars={}，overlap-sentences={}，min-chars={}，单文件切片上限={}，" +
                        "语义分块={}，相似度阈值={}，最小句数={}",
                chunking.getMaxChars(), chunking.getOverlapSentences(), chunking.getMinChars(),
                ragProperties.getMaxChunksPerDocument(), chunking.isSemanticEnabled(),
                chunking.getSemanticSimilarityThreshold(), chunking.getSemanticMinSentences());
    }
}
