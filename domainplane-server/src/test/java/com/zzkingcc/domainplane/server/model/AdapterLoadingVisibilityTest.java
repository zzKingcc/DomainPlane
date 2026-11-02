package com.zzkingcc.domainplane.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配加载必须 fail-loud。
 *
 * <p>F10 定线之后，{@code resources/adapters} 只剩两份：一份 chat 样板（DeepSeek 官方直连）
 * 与一份 embedding 族适配。<b>"只剩一两条"是常态</b>，而不是异常，所以"它还在不在"
 * 这件事必须能被断言、能被运维读到 —— 少了这条保险丝，哪天它们也丢了，
 * 现象只是"所有模型卡片都没有可调参数"，没人会往适配丢了这个方向想。</p>
 *
 * <p>归档目录 {@code adapters/disabled/} 里的 28 份（原先刻意禁用、写得不好的厂商适配）
 * 一律不参与加载：glob 是非递归的，子目录天然进不来。这里把它钉住，
 * 防止哪天有人把 glob 改成递归而让它们重新生效。</p>
 */
@DisplayName("F10 适配加载可见性")
class AdapterLoadingVisibilityTest {

    /** 扫不到任何文件的资源加载器（复现"适配文件全没了"） */
    private static final ResourceLoader EMPTY = new DefaultResourceLoader() {
        @Override
        public Resource getResource(String location) {
            return new ClassPathResource("__no_such_adapter_dir__/");
        }
    };

    @Test
    @DisplayName("扫到当前保留的两份适配，且全部加载成功")
    void loadsTheKeptAdapters() {
        new ModelAdapterRegistry(new DefaultResourceLoader());

        ModelAdapterRegistry.LoadStats stats = ModelAdapterRegistry.lastLoadStats();
        assertEquals(2, stats.scanned(), "当前只保留 chat 样板与 embedding 适配各一份：" + stats);
        assertEquals(2, stats.loaded(), "保留下来的两份都必须加载成功：" + stats);
        assertEquals(0, stats.skipped(), "不该有被跳过的文件：" + stats);
    }

    @Test
    @DisplayName("归档目录里的适配不参与加载（glob 非递归）")
    void archivedAdaptersAreNotLoaded() {
        ModelAdapterRegistry registry = new ModelAdapterRegistry(new DefaultResourceLoader());

        List<String> ids = registry.all().stream()
                .map(ModelAdapterRegistry.Adapter::id).toList();
        assertTrue(ids.size() <= 2, "生效的适配不得超过保留的那两份，归档目录不得被扫进来：" + ids);
        // 归档里被刻意禁用的厂商适配，任何一个 id 都不该出现在这里
        for (String archived : List.of("deepseek-v4-pro", "claude-opus-5-5", "openai-gpt-6-astra",
                "siliconflow-kolors", "openrouter-x-ai-grok-4.7", "siliconflow-bge-reranker-v2-m3",
                "siliconflow-deepseek-v4-flash")) {
            assertTrue(ids.stream().noneMatch(id -> id.contains(archived)),
                    "归档适配 " + archived + " 不得参与加载：" + ids);
        }
    }

    @Test
    @DisplayName("一个适配文件都扫不到时账目为 0（日志里会有 WARN，账目供断言）")
    void emptyScanLeavesZeroStats() {
        new ModelAdapterRegistry(EMPTY);

        ModelAdapterRegistry.LoadStats stats = ModelAdapterRegistry.lastLoadStats();
        assertEquals(0, stats.scanned(), "扫不到文件时账目应当是 0：" + stats);
        assertEquals(0, stats.loaded(), stats.toString());
    }
}
