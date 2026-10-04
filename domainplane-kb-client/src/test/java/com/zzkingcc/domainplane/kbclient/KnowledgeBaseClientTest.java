package com.zzkingcc.domainplane.kbclient;

import com.zzkingcc.domainplane.api.code.ErrorCode;
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
 * 知识库客户端：上传 / 列表 / 删除。
 *
 * <p>用 {@code exchangeFunction} 挡掉真实网络，只验证本 SDK 的契约部分 ——
 * 请求发到哪个路径、带没带域与凭证、响应怎么还原成返回值或带码异常。</p>
 */
class KnowledgeBaseClientTest {

    private static final String LOGIN_PATH = "/api/agent/login";
    private static final String DOCUMENTS_PATH = "/admin/kb/documents";

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

    // ==================== 上传 ====================

    @Test
    void 上传成功返回入库结果() {
        respond(HttpStatus.OK, "{\"code\":0,\"success\":true,\"docId\":\"d1\","
                + "\"fileName\":\"员工手册.pdf\",\"size\":1234,\"chunks\":7}");

        KnowledgeBaseClient.UploadResult result = kb.upload(bytes("内容"), "员工手册.pdf", false);

        assertEquals("d1", result.docId());
        assertEquals("员工手册.pdf", result.fileName());
        assertEquals(1234L, result.size());
        assertEquals(7, result.chunks());

        List<ClientRequest> uploads = businessRequests(DOCUMENTS_PATH);
        assertEquals(1, uploads.size());
        assertEquals("POST", uploads.get(0).method().name());
        assertEquals("replace=false", uploads.get(0).url().getQuery(), "不声明域时不该凭空带上 domain 参数");
    }

    @Test
    void 上传到指定域把域写进查询参数() {
        respond(HttpStatus.OK, "{\"code\":0,\"success\":true,\"docId\":\"d2\","
                + "\"fileName\":\"销售政策.docx\",\"size\":10,\"chunks\":1}");

        kb.upload(bytes("内容"), "销售政策.docx", true, "default.sales");

        String query = businessRequests(DOCUMENTS_PATH).get(0).url().getQuery();
        assertEquals("replace=true&domain=default.sales", query);
    }

    @Test
    void 上传的域首尾空白被规范化() {
        respond(HttpStatus.OK, "{\"code\":0,\"success\":true,\"docId\":\"d2\","
                + "\"fileName\":\"a.docx\",\"size\":1,\"chunks\":1}");

        kb.upload(bytes("x"), "a.docx", false, "  default.sales  ");

        assertTrue(businessRequests(DOCUMENTS_PATH).get(0).url().getQuery().contains("domain=default.sales"));
    }

    @Test
    void 上传的空白域等同于不声明域() {
        respond(HttpStatus.OK, "{\"code\":0,\"success\":true,\"docId\":\"d2\","
                + "\"fileName\":\"a.docx\",\"size\":1,\"chunks\":1}");

        kb.upload(bytes("x"), "a.docx", false, "   ");

        assertEquals("replace=false", businessRequests(DOCUMENTS_PATH).get(0).url().getQuery());
    }

    @Test
    void 上传的业务失败还原成带码异常() {
        // 服务端把"同名"这类业务失败也包成 200 + success=false，客户端要还原成可分支的码
        respond(HttpStatus.OK, "{\"code\":60005,\"success\":false,\"detail\":\"同名文档已存在\"}");

        DomainPlaneException ex = assertThrows(DomainPlaneException.class,
                () -> kb.upload(bytes("内容"), "员工手册.pdf", false));

        assertEquals(ErrorCode.KNOWLEDGE_DOCUMENT_DUPLICATE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("同名文档已存在"));
    }

    @Test
    void 上传的传输层失败翻译成带码异常() {
        respond(HttpStatus.UNAUTHORIZED, "{}");

        DomainPlaneException ex = assertThrows(DomainPlaneException.class,
                () -> kb.upload(bytes("内容"), "员工手册.pdf", false));

        assertEquals(ErrorCode.AUTH_REQUIRED, ex.getErrorCode());
    }

    @Test
    void 空内容或空文件名不发请求直接拒绝() {
        respond(HttpStatus.OK, "{}");

        assertEquals(ErrorCode.INVALID_PARAMETER, assertThrows(DomainPlaneException.class,
                () -> kb.upload(new byte[0], "a.pdf", false)).getErrorCode());
        assertEquals(ErrorCode.INVALID_PARAMETER, assertThrows(DomainPlaneException.class,
                () -> kb.upload(bytes("内容"), "  ", false)).getErrorCode());

        assertTrue(requests.isEmpty(), "本地能判定的非法入参不该先打一趟服务端");
    }

    // ==================== 列表 ====================

    @Test
    void 列表还原已入库文档() {
        respond(HttpStatus.OK, "{\"code\":0,\"count\":2,\"documents\":["
                + "{\"docId\":\"d1\",\"fileName\":\"员工手册.pdf\",\"chunks\":7},"
                + "{\"docId\":\"d2\",\"fileName\":\"销售政策.docx\",\"chunks\":3}]}");

        List<KnowledgeBaseClient.DocumentItem> documents = kb.list();

        assertEquals(2, documents.size());
        assertEquals("d1", documents.get(0).docId());
        assertEquals("员工手册.pdf", documents.get(0).fileName());
        assertEquals(7, documents.get(0).chunks());
        assertEquals("d2", documents.get(1).docId());
        assertEquals("GET", businessRequests(DOCUMENTS_PATH).get(0).method().name());
    }

    @Test
    void 列表没有documents字段时返回空列表而不是抛异常() {
        respond(HttpStatus.OK, "{\"code\":0}");

        assertTrue(kb.list().isEmpty(), "空知识库是正常状态，不该当成错误");
    }

    // ==================== 删除 ====================

    @Test
    void 删除返回是否命中() {
        respond(HttpStatus.OK, "{\"code\":0,\"deleted\":true}");
        assertTrue(kb.delete("d1"));

        respond(HttpStatus.OK, "{\"code\":0,\"deleted\":false}");
        assertFalse(kb.delete("not-exist"), "没命中不算错误，返回 false 让调用方自己决定");

        List<ClientRequest> deletes = businessRequests("/admin/kb/documents/d1");
        assertEquals(1, deletes.size());
        assertEquals("DELETE", deletes.get(0).method().name());
    }

    @Test
    void 删除的空docId直接拒绝() {
        respond(HttpStatus.OK, "{}");

        assertEquals(ErrorCode.INVALID_PARAMETER,
                assertThrows(DomainPlaneException.class, () -> kb.delete("  ")).getErrorCode());
        assertTrue(requests.isEmpty());
    }

    // ==================== 凭证 ====================

    @Test
    void 业务调用都带上约定凭证头() {
        respond(HttpStatus.OK, "{\"code\":0}");

        kb.list();

        ClientRequest list = businessRequests(DOCUMENTS_PATH).get(0);
        assertEquals("cred-1", list.headers().getFirst(ClientCredential.CREDENTIAL_HEADER));
        assertEquals(1, requests.size() - 1, "同一份凭证换一次就够，业务调用不该各自重登");
    }
}
