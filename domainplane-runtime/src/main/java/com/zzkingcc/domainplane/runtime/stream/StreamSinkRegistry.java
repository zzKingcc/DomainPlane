package com.zzkingcc.domainplane.runtime.stream;

import com.zzkingcc.domainplane.api.agent.CallerContext;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话 → 流式上下文 注册表
 * @author zzkingcc
 */
public class StreamSinkRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamSinkRegistry.class);

    private final ConcurrentHashMap<String, StreamContext> contexts = new ConcurrentHashMap<>();

    /**
     * 注册会话的流式上下文
     *
     * @param sessionId 会话 ID
     * @param sink      事件流 sink
     * @param caller    本轮调用方身份（权限决定可见工具集）
     * @return 新注册的上下文
     */
    public StreamContext register(String sessionId, FluxSink<AgentEvent> sink, CallerContext caller) {
        StreamContext context = new StreamContext(sessionId, sink, caller);
        StreamContext previous = contexts.put(sessionId, context);
        if (previous != null) {
            log.warn("[流式注册] 会话[{}] 存在未清理的旧上下文,已覆盖并结束旧流——同一会话不允许并发调用", sessionId);
            previous.complete();
        }
        return context;
    }

    /**
     * 取会话上下文
     *
     * @return 上下文;未注册(如节点在图外被调用)时返回 null
     */
    public StreamContext get(String sessionId) {
        return sessionId == null ? null : contexts.get(sessionId);
    }

    /** 该上下文是否仍是会话当前的那一个（旧流的收尾动作据此判断该不该生效） */
    public boolean isCurrent(String sessionId, StreamContext context) {
        return sessionId != null && context != null && contexts.get(sessionId) == context;
    }

    /**
     * 注销会话上下文 —— 只注销自己那一个。
     */
    public void unregister(String sessionId, StreamContext context) {
        if (sessionId == null || context == null) {
            return;
        }
        if (contexts.remove(sessionId, context)) {
            log.debug("[流式注册] 会话[{}] 上下文已注销", sessionId);
        } else {
            log.debug("[流式注册] 会话[{}] 的上下文已被新的一轮替换,跳过本次注销", sessionId);
        }
    }

    /** 当前注册的会话数(观测用) */
    public int size() {
        return contexts.size();
    }
}
