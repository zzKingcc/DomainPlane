package com.zzkingcc.domainplane.runtime.orchestration;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮模型应答的脚本。编排每调用一次模型就消耗一条脚本。
 *
 * <p>脚本里的步骤按顺序排放，可以混合"思考增量 / 答案增量 / 回调钩子"，末尾必须是三者之一：

 * <ul>
 *   <li>{@link End#ANSWER} —— {@code onCompleteResponse} 给一条纯文本 {@code AiMessage}；</li>
 *   <li>{@link End#TOOL_CALL} —— 给一条带工具调用的 {@code AiMessage}（触发 tools 节点）；</li>
 *   <li>{@link End#ERROR} —— {@code onError}。</li>
 * </ul>
 *
 * <p><b>钩子（{@link Hook}）是这类测试台唯一可靠的"中途插入外部事件"的手段</b>：编排默认跑在
 * {@code Runnable::run} 上（同步），测试线程无法在流进行到一半时插手；把"置停止标志"这类动作
 * 挂在某一步之前，执行点就精确落在该回调的检查点上，不依赖 sleep 与线程调度。</p>
 */
final class ScriptedTurn {

    /** 脚本里的一步 */
    sealed interface Step permits Thinking, Partial, Hook {
    }

    /** 推理增量（{@code onPartialThinking} 通道） */
    record Thinking(String text) implements Step {
    }

    /** 答案增量（{@code onPartialResponse} 通道） */
    record Partial(String text) implements Step {
    }

    /**
     * 执行到此处时跑一个动作。
     *
     * 典型的用法是在这里调 {@code service.stop(...)}，用来覆盖"某回调内的停止检查"。
     */
    record Hook(Runnable action) implements Step {
    }

    /** 脚本的终态 */
    enum End { ANSWER, TOOL_CALL, ERROR }

    private final List<Step> steps = new ArrayList<>();
    private final List<dev.langchain4j.agent.tool.ToolExecutionRequest> toolCalls = new ArrayList<>();
    private final End end;
    private final String answerText;
    private final Throwable error;

    private ScriptedTurn(End end, String answerText, Throwable error) {
        this.end = end;
        this.answerText = answerText;
        this.error = error;
    }

    /** 纯文本应答：先吐若干增量，最后给一条 {@code AiMessage} */
    static ScriptedTurn answer(String text, String... partials) {
        ScriptedTurn turn = new ScriptedTurn(End.ANSWER, text, null);
        for (String partial : partials) {
            turn.steps.add(new Partial(partial));
        }
        return turn;
    }

    /** 返回工具调用：模型在某一轮决定调工具 */
    static ScriptedTurn toolCall(dev.langchain4j.agent.tool.ToolExecutionRequest request) {
        ScriptedTurn turn = new ScriptedTurn(End.TOOL_CALL, null, null);
        turn.toolCalls.add(request);
        return turn;
    }

    /** 返回多个工具调用 */
    static ScriptedTurn multiToolCalls(dev.langchain4j.agent.tool.ToolExecutionRequest... requests) {
        ScriptedTurn turn = new ScriptedTurn(End.TOOL_CALL, null, null);
        for (dev.langchain4j.agent.tool.ToolExecutionRequest request : requests) {
            turn.toolCalls.add(request);
        }
        return turn;
    }

    /** 上游报错 */
    static ScriptedTurn error(Throwable t) {
        return new ScriptedTurn(End.ERROR, null, t);
    }

    ScriptedTurn add(Step step) {
        steps.add(step);
        return this;
    }

    ScriptedTurn partial(String text) {
        return add(new Partial(text));
    }

    ScriptedTurn thinking(String text) {
        return add(new Thinking(text));
    }

    ScriptedTurn hook(Runnable action) {
        return add(new Hook(action));
    }

    List<Step> steps() {
        return steps;
    }

    List<dev.langchain4j.agent.tool.ToolExecutionRequest> toolCalls() {
        return toolCalls;
    }

    End end() {
        return end;
    }

    String answerText() {
        return answerText;
    }

    Throwable error() {
        return error;
    }
}
