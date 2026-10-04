package com.zzkingcc.domainplane.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.server.auth.AuthService;
import com.zzkingcc.domainplane.server.auth.CredentialAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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

    public WebMvcConfig(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    /**
     * 跨域策略：全放行。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .exposedHeaders("*")
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
