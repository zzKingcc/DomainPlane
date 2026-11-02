package com.zzkingcc.domainplane.infrastructure.ingestion.txt;

import java.util.List;

/**
 * 切片器：把一个小节的正文行打包成若干切片正文。
 *
 * {@link Chunker} 按句子长度打包（结构感知，切点落在标题/句号上，字节安全）；
 * {@link SemanticChunker} 按句子嵌入相似度打包（语义感知，需 {@link EmbeddingModel}）。
 * 两者同签，由运行期开关二选一：
 *
 * <ul>
 *   <li>结构感知（默认）：保证标题路径、代码块、表格完整性，切片长度受 {@code maxChars} 硬上限；</li>
 *   <li>语义分块（F05 P2，互补，默认关闭）：在标题已切好的小节内部，再按句子嵌入向量的相邻
 *       余弦相似度切分，把主题相近的句子留在同一片、主题转折处断开；</li>
 * </ul>
 *
 * 两者都不跨标题（标题层由上层已经切好），也不触代码块/表格（不可切块由上层独占处理）。
 * 默认关闭时行为与改造前一字不变。
 *
 * @author zzkingcc
 */
public interface TextChunker {

    /**
     * 把一个小节的正文行切成若干切片正文。
     *
     * @param bodyLines 该小节的正文行（不含标题行）
     * @return 切片正文列表；无内容时返回空列表
     */
    List<String> chunk(List<String> bodyLines);
}
