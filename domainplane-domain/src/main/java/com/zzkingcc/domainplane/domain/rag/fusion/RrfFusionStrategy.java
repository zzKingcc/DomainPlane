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
 * RRF（Reciprocal Rank Fusion）融合策略 —— <b>多索引</b>召回时使用。
 *
 * <p>为什么多索引必须换它：BM25 的 idf 用<b>本索引（本分片）</b>的文档频率计算，
 * 同一个词在不同索引里量纲不同 —— 把多索引的 BM25 分池化后做 min-max 只会把
 * 「小索引里稀有词被抬高的分」当成 max，把其余结果压扁。向量分虽可比，但同一轮里
 * 两路量纲也不同。RRF 只看<b>名次</b>，天然免疫这一切。</p>
 *
 * <p>公式：{@code score(d) = Σ_L (w_L / (k + rank_L(d)))}。其中：</p>
 * <ul>
 *   <li><b>权重按模态均摊</b>：每张向量表的基准 {@code w = vectorWeight / 向量表个数}，
 *       每张关键词表 {@code w = keywordWeight / 关键词表个数} —— 这样 向量:关键词 的比例
 *       不随域链长度（索引个数）漂移，否则祖先越多、向量表越多，BM25 会被越压越扁。
 *       分摊只统计<b>有命中</b>的表，空表不占份额。</li>
 *   <li><b>层级衰减</b>：表的基准权重再乘 {@code ancestorDecay ^ depth}（depth = 距查询域的距离，
 *       见 {@link RankedList#depth()}），同模态内重新归一化 → 总量守恒，但本域自有知识优先于
 *       从祖先继承来的知识。{@code ancestorDecay=1.0} 即等权。</li>
 *   <li><b>标题 / 文件名增益是乘法</b>：{@code 分 × (1 + boost)}。RRF 的值域是
 *       {@code w/(k+rank)}（单表名次 1 约 0.01 量级），若沿用加法，"0.15 分" 会变成它的十几倍，
 *       排序直接被 boost 统治、且域链越长越严重。乘法在分数制与排名制下语义一致。</li>
 * </ul>
 *
 * @author zzkingcc
 */
public class RrfFusionStrategy implements FusionStrategy {

    private static final Logger log = LoggerFactory.getLogger(RrfFusionStrategy.class);

    /** 单张排名表最多计入的名次，防长尾把常量 k 的平滑效果冲淡 */
    private static final int MAX_RANK = 200;

    @Override
    public List<Content> fuse(String queryText,
                              List<RankedList> lists,
                              FusionConfig fusion) {
        long start = System.currentTimeMillis();

        // 只有"有命中"的表才参与权重分摊 —— 空表若也占份额，权重就摊给了空气，
        // 该模态剩下的表反而被压低（祖先域还没建索引时会频繁触发）。
        List<RankedList> active = (lists == null ? List.<RankedList>of() : lists).stream()
                .filter(l -> l != null && !l.isEmpty())
                .toList();

        double decay = normalizeDecay(fusion.ancestorDecay());
        double vectorScale = scaleOf(active, Modality.VECTOR, decay);
        double keywordScale = scaleOf(active, Modality.KEYWORD, decay);
        int k = Math.max(fusion.rrfK(), 1);

        // 查询候选词整轮只切一次：中文查询会展开出上千个 bigram，
        // 放进每条 ScoreEntry 的构造里重算就是「条数 × 上千次字符串分配」。
        Set<String> queryKeywords = FusionSupport.keywords(queryText);

        Map<String, ScoreEntry> scoreMap = new LinkedHashMap<>();
        for (RankedList list : active) {
            boolean vector = list.modality() == Modality.VECTOR;
            double scale = vector ? vectorScale : keywordScale;
            if (scale <= 0.0) {
                continue;
            }
            double perList = (vector ? fusion.vectorWeight() : fusion.keywordWeight())
                    * weightOf(decay, list.depth()) / scale;

            int rank = 0;
            for (Content c : list.contents()) {
                rank++;
                if (rank > MAX_RANK) {
                    break;
                }
                String hash = FusionSupport.hashContent(c);
                ScoreEntry entry = scoreMap.computeIfAbsent(hash, x -> new ScoreEntry(c, queryKeywords, fusion));
                entry.rrf += perList / (k + rank);
                double raw = FusionSupport.extractScore(c);
                if (vector) {
                    entry.vectorScore = entry.vectorScore == null ? raw : Math.max(entry.vectorScore, raw);
                } else {
                    entry.keywordScore = entry.keywordScore == null ? raw : Math.max(entry.keywordScore, raw);
                }
            }
        }

        if (scoreMap.isEmpty()) {
            log.info("[RRF融合] 无命中结果,耗时 {}ms", System.currentTimeMillis() - start);
            return new ArrayList<>();
        }

        List<ScoreEntry> entries = new ArrayList<>(scoreMap.values());
        for (ScoreEntry e : entries) {
            double score = e.rrf;
            if (FusionSupport.titleHit(e.content, e.queryKeywords)) {
                score *= 1.0 + e.fusion.titleBoost();
            }
            if (FusionSupport.fileNameHit(e.content, e.queryKeywords)) {
                score *= 1.0 + e.fusion.fileNameBoost();
            }
            e.fusedScore = score;
        }

        entries.sort(byFusedScoreDesc());
        int topN = Math.min(Math.max(fusion.topN(), 1), entries.size());

        List<Content> result = IntStream.range(0, topN)
                .mapToObj(i -> withScores(entries.get(i), i + 1))
                .collect(Collectors.toList());

        log.info("[RRF融合] 表数={}(向量{} / 关键词{}),去重后{}条,融合重排Top{},k={},衰减={},耗时{}ms",
                active.size(), countOf(active, Modality.VECTOR), countOf(active, Modality.KEYWORD),
                scoreMap.size(), topN, k, decay, System.currentTimeMillis() - start);
        return result;
    }

    /** 融合分降序；同分按原文顺序（chunk_seq）稳定排序，保证结果可复现 */
    private static Comparator<ScoreEntry> byFusedScoreDesc() {
        return Comparator.comparingDouble((ScoreEntry e) -> e.fusedScore).reversed()
                .thenComparingInt(e -> FusionSupport.chunkSeq(e.content))
                .thenComparing(e -> FusionSupport.hashContent(e.content));
    }

    /** 衰减系数收敛到 (0,1]：非法值（NaN / ≤0）按 1.0（不衰减）处理 */
    private static double normalizeDecay(double ancestorDecay) {
        if (Double.isNaN(ancestorDecay) || ancestorDecay <= 0.0) {
            return 1.0;
        }
        return Math.min(ancestorDecay, 1.0);
    }

    private static double weightOf(double decay, int depth) {
        return Math.pow(decay, Math.max(depth, 0));
    }

    /** 某模态全部有效表的衰减权重之和（分母），用于把衰减后的权重重新归一化到总量守恒 */
    private static double scaleOf(List<RankedList> lists, Modality modality, double decay) {
        return lists.stream()
                .filter(l -> l.modality() == modality)
                .mapToDouble(l -> weightOf(decay, l.depth()))
                .sum();
    }

    private static long countOf(List<RankedList> lists, Modality modality) {
        return lists.stream().filter(l -> l.modality() == modality).count();
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
        meta.put(RetrievalScoreKeys.FUSED_SCORE, e.fusedScore);
        meta.put(RetrievalScoreKeys.FUSION_RANK, rank);
        meta.put(RetrievalScoreKeys.MATCH_CHANNEL,
                FusionSupport.channel(e.vectorScore != null, e.keywordScore != null));

        return Content.from(TextSegment.from(source.text(), Metadata.from(meta)));
    }

    /** 单条结果的 RRF 累计分记录 */
    private static class ScoreEntry {
        final Content content;
        final Set<String> queryKeywords;
        final FusionConfig fusion;
        Double vectorScore;
        Double keywordScore;
        double rrf;
        double fusedScore;

        ScoreEntry(Content content, Set<String> queryKeywords, FusionConfig fusion) {
            this.content = content;
            this.queryKeywords = queryKeywords;
            this.fusion = fusion;
        }
    }
}
