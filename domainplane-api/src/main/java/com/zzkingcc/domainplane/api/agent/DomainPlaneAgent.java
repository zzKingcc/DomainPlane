package com.zzkingcc.domainplane.api.agent;

import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.model.ContentType;
import com.zzkingcc.domainplane.api.model.MessageContent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * DomainPlane 对外的 Agent。
 *
 * <p>每个方法都有两个形态：{@code String question}（旧）与 {@code List<MessageContent>}（新）。
 * 旧的三个是 {@code default} 方法，内部转成单 TEXT 块转调新形态：这样实现类只需要
 * 实现新形态，且存量调用方一行不改。</p>
 *
 * <p>反向的兜底（新形态转调旧形态）刻意不做成"静默合并文本"：那会让一个没实现多模态的
 * 实现类把图片悄悄丢掉，调用方以为发出去了。带媒体块而实现类只支持文本时应当暴露缺口，
 * 而不是裁掉输入继续跑。</p>
 *
 * @author zzkingcc
 */
public interface DomainPlaneAgent {

    /**
     * 本实例绑定的域标识，永不为空（未指定时是 {@link Domains#DEFAULT}）。
     */
    String domainId();

    /**
     * 问一句，同步拿最终答案。
     *
     * <p>内部会消费完整条事件流并把 {@code TOKEN} 增量拼成整段文本。</p>
     *
     * @param sessionId 会话 ID，需由调用方保持稳定（记忆与检查点的唯一键）
     * @param question  用户输入
     * @return 模型的最终回答
     * @throws com.zzkingcc.domainplane.api.agent.ApprovalRequiredException 本轮被人工审批挂起（宿主应展示待审批工具，再调 {@link #resume}）
     * @throws RuntimeException          服务端返回错误或传输层失败：由 SDK 抛出携带 {@code ErrorCode} 的
     *                                   {@code com.zzkingcc.domainplane.clientcore.exception.DomainPlaneException}
     */
    default String ask(String sessionId, String question) {
        return ask(sessionId, List.of(MessageContent.text(question)));
    }

    /**
     * 问一句，逐字输出（只含模型文本增量）。
     */
    default Flux<String> stream(String sessionId, String question) {
        return stream(sessionId, List.of(MessageContent.text(question)));
    }

    /**
     * 问一句，拿完整事件流：需要看工具调用、审批中断、错误码时用它。
     *
     * <p>返回的是冷流，订阅后才开始执行；调用方可自行做超时与背压。</p>
     */
    default Flux<AgentEvent> events(String sessionId, String question) {
        return events(sessionId, List.of(MessageContent.text(question)));
    }

    /**
     * 问一句（可带图），同步拿最终答案。
     *
     * @param contents 内容块列表；空 / 只含空白 TEXT 时等同纯文本提问
     */
    String ask(String sessionId, List<MessageContent> contents);

    /** 问一句（可带图），逐字输出。 */
    Flux<String> stream(String sessionId, List<MessageContent> contents);

    /** 问一句（可带图），拿完整事件流。 */
    Flux<AgentEvent> events(String sessionId, List<MessageContent> contents);

    /**
     * 恢复被 {@link ApprovalRequiredException} 挂起的会话。
     *
     * @param approved {@code true}=批准执行工具；{@code false}=拒绝，由模型重新决策
     */
    Flux<AgentEvent> resume(String sessionId, boolean approved);

    /**
     * 请求停止会话任务（不可恢复，幂等）。
     *
     * @return {@code true}=本次设置成功；{@code false}=已在停止状态或被服务端拒绝
     */
    boolean stop(String sessionId);

    /**
     * 内容块里的纯文本（按块序、换行相接）；无 TEXT 块时返回空串。
     *
     * <p>给"只肯收文本"的调用方用：它们要能判断"这批内容里有没有我说不了的部分"，
     * 而不是拿到一个被悄悄裁过的输入。</p>
     */
    static String mergedText(List<MessageContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        for (MessageContent block : contents) {
            if (block != null && block.type() == ContentType.TEXT
                    && block.text() != null && !block.text().isBlank()) {
                if (merged.length() > 0) {
                    merged.append('\n');
                }
                merged.append(block.text());
            }
        }
        return merged.toString();
    }

    /** 内容块里是否带媒体（图 / 音频）—— 纯文本实现类据此拒绝而不是静默丢弃 */
    static boolean hasMedia(List<MessageContent> contents) {
        return contents != null && contents.stream()
                .anyMatch(b -> b != null && !ContentType.TEXT.equals(b.type()));
    }
}
