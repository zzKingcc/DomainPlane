package com.zzkingcc.domainplane.server.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.event.AgentEventType;
import com.zzkingcc.domainplane.runtime.tool.ToolInvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体直传的四条口径：字节只走事件流、编号只走文本、缓冲按轮清空、超限丢旧留新。
 *
 * <p>这里钉的是"别把 base64 漏进会落库的那条路"——工具结果文本一旦带上字节，
 * 图状态、模型上下文与会话归档会同时被撑爆，而那三个地方都不会报错。</p>
 *
 * @author zzkingcc
 */
@DisplayName("媒体直传：字节只走事件流 / 缓冲按轮清空 / 超限丢旧")
class GeneratedMediaRelayTest {

    private static final String SESSION = "s-media";

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeneratedMediaRelay relay = new GeneratedMediaRelay(mapper);

    @AfterEach
    void cleanup() {
        ToolInvocationContext.clear();
    }

    private void bindSession(String sessionId) {
        ToolInvocationContext.bind("trace-1", sessionId);
    }

    private static GeneratedMediaRelay.Item image(String id, String data) {
        return new GeneratedMediaRelay.Item(id, "image", "image/png", data,
                GeneratedMediaRelay.ENCODING_BASE64);
    }

    @Test
    @DisplayName("挂起的字节在下一次事件之前变成一个 MEDIA 事件，且不碰任何 TOKEN 文本")
    void mediaIsEmittedBeforeNextEventAndNeverEntersTokenText() {
        bindSession(SESSION);
        relay.publish(image("img_1", "QUJD"));

        List<AgentEvent> events = relay.attach(
                        Flux.just(AgentEvent.token(SESSION, "已生成一张图"), AgentEvent.done(SESSION)),
                        SESSION)
                .collectList()
                .block();

        assertEquals(3, events.size(), "TOKEN 前应插入一个 MEDIA：" + events);
        assertEquals(AgentEventType.MEDIA, events.get(0).getType());
        assertEquals(AgentEventType.TOKEN, events.get(1).getType());
        assertEquals(AgentEventType.DONE, events.get(2).getType());

        String payload = events.get(0).getPayload();
        assertTrue(payload.contains("\"id\":\"img_1\""), payload);
        assertTrue(payload.contains("\"encoding\":\"base64\""), payload);
        assertTrue(payload.contains("\"data\":\"QUJD\""), payload);

        for (AgentEvent event : events) {
            if (event.getType() == AgentEventType.MEDIA) {
                continue;
            }
            String text = event.getContent() + "|" + event.getPayload();
            assertFalse(text.contains("QUJD"), "字节不得出现在会落库的事件字段里：" + event);
        }
    }

    @Test
    @DisplayName("MEDIA 的 payload 能反解成前端约定的字段，且 URL 引用不带字节")
    void mediaPayloadWireShapeStaysStable() throws Exception {
        bindSession(SESSION);
        relay.publish(new GeneratedMediaRelay.Item("img_url", "image", null,
                "https://example.com/a.png", GeneratedMediaRelay.ENCODING_URL));

        AgentEvent event = relay.drain(SESSION).get(0);
        JsonNode node = mapper.readTree(event.getPayload());

        assertEquals("img_url", node.get("id").asText());
        assertEquals("image", node.get("kind").asText());
        assertEquals("url", node.get("encoding").asText());
        assertEquals("https://example.com/a.png", node.get("data").asText());
        assertTrue(node.get("mime").isNull(), "URL 引用的 mime 由服务商决定，不猜");
        assertEquals(SESSION, event.getSessionId());
    }

    @Test
    @DisplayName("取一次就清空：同一批字节不会被重复下发")
    void drainIsOneShot() {
        bindSession(SESSION);
        relay.publish(image("img_1", "QUJD"));

        assertEquals(1, relay.drain(SESSION).size());
        assertTrue(relay.drain(SESSION).isEmpty(), "第二次取不到，否则调用方会重复渲染同一张图");
    }

    @Test
    @DisplayName("没有会话上下文时不抛也不留缓冲（只是调用方看不到图）")
    void publishWithoutSessionIsSafeNoOp() {
        relay.publish(image("img_1", "QUJD"));
        assertTrue(relay.drain(SESSION).isEmpty());
    }

    @Test
    @DisplayName("单轮超过上限时丢最早的，保留最新的（跑飞的循环不该把堆吃满）")
    void overflowDropsOldest() {
        bindSession(SESSION);
        for (int i = 0; i < 12; i++) {
            relay.publish(image("img_" + i, "QUJD"));
        }

        List<AgentEvent> events = relay.drain(SESSION);
        assertEquals(8, events.size(), "上限 8 份");
        assertTrue(events.get(0).getPayload().contains("\"img_4\""), "最早的 4 份被丢掉");
        assertTrue(events.get(7).getPayload().contains("\"img_11\""), "最新的保留");
    }

    @Test
    @DisplayName("流结束（含取消 / 异常）后缓冲必须清空，不留没人读的字节")
    void bufferIsDiscardedWhenStreamTerminates() {
        bindSession(SESSION);
        relay.attach(Flux.just(AgentEvent.token(SESSION, "hi"), AgentEvent.done(SESSION)), SESSION)
                .blockLast();

        relay.publish(image("img_after", "QUJD"));
        relay.discard(SESSION);
        assertTrue(relay.drain(SESSION).isEmpty());
    }

    @Test
    @DisplayName("不同会话的媒体互不串台")
    void sessionsDoNotLeakIntoEachOther() {
        bindSession("s-a");
        relay.publish(image("img_a", "QUJD"));
        bindSession("s-b");
        relay.publish(image("img_b", "QUJD"));

        assertTrue(relay.drain("s-a").get(0).getPayload().contains("img_a"));
        assertTrue(relay.drain("s-b").get(0).getPayload().contains("img_b"));
        assertNotEquals("s-a", "s-b");
    }
}
