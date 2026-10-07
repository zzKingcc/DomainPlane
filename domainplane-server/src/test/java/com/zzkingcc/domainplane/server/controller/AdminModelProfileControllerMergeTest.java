package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.server.model.ModelCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「读取提供商信息」写回档案时，元数据与已有声明的合并语义。
 *
 * <p>这两条合并规则很容易写错，而写错的后果是<b>静默丢用户数据</b>：
 * 一次「读取」把用户手选的类型 / 模态 / 能力抹掉，界面上还看不出发生了什么。</p>
 *
 * @author zzkingcc
 */
class AdminModelProfileControllerMergeTest {

    @Test
    void 模态类字段读到了就以元数据为准() {
        /* 提供商的 input_modalities / output_modalities 是完整声明 ——
           读到了就该覆盖，否则模型改过之后会留着陈旧的旧模态 */
        assertEquals(List.of("text", "image", "video"),
                AdminModelProfileController.prefer(List.of("text", "image", "video"), List.of("text")));
    }

    @Test
    void 模态类字段读不到时保留原有声明() {
        assertEquals(List.of("text", "image"),
                AdminModelProfileController.prefer(List.of(), List.of("text", "image")),
                "元数据为空不等于「该模型没有模态」，不能把用户声明的抹掉");
        assertEquals(List.of("chat"),
                AdminModelProfileController.prefer(null, List.of("chat")));
    }

    @Test
    void 能力必须是并集而不能覆盖() {
        /* OpenRouter 的 supported_parameters 不列 stream（人人都支持），
           所以元数据只回 tools —— 若按"读到了就覆盖"，用户声明的 streaming 会被悄悄抹掉。 */
        assertEquals(List.of("streaming", "tools"),
                AdminModelProfileController.union(List.of("tools"), List.of("streaming")),
                "不列某项只代表提供商没声明，不代表不支持");
    }

    @Test
    void 能力并集去重且容忍空值() {
        assertEquals(List.of("tools"),
                AdminModelProfileController.union(List.of("tools"), List.of("tools")));
        assertEquals(List.of("streaming"),
                AdminModelProfileController.union(List.of(), List.of("streaming")));
        assertEquals(List.of("tools"),
                AdminModelProfileController.union(List.of("tools"), null));
        assertEquals(List.of(),
                AdminModelProfileController.union(null, null));
    }

    /**
     * 变异验证点：把「基线值当完整枚举」改回来（这里直接把 mergeModalities 换成 prefer），
     * {@link #基线模态不能覆盖用户为多模态模型声明的输入()} 就会变红。
     */
    @Test
    void 基线模态不能覆盖用户为多模态模型声明的输入() {
        /* sub_type=chat 的读数：端点族 chat、输入输出都是按 /chat/completions 规范给的基线 text */
        ModelCatalog.CatalogResult subTypeChat = result(ModelCatalog.Flavor.SUB_TYPE_FILTER,
                List.of("text"), List.of("text"));

        assertEquals(List.of("text", "image"),
                AdminModelProfileController.mergeModalities(
                        subTypeChat, List.of("text"), List.of("text", "image")),
                "基线值里的 text 不能当完整枚举；用户为 VLM 声明的 image 不能被一次「读取」抹掉");
    }

    @Test
    void 完整枚举的模态仍以元数据为准() {
        ModelCatalog.CatalogResult openRouter = result(ModelCatalog.Flavor.OPENROUTER_METADATA,
                List.of("text"), List.of("text"));

        assertEquals(List.of("text"),
                AdminModelProfileController.mergeModalities(openRouter, List.of("text"), List.of("text", "image")),
                "input_modalities 是完整枚举，读到就该覆盖档案里过时的旧模态");
    }

    private static ModelCatalog.CatalogResult result(ModelCatalog.Flavor flavor,
                                                     List<String> input, List<String> output) {
        return new ModelCatalog.CatalogResult(flavor, List.of("chat"), input, output,
                List.of(), null, "");
    }
}
