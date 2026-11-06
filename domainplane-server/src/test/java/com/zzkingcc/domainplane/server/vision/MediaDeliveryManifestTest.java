package com.zzkingcc.domainplane.server.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.event.AgentEvent;
import com.zzkingcc.domainplane.api.event.AgentEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交付单的五条口径：只认最终答复里的引用、无引用兜底最后一张、无候选不动 DONE、
 * 审批暂停不结算、每条流独立计数。
 *
 * <p>特别钉住「工具结果里的 img_xxx 不能算选中」：一轮里最先生成的那张会出现在
 * TOOL_RESULT 文本里，但它不是最终答复，扫错地方就会稳定选错图。</p>
 *
 * @author zzkingcc
 */
@DisplayName("交付单：DONE 只带引用名 / 选中只认最终答复 / 暂停不结算")
class MediaDeliveryManifestTest {

    private static final String SESSION = "s-delivery";
    private final ObjectMapper mapper = new ObjectMapper();

    private static AgentEvent media(String id) {
        return AgentEvent.media(SESSION,
                "{\"id\":\"" + id + "\",\"kind\":\"image\",\"mime\":\"image/png\","
                        + "\"encoding\":\"base64\",\"data\":\"QUJD\"}");
    }

    private static AgentEvent doneOf(List<AgentEvent> events) {
        return events.stream()
                .filter(e -> e.getType() == AgentEventType.DONE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("流里没有 DONE"));
    }

    private JsonNode manifestOf(List<AgentEvent> events) throws Exception {
        String payload = doneOf(events).getPayload();
        assertNotNull(payload, "DONE 应带交付单");
        return mapper.readTree(payload);
    }

    @Test
    @DisplayName("六张候选 + 答复引用第三张：交付单只给 img_3，工具结果里的 img_1 不算数")
    void deliveryFollowsFinalReplyAmongSixCandidates() throws Exception {
        Flux<AgentEvent> source = Flux.just(
                media("img_1"),
                AgentEvent.toolResult(SESSION, "generateImage", "已生成 1 张：img_1"),
                media("img_2"),
                AgentEvent.thinking(SESSION, "复核 img_2 不合格"),
                media("img_3"),
                media("img_4"),
                media("img_5"),
                media("img_6"),
                AgentEvent.token(SESSION, "最终采用这一张："),
                AgentEvent.token(SESSION, "img_3"),
                AgentEvent.done(SESSION));

        List<AgentEvent> events = MediaDeliveryManifest.attach(source).collectList().block();
        assertNotNull(events);
        JsonNode manifest = manifestOf(events);
        assertEquals("img_3", manifest.path("delivery").get(0).asText());
        assertEquals(6, manifest.path("attempts").asInt());
        assertEquals(11, events.size(), "事件数量不变，交付单只改 DONE 的 payload");
        // 字节仍在 MEDIA 事件里，交付单不搬运它
        assertEquals("QUJD", mapper.readTree(events.get(0).getPayload()).path("data").asText());
        assertFalse(manifest.has("data"));
    }

    @Test
    @DisplayName("答复没写编号：兜底最后一张，不允许空交付")
    void fallsBackToLastWhenReplyHasNoRef() throws Exception {
        List<AgentEvent> events = MediaDeliveryManifest.attach(Flux.just(
                        media("img_1"), media("img_2"), media("img_3"),
                        AgentEvent.token(SESSION, "图已生成，请查收。"),
                        AgentEvent.done(SESSION)))
                .collectList().block();
        assertNotNull(events);
        JsonNode manifest = manifestOf(events);
        assertEquals("img_3", manifest.path("delivery").get(0).asText());
        assertEquals(3, manifest.path("attempts").asInt());
    }

    @Test
    @DisplayName("答复写了不在本轮候选里的编号：仍兜底最后一张")
    void fallsBackWhenReplyRefIsUnknown() throws Exception {
        List<AgentEvent> events = MediaDeliveryManifest.attach(Flux.just(
                        media("img_1"), media("img_2"),
                        AgentEvent.token(SESSION, "沿用上一轮的 img_old"),
                        AgentEvent.done(SESSION)))
                .collectList().block();
        assertNotNull(events);
        assertEquals("img_2", manifestOf(events).path("delivery").get(0).asText());
    }

    @Test
    @DisplayName("本轮没有生成媒体：DONE 保持原样，payload 为 null")
    void noMediaLeavesDoneUntouched() {
        List<AgentEvent> events = MediaDeliveryManifest.attach(Flux.just(
                        AgentEvent.token(SESSION, "普通回答"),
                        AgentEvent.done(SESSION)))
                .collectList().block();
        assertNotNull(events);
        assertNull(doneOf(events).getPayload());
    }

    @Test
    @DisplayName("审批暂停的流里没有 DONE，不结算、不附加交付单")
    void interruptStreamDoesNotSettle() {
        List<AgentEvent> events = MediaDeliveryManifest.attach(Flux.just(
                        media("img_1"),
                        AgentEvent.token(SESSION, "需要授权"),
                        AgentEvent.interrupt(SESSION, "{\"pendingTools\":[]}")))
                .collectList().block();
        assertNotNull(events);
        assertEquals(3, events.size());
        assertTrue(events.stream().noneMatch(e -> e.getType() == AgentEventType.DONE));
        assertNotNull(events.get(0).getPayload(), "暂停流的 MEDIA 字节仍照常下发");
    }

    @Test
    @DisplayName("非法 MEDIA payload 只影响计数，不让本轮结束失败")
    void malformedMediaPayloadIsIgnored() {
        List<AgentEvent> events = MediaDeliveryManifest.attach(Flux.just(
                        AgentEvent.media(SESSION, "not-json"),
                        AgentEvent.token(SESSION, "img_1"),
                        AgentEvent.done(SESSION)))
                .collectList().block();
        assertNotNull(events);
        assertNull(doneOf(events).getPayload());
    }

    @Test
    @DisplayName("同一流订阅两次：计数状态各自独立，不把上一轮的候选带进来")
    void eachSubscriptionGetsFreshState() throws Exception {
        Flux<AgentEvent> attached = MediaDeliveryManifest.attach(Flux.just(
                media("img_1"),
                AgentEvent.token(SESSION, "采用 img_1"),
                AgentEvent.done(SESSION)));
        for (int i = 0; i < 2; i++) {
            List<AgentEvent> events = attached.collectList().block();
            assertNotNull(events);
            JsonNode manifest = manifestOf(events);
            assertEquals("img_1", manifest.path("delivery").get(0).asText());
            assertEquals(1, manifest.path("attempts").asInt());
        }
    }
}
