package com.zzkingcc.domainplane.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量身份判据：什么算"同一个向量模型"。
 *
 * <p>这组用例守的是两个方向的错，任何一个放过去都是真事故：</p>
 * <ul>
 *   <li>把同一个模型判成"换了" → 全库白重灌（时间 + embed 费用）；</li>
 *   <li>把不同的模型判成"没换" → 索引静默错配，检索结果全是噪声却不报错。</li>
 * </ul>
 *
 * <p>第二个方向尤其要靠用例钉住"模型名不同就是不同"这条：向量维度已不由人填（按模型实测），
 * 两个模型即使维度一致也不能当成同一套向量。</p>
 */
class VectorIdentityTest {

    private static ModelProfile profile(String baseUrl, String modelName) {
        return new ModelProfile("p", List.of(ModelProfile.EP_EMBEDDING), List.of(ModelProfile.MOD_TEXT),
                List.of(ModelProfile.MOD_EMBEDDING), baseUrl, "sk-test", modelName,
                null, null, false, false, List.of(), Map.of());
    }

    @Test
    @DisplayName("别名不参与身份：删了重建同一个模型，身份必须相等")
    void aliasIsNotPartOfIdentity() {
        ModelProfile a = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3");
        ModelProfile b = new ModelProfile("另一个别名", List.of(ModelProfile.EP_EMBEDDING),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_EMBEDDING),
                "https://api.siliconflow.cn/v1", "sk-换了一把", "BAAI/bge-m3",
                null, null, false, false, List.of(), Map.of());

        assertTrue(VectorIdentity.same(a, b),
                "地址 / 模型名一样就是同一个向量空间：换个档案名、换把 Key 都不该逼人重灌");
    }

    @Test
    @DisplayName("模型名不同 → 不是同一个向量空间，必须重灌")
    void differentModelIsNotSame() {
        ModelProfile bge = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3");
        ModelProfile other = profile("https://api.siliconflow.cn/v1", "netease-youdao/bce-embedding-base_v1");

        assertFalse(VectorIdentity.same(bge, other),
                "两个模型的向量空间不同，旧向量检索出来是噪声");
    }

    @Test
    @DisplayName("同名模型换了服务商 → 不是同一个向量空间")
    void sameModelNameDifferentProviderIsNotSame() {
        ModelProfile a = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3");
        ModelProfile b = profile("https://api.other-provider.com/v1", "BAAI/bge-m3");

        assertFalse(VectorIdentity.same(a, b),
                "两家都叫 bge-m3 也可能是两套权重，地址必须进身份");
    }

    @Test
    @DisplayName("地址的大小写与尾部斜杠不算变更：复制粘贴一下不该触发全库重灌")
    void baseUrlIsNormalizedForComparison() {
        ModelProfile a = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3");
        ModelProfile b = profile("  HTTPS://API.SiliconFlow.cn/v1//  ", "BAAI/bge-m3");

        assertTrue(VectorIdentity.same(a, b));
        assertEquals("https://api.siliconflow.cn/v1", VectorIdentity.of(b).baseUrl());
    }

    @Test
    @DisplayName("未配置（null）只与未配置相等：从无到有、从有到无都是真实变更")
    void nullProfileOnlyEqualsNullProfile() {
        ModelProfile vec = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3");

        assertTrue(VectorIdentity.same(null, null));
        assertFalse(VectorIdentity.same(null, vec), "首次配置向量模型也要走确认（有内容时）");
        assertFalse(VectorIdentity.same(vec, null), "取消配置同样是一次真实变更");
        assertNull(VectorIdentity.of(null));
    }

    @Test
    @DisplayName("模型名的首尾空白不算不同：表单里多打一个空格不该变成另一个模型")
    void modelNameIsTrimmed() {
        ModelProfile a = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3");
        ModelProfile b = profile("https://api.siliconflow.cn/v1", "  BAAI/bge-m3  ");

        assertTrue(VectorIdentity.same(a, b));
    }
}
