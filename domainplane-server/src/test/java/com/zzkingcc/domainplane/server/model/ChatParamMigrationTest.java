package com.zzkingcc.domainplane.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 存量对话档案的键名归一（{@link ChatParamMigration}）。
 *
 * <p>钉三件事，缺一条都会让这次改造在存量配置上静默失效：</p>
 * <ol>
 *   <li><b>四个旧 UI 名都换到线上字段名</b> —— 换漏一个，那一个参数就从"曾经生效"变成
 *       "HTTP 200 但上游不认识"，没有任何报错指向它。</li>
 *   <li><b>幂等</b> —— 迁移挂在 {@code ModelProfileStore.load()} 上，每次启动都会跑；
 *       第二遍必须返回 {@code null}（不改写、不重存），否则每次启动都把配置文件重写一遍。</li>
 *   <li><b>不误伤</b> —— 已经是线上名的键、用户自己写的第三方键一个字节都不能动：
 *       迁移表的键名挑错一次，就会把用户正常配置改坏，而且改坏后看起来"很像他自己填错的"。</li>
 * </ol>
 *
 * @author zzkingcc
 */
class ChatParamMigrationTest {

    @Test
    @DisplayName("四个旧 UI 名都换成线上字段名")
    void migratesLegacyKeys() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("reasoningEffort", "high");
        in.put("topP", 0.9);
        in.put("topLogprobs", 5);
        in.put("responseFormat", "json_object");

        Map<String, Object> out = ChatParamMigration.migrate(in);

        assertNotNull(out, "有旧键时必须返回改写结果");
        assertEquals("high", out.get("reasoning_effort"));
        assertEquals(0.9, out.get("top_p"));
        assertEquals(5, out.get("top_logprobs"));
        assertEquals(Map.of("type", "json_object"), out.get("response_format"),
                "responseFormat 要包成 {\"type\": ...}，与适配里的 wireWrap 一致");
        assertTrue(out.keySet().stream().noneMatch(k -> k.equals("reasoningEffort")
                || k.equals("topP") || k.equals("topLogprobs") || k.equals("responseFormat")),
                "旧键不能在结果里残留：" + out.keySet());
    }

    @Test
    @DisplayName("幂等：迁移结果再跑一遍不改任何东西")
    void idempotent() {
        Map<String, Object> once = ChatParamMigration.migrate(Map.of("reasoningEffort", "high"));
        assertNotNull(once);

        assertNull(ChatParamMigration.migrate(once),
                "第二遍没有任何旧键，必须返回 null（调用方据此决定不落盘）");
    }

    @Test
    @DisplayName("空 / 无旧键时返回 null，不算一次改写")
    void noOpReturnsNull() {
        assertNull(ChatParamMigration.migrate(null));
        assertNull(ChatParamMigration.migrate(Map.of()));
        assertNull(ChatParamMigration.migrate(Map.of(
                "reasoning_effort", "high", "top_p", 0.9, "enable_thinking", false)));
    }

    @Test
    @DisplayName("用户自写的第三方键原样保留，不因迁移被丢")
    void keepsUnrelatedKeys() {
        Map<String, Object> out = ChatParamMigration.migrate(Map.of(
                "reasoningEffort", "low", "enable_thinking", false, "thinking_budget", 2048));

        assertNotNull(out);
        assertEquals("low", out.get("reasoning_effort"));
        assertEquals(false, out.get("enable_thinking"));
        assertEquals(2048, out.get("thinking_budget"));
    }

    @Test
    @DisplayName("新旧键并存：保留新键、丢旧键（不覆盖用户手填的值）")
    void wireWinsWhenBothPresent() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("topP", 0.1);
        in.put("top_p", 0.7);

        Map<String, Object> out = ChatParamMigration.migrate(in);

        assertNotNull(out);
        assertEquals(0.7, out.get("top_p"), "并存时以线上名为准");
        assertFalse(out.containsKey("topP"));
    }

    @Test
    @DisplayName("值已经是对象时不再套一层 {\"type\": ...}")
    void doesNotDoubleWrap() {
        Map<String, Object> out = ChatParamMigration.migrate(
                Map.of("responseFormat", Map.of("type", "json_object")));

        assertNotNull(out);
        assertEquals(Map.of("type", "json_object"), out.get("response_format"));
    }
}
