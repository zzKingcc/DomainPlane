package com.zzkingcc.domainplane.domain.rag.fusion;

import com.zzkingcc.domainplane.domain.rag.model.Modality;
import com.zzkingcc.domainplane.domain.rag.model.RankedList;
import com.zzkingcc.domainplane.domain.rag.model.RetrievalScoreKeys;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 默认融合策略（分数制）—— 单来源场景使用（一域一索引时即"只查到本域或某个祖先的索引"）：
 *
 *
 *   • 把多份排名表按模态拼回两路（向量 / 关键词），保持各表内部顺序；
 *   • 去重：按文本内容 SHA-256 判同一条；两路都命中只记一次，但两个通道分都保留
 *   • 归一化：向量分 / 关键词分各自做 min-max（单路缺失按 0 算）
 *   • 加权：{@code 向量权重×归一化向量分 + 关键词权重×归一化关键词分}，再乘标题 / 文件名增益
 *   • 重排：按融合分降序（同分按 chunk_seq），取 TopN，回写分项分 / 融合分 / 名次 / 命中通道
 *
 *
 * 只在"实际有命中的来源数 ≤ 1"时被选中（见 {@link AdaptiveFusionStrategy}）：此时所有表同源，
 * BM25 分数在同一索引内可比，分数制才成立。多来源场景由 {@link RrfFusionStrategy} 承担。
 * 也因为是单来源，本策略不使用 {@code RankedList#depth}（层级衰减无意义）。
 *
 * @author zzkingcc
 */
public class DefaultFusionStrategy implements FusionStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultFusionStrategy.class);

    @Override
    public List<Content> fuse(String queryText,
                             List<RankedList> lists,
                             FusionConfig fusion) {
        long start = System.currentTimeMillis();

        List<Content> vector = new ArrayList<>();
        List<Content> keyword = new ArrayList<>();
        if (lists != null) {
            for (RankedList l : lists) {
                if (l == null) {
                    continue;
                }
                if (l.modality() == Modality.VECTOR) {
                    vector.addAll(l.contents());
                } else if (l.modality() == Modality.KEYWORD) {
                    keyword.addAll(l.contents());
                }
            }
        }

        // 查询候选词整轮只切一次：中文查询会展开出上千个 bigram，
        // 放进每条 ScoreEntry 的构造里重算就是「条数 × 上千次字符串分配」。
        Set<String> queryKeywords = FusionSupport.keywords(queryText);

        // 合并去重 + 记录分数来源
        Map<String, ScoreEntry> scoreMap = new LinkedHashMap<>();

        for (Content c : vector) {
            String hash = FusionSupport.hashContent(c);
            double score = FusionSupport.extractScore(c);
            ScoreEntry existing = scoreMap.get(hash);
            if (existing == null) {
                ScoreEntry entry = new ScoreEntry(c, queryKeywords, fusion);
                entry.vectorScore = score;
                scoreMap.put(hash, entry);
            } else {
                // 取最大值而不是覆盖：同一去重键出现多次时，覆盖会让分数随列表顺序变化，
                // 与 RRF 那套口径也不一致（那边取的是排名最优）。
                existing.vectorScore = Math.max(existing.vectorScore, score);
            }
        }

        int keywordAdded = 0;
        for (Content c : keyword) {
            String hash = FusionSupport.hashContent(c);
            double score = FusionSupport.extractScore(c);
            ScoreEntry exist = scoreMap.get(hash);
            if (exist != null) {
                // keywordScore 为 null 表示"该条没被关键词通道命中过"，不能直接参与 Math.max
                // （拆箱即 NPE）。取最大值而不是覆盖：同一条被关键词通道命中多次时，
                // 覆盖会让分数随列表顺序变化，与 RRF 那套"取排名最优"的口径也不一致。
                exist.keywordScore = exist.keywordScore == null
                        ? score
                        : Math.max(exist.keywordScore, score);
            } else {
                ScoreEntry entry = new ScoreEntry(c, queryKeywords, fusion);
                entry.keywordScore = score;
                scoreMap.put(hash, entry);
                keywordAdded++;
            }
        }

        if (scoreMap.isEmpty()) {
            log.info("[融合] 无命中结果,耗时 {}ms", System.currentTimeMillis() - start);
            return new ArrayList<>();
        }

        List<ScoreEntry> entries = new ArrayList<>(scoreMap.values());
        normalize(entries);
        computeFusedScores(entries);

        // 融合分降序；同分按原文顺序（chunk_seq）稳定排序，保证同一问题两次检索结果一致
        entries.sort(Comparator.comparingDouble((ScoreEntry e) -> e.fusedScore).reversed()
                .thenComparingInt(e -> FusionSupport.chunkSeq(e.content))
                .thenComparing(e -> FusionSupport.hashContent(e.content)));
        int topN = Math.min(Math.max(fusion.topN(), 1), entries.size());

        List<Content> result = IntStream.range(0, topN)
                .mapToObj(i -> withScores(entries.get(i), i + 1))
                .collect(Collectors.toList());

        log.info("[融合] 向量命中{}条,关键词命中{}条(新增{}条),去重后{}条,融合重排Top{},耗时{}ms",
                vector.size(), keyword.size(), keywordAdded,
                scoreMap.size(), topN, System.currentTimeMillis() - start);

        return result;
    }

    // ==================== 以下为复刻的融合算法细节 ====================

    /**
     * 对向量分数和关键词分数分别做 min-max 归一化
     */
    private void normalize(List<ScoreEntry> entries) {
        normalizeChannel(entries, true);
        normalizeChannel(entries, false);
    }

    private void normalizeChannel(List<ScoreEntry> entries, boolean vector) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (ScoreEntry e : entries) {
            Double v = vector ? e.vectorScore : e.keywordScore;
            if (v != null) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        for (ScoreEntry e : entries) {
            Double v = vector ? e.vectorScore : e.keywordScore;
            if (v == null) {
                continue;
            }
            double norm = (max == min) ? 1.0 : (v - min) / (max - min);
            if (vector) {
                e.normVectorScore = norm;
            } else {
                e.normKeywordScore = norm;
            }
        }
    }

    /**
     * 计算融合分数：加权求和 × 标题/文件名增益
     *
     * 增益是乘法（{@code 分 × (1 + boost)}），与 {@link RrfFusionStrategy} 语义一致。
     * 加法 boost 在本轨（归一化分 0~1）看着合理，但在 RRF 轨（分约 0.01 量级）会变成十几倍、
     * 直接统治排序：同一个常量在两轨下含义不同是隐性坑，故统一为乘法。
     */
    private void computeFusedScores(List<ScoreEntry> entries) {
        for (ScoreEntry e : entries) {
            double fused = e.fusion.vectorWeight() * (e.normVectorScore != null ? e.normVectorScore : 0.0)
                    + e.fusion.keywordWeight() * (e.normKeywordScore != null ? e.normKeywordScore : 0.0);

            if (FusionSupport.titleHit(e.content, e.queryKeywords)) {
                fused *= 1.0 + e.fusion.titleBoost();
            }
            if (FusionSupport.fileNameHit(e.content, e.queryKeywords)) {
                fused *= 1.0 + e.fusion.fileNameBoost();
            }

            e.fusedScore = fused;
        }
    }

    /**
     * 把分项分、融合分、名次、命中通道回写到 metadata
     */
    private Content withScores(ScoreEntry e, int rank) {
        TextSegment source = e.content.textSegment();
        Map<String, Object> meta = new LinkedHashMap<>(source.metadata().toMap());

        if (e.vectorScore != null) {
            meta.put(RetrievalScoreKeys.VECTOR_SCORE, e.vectorScore);
        }
        if (e.keywordScore != null) {
            meta.put(RetrievalScoreKeys.KEYWORD_SCORE, e.keywordScore);
        }
        if (e.normVectorScore != null) {
            meta.put(RetrievalScoreKeys.NORM_VECTOR_SCORE, e.normVectorScore);
        }
        if (e.normKeywordScore != null) {
            meta.put(RetrievalScoreKeys.NORM_KEYWORD_SCORE, e.normKeywordScore);
        }
        meta.put(RetrievalScoreKeys.FUSED_SCORE, e.fusedScore);
        meta.put(RetrievalScoreKeys.FUSION_RANK, rank);
        meta.put(RetrievalScoreKeys.MATCH_CHANNEL,
                FusionSupport.channel(e.vectorScore != null, e.keywordScore != null));

        return Content.from(TextSegment.from(source.text(), Metadata.from(meta)));
    }

    /**
     * 单条检索结果的分数记录,用于融合计算与回写
     */
    private static class ScoreEntry {
        final Content content;
        final Set<String> queryKeywords;
        final FusionConfig fusion;
        Double vectorScore;
        Double keywordScore;
        Double normVectorScore;
        Double normKeywordScore;
        double fusedScore;

        ScoreEntry(Content content, Set<String> queryKeywords, FusionConfig fusion) {
            this.content = content;
            this.queryKeywords = queryKeywords;
            this.fusion = fusion;
        }
    }
}
