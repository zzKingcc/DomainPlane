package com.zzkingcc.domainplane.server.prompt;

import com.zzkingcc.domainplane.server.config.PromptProperties;
import com.zzkingcc.domainplane.server.settings.DomainSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词链分段视图：域空间页要显示"生效提示词由链上哪几个域拼成、各多少字"。
 *
 * 判定口径与 {@code compose} 完全一致（管控台优先、未配置的段不进链、trim 后计字），
 * 两处若漂移，页面显示的分段构成与实际拼接内容就会对不上。
 */
class DomainSystemPromptResolverChainTest {

    private DomainSettings consoleWith(String... domainThenText) {
        DomainSettings console = new DomainSettings();
        for (int i = 0; i < domainThenText.length; i += 2) {
            console.getPrompts().put(domainThenText[i], domainThenText[i + 1]);
        }
        return console;
    }

    @Test
    @DisplayName("链分段只收有内容的段，按由根向下排序，字数取 trim 后长度")
    void chainSkipsBlankSegmentsAndCountsTrimmedLength() {
        DomainSystemPromptResolver resolver =
                new DomainSystemPromptResolver(null, new PromptProperties());
        DomainSettings console = consoleWith(
                "default", "  根域基底  ",
                "default.a", "A段",
                "default.a.b", "B段");

        List<DomainSystemPromptResolver.PromptSegment> chain = resolver.chain("default.a.b", console);

        assertEquals(3, chain.size(), "根、A、B 三段都有内容，都进链");
        assertEquals("default", chain.get(0).domain());
        assertEquals(4, chain.get(0).chars(), "根段 trim 后 4 字");
        assertEquals("default.a", chain.get(1).domain());
        assertEquals(2, chain.get(1).chars());
        assertEquals("default.a.b", chain.get(2).domain());
    }

    @Test
    @DisplayName("空白片段视为未配置：不进链（与 compose 的跳过口径一致）")
    void blankSegmentIsNotASegment() {
        DomainSystemPromptResolver resolver =
                new DomainSystemPromptResolver(null, new PromptProperties());
        DomainSettings console = consoleWith(
                "default", "根",
                "default.a", "   ",
                "default.a.b", "B段");

        List<DomainSystemPromptResolver.PromptSegment> chain = resolver.chain("default.a.b", console);

        assertEquals(2, chain.size(), "空白段不进链，只剩根与 B");
        assertEquals("default", chain.get(0).domain());
        assertEquals("default.a.b", chain.get(1).domain(), "顺序仍按由根向下，不因跳过而乱");
    }

    @Test
    @DisplayName("整条链都没配时链为空（页面据此显示「缺提示词」）")
    void emptyChainWhenNothingConfigured() {
        DomainSystemPromptResolver resolver =
                new DomainSystemPromptResolver(null, new PromptProperties());

        assertTrue(resolver.chain("default.a", new DomainSettings()).isEmpty());
    }
}
