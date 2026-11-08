package com.zzkingcc.domainplane.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * <p>第二个方向尤其要靠用例钉住"维度相同也不行"这条：它是最容易被直觉放过的。</p>
 */
class VectorIdentityTest {

    private static ModelProfile profile(String baseUrl, String modelName, Integer dimensions) {
        return new ModelProfile("p", List.of(ModelProfile.EP_EMBEDDING), List.of(ModelProfile.MOD_TEXT),
                List.of(ModelProfile.MOD_EMBEDDING), baseUrl, "sk-test", modelName,
                null, null, false, false, dimensions, List.of(), Map.of());
    }

    @Test
    @DisplayName("别名不参与身份：删了重建同一个模型，身份必须相等")
    void aliasIsNotPartOfIdentity() {
        ModelProfile a = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3", 1024);
        ModelProfile b = new ModelProfile("另一个别名", List.of(ModelProfile.EP_EMBEDDING),
                List.of(ModelProfile.MOD_TEXT), List.of(ModelProfile.MOD_EMBEDDING),
                "https://api.siliconflow.cn/v1", "sk-换了一把", "BAAI/bge-m3",
                null, null, false, false, 1024, List.of(), Map.of());

        assertTrue(VectorIdentity.same(a, b),
                "地址 / 模型名 / 维度全一样就是同一个向量空间：换个档案名、换把 Key 都不该逼人重灌");
    }

    @Test
    @DisplayName("维度相同但模型名不同 → 不是同一个向量空间，必须重灌")
    void sameDimensionsDifferentModelIsNotSame() {
        ModelProfile bge = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3", 1024);
        ModelProfile other = profile("https://api.siliconflow.cn/v1", "netease-youdao/bce-embedding-base_v1", 1024);

        assertFalse(VectorIdentity.same(bge, other),
                "维度一致不代表向量可比：两个模型的向量空间不同，旧向量检索出来是噪声");
    }

    @Test
    @DisplayName("同名模型换了服务商 → 不是同一个向量空间")
    void sameModelNameDifferentProviderIsNotSame() {
        ModelProfile a = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3", 1024);
        ModelProfile b = profile("https://api.other-provider.com/v1", "BAAI/bge-m3", 1024);

        assertFalse(VectorIdentity.same(a, b),
                "两家都叫 bge-m3 也可能是两套权重，地址必须进身份");
    }

    @Test
    @DisplayName("维度变了 → 不是同一个（集合维度是建时冻结的）")
    void changedDimensionsIsNotSame() {
        ModelProfile a = profile("https://api.openai.com/v1", "text-embedding-3-small", 1536);
        ModelProfile b = profile("https://api.openai.com/v1", "text-embedding-3-small", 512);

        assertFalse(VectorIdentity.same(a, b),
                "同模型可显式指定输出维度，维度一变集合就得按新维度重建");
    }

    @Test
    @DisplayName("地址的大小写与尾部斜杠不算变更：复制粘贴一下不该触发全库重灌")
    void baseUrlIsNormalizedForComparison() {
        ModelProfile a = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3", 1024);
        ModelProfile b = profile("  HTTPS://API.SiliconFlow.cn/v1//  ", "BAAI/bge-m3", 1024);

        assertTrue(VectorIdentity.same(a, b));
        assertEquals("https://api.siliconflow.cn/v1", VectorIdentity.of(b).baseUrl());
    }

    @Test
    @DisplayName("未声明维度与声明了维度不相等：拿不准就按不同处理，宁可多问一句")
    void nullDimensionsDiffersFromConcreteValue() {
        ModelProfile declared = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3", 1024);
        ModelProfile undeclared = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3", null);

        assertFalse(VectorIdentity.same(declared, undeclared),
                "一个声明 1024、一个未声明，无法在本地断言它们一定同维度，按不同处理");
        assertNotEquals(VectorIdentity.of(declared), VectorIdentity.of(undeclared));
    }

    @Test
    @DisplayName("未配置（null）只与未配置相等：从无到有、从有到无都是真实变更")
    void nullProfileOnlyEqualsNullProfile() {
        ModelProfile vec = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3", 1024);

        assertTrue(VectorIdentity.same(null, null));
        assertFalse(VectorIdentity.same(null, vec), "首次配置向量模型也要走确认（有内容时）");
        assertFalse(VectorIdentity.same(vec, null), "取消配置同样是一次真实变更");
        assertNull(VectorIdentity.of(null));
    }

    @Test
    @DisplayName("模型名的首尾空白不算不同：表单里多打一个空格不该变成另一个模型")
    void modelNameIsTrimmed() {
        ModelProfile a = profile("https://api.siliconflow.cn/v1", "BAAI/bge-m3", 1024);
        ModelProfile b = profile("https://api.siliconflow.cn/v1", "  BAAI/bge-m3  ", 1024);

        assertTrue(VectorIdentity.same(a, b));
    }
}
