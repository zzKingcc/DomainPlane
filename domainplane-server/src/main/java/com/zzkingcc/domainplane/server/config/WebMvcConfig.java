package com.zzkingcc.domainplane.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.server.auth.AuthService;
import com.zzkingcc.domainplane.server.auth.CredentialAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * 服务端 Web MVC 配置：跨域策略 + 凭证校验拦截器。
 * @author zzkingcc
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 跳转入口（只做跳转，不放任何内容） */
    private static final String[] STATIC_RESOURCES = {
            "/admin.html", "/login.html", "/console/**", "/favicon.ico", "/error"
    };

    /** 管控台免检路径：换凭证 / 判登录态 / 首次初始化 + 静态资源 */
    private static final String[] ADMIN_EXCLUDES = concat(
            new String[]{"/admin/login", "/admin/init", "/admin/session"}, STATIC_RESOURCES);

    private static String[] concat(String[] first, String[] second) {
        String[] all = new String[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final List<String> allowedOrigins;

    public WebMvcConfig(AuthService authService,
                        ObjectMapper objectMapper,
                        @Value("${domainplane.cors.allowed-origins:}") String allowedOrigins) {
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
    }

    /**
     * 跨域策略：默认不放行任何跨域来源，需要时用 {@code domainplane.cors.allowed-origins}
     * （逗号分隔的完整来源，如 {@code https://console.example.com}）显式开启。
     *
     * 不能"任意来源 + allowCredentials"：管控台的鉴权完全依赖 Cookie，那样等于允许
     * 任意站点带着用户的登录态读取管控台响应。管控台与 API 同源部署时本就不需要 CORS。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            return;
        }
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CredentialAuthInterceptor(
                        authService, objectMapper, CredentialAuthInterceptor.Carrier.HEADER))
                .addPathPatterns("/api/agent/**")
                .excludePathPatterns("/api/agent/login");

        registry.addInterceptor(new CredentialAuthInterceptor(
                        authService, objectMapper, CredentialAuthInterceptor.Carrier.COOKIE))
                .addPathPatterns("/admin/**")
                .excludePathPatterns(ADMIN_EXCLUDES);
    }
}
