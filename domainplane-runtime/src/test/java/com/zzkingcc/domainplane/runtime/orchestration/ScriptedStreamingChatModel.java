package com.zzkingcc.domainplane.runtime.orchestration;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 按脚本回放的模型桩。
 *
 * <p>全部回调都在<b>调用线程</b>上同步打完，不启线程、不做异步：编排配备 {@code Runnable::run}
 * 时，一次 {@code chat()} 就同步走完整轮。这一条是测试台能不能"钉住行为"的前提——
 * 一旦掺进线程池与调度，"现象偶发"就无法断言。</p>
 *
 * <p>脚本用尽时的处理是<b>报错而不是静默给空应答</b>：静默空应答会让一次额外的模型调用伪装成
 * "模型说了一句空的"，断言因此 miss掉真正的偏差。宁可让用例以"脚本已耗尽"显式失败。</p>
 */
final class ScriptedStreamingChatModel implements StreamingChatModel {

    private final Queue<ScriptedTurn> turns = new ConcurrentLinkedQueue<>();
    private final List<ChatRequest> capturedRequests = new ArrayList<>();

    ScriptedStreamingChatModel enqueue(ScriptedTurn turn) {
        turns.add(turn);
        return this;
    }

    /** 排一条纯文本应答（可带若干增量） */
    ScriptedStreamingChatModel answer(String text, String... partials) {
        return enqueue(ScriptedTurn.answer(text, partials));
    }

    /** 排一条带工具调用的应答 */
    ScriptedStreamingChatModel toolCall(String name, String arguments) {
        return enqueue(ScriptedTurn.toolCall(
                ToolExecutionRequest.builder().id("call-" + name).name(name).arguments(arguments).build()));
    }

    ScriptedStreamingChatModel error(Throwable t) {
        return enqueue(ScriptedTurn.error(t));
    }

    /** 已发出去的请求（断言"模型看到了什么"用） */
    List<ChatRequest> requests() {
        return capturedRequests;
    }

    ChatRequest lastRequest() {
        if (capturedRequests.isEmpty()) {
            return null;
        }
        return capturedRequests.get(capturedRequests.size() - 1);
    }

    int pendingTurns() {
        return turns.size();
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        capturedRequests.add(request);
        ScriptedTurn turn = turns.poll();
        if (turn == null) {
            handler.onError(new IllegalStateException(
                    "脚本已耗尽：本轮是第 " + capturedRequests.size() + " 次模型调用，但没有排对应的脚本"));
            return;
        }
        for (ScriptedTurn.Step step : turn.steps()) {
            switch (step) {
                case ScriptedTurn.Thinking thinking ->
                        handler.onPartialThinking(new PartialThinking(thinking.text()), null);
                case ScriptedTurn.Partial partial -> handler.onPartialResponse(partial.text());
                case ScriptedTurn.Hook hook -> hook.action().run();
            }
        }
        switch (turn.end()) {
            case ANSWER -> handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(AiMessage.from(turn.answerText())).build());
            case TOOL_CALL -> handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(AiMessage.from(turn.toolCalls())).build());
            case ERROR -> handler.onError(turn.error());
        }
    }
}
