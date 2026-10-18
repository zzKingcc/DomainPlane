package com.zzkingcc.domainplane.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.cors.CorsConfiguration;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #11：CORS 不能再出现「任意来源 + allowCredentials(true)」。
 *
 * <p>管控台鉴权完全依赖 {@code domainplane_admin} Cookie。放行任意来源又允许携带凭证，
 * 等于任何站点都能用用户的登录态读管控台响应：认证面与 CORS 面自相矛盾，
 * CSRF 防线只剩 Cookie 的 SameSite=Lax。</p>
 *
 * <p>默认（不配置）必须是"完全不放行"；只有显式配了来源才开，且来源必须精确列举。</p>
 *
 * @author zzkingcc
 */
@DisplayName("WebMvcConfig：CORS 默认关闭，开启时只放行显式来源")
class WebMvcCorsConfigurationTest {

    /**
     * {@code CorsRegistry#getCorsConfigurations()} 是 protected 的，测试与它不同包，
     * 只能反射取。取不到就说明 Spring 改了结构，测试应该红而不是静默跳过。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, CorsConfiguration> mappingsFor(String allowedOrigins) {
        WebMvcConfig config = new WebMvcConfig(null, null, allowedOrigins);
        CorsRegistry registry = new CorsRegistry();
        config.addCorsMappings(registry);

        Method getter = null;
        for (Class<?> c = CorsRegistry.class; c != null; c = c.getSuperclass()) {
            try {
                getter = c.getDeclaredMethod("getCorsConfigurations");
                break;
            } catch (NoSuchMethodException ignored) {
                // 往父类找
            }
        }
        assertNotNull(getter, "CorsRegistry 上必须能拿到已注册的 CORS 映射");
        getter.setAccessible(true);
        try {
            return (Map<String, CorsConfiguration>) getter.invoke(registry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取 CORS 映射失败", e);
        }
    }

    @Test
    @DisplayName("未配置来源：一条 CORS 映射都不注册")
    void noMappingWhenOriginsNotConfigured() {
        assertTrue(mappingsFor("").isEmpty(), "默认必须完全不放行跨域，实际：" + mappingsFor(""));
        assertTrue(mappingsFor("   ").isEmpty(), "全空白等于没配");
        assertTrue(mappingsFor(",,").isEmpty(), "只有分隔符等于没配");
    }

    @Test
    @DisplayName("配置了来源：精确列举 + 允许凭证，绝不含 *")
    void explicitOriginsWithCredentials() {
        Map<String, CorsConfiguration> mappings =
                mappingsFor("https://console.example.com, https://ops.example.com");

        assertEquals(1, mappings.size(), "只该有一条 /** 映射，实际：" + mappings.keySet());
        CorsConfiguration cors = mappings.get("/**");
        assertNotNull(cors, "映射必须覆盖 /**，实际：" + mappings.keySet());

        List<String> origins = cors.getAllowedOrigins();
        assertEquals(2, origins.size(), "逗号分隔的多个来源都要登记");
        assertTrue(origins.contains("https://console.example.com"));
        assertTrue(origins.contains("https://ops.example.com"));
        assertFalse(origins.contains("*"), "不允许任意来源");

        assertTrue(Boolean.TRUE.equals(cors.getAllowCredentials()), "同源部署也要保留凭证开关的语义");
        assertEquals(3600L, cors.getMaxAge());
    }

    @Test
    @DisplayName("来源里混入 '*' 也不许当成通配：必须原样出现在清单里，让它在请求期就被拒")
    void wildcardIsNotTreatedAsMatchAll() {
        /* Spring 的 allowCredentials(true) + "*" 要到请求期才会被 CorsConfiguration 校验拒绝，
           注册阶段不报错。所以这里断言的是：配置里的 "*" 不会被改写/吞掉，
           请求期才有机会拒：若有人把实现改成 allowedOriginPatterns("*")，
           getAllowedOrigins() 就查不到它，这个断言立刻红。 */
        Map<String, CorsConfiguration> mappings = mappingsFor("https://ok.example.com, *");

        CorsConfiguration cors = mappings.get("/**");
        assertNotNull(cors, "映射必须覆盖 /**");
        assertTrue(cors.getAllowedOrigins().contains("*"),
                "* 必须原样留在清单里等着请求期拒绝，而不是被静默放行，实际：" + cors.getAllowedOrigins());
    }
}
