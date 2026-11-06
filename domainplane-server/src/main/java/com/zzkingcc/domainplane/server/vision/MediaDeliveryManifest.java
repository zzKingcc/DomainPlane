package com.zzkingcc.domainplane.server.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.event.AgentEventType;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSE 出口的「交付单」变换：算出本轮生成过几张、最终交付哪张，写进 DONE 的 payload。
 *
 * <p><b>为什么单独一层</b>：{@link GeneratedMediaRelay} 只管把字节插进事件流，
 * 它不知道最终答复引用了谁。这一层跟在它后面做判定，不碰字节的产出、
 * 不落库、不进归档。</p>
 *
 * <p><b>为什么挂在出口而不是编排层</b>：判定要用「最终答复文本」，那串文本在流里
 * 是逐字 TOKEN 攒出来的，只有出口能同时看见 TOKEN、MEDIA 与 DONE。编排层若持有
 * 判定结果，它就会跟着图状态一起被检查点带走 —— 而交付单是给调用方看的，不是状态。</p>
 *
 * <p><b>两种下发模式</b>：{@link #attach(Flux)} 是 P1 的全量模式（MEDIA 原样下发，
 * 只在 DONE 附交付单）；{@link #attachSelective(Flux)} 是 P3 的按需模式 ——
 * base64 字节先扣在流状态里，SSE 上只发不含 {@code data} 的占位事件，
 * 等 DONE 结算出选中那张，才把它的完整字节补发在 DONE 之前。
 * 未被选中的候选字节始终留在服务端，不占带宽；模型侧 {@code seeImage}
 * 读的是 {@code MediaAttachmentStore}，与这条下发链路无关，多轮复核不受影响。</p>
 *
 * <p><b>只省真正贵的东西</b>：{@code encoding=url} 的服务商给的是几百字节的签名地址，
 * 原样全发，折叠区仍可展开看到缩略图；只有 base64（一张 1024 图动辄上兆）才延迟。
 * 这样既不牺牲「其余候选可回看」，又把大头流量砍掉。</p>
 *
 * <p><b>结算范围</b>：只认 DONE。审批 INTERRUPT 是暂停不是结束，STOPPED / ERROR
 * 也不出交付单，但这两种都会把扣下的最后一份字节补发出去，保证至少看得到一张。</p>
 *
 * @author zzkingcc
 */
public final class MediaDeliveryManifest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern REF = Pattern.compile("img_[A-Za-z0-9_-]+");

    /** 占位事件的编码标记：只有引用名，没有 data */
    public static final String ENCODING_LAZY = "lazy";

    private MediaDeliveryManifest() {
    }

    /** P1 全量模式：MEDIA 原样下发，只在 DONE 附交付单 */
    public static Flux<AgentEvent> attach(Flux<AgentEvent> source) {
        return attach(source, false);
    }

    /** P3 按需模式：base64 只在结算后补发选中的那一份 */
    public static Flux<AgentEvent> attachSelective(Flux<AgentEvent> source) {
        return attach(source, true);
    }

    /**
     * {@code Flux.defer} 保证每次订阅拿到独立的计数与暂存状态：同一个流被订阅两次时，
     * 第二次不会把第一次的候选、答复与字节接着累加。
     */
    private static Flux<AgentEvent> attach(Flux<AgentEvent> source, boolean selective) {
        if (source == null) {
            return Flux.empty();
        }
        return Flux.defer(() -> {
            State state = new State();
            return source.concatMap(event -> transform(event, state, selective));
        });
    }

    private static Flux<AgentEvent> transform(AgentEvent event, State state, boolean selective) {
        if (event == null) {
            return Flux.empty();
        }
        AgentEventType type = event.getType();
        if (type == AgentEventType.MEDIA) {
            MediaInfo info = parse(event.getPayload());
            if (info != null && !state.candidates.contains(info.id())) {
                state.candidates.add(info.id());
            }
            if (!selective || info == null || !info.hasBase64()) {
                return Flux.just(event);
            }
            state.retained.put(info.id(), event.getPayload());
            return Flux.just(AgentEvent.media(event.getSessionId(), placeholder(info)));
        }
        if (type == AgentEventType.TOKEN) {
            if (event.getContent() != null) {
                state.reply.append(event.getContent());
            }
            return Flux.just(event);
        }
        if (type == AgentEventType.DONE) {
            if (state.candidates.isEmpty()) {
                return Flux.just(event);
            }
            String chosen = pick(state);
            List<AgentEvent> out = new ArrayList<>(2);
            String full = state.retained.get(chosen);
            if (full != null) {
                out.add(AgentEvent.media(event.getSessionId(), full));
            }
            out.add(AgentEvent.done(event.getSessionId(), manifest(chosen, state.candidates.size())));
            // 结算即释放：同一条流里万一出现第二个 DONE，不能把字节再补发一遍
            state.retained.clear();
            return Flux.fromIterable(out);
        }
        if (type == AgentEventType.STOPPED || type == AgentEventType.ERROR
                || type == AgentEventType.INTERRUPT) {
            // 异常 / 暂停出口没有 DONE，扣下的字节不能就此烂在内存里：补发最后一份，
            // 至少让调用方看得到一张。这一流不省流量，是宁可多给也不留白的兜底。
            if (!state.retained.isEmpty()) {
                String last = lastKey(state.retained);
                AgentEvent released = AgentEvent.media(event.getSessionId(), state.retained.get(last));
                state.retained.clear();
                return Flux.just(released, event);
            }
            return Flux.just(event);
        }
        return Flux.just(event);
    }

    /**
     * 选中只认最终答复文本里的引用名。工具结果文本里也有 {@code img_xxx}，但它不在
     * TOKEN 里，不会被扫到 —— 否则第一轮生成的那张会被误当成最终选定的。
     * 答复没写编号 / 写了个不在候选里的编号，一律兜底最后一张，不允许空交付。
     */
    private static String pick(State state) {
        Matcher m = REF.matcher(state.reply);
        while (m.find()) {
            if (state.candidates.contains(m.group())) {
                return m.group();
            }
        }
        return state.candidates.get(state.candidates.size() - 1);
    }

    private static String manifest(String chosen, int attempts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("delivery", List.of(chosen));
        body.put("attempts", attempts);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            // 序列化失败只影响交付单，本轮仍要正常结束；调用方按无交付单兜底
            return null;
        }
    }

    private static MediaInfo parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(payload);
            String id = node.path("id").asText(null);
            if (id == null || id.isBlank()) {
                return null;
            }
            return new MediaInfo(id, node.path("kind").asText("image"),
                    node.path("mime").asText(null), node.path("encoding").asText(null),
                    node.path("data").asText(null), payload);
        } catch (Exception e) {
            return null;
        }
    }

    /** 占位事件：保留渲染所需的分类信息，去掉所有字节 */
    private static String placeholder(MediaInfo info) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", info.id());
        body.put("kind", info.kind());
        body.put("mime", info.mime());
        body.put("encoding", ENCODING_LAZY);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return info.payload();
        }
    }

    private static String lastKey(Map<String, String> map) {
        String last = null;
        for (String key : map.keySet()) {
            last = key;
        }
        return last;
    }

    private record MediaInfo(String id, String kind, String mime, String encoding,
                             String data, String payload) {
        private boolean hasBase64() {
            return GeneratedMediaRelay.ENCODING_BASE64.equals(encoding)
                    && data != null && !data.isEmpty();
        }
    }

    private static final class State {
        private final List<String> candidates = new ArrayList<>();
        private final StringBuilder reply = new StringBuilder();
        /** 按需模式扣下的完整 MEDIA payload（仅 base64），key = 引用名 */
        private final Map<String, String> retained = new LinkedHashMap<>();
    }
}
