package com.zzkingcc.domainplane.kbclient;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneErrors;
import com.zzkingcc.domainplane.clientcore.exception.DomainPlaneException;
import com.zzkingcc.domainplane.clientcore.http.ClientCredential;
import com.zzkingcc.domainplane.clientcore.properties.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 知识库客户端：文档的上传 / 列表 / 删除。
 *
 * 只管"把内容放进去"与"拿出来"——检索是服务端的事：模型通过检索工具自动取用，
 * SDK 侧没有检索入口，也没有"检索参数"要配。这里声明的域决定哪些对话能检索到这份文档。
 *
 * 不在启动期连接服务端：远端不可达由首次调用暴露，带码的异常与对话 SDK 完全一致。
 *
 * @author zzkingcc
 */
public class KnowledgeBaseClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseClient.class);

    private final WebClient webClient;
    private final ClientProperties properties;
    private final ClientCredential credential;

    public KnowledgeBaseClient(WebClient webClient, ClientProperties properties, ClientCredential credential) {
        this.webClient = webClient;
        this.properties = properties;
        this.credential = credential;
    }

    /** 上传结果 */
    public record UploadResult(String docId, String fileName, long size, int chunks) {}

    /** 已入库文档条目 */
    public record DocumentItem(String docId, String fileName, int chunks) {}

    /**
     * 上传一个文档到根域 {@code default} 的索引（按累加语义对全树可见）。
     *
     * @param content  文件字节
     * @param fileName 文件名（含扩展名，需在服务端白名单内）
     * @param replace  {@code true} = 该域索引内已存在同名文档时覆盖更新；{@code false} = 同名直接拒绝
     */
    public UploadResult upload(byte[] content, String fileName, boolean replace) {
        return upload(content, fileName, replace, null);
    }

    /**
     * 上传一个文档，并指定它归属的域（即落到该域的索引里）。
     *
     * 域决定哪些对话能检索到这份文档，与 {@code @Tool(domains = {...})} 同构：
     * 必须是从根域出发的完整路径，挂在该域即其全部后代域都能检索到；
     * 留空 = 挂根域 = 全域可见。没有通配写法，路径非法会被服务端直接拒绝。
     */
    public UploadResult upload(byte[] content, String fileName, boolean replace, String domain) {
        if (content == null || content.length == 0) {
            throw new DomainPlaneException(ErrorCode.INVALID_PARAMETER, "上传内容为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new DomainPlaneException(ErrorCode.INVALID_PARAMETER, "文件名不能为空");
        }
        log.info("[DomainPlane客户端] 上传知识库文档：文件={}，大小={} 字节，replace={}",
                fileName, content.length, replace);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });

        String effectiveDomain = domain == null ? null : domain.trim();

        // defer：凭证在首次调用时才换取。放在链外同步 get() 的话，换凭证失败会直接抛在
        // 本方法调用处，onErrorMap 拦不到，接入方拿到的就不是带码的业务异常。
        Map<String, Object> resp = Mono.defer(() -> webClient.post()
                .uri(uri -> {
                    uri.path("/admin/kb/documents").queryParam("replace", replace);
                    if (effectiveDomain != null && !effectiveDomain.isEmpty()) {
                        uri.queryParam("domain", effectiveDomain);
                    }
                    return uri.build();
                })
                .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(Map.class))
                .onErrorMap(DomainPlaneErrors::fromTransport)
                .block(properties.getReadTimeout());

        if (resp == null) {
            throw new DomainPlaneException(ErrorCode.UNEXPECTED_ERROR, "上传知识库文档无响应");
        }
        if (Boolean.FALSE.equals(resp.get("success"))) {
            // 服务端把业务失败也包成 200 + success=false，这里还原成带码异常，
            // 接入方才能按"重名 / 类型不支持 / 超限"分别处理
            throw DomainPlaneErrors.fromResponseBody(resp);
        }
        return new UploadResult(
                str(resp.get("docId")),
                str(resp.get("fileName")),
                resp.get("size") instanceof Number n ? n.longValue() : 0L,
                resp.get("chunks") instanceof Number n ? n.intValue() : 0);
    }

    /** 已入库文档列表 */
    @SuppressWarnings("unchecked")
    public List<DocumentItem> list() {
        Map<String, Object> resp = Mono.defer(() -> webClient.get()
                .uri("/admin/kb/documents")
                .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                .retrieve()
                .bodyToMono(Map.class))
                .onErrorMap(DomainPlaneErrors::fromTransport)
                .block(properties.getReadTimeout());
        if (resp == null) {
            return List.of();
        }
        Object docs = resp.get("documents");
        if (!(docs instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(Map.class::isInstance)
                .map(o -> (Map<String, Object>) o)
                .map(m -> new DocumentItem(str(m.get("docId")), str(m.get("fileName")),
                        m.get("chunks") instanceof Number n ? n.intValue() : 0))
                .toList();
    }

    /**
     * 删除文档并释放其文件名（删除后同名可再次上传）。
     *
     * @return 是否命中并删除
     */
    public boolean delete(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new DomainPlaneException(ErrorCode.INVALID_PARAMETER, "docId 不能为空");
        }
        Map<String, Object> resp = Mono.defer(() -> webClient.delete()
                .uri("/admin/kb/documents/{docId}", docId)
                .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                .retrieve()
                .bodyToMono(Map.class))
                .onErrorMap(DomainPlaneErrors::fromTransport)
                .block(properties.getReadTimeout());
        return resp != null && Boolean.TRUE.equals(resp.get("deleted"));
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
