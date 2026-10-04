package com.zzkingcc.domainplane.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传上限必须<b>容器与业务层一致</b>。
 *
 * <p>容器（{@code spring.servlet.multipart.max-file-size}）在请求进入 Controller 之前就拦截，
 * 业务层（{@code RagProperties.maxFileSize}）在那之后才校验。两者不一致时：
 * 容器上限更小 → 用户拿到的是与"文件太大"无关的 500；业务层上限更小 → 容器那段配置形同虚设。
 * 两条都不该发生，所以把它们绑在一起断言，改一处必炸。</p>
 */
class UploadLimitConsistencyTest {

    /** 与 application.yaml 中 spring.servlet.multipart 保持一致 */
    private static MultipartProperties multipartFromYamlValues() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("spring.servlet.multipart.max-file-size", "5MB");
        props.put("spring.servlet.multipart.max-request-size", "6MB");
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("yaml", props));
        return Binder.get(env)
                .bind("spring.servlet.multipart", MultipartProperties.class)
                .get();
    }

    @Test
    void 容器上限为5MB() {
        assertEquals(5L * 1024 * 1024, multipartFromYamlValues().getMaxFileSize().toBytes(),
                "容器实际上限必须是 5MB —— yaml 未配时 Boot 默认只有 1MB");
    }

    @Test
    void 请求体上限严格大于单文件上限() {
        MultipartProperties props = multipartFromYamlValues();
        assertTrue(props.getMaxRequestSize().toBytes() > props.getMaxFileSize().toBytes(),
                "max-request-size 必须大于 max-file-size：multipart 封装有 boundary 与 part 头的开销，"
                        + "两者取同一个值会让一个正好等于上限的文件被判超限而拒收");
    }

    @Test
    void 业务层默认上限与容器上限一致() {
        // RagProperties 未在 yaml 显式配置，取其默认值；此处与容器配置对齐
        assertEquals(multipartFromYamlValues().getMaxFileSize().toBytes(),
                new RagProperties().getMaxFileSize().toBytes(),
                "RagProperties.maxFileSize 必须与容器 multipart 上限一致，"
                        + "否则业务层的校验永远走不到（容器先拦），或容器那段配置形同虚设");
    }
}