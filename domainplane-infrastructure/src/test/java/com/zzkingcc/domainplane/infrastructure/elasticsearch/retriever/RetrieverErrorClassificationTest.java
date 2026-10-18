package com.zzkingcc.domainplane.infrastructure.elasticsearch.retriever;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.transport.ElasticsearchTransport;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #47：两个检索器原先只 {@code catch (IOException)}。
 *
 * <p>ES 返回 4xx/5xx 时抛的是 {@link ElasticsearchException}，运行时异常，
 * {@code IOException} 接不住，直接逃逸出检索链。逃逸的后果不是"报错"这么轻松：
 * 上层按 {@code ErrorCode} 分类的兜底逻辑被绕过，前端拿到的是"未知错误"，
 * 用户既不知道该重试还是该改配置。</p>
 *
 * <p>本测试钉住：任何 ES 侧故障都必须转成带 {@code ErrorCode} 的
 * {@link KnowledgeBaseException}。</p>
 *
 * @author zzkingcc
 */
@DisplayName("ES 检索器：上游故障必须转成带 ErrorCode 的领域异常")
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

    /** 只负责给出一个向量，让 NativeScript 检索器走到 search 那一步 */
    private static final class StubEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            return Response.from(List.of(Embedding.from(new float[]{0.1f, 0.2f, 0.3f})));
        }
    }

    /** ES 的 500：运行时异常，IOException 接不住的那一类 */
    private static ElasticsearchException esServerError() {
        ErrorResponse resp = ErrorResponse.of(b -> b
                .status(500)
                .error(ErrorCause.of(c -> c.type("server_error").reason("分片不可用"))));
        return new ElasticsearchException("server_error: 分片不可用", resp);
    }

    @Test
    @DisplayName("关键词检索：ES 500 → KNOWLEDGE_SEARCH_ERROR")
    void keywordRetrieverWrapsEsFailure() {
        KeywordMatchContentRetriever retriever = new KeywordMatchContentRetriever(
                stubClient(esServerError()), "kb_default.sales", 10, "75%");

        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                () -> retriever.retrieve(Query.from("向量检索为什么报错")));

        assertEquals(ErrorCode.KNOWLEDGE_SEARCH_ERROR, e.getErrorCode(),
                "ES 故障必须归到检索错误这一类，不能逃逸成未知异常");
    }

    @Test
    @DisplayName("脚本打分检索：ES 500 → KNOWLEDGE_SEARCH_ERROR")
    void scriptScoreRetrieverWrapsEsFailure() {
        NativeScriptScoreContentRetriever retriever = new NativeScriptScoreContentRetriever(
                stubClient(esServerError()), "kb_default.sales", new StubEmbeddingModel(), 10, 0.5);

        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                () -> retriever.retrieve(Query.from("向量检索为什么报错")));

        assertEquals(ErrorCode.KNOWLEDGE_SEARCH_ERROR, e.getErrorCode());
    }

    @Test
    @DisplayName("IO 故障同样被分类：不能因为换了个异常类型就逃逸")
    void ioFailureIsAlsoClassified() {
        KeywordMatchContentRetriever retriever = new KeywordMatchContentRetriever(
                stubClient(new IOException("连接被重置")), "kb_default.sales", 10, "75%");

        KnowledgeBaseException e = assertThrows(KnowledgeBaseException.class,
                () -> retriever.retrieve(Query.from("连接被重置")));

        assertEquals(ErrorCode.KNOWLEDGE_SEARCH_ERROR, e.getErrorCode());
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
