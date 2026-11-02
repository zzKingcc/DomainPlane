package com.zzkingcc.domainplane.server.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管控台「向量库」配置段（InfraSettings.VectorStore）的形态校验与取值回落。
 *
 * <p>只判断"填得像不像"，不测连通性——连通性是「测试连接」按钮的职责。
 * 关键是：未配置是合法状态（服务照常启动），且口令绝不原样回传。</p>
 *
 * @author zzkingcc
 */
@DisplayName("InfraSettings.VectorStore：配置段取值 / 形态校验 / 脱敏")
class InfraSettingsVectorStoreTest {

    @Test
    @DisplayName("未配置是合法状态：isUsable=false、度量按 cosine、前缀空、不报形态问题")
    void unconfigured_isValidState() {
        InfraSettings.VectorStore vs = new InfraSettings.VectorStore();

        assertFalse(vs.isUsable(), "地址为空即'未配置'，是合法状态不是错误");
        assertEquals("cosine", vs.effectiveMetric(), "度量缺省按 cosine");
        assertEquals("", vs.effectiveCollectionPrefix(), "前缀缺省为空");
        assertTrue(vs.validate().isEmpty(), "未配置时不该报形态问题（否则保存会被卡死）");
    }

    @Test
    @DisplayName("已配置：可用、度量/前缀生效、口令脱敏、形态通过")
    void configured_effectiveValuesAndMasking() {
        InfraSettings.VectorStore vs = new InfraSettings.VectorStore();
        vs.setUri("http://qdrant.internal:6333");
        vs.setToken("secret-token");
        vs.setMetric("dot");
        vs.setCollectionPrefix("prod_");

        assertTrue(vs.isUsable());
        assertEquals("dot", vs.effectiveMetric());
        assertEquals("prod_", vs.effectiveCollectionPrefix());
        String masked = vs.getMaskedToken();
        assertTrue(masked.contains("******"), "口令必须脱敏：" + masked);
        assertTrue(vs.validate().isEmpty());
    }

    @Test
    @DisplayName("形态校验：缺协议前缀的地址、不支持的度量都应被点名")
    void validate_flagsBadUriAndMetric() {
        InfraSettings.VectorStore vs = new InfraSettings.VectorStore();
        vs.setUri("qdrant.internal:6333");   // 缺 http:// 前缀
        vs.setMetric("hamming");              // 不支持

        List<String> problems = vs.validate();
        assertTrue(problems.stream().anyMatch(p -> p.contains("协议前缀")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("距离度量")), problems.toString());
    }
}
