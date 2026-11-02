package com.zzkingcc.domainplane.infrastructure.kb.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.transport.ElasticsearchTransport;
import com.zzkingcc.domainplane.domain.rag.store.StoreAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #47（平移）：两个检索器原先只 {@code catch (IOException)}。
 *
 * <p>ES 返回 4xx/5xx 时抛的是 {@link ElasticsearchException}，运行时异常，
 * {@code IOException} 接不住，直接逃逸出检索链。逃逸的后果不是"报错"这么轻松：
 * 上层按 {@code ErrorCode} 分类的兜底逻辑被绕过，前端拿到的是"未知错误"，
 * 用户既不知道该重试还是该改配置。</p>
 *
 * <p>改造后异常归类点从检索器（{@code KnowledgeBaseException}）下沉到 SPI 边界
 * （{@link StoreAccessException}）：searcher 是纯 SPI 组件，不认识 ErrorCode；
 * "归类成用户可行动的错误"由编排层按通道降级 + 工具层兜底文案完成
 * （{@code KnowledgeSearchService} 的 catch 分支）。本测试钉住的是同一件事的
 * SPI 侧形态：任何 ES 侧故障都必须被接住并转成 {@code StoreAccessException}，
 * 带着原始原因，不逃逸成未知异常。</p>
 *
 * @author zzkingcc
 */
@DisplayName("ES searcher：上游故障必须转成 StoreAccessException（不逃逸）")
class RetrieverErrorClassificationTest {

    /**
     * 传输层桩：{@code ElasticsearchClient.search(Function, Class)} 是 final，不能覆写，
     * 所以从它依赖的传输层下手，这与真实故障形态一致：ES 返回 4xx/5xx 时，
     * 就是传输层把错误变成异常交回客户端的。
     */
    private static ElasticsearchClient stubClient(Throwable failure) {
        ElasticsearchTransport transport = (ElasticsearchTransport) Proxy.newProxyInstance(
                RetrieverErrorClassificationTest.class.getClassLoader(),
                new Class<?>[]{ElasticsearchTransport.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw failure;
                });
        return new ElasticsearchClient(transport);
    }

    /** ES 的 500：运行时异常，IOException 接不住的那一类 */
    private static ElasticsearchException esServerError() {
        ErrorResponse resp = ErrorResponse.of(b -> b
                .status(500)
                .error(ErrorCause.of(c -> c.type("server_error").reason("分片不可用"))));
        return new ElasticsearchException("server_error: 分片不可用", resp);
    }

    @Test
    @DisplayName("关键词检索：ES 500 → StoreAccessException（原因进消息）")
    void keywordSearcherWrapsEsFailure() {
        EsKeywordSearcher searcher = new EsKeywordSearcher(
                stubClient(esServerError()), "75%");

        StoreAccessException e = assertThrows(StoreAccessException.class,
                () -> searcher.search("kb_default.sales", "向量检索为什么报错", 10));

        assertTrue(e.getMessage().contains("分片不可用"),
                "原始原因要带在消息里，便于排障，实际：" + e.getMessage());
        assertTrue(e.getCause() instanceof ElasticsearchException, "原始异常要保留为 cause");
    }

    @Test
    @DisplayName("向量检索：ES 500 → StoreAccessException")
    void vectorSearcherWrapsEsFailure() {
        EsVectorSearcher searcher = new EsVectorSearcher(stubClient(esServerError()), 0.5);

        StoreAccessException e = assertThrows(StoreAccessException.class,
                () -> searcher.search("kb_default.sales", new float[]{0.1f, 0.2f, 0.3f}, 10));

        assertTrue(e.getMessage().contains("分片不可用"),
                "原始原因要带在消息里，便于排障，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("IO 故障同样被接住：不能因为换了个异常类型就逃逸")
    void ioFailureIsAlsoClassified() {
        EsKeywordSearcher searcher = new EsKeywordSearcher(
                stubClient(new IOException("连接被重置")), "75%");

        StoreAccessException e = assertThrows(StoreAccessException.class,
                () -> searcher.search("kb_default.sales", "连接被重置", 10));

        assertTrue(e.getMessage().contains("连接被重置"),
                "原始原因要带在消息里，便于排障，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("桩自身健全性：异常类型确实是 ElasticsearchException（运行时）")
    void stubFailureIsRuntimeException() {
        assertTrue(esServerError() instanceof RuntimeException,
                "ElasticsearchException 必须是运行时异常，否则 catch (IOException) 就够用了");
    }
}
