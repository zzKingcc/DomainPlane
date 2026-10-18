package com.zzkingcc.domainplane.runtime.conversation;

import com.zzkingcc.domainplane.api.conversation.FlowEvent;
import com.zzkingcc.domainplane.api.conversation.FlowEventType;
import com.zzkingcc.domainplane.api.conversation.HistoryCard;
import com.zzkingcc.domainplane.api.conversation.MediaRef;
import com.zzkingcc.domainplane.api.conversation.TurnRecord;
import com.zzkingcc.domainplane.common.util.CjkWidth;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 轮记录的模型可见视图：把一条 {@link TurnRecord} 渲染成给模型看的文本。
 *
 * <p>读侧渲染与写侧抽取（{@link TurnRecorder}）分开：一个管"怎么存"，
 * 一个管"怎么给模型看"，两侧的截断口径不同（存档 8K/元素防爆，
 * 读侧每轮节流 ≤ ~1500 token 防撑上下文）。</p>
 *
 * @author zzkingcc
 */
public final class TurnView {

    /**
     * 读侧每轮节流：用户文本与回答分别截断的字符数。
     *
     * <p>口径是"每轮 ≤ ~1500 token"：CJK 最坏 1.5 token/字符，
     * 300 + 800 + 少量结构文字 ≈ 1200 字符 ≈ 1800 token，留了余量也压得住。
     * 精确按 token 截断的循环留 M4 按命中率数据再收紧。</p>
     */
    private static final int USER_MAX_CHARS = 300;
    private static final int ANSWER_MAX_CHARS = 800;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private TurnView() {
    }

    /**
     * 渲染一条轮记录给模型看（{@code read_history} 工具的产出单元）。
     *
     * <p>中止 / 审批被拒的轮次也渲染，但带标记，现场是档案的一部分，
     * 模型需要知道"那一轮问过但没答完"，而不是以为它不存在。</p>
     */
    public static String renderForModel(TurnRecord t) {
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【第 ").append(t.turnId()).append(" 轮】");
        if (t.startedAt() != null) {
            sb.append(' ').append(TS.format(t.startedAt()));
        }
        if (t.flags() != null && t.flags().aborted()) {
            sb.append(" · 未完成（已中止）");
        }
        if (t.flags() != null && "REJECTED".equals(t.flags().approvalOutcome())) {
            sb.append(" · 审批被拒后取消");
        }
        sb.append('\n');

        String userText = t.user() == null ? "" : TurnRecorder.clip(t.user().text(), USER_MAX_CHARS);
        sb.append("用户：").append(userText.isBlank() ? "（无文本）" : userText).append('\n');
        if (t.user() != null && !t.user().media().isEmpty()) {
            sb.append("附件：");
            List<String> imageIds = new ArrayList<>();
            List<String> audioIds = new ArrayList<>();
            List<String> videoIds = new ArrayList<>();
            for (MediaRef m : t.user().media()) {
                sb.append(m.kind()).append(' ');
                if (!m.hasAttachment()) {
                    continue;
                }
                if (m.isImage()) {
                    imageIds.add(m.attachmentId());
                } else if (isAudio(m)) {
                    audioIds.add(m.attachmentId());
                } else {
                    videoIds.add(m.attachmentId());
                }
            }
            /* M4-R4：写出引用名并说明"用它还能做什么"。三类要分开写，因为通道不同，
               图片有 seeImage（会话活跃期内存活期会被续期），音频有 transcribeAudio
               （只能拿回文字，听不到语气音色，所以不写成"取回音频"，那是句做不到的承诺），
               视频目前没有任何取回通道。把音频指向 seeImage、或把视频说成能取回，都是骗模型。
               三者都要提"可能已过期"，让它对取不到有心理预期而不是凭空编内容。 */
            List<String> notes = new ArrayList<>();
            if (!imageIds.isEmpty()) {
                notes.add("图片可凭引用名 " + String.join("、", imageIds)
                        + " 用 seeImage 取回，过期则取不到");
            }
            if (!audioIds.isEmpty()) {
                notes.add("音频可凭引用名 " + String.join("、", audioIds)
                        + " 用 transcribeAudio 转成文字（只拿得到说了什么，听不到语气音色），过期则取不到");
            }
            if (!videoIds.isEmpty()) {
                notes.add("视频可凭引用名 " + String.join("、", videoIds)
                        + " 定位归档中的那份字节，但没有取回内容的工具");
            }
            sb.append('（');
            sb.append(notes.isEmpty() ? "仅存档引用，像素/字节不可取回" : String.join("；", notes));
            sb.append("）\n");
        }

        Set<String> tools = new LinkedHashSet<>();
        if (t.flow() != null) {
            for (FlowEvent e : t.flow()) {
                if (e.type() == FlowEventType.TOOL_CALL && e.toolName() != null) {
                    tools.add(e.toolName());
                }
            }
        }
        if (!tools.isEmpty()) {
            sb.append("调用过工具：").append(String.join("、", tools)).append('\n');
        }

        String answer = TurnRecorder.clip(t.answer(), ANSWER_MAX_CHARS);
        sb.append("回答：").append(answer.isBlank() ? "（无回答）" : answer);
        return sb.toString();
    }

    /** 渲染文本的 token 估算（读侧节流自检 / 可观测用） */
    public static int estimateTokens(String rendered) {
        return CjkWidth.estimateTokens(rendered);
    }

    /**
     * 是不是音频。
     *
     * <p>字面量必须与 {@code MediaAttachmentStore.Kind.AUDIO.wire()} 一致
     * （词表的定义方在 runtime，这里只读 api 侧的 {@code kind} 字符串，反向依赖不成立）。
     * 不认 {@code kind} 为 audio 的一律当视频处理，它落到的那句是"没有取回内容的工具"，
     * 是这里最保守的一种说法。</p>
     */
    private static boolean isAudio(MediaRef media) {
        return "audio".equals(media.kind());
    }

    /**
     * 渲染早期摘要段（装配层注入的用户消息段正文）。
     *
     * <p>与 {@link #renderForModel} 同一条界线：这边给的是<b>压缩</b>，不是原文。
     * 模型要细节按轮号去调 {@code read_history}。</p>
     */
    public static String renderSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        return "【早期对话摘要】以下是更早轮次的压缩记录，细节可按轮号用 read_history 取原文：\n"
                + summary;
    }

    /**
     * 渲染相关轮卡片段（装配层注入的用户消息段正文）。
     *
     * <p>卡片不附全文，都塞全文就等于没选取，上下文又会线性胀回去（方案 §5.1）。</p>
     */
    public static String renderCards(List<HistoryCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【可能相关的历史轮次】");
        for (HistoryCard card : cards) {
            sb.append("\n· 第 ").append(card.turnId()).append(" 轮");
            String when = ContextAssembler.relativeTime(card.at(), Instant.now());
            if (!when.isEmpty()) {
                sb.append('（').append(when).append('）');
            }
            sb.append("：").append(card.digest() == null ? "" : card.digest());
            if (!card.tools().isEmpty()) {
                sb.append("（用过 ").append(String.join("、", card.tools())).append('）');
            }
        }
        sb.append("\n需要细节时用 read_history 按轮号取原文；中止（未完成）的轮次不是可引用的结论。");
        return sb.toString();
    }

    /** 供测试与日志复用：把 Instant 格式化成同一口径 */
    public static String formatTime(Instant at) {
        return at == null ? "" : TS.format(at);
    }
}
