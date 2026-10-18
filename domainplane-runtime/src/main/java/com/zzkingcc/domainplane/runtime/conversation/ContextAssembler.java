package com.zzkingcc.domainplane.runtime.conversation;

import com.zzkingcc.domainplane.api.conversation.HistoryCard;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.api.conversation.TurnFlags;
import com.zzkingcc.domainplane.common.util.CjkWidth;
import com.zzkingcc.domainplane.common.util.TextClip;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 召回装配：把「系统提示 + 早期摘要 + 相关轮卡片 + 近况窗口 + 本轮提问」拼成请求消息序列。
 *
 * <p>与 {@link TurnRecorder}（写侧）、{@link TurnView}（读侧渲染）分工：这边只做
 * <b>预算与取舍</b>，不碰存储、不调模型。全部是纯函数，装配策略是"这轮给模型看多少"的
 * 唯一决策点，值得有测试盯着。</p>
 *
 * <p><b>三条不可让的边界</b>：</p>
 * <ul>
 *   <li><b>永不拒绝开轮</b>：装不下就按 {@link #DEGRADE} 降级，不存在"会话满了"这个状态。
 *       原封顶语义（记忆到上限即拒绝、会话作废）在 M2 移除，见方案 §5.3 / §8；</li>
 *   <li><b>本轮提问绝不丢</b>：任何降级都以它为底线，最坏情况也只退化成
 *       "系统提示 + 最近 2 轮 + 本轮提问"；</li>
 *   <li><b>系统提示不进预算</b>：它是"这次用哪个域"的资产，不是记忆。</li>
 * </ul>
 *
 * <p>摘要段与卡片段走 {@link UserMessage} 而不是塞进系统提示：系统提示保持"纯域函数"
 * （缓存 / 审计口径不动），平台注入的召回内容放用户消息段，与附件清单同一套路。</p>
 *
 * @author zzkingcc
 */
public final class ContextAssembler {

    /** 总装配预算（token）：取代原 30000 封顶的角色，只约束"这轮给模型看多少" */
    public static final int TOTAL_BUDGET = 24_000;

    /** 近况窗口预算占总预算的比例 */
    public static final double WINDOW_SHARE = 0.6;

    /** 滚动摘要预算 */
    public static final int SUMMARY_BUDGET = 2_000;

    /** 相关轮卡片默认条数 */
    public static final int MAX_CARDS = 5;

    /** 近况窗口默认轮数（一轮 = 提问 + 回答两条消息） */
    public static final int WINDOW_TURNS = 8;

    /** 摘要段最多带多少条老轮次的一句话摘要 */
    public static final int SUMMARY_MAX_TURNS = 40;

    /** 相对时间的天数分界：超过它显示日期而不是"今天 / 昨天" */
    private static final long RELATIVE_DAYS = 6;

    private final int totalBudget;
    private final int windowBudget;
    private final int summaryBudget;
    private final int windowTurns;
    private final int maxCards;

    public ContextAssembler() {
        this(TOTAL_BUDGET, (int) (TOTAL_BUDGET * WINDOW_SHARE), SUMMARY_BUDGET, WINDOW_TURNS, MAX_CARDS);
    }

    public ContextAssembler(int totalBudget, int windowBudget, int summaryBudget,
                            int windowTurns, int maxCards) {
        this.totalBudget = Math.max(1, totalBudget);
        this.windowBudget = Math.max(1, windowBudget);
        this.summaryBudget = Math.max(1, summaryBudget);
        this.windowTurns = Math.max(1, windowTurns);
        this.maxCards = Math.max(0, maxCards);
    }

    /**
     * 装配一轮请求的消息序列。
     *
     * @param system  系统提示（域资产，不进预算）；{@code null} / 空白则不加这一段
     * @param window  近况窗口候选消息，<b>调用方须已按 {@link #windowTurns()} 取好尾部</b>；
     *                这里只按 token 继续收紧，不再按轮数补取
     * @param summary 早期轮次的一句话摘要拼接（可空）；超出预算时从最老的一端截
     * @param cards   相关轮卡片，调用方按相关度从高到低排（可空）；预算不够时从末位开始砍
     * @param current 本轮提问，永不丢弃
     * @return 完整消息序列；窗口为空时至少返回 {@code [system?, current]}
     */
    public List<ChatMessage> assemble(SystemMessage system,
                                      List<ChatMessage> window,
                                      String summary,
                                      List<HistoryCard> cards,
                                      UserMessage current) {
        List<ChatMessage> out = new ArrayList<>();
        if (system != null && system.text() != null && !system.text().isBlank()) {
            out.add(system);
        }

        // 顺序即优先级：先放最可能被砍的两段，再放窗口与提问。
        // 预算分配是"各自管各自的上限"，不是三者抢一个池子，这样任一段超限都只波及自己，
        // 不会把窗口挤到只剩两条（那才是真的把历史扔了）。
        int remaining = totalBudget - tokensOf(current);
        if (remaining <= 0) {
            // 单条提问就顶满预算：只留系统提示与提问，其余全部让位
            out.add(current);
            return out;
        }

        if (summary != null && !summary.isBlank()) {
            int budget = Math.min(summaryBudget, remaining);
            String clipped = clipLines(summary, budget);
            if (!clipped.isBlank()) {
                out.add(UserMessage.from(TurnView.renderSummary(clipped)));
                remaining -= tokensOf(out.get(out.size() - 1));
            }
        }

        if (cards != null && !cards.isEmpty() && remaining > 0) {
            int budget = Math.min(remaining, maxCards * CARD_ESTIMATE_TOKENS);
            List<HistoryCard> kept = takeCards(cards, budget);
            if (!kept.isEmpty()) {
                out.add(UserMessage.from(TurnView.renderCards(kept)));
                remaining -= tokensOf(out.get(out.size() - 1));
            }
        }

        if (window != null && !window.isEmpty()) {
            int budget = Math.min(windowBudget, Math.max(remaining, MIN_WINDOW_TOKENS));
            List<ChatMessage> kept = takeWindow(window, budget);
            out.addAll(kept);
        }

        out.add(current);
        return out;
    }

    /**
     * 窗口按 token 收紧：<b>从最老的一端整轮丢</b>，不留半轮。
     *
     * <p>整轮丢是为了不让"有问无答"的提问留在窗口里，模型看到孤零零一个提问
     * 会顺着它答非所问。记忆的写入单位就是"提问 + 回答"两条，所以按 2 条一批丢。</p>
     *
     * <p>兜底：再怎么紧也保留最后 2 条（最近一轮），与原封顶语义的"最坏只留最近 2 轮"一致。</p>
     */
    private static List<ChatMessage> takeWindow(List<ChatMessage> window, int budget) {
        List<ChatMessage> all = new ArrayList<>(window);
        while (all.size() > MIN_WINDOW_MESSAGES && tokensOf(all) > budget) {
            // 从头部丢一整轮；丢不完（奇数条）时先丢单条，保证最终留下的是完整的尾轮
            int drop = Math.min(2, all.size() - MIN_WINDOW_MESSAGES);
            if (drop <= 0) {
                break;
            }
            all.subList(0, drop).clear();
        }
        // 仍然超预算（最近一轮自身就很大）：只保留最后 2 条，接受轻度超限，
        // 丢本轮提问或丢系统提示都不在选项里，而这两条已经在上游保住了
        while (all.size() > MIN_WINDOW_MESSAGES && tokensOf(all) > budget) {
            all.remove(0);
        }
        return all;
    }

    /** 卡片按预算从末位开始砍（调用方已按相关度排序） */
    private static List<HistoryCard> takeCards(List<HistoryCard> cards, int budget) {
        List<HistoryCard> kept = new ArrayList<>();
        int used = 0;
        for (HistoryCard card : cards) {
            int cost = tokensOf(TurnView.renderCards(List.of(card)));
            if (used + cost > budget && !kept.isEmpty()) {
                break;
            }
            kept.add(card);
            used += cost;
        }
        return kept;
    }

    /**
     * 摘要按行截断：<b>保留最近的、丢最老的</b>（与方案 §5.3 一致）。
     *
     * <p>按行而不是按字符切：一行 = 一条老轮次的一句话摘要，按字符切会切出半句话。</p>
     */
    static String clipLines(String summary, int budget) {
        List<String> lines = new ArrayList<>(List.of(summary.split("\n")));
        while (!lines.isEmpty() && tokensOf(String.join("\n", lines)) > budget) {
            lines.remove(0);
        }
        return String.join("\n", lines);
    }

    /** 一张卡片的粗估 token（约 80，见方案 §5.2） */
    private static final int CARD_ESTIMATE_TOKENS = 80;

    /** 窗口再怎么降也保留的消息条数（= 最近一轮） */
    private static final int MIN_WINDOW_MESSAGES = 2;

    /** 单条消息超预算时的硬底：至少留这么多 token 给窗口 */
    private static final int MIN_WINDOW_TOKENS = 512;

    static int tokensOf(ChatMessage message) {
        return CjkWidth.estimateTokens(TurnRecorder.clip(textOf(message), 8192)) + 4;
    }

    /** 纯文本的 token 估算（摘要段 / 卡片段按文本算，不再叠加消息结构开销） */
    static int tokensOf(String text) {
        return CjkWidth.estimateTokens(TurnRecorder.clip(text, 8192));
    }

    static int tokensOf(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage m : messages) {
            total += tokensOf(m);
        }
        return total;
    }

    private static String textOf(ChatMessage message) {
        if (message instanceof SystemMessage sm) {
            return sm.text();
        }
        if (message instanceof UserMessage um) {
            if (um.hasSingleText()) {
                return um.singleText();
            }
            StringBuilder sb = new StringBuilder();
            for (var c : um.contents()) {
                if (c instanceof dev.langchain4j.data.message.TextContent tc && tc.text() != null) {
                    sb.append(tc.text());
                }
            }
            return sb.toString();
        }
        if (message instanceof dev.langchain4j.data.message.AiMessage am) {
            return am.text() == null ? "" : am.text();
        }
        if (message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage tr) {
            return tr.text();
        }
        return "";
    }

    public int totalBudget() {
        return totalBudget;
    }

    /**
     * 复制一份、把总预算换成给定值（其余按比例同步收窄）。
     *
     * <p>用在"本轮模型上下文比配置预算更小"这一种情形上（见 {@link ContextBudget}）：
     * 装配实例是启动期按配置造好的单例，而模型是<b>按域、按请求</b>解析的，
     * 于是"给模型看多少"必须每轮重算一次。本类无状态、全是纯函数，复制一份的代价可以忽略。</p>
     *
     * <p><b>窗口段与摘要段按同一比例收窄</b>，而不是只换总预算让窗口段去吸收全部收缩：
     * 三个段各自有独立上限（见 {@link #assemble} 的说明），只改总预算的话，收缩会全部落到
     * 最后放入的窗口段上，总预算 2000 时摘要段能吃掉 1900，窗口只剩兜底的 512，
     * 与"摘要只是补缺口、窗口才是近况原文"的优先级正好相反。</p>
     *
     * <p>{@code windowTurns} 与 {@code maxCards} <b>不动</b>：前者是轮数上限、后者是条数上限，
     * 都不由 token 预算定义；条数在实际装配时仍会被预算进一步收紧。</p>
     */
    public ContextAssembler withTotalBudget(int newTotalBudget) {
        int total = Math.max(1, newTotalBudget);
        if (total == totalBudget) {
            return this;
        }
        double ratio = (double) total / totalBudget;
        return new ContextAssembler(total,
                (int) Math.round(windowBudget * ratio),
                (int) Math.round(summaryBudget * ratio),
                windowTurns,
                maxCards);
    }

    public int windowBudget() {
        return windowBudget;
    }

    public int summaryBudget() {
        return summaryBudget;
    }

    public int windowTurns() {
        return windowTurns;
    }

    public int maxCards() {
        return maxCards;
    }

    // ==================== 摘要段素材（M2：digest 拼接） ====================

    /**
     * 从归档轮记录里挑出"滑出窗口但还没进摘要"的那些，拼成摘要段素材。
     *
     * <p>M2 用<b>现成的 digest 拼接</b>，不调 LLM：digest 是闭轮时就地生成的
     * （问 60 字 + 答 140 字），足够当"老轮次压缩"用，且零成本零延迟。
     * M4 若要模型生成的滚动摘要，替换的就是这个方法的实现，装配层不用动。</p>
     *
     * <p>排除中止轮与开放轮：半截现场不是可引用内容（方案 §5.5 同一口径）。</p>
     *
     * @param records     归档尾部轮记录（由调用方取， oldest-first）
     * @param windowTurns 当前窗口轮数；尾部这么多轮不进摘要（它们在窗口里已经出现了）
     * @return 摘要文本；没有可摘要的老轮次时返回 {@code null}
     */
    public static String summaryOf(List<TurnRecord> records, int windowTurns) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        int skip = Math.max(0, records.size() - Math.max(0, windowTurns));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < skip && lines.size() < SUMMARY_MAX_TURNS; i++) {
            TurnRecord t = records.get(i);
            if (t == null || t.isOpen()) {
                continue;
            }
            if (t.flags() != null && t.flags().aborted()) {
                continue;
            }
            String digest = t.digest();
            if (digest == null || digest.isBlank()) {
                continue;
            }
            lines.add("第 " + t.turnId() + " 轮：" + digest);
        }
        if (lines.isEmpty()) {
            return null;
        }
        return String.join("\n", lines);
    }

    /**
     * 滚动摘要 + 尚未进摘要的"滑出窗口"轮次的 digest，拼成完整的摘要段素材（M4-R1）。
     *
     * <p>两段合一是刻意的：滚动摘要是模型生成的有损压缩，覆盖到 {@code upToTurnId}；
     * 但它天生滞后，更新是异步的，而且过了阈值才触发。所以"摘要之后又滑出窗口"的
     * 那几轮必须用现成的 digest 补上，否则这段历史在请求里会凭空消失
     * （既不在窗口里，也不在摘要里）。</p>
     *
     * <p>三种退化都对：没摘要 → 纯 digest 拼接（M2 行为）；没缺口 → 纯摘要；
     * 两者都没有 → {@code null}（装配层据此不加摘要段）。</p>
     *
     * @param rollingSummary 已存的滚动摘要正文；{@code null} / 空白视为没有
     * @param records        归档尾部轮记录（oldest-first）
     * @param windowTurns    当前窗口轮数；尾部这么多轮不进摘要（它们在窗口里已经出现了）
     * @param upToTurnId     滚动摘要已覆盖到的最大轮号
     */
    public static String summaryWithRolling(String rollingSummary, List<TurnRecord> records,
                                            int windowTurns, long upToTurnId) {
        String gap = summaryOfAfter(records, windowTurns, upToTurnId);
        boolean hasRolling = rollingSummary != null && !rollingSummary.isBlank();
        if (!hasRolling) {
            return gap;
        }
        return gap == null ? rollingSummary.trim() : rollingSummary.trim() + "\n" + gap;
    }

    /**
     * 与 {@link #summaryOf} 同一口径，但只收 {@code turnId > afterTurnId} 的那些轮：
     * 即"滑出窗口、且还没被滚动摘要覆盖"的缺口部分。
     */
    private static String summaryOfAfter(List<TurnRecord> records, int windowTurns, long afterTurnId) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        int skip = Math.max(0, records.size() - Math.max(0, windowTurns));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < skip && lines.size() < SUMMARY_MAX_TURNS; i++) {
            TurnRecord t = records.get(i);
            if (t == null || t.isOpen()) {
                continue;
            }
            if (t.flags() != null && t.flags().aborted()) {
                continue;
            }
            if (t.turnId() <= afterTurnId) {
                continue;
            }
            String digest = t.digest();
            if (digest == null || digest.isBlank()) {
                continue;
            }
            lines.add("第 " + t.turnId() + " 轮：" + digest);
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /**
     * 把归档轮记录转成相关轮卡片（M2 尚无倒排索引，调用方按轮号倒序喂进来即可）。
     *
     * <p>卡片不含全文，工具名去重后按出现序排列（{@code LinkedHashSet}）。</p>
     */
    public static List<HistoryCard> cardsOf(List<TurnRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<HistoryCard> out = new ArrayList<>(records.size());
        for (TurnRecord t : records) {
            if (t == null || t.isOpen()) {
                continue;
            }
            if (t.flags() != null && t.flags().aborted()) {
                continue;
            }
            Set<String> tools = new LinkedHashSet<>();
            for (var e : t.flow()) {
                if (e.type() == com.zzkingcc.domainplane.api.conversation.FlowEventType.TOOL_CALL
                        && e.toolName() != null) {
                    tools.add(e.toolName());
                }
            }
            out.add(new HistoryCard(t.turnId(), t.startedAt(), t.digest(), List.copyOf(tools)));
        }
        return out;
    }

    /** 卡片上的相对时间："今天 HH:mm / 昨天 HH:mm / N 天前 / MM-dd" */
    static String relativeTime(Instant at, Instant now) {
        if (at == null) {
            return "";
        }
        java.time.LocalDate date = at.atZone(ZoneId.systemDefault()).toLocalDate();
        java.time.LocalDate today = now.atZone(ZoneId.systemDefault()).toLocalDate();
        long days = java.time.temporal.ChronoUnit.DAYS.between(date, today);
        String hm = DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault()).format(at);
        if (days <= 0) {
            return "今天 " + hm;
        }
        if (days == 1) {
            return "昨天 " + hm;
        }
        if (days <= RELATIVE_DAYS) {
            return days + " 天前";
        }
        return DateTimeFormatter.ofPattern("MM-dd")
                .withZone(ZoneId.systemDefault()).format(at);
    }

    /** 供测试与日志：文本的 token 估算（与装配同口径） */
    public static int estimateTokens(String text) {
        return CjkWidth.estimateTokens(TextClip.clip(text, 8192));
    }
}
