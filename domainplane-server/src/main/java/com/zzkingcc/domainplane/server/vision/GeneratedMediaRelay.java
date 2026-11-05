package com.zzkingcc.domainplane.server.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.runtime.tool.ToolInvocationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成媒体的"原样直传"通道：工具线程写入，事件流线程读出并下发。
 *
 * <p><b>为什么需要它</b>：能力工具的返回值会进图状态、模型上下文与会话归档，
 * 所以那里只能放引用名（{@code img_xxx}）。但调用方要看见图，就必须拿到字节。
 * 两者不能共用一条通道 —— 一条要落库、一条绝不能落库。这里就是第二条：
 * 工具把字节塞进本轮的会话缓冲，SSE 出口每转发一个事件前先把缓冲清空，
 * 变成 {@link com.zzkingcc.domainplane.api.event.AgentEventType#MEDIA} 事件推给调用方。</p>
 *
 * <p><b>为什么按 sessionId 缓冲而不是 ThreadLocal</b>：生图工具跑在能力隔离池的线程上
 * （见 {@code CapabilityCallExecutor}），ThreadLocal 只能在"调用方 → 工具线程"单向搬运，
 * 搬不回来。会话在平台内是串行的（同一会话同时只允许一轮），所以按 sessionId 缓冲
 * 天然不会把两轮的图混在一起。</p>
 *
 * <p><b>为什么每个事件前都清一次，而不是只挂在 TOOL_RESULT 上</b>：
 * 域级"工具调用回写"开关关掉时根本没有 TOOL_RESULT 事件，可图还是该看得见。
 * 挂在"每个事件之前"就不依赖那个开关 —— 图会随着下一个事件（哪怕只是 TOKEN 或 DONE）
 * 一起下发。</p>
 *
 * <p><b>不落库的边界</b>：这里的缓冲是纯内存、按轮生灭（{@code doFinally} 清空），
 * 不写 Redis、不写 blob、不进归档。会话记录里永远只有引用名。</p>
 *
 * @author zzkingcc
 */
@Component
public class GeneratedMediaRelay {

    private static final Logger log = LoggerFactory.getLogger(GeneratedMediaRelay.class);

    /**
     * 单轮最多缓冲几份媒体。一轮里模型反复调生图工具是可能的，
     * 无上限等于让一个跑飞的循环把堆吃满；超出丢最早的那份并告警。
     */
    private static final int MAX_PENDING_PER_SESSION = 8;

    /** 媒体编码方式：字节直传 / 服务商地址 */
    public static final String ENCODING_BASE64 = "base64";
    public static final String ENCODING_URL = "url";

    /**
     * 一份待下发的媒体。
     *
     * @param id       引用名（{@code img_xxx}），与工具结果文本里那个编号是同一个
     * @param kind     {@code image} / {@code audio} / {@code video}，调用方据此选渲染器
     * @param mime     内容类型；为 {@code null} 时由调用方按 kind 兜底
     * @param data     base64 数据（不含 {@code data:} 前缀）或地址
     * @param encoding {@link #ENCODING_BASE64} 或 {@link #ENCODING_URL}
     */
    public record Item(String id, String kind, String mime, String data, String encoding) {
    }

    private final ConcurrentHashMap<String, Deque<Item>> pending = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public GeneratedMediaRelay(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 工具线程调用：把一份媒体挂到本轮会话的待下发队列。
     *
     * <p>会话标识取自 {@link ToolInvocationContext}（编排层在调用工具前绑好）。
     * 拿不到会话时不抛：媒体只是这一轮看不到，不该让一次成功的生图变成工具失败。</p>
     */
    public void publish(Item item) {
        if (item == null || item.data() == null || item.data().isEmpty()) {
            return;
        }
        String sessionId = ToolInvocationContext.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[媒体直传] 无会话上下文，丢弃媒体 {}（调用方将只看到引用名）", item.id());
            return;
        }
        Deque<Item> queue = pending.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(item);
            while (queue.size() > MAX_PENDING_PER_SESSION) {
                Item dropped = queue.removeFirst();
                log.warn("[媒体直传] 会话[{}] 待下发媒体超过 {} 份，丢弃 {}", sessionId,
                        MAX_PENDING_PER_SESSION, dropped.id());
            }
        }
        log.debug("[媒体直传] 会话[{}] 已挂起 {} kind={} encoding={} 字节数={}",
                sessionId, item.id(), item.kind(), item.encoding(), item.data().length());
    }

    /**
     * 事件流线程调用：把该会话待下发的媒体全部转成事件并清空队列。
     *
     * <p>先取快照再清空：清空后工具线程再写入的是下一批，不会与这一批混序。</p>
     */
    public List<AgentEvent> drain(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        Deque<Item> queue = pending.get(sessionId);
        if (queue == null) {
            return List.of();
        }
        List<Item> items;
        synchronized (queue) {
            if (queue.isEmpty()) {
                return List.of();
            }
            items = new ArrayList<>(queue);
            queue.clear();
        }
        List<AgentEvent> events = new ArrayList<>(items.size());
        for (Item item : items) {
            try {
                events.add(AgentEvent.media(sessionId, mapper.writeValueAsString(wire(item))));
            } catch (Exception e) {
                // 序列化失败只影响这一份媒体的展示；引用名仍在，调用方不会因此丢内容
                log.warn("[媒体直传] 会话[{}] 媒体 {} 序列化失败：{}", sessionId, item.id(), e.getMessage());
            }
        }
        return events;
    }

    /** 清掉该会话的缓冲（流结束时调用，防止异常路径留下不再有人读的字节） */
    public void discard(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            pending.remove(sessionId);
        }
    }

    /**
     * 把媒体下发嵌进事件流：每个事件之前先放出已挂起的媒体。
     *
     * <p>用 {@code concatMap} 而不是 {@code flatMap}：媒体必须与触发它的那一轮事件保持先后，
     * 并发合流会让图跑到 DONE 后面去。</p>
     */
    public Flux<AgentEvent> attach(Flux<AgentEvent> source, String sessionId) {
        if (source == null) {
            return Flux.empty();
        }
        return source.concatMap(event -> {
            List<AgentEvent> media = drain(sessionId);
            if (media.isEmpty()) {
                return Mono.just(event);
            }
            return Flux.fromIterable(media).concatWith(Mono.just(event));
        }).doFinally(signal -> discard(sessionId));
    }

    /** 下发用的线上形状（字段名即前端读的名字，别改） */
    private static Map<String, Object> wire(Item item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", item.id());
        body.put("kind", item.kind());
        body.put("mime", item.mime());
        body.put("encoding", item.encoding());
        body.put("data", item.data());
        return body;
    }
}
