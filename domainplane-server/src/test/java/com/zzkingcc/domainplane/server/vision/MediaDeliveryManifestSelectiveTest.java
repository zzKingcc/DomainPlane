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
 * P3 按需下发：base64 字节扣到结算点，只放选中的那一份；URL 候选照常全发。
 *
 * <p>钉的是「省了流量但没省掉信息」——占位事件仍带引用名，交付单仍带 attempts，
 * 折叠区还能列出本轮试过几张；不能为了省字节把候选清单一起弄丢。</p>
 *
 * @author zzkingcc
 */
@DisplayName("按需下发：只补发选中的 base64 / URL 原样 / 异常出口不空手")
class MediaDeliveryManifestSelectiveTest {

    private static final String SESSION = "s-lazy";
    private final ObjectMapper mapper = new ObjectMapper();

    private static AgentEvent base64(String id) {
        return AgentEvent.media(SESSION,
                "{\"id\":\"" + id + "\",\"kind\":\"image\",\"mime\":\"image/png\","
                        + "\"encoding\":\"base64\",\"data\":\"QUJD\"}");
    }

    private static AgentEvent url(String id) {
        return AgentEvent.media(SESSION,
                "{\"id\":\"" + id + "\",\"kind\":\"image\",\"mime\":\"image/png\","
                        + "\"encoding\":\"url\",\"data\":\"https://example.com/" + id + ".png\"}");
    }

    private JsonNode payload(AgentEvent e) throws Exception {
        return mapper.readTree(e.getPayload());
    }

    private static List<AgentEvent> media(List<AgentEvent> events) {
        return events.stream().filter(e -> e.getType() == AgentEventType.MEDIA).toList();
    }

    private static AgentEvent doneOf(List<AgentEvent> events) {
        return events.stream().filter(e -> e.getType() == AgentEventType.DONE)
                .findFirst().orElseThrow(() -> new AssertionError("流里没有 DONE"));
    }

    @Test
    @DisplayName("三份 base64：SSE 上三份占位，结算时只补发选中那一份完整字节")
    void onlyChosenBase64IsSentInFull() throws Exception {
        List<AgentEvent> events = MediaDeliveryManifest.attachSelective(Flux.just(
                        base64("img_1"), base64("img_2"), base64("img_3"),
                        AgentEvent.token(SESSION, "最终采用 img_2"),
                        AgentEvent.done(SESSION)))
                .collectList().block();
        assertNotNull(events);

        List<AgentEvent> all = media(events);
        assertEquals(4, all.size(), "3 份占位 + 1 份完整");
        long full = all.stream().filter(e -> {
            try {
                return payload(e).hasNonNull("data");
            } catch (Exception ex) {
                return false;
            }
        }).count();
        assertEquals(1, full);
        assertEquals("img_2", payload(all.get(all.size() - 1)).path("id").asText());
        assertEquals("QUJD", payload(all.get(all.size() - 1)).path("data").asText());
        for (int i = 0; i < 3; i++) {
            JsonNode p = payload(all.get(i));
            assertEquals(MediaDeliveryManifest.ENCODING_LAZY, p.path("encoding").asText());
            assertFalse(p.has("data"), "占位事件不能带字节");
            assertEquals("img_" + (i + 1), p.path("id").asText());
        }

        JsonNode manifest = payload(doneOf(events));
        assertEquals("img_2", manifest.path("delivery").get(0).asText());
        assertEquals(3, manifest.path("attempts").asInt());
        assertTrue(events.indexOf(doneOf(events)) > events.indexOf(all.get(all.size() - 1)),
                "完整字节必须补发在 DONE 之前");
    }

    @Test
    @DisplayName("URL 候选原样全发：几百字节的地址不值得延迟，折叠区仍可看缩略图")
    void urlMediaPassesThroughUntouched() throws Exception {
        List<AgentEvent> events = MediaDeliveryManifest.attachSelective(Flux.just(
                        url("img_1"), url("img_2"),
                        AgentEvent.token(SESSION, "采用 img_1"),
                        AgentEvent.done(SESSION)))
                .collectList().block();
        assertNotNull(events);
        List<AgentEvent> all = media(events);
        assertEquals(2, all.size(), "两份 URL 原样，不额外补发");
        assertTrue(all.stream().allMatch(e -> {
            try {
                return payload(e).hasNonNull("data");
            } catch (Exception ex) {
                return false;
            }
        }));
        assertEquals("img_1", payload(doneOf(events)).path("delivery").get(0).asText());
    }

    @Test
    @DisplayName("答复没写编号：兜底最后一张，并且它的字节确实被补发")
    void noRefFallsBackToLastWithFullBytes() throws Exception {
        List<AgentEvent> events = MediaDeliveryManifest.attachSelective(Flux.just(
                        base64("img_1"), base64("img_2"),
                        AgentEvent.token(SESSION, "图已生成，请查收。"),
                        AgentEvent.done(SESSION)))
                .collectList().block();
        assertNotNull(events);
        List<AgentEvent> all = media(events);
        JsonNode last = payload(all.get(all.size() - 1));
        assertEquals("img_2", last.path("id").asText());
        assertEquals("QUJD", last.path("data").asText());
        assertEquals("img_2", payload(doneOf(events)).path("delivery").get(0).asText());
    }

