package com.zzkingcc.domainplane.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.ResourceBanner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动 banner：必须是项目自己的字样，且占位符要真被解析掉。
 *
 * <p>为什么值得钉：banner 出问题不会有任何报错——最坏情况是打出一串
 * {@code ${build.version:dev}} 字面量，或者干脆退回 Spring 默认 banner，
 * 两种都只在启动那一眼看得见，没人会为它开一次 issue。</p>
 *
 * @author zzkingcc
 */
@DisplayName("启动 banner：项目字样 + 占位符解析")
class BannerTest {

    private static String render() {
        ClassPathResource resource = new ClassPathResource("banner.txt");
        assertTrue(resource.exists(), "banner.txt 必须放在 classpath 根（src/main/resources/banner.txt）");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Banner banner = new ResourceBanner(resource);
        banner.printBanner(new StandardEnvironment(), BannerTest.class,
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("渲染出的是 DOMAINPLANE 字形，不再有 Spring 默认 banner")
    void rendersProjectName() {
        String text = render();
        assertTrue(text.contains("|_____|"), "应包含 DOMAINPLANE 的字形片段，实际：\n" + text);
        assertFalse(text.contains("Spring"), "不应再出现 Spring 默认 banner，实际：\n" + text);
    }

    @Test
    @DisplayName("版本占位符被解析（取不到 build.version 时回落 dev，而不是原样输出 ${…}）")
    void resolvesVersionPlaceholder() {
        String text = render();
        assertFalse(text.contains("${"), "占位符未被解析，会原样打到控制台，实际：\n" + text);
        assertTrue(text.contains("vdev"), "无构建信息时应回落为 vdev，实际：\n" + text);
    }
}
