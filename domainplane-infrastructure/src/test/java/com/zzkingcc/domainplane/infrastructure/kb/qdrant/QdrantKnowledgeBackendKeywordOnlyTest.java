package com.zzkingcc.domainplane.infrastructure.kb.qdrant;

import com.zzkingcc.domainplane.domain.rag.store.StoreCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯 keyword 后端（P2）的装配形态：{@code vectorEnabled=false} 时后端该怎么声明自己。
 *
 * <p>零网络：只断言能力声明与检索器的装配结果——三者都不会碰客户端，
 * 所以用 {@link VectorTarget#UNCONFIGURED} 就够了（真出网会抛未配置异常，
 * 那正好暴露"不该在这条路径上出网"）。</p>
 *
 * @author zzkingcc
 */
@DisplayName("QdrantKnowledgeBackend：纯 keyword 变体（P2）")
class QdrantKnowledgeBackendKeywordOnlyTest {

    private static QdrantHttpClient unconfiguredClient() {
        return QdrantHttpClient.fixed(VectorTarget.UNCONFIGURED, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("vector:false → 能力声明 vector=false，不接向量检索器，keyword 仍在")
    void keywordOnly_declaresVectorFalseAndWiresKeywordOnly() {
        QdrantKnowledgeBackend backend =
                new QdrantKnowledgeBackend("kw-only", unconfiguredClient(), 0.0, false);

        StoreCapabilities c = backend.capabilities();
        assertTrue(c.keyword(), "纯 keyword 后端必须声明 keyword 能力（这是它存在的意义）");
        assertFalse(c.vector(), "纯 keyword 后端必须声明 vector=false");
        assertFalse(c.wildcard(), "Qdrant 无索引模式，通配恒 false");
        assertTrue(c.storesText(), "切片正文存在 payload 里，keyword 检索要读它");
        assertFalse(c.reindexInPlace(), "不存向量 → 收不下重灌，不能作为重灌目标");

        assertTrue(backend.keyword().isPresent(), "关键词检索器必须还在");
        assertTrue(backend.vector().isEmpty(), "纯 keyword 后端不该有向量检索器（SPI 用空表达）");
        assertTrue(backend.store().isPresent(), "仍是写入主库：只写 payload，不写向量");
        assertFalse(backend.store().get().capabilities().vector(),
                "后端与 store 的能力声明必须自洽");
    }

    @Test
    @DisplayName("未显式关闭向量 → 保持 P1 的全能力行为（默认构造器回归）")
    void fullBackend_keepsVectorTrue() {
        QdrantHttpClient client = unconfiguredClient();

        QdrantKnowledgeBackend threeArg = new QdrantKnowledgeBackend("full-a", client, 0.0);
        assertTrue(threeArg.capabilities().vector(), "3 参构造器默认带向量（P1 行为不变）");
        assertTrue(threeArg.capabilities().reindexInPlace(), "带向量即可原地重灌");
        assertTrue(threeArg.vector().isPresent());

        QdrantKnowledgeBackend explicit = new QdrantKnowledgeBackend("full-b", client, 0.0, true);
        assertTrue(explicit.capabilities().vector());
        assertTrue(explicit.capabilities().reindexInPlace());
        assertTrue(explicit.vector().isPresent());
        assertTrue(explicit.keyword().isPresent());
    }
}
