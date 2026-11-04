package com.zzkingcc.domainplane.kbclient;

import com.zzkingcc.domainplane.clientcore.exception.ClientErrorCode;
import com.zzkingcc.domainplane.clientcore.exception.ServerRejectedException;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneException;
import com.zzkingcc.domainplane.clientcore.http.ClientCredential;
import com.zzkingcc.domainplane.clientcore.properties.ClientProperties;
import com.zzkingcc.domainplane.sdkcore.config.DomainPlaneProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库客户端：知识体查询 / 文档上传 / 列表 / 删除。
 *
 * <p>用 {@code exchangeFunction} 挡掉真实网络，只验证本 SDK 的契约部分：请求发到哪个路径、
 * 带没带知识体与凭证、响应怎么还原成返回值或带码异常。</p>
 *
 * <p>v2 的契约变化：所有文档操作都必须给 {@code bodyId}——文档落在知识体里，"传到哪儿"
 * 不能靠默认域猜；域只决定"哪些对话能查到"，那是服务端的事。</p>
 */
class KnowledgeBaseClientTest {

    private static final String LOGIN_PATH = "/api/agent/login";
    private static final String BODIES_PATH = "/admin/bodies";
    private static final String DOCS_PATH = "/admin/bodies/kb-sales/documents";

    private final List<ClientRequest> requests = new ArrayList<>();

    private Function<ClientRequest, ClientResponse> responder;
    private KnowledgeBaseClient kb;

    @BeforeEach
    void setUp() {
        DomainPlaneProperties server = new DomainPlaneProperties();
        server.setServer("http://localhost:9527");
        server.setUsername("domainplane");
        server.setPassword("domainplane");

        ClientProperties properties = new ClientProperties();
        WebClient webClient = WebClient.builder()
                .baseUrl(server.getServerUrl())
                .exchangeFunction(request -> {
                    requests.add(request);
                    return Mono.just(responder.apply(request));
                })
                .build();

        kb = new KnowledgeBaseClient(webClient, properties, new ClientCredential(webClient, server, properties));
    }

