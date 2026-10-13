package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.model.ProviderConnectionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务商连接库的接口契约。
 *
 * <p>最重要的一条是<b>列表不返回明文密钥</b>：明文只在用户于下拉里选中某个名称时按名单取一次。
 * 这条一旦破功，整库密钥就会随每次页面加载进浏览器；而且没有任何报错。
 * 另一条是<b>地址原样回显</b>：地址不是凭据，遮起来只会妨碍排障。</p>
 *
 * @author zzkingcc
 */
class AdminProviderConnectionControllerTest {

    private static final String SF = "https://api.siliconflow.cn/v1";

    @TempDir
    Path tempDir;

    private AdminProviderConnectionController controller() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new AdminProviderConnectionController(
                new ProviderConnectionStore(new StorageLocations(env)));
    }

    private static AdminProviderConnectionController.ConnectionBody body(String name, String url, String key) {
        AdminProviderConnectionController.ConnectionBody b = new AdminProviderConnectionController.ConnectionBody();
        b.setName(name);
        b.setBaseUrl(url);
        b.setApiKey(key);
        return b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(Map<String, Object> list) {
        return ((List<Map<String, Object>>) list.get("connections")).get(0);
    }

    @Test
    void 列表给地址原样与脱敏密钥但绝不含明文() {
        AdminProviderConnectionController controller = controller();
        controller.add(body("硅基流动-主", SF, "sk-super-secret-9999"));

        Map<String, Object> item = first(controller.list());

        assertEquals("硅基流动-主", item.get("name"));
        assertEquals(SF, item.get("baseUrl"), "地址不是凭据，原样回显 —— 排障要看到真实地址");
        assertEquals(true, item.get("hasApiKey"));
        assertEquals("sk-s****9999", item.get("maskedApiKey"));
        assertFalse(item.containsKey("apiKey"), "列表绝不能带明文 —— 它会随每次页面加载进浏览器");
        assertFalse(controller.list().toString().contains("sk-super-secret-9999"),
                "整个响应体里都不该出现明文，实际：" + controller.list());
    }

    @Test
    void 没配密钥的连接在列表里如实标出来() {
        AdminProviderConnectionController controller = controller();
        controller.add(body("本地 Ollama", "http://localhost:11434/v1", null));

        Map<String, Object> item = first(controller.list());
        assertEquals(false, item.get("hasApiKey"));
        assertEquals("", item.get("maskedApiKey"), "没配密钥时是空串，前端据此显示「无密钥」而不是 ****");
    }

    @Test
    void 选中某条时一次取回地址与密钥() {
        AdminProviderConnectionController controller = controller();
        controller.add(body("硅基流动-主", SF, "sk-super-secret-9999"));

        Map<String, Object> resolved = controller.resolve("硅基流动-主");
        assertEquals(SF, resolved.get("baseUrl"));
        assertEquals("sk-super-secret-9999", resolved.get("apiKey"));
        assertEquals(true, resolved.get("hasApiKey"));
    }

    @Test
    void 选中一条没配密钥的连接时hasApiKey为false让前端别去动已填的Key() {
        AdminProviderConnectionController controller = controller();
        controller.add(body("本地 Ollama", "http://localhost:11434/v1", ""));

        Map<String, Object> resolved = controller.resolve("本地 Ollama");
        assertEquals(false, resolved.get("hasApiKey"));
        assertEquals("", resolved.get("apiKey"));
        assertEquals("http://localhost:11434/v1", resolved.get("baseUrl"));
    }

    @Test
    void 取一个不存在的名称要报错而不是返回空串() {
        BaseException e = assertThrows(BaseException.class, () -> controller().resolve("没有这条"));
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("没有这条"), "要说清是哪一条取不到；实际：" + e.getMessage());
    }

    @Test
    void 重名新增直接被拒并把原因带出来() {
        AdminProviderConnectionController controller = controller();
        controller.add(body("dup", SF, "sk-1"));

        BaseException e = assertThrows(BaseException.class, () -> controller.add(body("dup", SF, "sk-2")));
        assertTrue(e.getMessage().contains("名称已存在"), "实际：" + e.getMessage());
    }

    @Test
    void 地址不合法时拒绝并说清哪里不对() {
        AdminProviderConnectionController controller = controller();
        BaseException e = assertThrows(BaseException.class,
                () -> controller.add(body("没协议", "api.example.com/v1", "sk-1")));
        assertTrue(e.getMessage().contains("协议"), "实际：" + e.getMessage());
    }

    @Test
    void 删不存在的名称要报错() {
        BaseException e = assertThrows(BaseException.class, () -> controller().delete("nope"));
        assertTrue(e.getMessage().contains("nope"), "实际：" + e.getMessage());
    }

    @Test
    void 删除的提示要说明档案不受影响() {
        AdminProviderConnectionController controller = controller();
        controller.add(body("tmp", SF, "sk-1"));

        Map<String, Object> out = controller.delete("tmp");
        assertTrue(String.valueOf(out.get("message")).contains("不受影响"),
                "库里只是取连接快捷方式，删掉不等于从档案里撤掉了配置；实际：" + out.get("message"));
    }

    @Test
    void 与内置配置项重名必须拦下来() {
        AdminProviderConnectionController controller = controller();

        BaseException e = assertThrows(BaseException.class,
                () -> controller.add(body("DeepSeek 官方", SF, "sk-1")));

        assertTrue(e.getMessage().contains("内置配置项"),
                "内置与自定义共用同一个预设下拉，重名就分不清点的是哪一条；实际：" + e.getMessage());
    }
}