    @Test
    @DisplayName("STOPPED 出口没有 DONE，也要把扣下的最后一份字节放出来")
    void stoppedExitReleasesLastRetained() throws Exception {
        List<AgentEvent> events = MediaDeliveryManifest.attachSelective(Flux.just(
                        base64("img_1"), base64("img_2"),
                        AgentEvent.stopped(SESSION)))
                .collectList().block();
        assertNotNull(events);
        List<AgentEvent> all = media(events);
        assertEquals(3, all.size(), "2 份占位 + 1 份兜底完整");
        assertEquals("QUJD", payload(all.get(2)).path("data").asText());
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.STOPPED));
        assertTrue(events.stream().noneMatch(e -> e.getType() == AgentEventType.DONE));
    }

    @Test
    @DisplayName("审批暂停不结算、不出交付单，但补发最后一份避免这一流空白")
    void interruptDoesNotSettleButReleasesBytes() throws Exception {
        List<AgentEvent> events = MediaDeliveryManifest.attachSelective(Flux.just(
                        base64("img_1"),
                        AgentEvent.interrupt(SESSION, "{\"pendingTools\":[]}")))
                .collectList().block();
        assertNotNull(events);
        assertEquals(3, events.size());
        assertTrue(events.stream().noneMatch(e -> e.getType() == AgentEventType.DONE));
        assertEquals("QUJD", payload(media(events).get(1)).path("data").asText());
    }

    @Test
    @DisplayName("混合 base64 与 URL：只扣 base64，URL 照常下发")
    void mixedEncodingsOnlyStripBase64() throws Exception {
        List<AgentEvent> events = MediaDeliveryManifest.attachSelective(Flux.just(
                        base64("img_1"), url("img_2"),
                        AgentEvent.token(SESSION, "采用 img_1"),
                        AgentEvent.done(SESSION)))
                .collectList().block();
        assertNotNull(events);
        List<AgentEvent> all = media(events);
        assertEquals(3, all.size(), "1 占位 + 1 URL 原样 + 1 完整补发");
        assertEquals(MediaDeliveryManifest.ENCODING_LAZY, payload(all.get(0)).path("encoding").asText());
        assertEquals("url", payload(all.get(1)).path("encoding").asText());
        assertEquals("base64", payload(all.get(2)).path("encoding").asText());
    }

    @Test
    @DisplayName("全量模式仍是 P1 原行为：三份 MEDIA 全部带字节，没有占位")
    void fullModeKeepsP1Behavior() throws Exception {
        Flux<AgentEvent> source = Flux.just(
                base64("img_1"), base64("img_2"), base64("img_3"),
                AgentEvent.token(SESSION, "采用 img_2"),
                AgentEvent.done(SESSION));
        List<AgentEvent> events = MediaDeliveryManifest.attach(source).collectList().block();
        assertNotNull(events);
        List<AgentEvent> all = media(events);
        assertEquals(3, all.size());
        for (AgentEvent e : all) {
            assertTrue(payload(e).hasNonNull("data"));
        }
        assertEquals("img_2", payload(doneOf(events)).path("delivery").get(0).asText());
    }

    @Test
    @DisplayName("同一条流出现第二个 DONE：字节只补发一次，不重复占带宽")
    void duplicateDoneDoesNotResendFullMedia() {
        List<AgentEvent> events = MediaDeliveryManifest.attachSelective(Flux.just(
                        base64("img_1"),
                        AgentEvent.token(SESSION, "采用 img_1"),
                        AgentEvent.done(SESSION),
                        AgentEvent.done(SESSION)))
                .collectList().block();
        assertNotNull(events);
        List<AgentEvent> all = media(events);
        assertEquals(2, all.size(), "1 份占位 + 1 份完整，不能有第二份完整");
        assertEquals(2, events.stream().filter(e -> e.getType() == AgentEventType.DONE).count());
    }

    @Test
    @DisplayName("resume 段没有自己的 MEDIA：不出交付单，由前端累计的候选池兜底")
    void resumeSegmentWithoutMediaLeavesDoneAlone() {
        List<AgentEvent> events = MediaDeliveryManifest.attachSelective(Flux.just(
                        AgentEvent.token(SESSION, "沿用上一张 img_1"),
                        AgentEvent.done(SESSION)))
                .collectList().block();
        assertNotNull(events);
        assertEquals(2, events.size());
        assertNull(doneOf(events).getPayload());
    }

    @Test
    @DisplayName("非法 MEDIA payload 不让整条流失败，也不产生候选")
    void malformedMediaPayloadDoesNotBreakStream() {
        List<AgentEvent> events = MediaDeliveryManifest.attachSelective(Flux.just(
                        AgentEvent.media(SESSION, "not-json"),
                        AgentEvent.token(SESSION, "采用 img_1"),
                        AgentEvent.done(SESSION)))
                .collectList().block();
        assertNotNull(events);
        assertEquals(3, events.size());
        assertNull(doneOf(events).getPayload());
    }

    @Test
    @DisplayName("STOPPED 补发后即使还有后续事件，也不会再补发一次")
    void stoppedReleasesOnlyOnce() {
        List<AgentEvent> events = MediaDeliveryManifest.attachSelective(Flux.just(
                        base64("img_1"),
                        AgentEvent.stopped(SESSION),
                        AgentEvent.token(SESSION, "迟到的增量")))
                .collectList().block();
        assertNotNull(events);
        assertEquals(4, events.size(), "1 占位 + 1 补发 + STOPPED + TOKEN");
        assertEquals(2, media(events).size());
    }
}