    /** 登录固定换到 cred-1，其余请求回给定的响应 */
    private void respond(HttpStatus status, String body) {
        responder = request -> LOGIN_PATH.equals(request.url().getPath())
                ? json(HttpStatus.OK, "{\"credential\":\"cred-1\"}")
                : json(status, body);
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    /** 只看业务请求，跳过登录那一次 */
    private List<ClientRequest> businessRequests(String path) {
        return requests.stream().filter(r -> path.equals(r.url().getPath())).toList();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ==================== 知识体 ====================

    @Test
    void 知识体列表还原身份与计数() {
        respond(HttpStatus.OK, "{\"code\":0,\"bodies\":["
                + "{\"id\":\"kb-sales\",\"name\":\"销售知识\",\"slug\":\"sales-kb\","
                + "\"index\":\"domainplane_kb_sales-kb_1a2b3c4d\",\"documents\":2,\"chunks\":7},"
                + "{\"id\":\"kb-hr\",\"name\":\"人事\",\"slug\":\"hr-kb\","
                + "\"index\":\"domainplane_kb_hr-kb_9f8e7d6c\",\"documents\":0,\"chunks\":0}]}");

        List<KnowledgeBaseClient.KnowledgeBody> bodies = kb.bodies();

        assertEquals(2, bodies.size());
        assertEquals("kb-sales", bodies.get(0).id());
        assertEquals("sales-kb", bodies.get(0).slug());
        assertEquals("domainplane_kb_sales-kb_1a2b3c4d", bodies.get(0).index());
        assertEquals(2L, bodies.get(0).documents());
        assertEquals(7L, bodies.get(0).chunks());
        assertEquals("GET", businessRequests(BODIES_PATH).get(0).method().name());
    }

    @Test
    void 知识体列表缺字段时返回空列表() {
        respond(HttpStatus.OK, "{\"code\":0}");
        assertTrue(kb.bodies().isEmpty(), "没有体是正常状态（刚部署），不该当错误");
    }

    // ==================== 上传 ====================

    @Test
    void 上传成功返回入库结果并带上知识体路径() {
        respond(HttpStatus.OK, "{\"code\":0,\"docId\":\"d1\","
                + "\"fileName\":\"员工手册.pdf\",\"size\":1234,\"chunks\":7}");

        KnowledgeBaseClient.UploadResult result = kb.upload(bytes("内容"), "员工手册.pdf", false, "kb-sales");

        assertEquals("d1", result.docId());
        assertEquals("员工手册.pdf", result.fileName());
        assertEquals(1234L, result.size());
        assertEquals(7, result.chunks());

        List<ClientRequest> uploads = businessRequests(DOCS_PATH);
        assertEquals(1, uploads.size());
        assertEquals("POST", uploads.get(0).method().name());
        assertEquals("replace=false", uploads.get(0).url().getQuery(), "不该再出现 domain 参数");
    }

    @Test
    void 知识体首尾空白被规范化() {
        respond(HttpStatus.OK, "{\"code\":0,\"docId\":\"d2\",\"fileName\":\"a.docx\",\"size\":1,\"chunks\":1}");

        kb.upload(bytes("x"), "a.docx", true, "  kb-sales  ");

        assertEquals("replace=true", businessRequests(DOCS_PATH).get(0).url().getQuery());
    }

    @Test
    void 上传的业务失败原样带出服务端码与文案() {
        // 服务端把"同名"这类业务失败也包成 200 + success=false。
        // 客户端不翻译这个码：60005 是什么含义由宿主判断，SDK 只保证它原样到达。
        respond(HttpStatus.OK, "{\"code\":60005,\"success\":false,\"detail\":\"同名文档已存在\"}");

        ServerRejectedException ex = assertThrows(ServerRejectedException.class,
                () -> kb.upload(bytes("内容"), "员工手册.pdf", false, "kb-sales"));

        assertEquals(ClientErrorCode.SERVER_REJECTED, ex.getClientCode(), "客户端侧只归类为'服务端拒绝'");
        assertEquals(60005, ex.getServerCode(), "服务端的码必须原样带出");
        assertTrue(ex.getMessage().contains("同名文档已存在"));
    }

    @Test
    void 上传的传输层失败归为客户端自己的码() {
        respond(HttpStatus.UNAUTHORIZED, "{}");

        DomainPlaneException ex = assertThrows(DomainPlaneException.class,
                () -> kb.upload(bytes("内容"), "员工手册.pdf", false, "kb-sales"));

        assertEquals(ClientErrorCode.CREDENTIAL_REJECTED, ex.getClientCode());
    }

    @Test
    void 空内容空文件名或空知识体不发请求直接拒绝() {
        respond(HttpStatus.OK, "{}");

        assertEquals(ClientErrorCode.INVALID_ARGUMENT, assertThrows(DomainPlaneException.class,
                () -> kb.upload(new byte[0], "a.pdf", false, "kb-sales")).getClientCode());
        assertEquals(ClientErrorCode.INVALID_ARGUMENT, assertThrows(DomainPlaneException.class,
                () -> kb.upload(bytes("内容"), "  ", false, "kb-sales")).getClientCode());
        assertEquals(ClientErrorCode.INVALID_ARGUMENT, assertThrows(DomainPlaneException.class,
                () -> kb.upload(bytes("内容"), "a.pdf", false, "   ")).getClientCode(),
                "没给知识体不能猜一个默认值");

        assertTrue(requests.isEmpty(), "本地能判定的非法入参不该先打一趟服务端");
    }

    // ==================== 列表 ====================

    @Test
    void 列表还原已入库文档() {
        respond(HttpStatus.OK, "{\"code\":0,\"count\":2,\"documents\":["
                + "{\"docId\":\"d1\",\"fileName\":\"员工手册.pdf\",\"chunks\":7},"
                + "{\"docId\":\"d2\",\"fileName\":\"销售政策.docx\",\"chunks\":3}]}");

        List<KnowledgeBaseClient.DocumentItem> documents = kb.list("kb-sales");

        assertEquals(2, documents.size());
        assertEquals("d1", documents.get(0).docId());
        assertEquals("员工手册.pdf", documents.get(0).fileName());
        assertEquals(7, documents.get(0).chunks());
        assertEquals("d2", documents.get(1).docId());
        assertEquals("GET", businessRequests(DOCS_PATH).get(0).method().name());
    }

    @Test
    void 列表没有documents字段时返回空列表而不是抛异常() {
        respond(HttpStatus.OK, "{\"code\":0}");

        assertTrue(kb.list("kb-sales").isEmpty(), "空知识体是正常状态，不该当成错误");
    }

    @Test
    void 列表的空知识体直接拒绝() {
        respond(HttpStatus.OK, "{}");
        assertEquals(ClientErrorCode.INVALID_ARGUMENT,
                assertThrows(DomainPlaneException.class, () -> kb.list(null)).getClientCode());
        assertTrue(requests.isEmpty());
    }

    // ==================== 删除 ====================

    @Test
    void 删除返回是否命中() {
        respond(HttpStatus.OK, "{\"code\":0,\"deleted\":true}");
        assertTrue(kb.delete("kb-sales", "d1"));

        respond(HttpStatus.OK, "{\"code\":0,\"deleted\":false}");
        assertFalse(kb.delete("kb-sales", "not-exist"), "没命中不算错误，返回 false 让调用方自己决定");

        List<ClientRequest> deletes = businessRequests(DOCS_PATH + "/d1");
        assertEquals(1, deletes.size());
        assertEquals("DELETE", deletes.get(0).method().name());
    }

    @Test
    void 删除的空docId或空知识体直接拒绝() {
        respond(HttpStatus.OK, "{}");

        assertEquals(ClientErrorCode.INVALID_ARGUMENT,
                assertThrows(DomainPlaneException.class, () -> kb.delete("kb-sales", "  ")).getClientCode());
        assertEquals(ClientErrorCode.INVALID_ARGUMENT,
                assertThrows(DomainPlaneException.class, () -> kb.delete(" ", "d1")).getClientCode());
        assertTrue(requests.isEmpty());
    }

    // ==================== 凭证 ====================

    @Test
    void 业务调用都带上约定凭证头() {
        respond(HttpStatus.OK, "{\"code\":0}");

        kb.list("kb-sales");

        ClientRequest list = businessRequests(DOCS_PATH).get(0);
        assertEquals("cred-1", list.headers().getFirst(ClientCredential.CREDENTIAL_HEADER));
        assertEquals(1, requests.size() - 1, "同一份凭证换一次就够，业务调用不该各自重登");
    }
}
