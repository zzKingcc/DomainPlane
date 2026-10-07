package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.server.env.StorageLocations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务商连接库：名称唯一、地址校验、密钥可空、明文出口、删除不误伤。
 *
 * <p>三条最容易写错的：<b>名称重复</b>（错了下拉里会有两个同名项、删不掉第二个）、
 * <b>密钥可空</b>（自建端点没有真密钥，误判成必填就逼用户编一个假值）、
 * <b>地址不强制补 /v1</b>（各家网关路径段五花八门，按一种模板改写只会把合法地址改坏）。</p>
 *
 * @author zzkingcc
 */
class ProviderConnectionStoreTest {

    private static final String SF = "https://api.siliconflow.cn/v1";
    private static final String OLLAMA = "http://localhost:11434/v1";

    @TempDir
    Path tempDir;

    private ProviderConnectionStore store() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new ProviderConnectionStore(new StorageLocations(env));
    }

    private Path file() {
        return tempDir.resolve("config").resolve("provider-connections.json");
    }

    @Test
    void 新增后可按名称取到整条连接并按名称排序() {
        ProviderConnectionStore store = store();
        assertNull(store.add("beta", SF, "sk-beta-1111"));
        assertNull(store.add("alpha", OLLAMA, ""));

        assertEquals(List.of("alpha", "beta"),
                store.entries().stream().map(ProviderConnectionStore.ProviderConnection::name).toList());

        ProviderConnectionStore.ProviderConnection resolved = store.resolve("beta").orElseThrow();
        assertEquals(SF, resolved.baseUrl());
        assertEquals("sk-beta-1111", resolved.apiKey());
    }

    @Test
    void 名称重复必须被拒绝且报错点名是哪一条() {
        ProviderConnectionStore store = store();
        assertNull(store.add("主连接", SF, "sk-first-3333"));

        String failure = store.add("主连接", OLLAMA, "sk-second-4444");
        assertTrue(failure != null && failure.contains("名称已存在"), "重名必须拦下来，实际：" + failure);
        assertTrue(failure.contains("主连接"), "要点名是哪个名字撞了，实际：" + failure);
        assertTrue(failure.contains(SF), "要说是哪条地址那一条，避免用户以为库里是空的重名，实际：" + failure);

        /* 被拒的不能写进去：否则用户以为换成功了，用的却是旧的 */
        assertEquals(SF, store.resolve("主连接").orElseThrow().baseUrl());
        assertEquals(1, store.entries().size());
    }

    @Test
    void 名称去首尾空白后算同一个名字() {
        ProviderConnectionStore store = store();
        assertNull(store.add("主连接", SF, "sk-1"));
        assertNotNull(store.add("  主连接  ", OLLAMA, "sk-2"),
                "两边都 trim 过，空格差异不该成为两个同名项");
        assertEquals(1, store.entries().size());
    }

    @Test
    void 名称大小写不同视为不同名字且不互相覆盖() {
        /* 不折叠大小写：OpenRouter / openrouter 可能是用户真想区分的两条 */
        ProviderConnectionStore store = store();
        assertNull(store.add("OpenRouter", "https://openrouter.ai/api/v1", "sk-A"));
        assertNull(store.add("openrouter", "https://openrouter.ai/api/v1", "sk-B"));
        assertEquals(2, store.entries().size());
        assertEquals("sk-A", store.resolve("OpenRouter").orElseThrow().apiKey());
        assertEquals("sk-B", store.resolve("openrouter").orElseThrow().apiKey());
    }

    @Test
    void 密钥可空但名称与地址不行() {
        ProviderConnectionStore store = store();
        assertNull(store.add("本地 Ollama", OLLAMA, null),
                "自建端点没有真密钥，空密钥必须合法 —— 误判成必填就逼用户编一个假值");

        assertNotNull(store.add("", SF, "sk-1"), "名称不能为空");
        assertNotNull(store.add("  ", SF, "sk-1"), "全空白的名称同样不行");
        assertNotNull(store.add("某家", "", "sk-1"), "服务商地址不能为空");

        ProviderConnectionStore.ProviderConnection ollama = store.resolve("本地 Ollama").orElseThrow();
        assertFalse(ollama.hasApiKey());
        assertEquals("", ollama.maskedApiKey(), "没配密钥时脱敏值是空串，不是 ****");
    }

    @Test
    void 地址必须是可以请求的http地址() {
        assertNull(ProviderConnectionStore.checkBaseUrl(SF));
        assertNull(ProviderConnectionStore.checkBaseUrl(OLLAMA));
        assertNull(ProviderConnectionStore.checkBaseUrl("https://example.com/openai/v1"),
                "各家网关的路径段五花八门，不能按一种模板判非法");

        assertNotNull(ProviderConnectionStore.checkBaseUrl(""));
        assertNotNull(ProviderConnectionStore.checkBaseUrl("api.example.com/v1"), "缺协议");
        assertNotNull(ProviderConnectionStore.checkBaseUrl("ftp://example.com/v1"), "只支持 http / https");
        assertNotNull(ProviderConnectionStore.checkBaseUrl("https:///v1"), "缺主机名");
        assertNotNull(ProviderConnectionStore.checkBaseUrl("https://例え.com/v1"), "非法字符");
    }

    @Test
    void 地址不强制补v1而是留给探测兜底() {
        /* 少写 /v1 要能存：探测环节会按 404 补一次，库这一层强改反而会把合法地址改坏 */
        assertNull(store().add("漏了版本段", "https://api.example.com", "sk-1"));
    }

    @Test
    void 删除只影响库里那一条() {
        ProviderConnectionStore store = store();
        store.add("keep", SF, "sk-keep");
        store.add("drop", OLLAMA, "");

        assertTrue(store.delete("drop"));
        assertFalse(store.delete("drop"), "删第二次应如实说没删掉，而不是悄悄再删一条别的");
        assertEquals(1, store.entries().size());
        assertEquals(SF, store.resolve("keep").orElseThrow().baseUrl());
        assertFalse(store.resolve("drop").isPresent());
    }

    @Test
    void 取不存在的名称返回空而不是抛() {
        ProviderConnectionStore store = store();
        assertFalse(store.resolve("没有这条").isPresent());
        assertFalse(store.resolve("").isPresent());
        assertFalse(store.resolve(null).isPresent());
    }

    @Test
    void 落盘可往返即重启后连接还在() throws IOException {
        ProviderConnectionStore store = store();
        store.add("主连接", SF, "sk-secret-value-1234567890");

        String json = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(json.contains("sk-secret-value-1234567890"), "库里就是明文落盘，否则选中时无从取回");

        ProviderConnectionStore reopened = store();
        assertEquals(1, reopened.entries().size());
        ProviderConnectionStore.ProviderConnection c = reopened.resolve("主连接").orElseThrow();
        assertEquals(SF, c.baseUrl());
        assertEquals("sk-secret-value-1234567890", c.apiKey());
        assertEquals("sk-s****7890", c.maskedApiKey());
    }

    @Test
    void 文件不存在时按空库继续() {
        assertEquals(0, store().entries().size(), "首启不该因为没有这个文件就失败");
    }

    @Test
    void 文件被改坏时按空库继续而不是启动失败() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{ 这不是合法 JSON", StandardCharsets.UTF_8);
        assertEquals(0, store().entries().size(), "解析失败要能进管控台修正，不能让服务起不来");
    }

    @Test
    void 手工改出重名条目时按先出现的赢并去重() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
                {"connections":[
                  {"name":"dup","baseUrl":"https://a.example.com/v1","apiKey":"sk-1"},
                  {"name":"dup","baseUrl":"https://b.example.com/v1","apiKey":"sk-2"}]}
                """, StandardCharsets.UTF_8);

        ProviderConnectionStore store = store();
        assertEquals(1, store.entries().size(), "两个同名项会让下拉和删除都无法定位");
        Optional<ProviderConnectionStore.ProviderConnection> kept = store.resolve("dup");
        assertTrue(kept.isPresent());
        assertEquals("https://a.example.com/v1", kept.get().baseUrl());
    }
}