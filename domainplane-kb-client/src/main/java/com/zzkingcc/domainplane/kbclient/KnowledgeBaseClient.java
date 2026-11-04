package com.zzkingcc.domainplane.kbclient;

import com.zzkingcc.domainplane.clientcore.exception.ClientErrorCode;
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
 * 知识库客户端：知识体的查询 + 文档的上传 / 列表 / 删除。
 *
 * <p>v2 起一切以<b>知识体</b>为起点（不再按域）：文档落在某个知识体里，域只是消费者。
 * 所以这里的每个方法都带 {@code bodyId}——"传到哪儿"必须是显式的，不能靠"默认域"猜。</p>
 *
 * <p>只管"把内容放进去"与"拿出来"，检索是服务端的事：模型通过检索工具自动取用，
 * SDK 侧没有检索入口。哪些对话能查到这份文档，由服务端"域绑了哪个体"决定。</p>
 *
 * <p>不在启动期连接服务端：远端不可达由首次调用暴露，带码的异常与对话 SDK 完全一致。</p>
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

    /** 知识体（数据身份：唯一名 + 冻结索引 + 文档数） */
    public record KnowledgeBody(String id, String name, String slug, String index,
                                long documents, long chunks) {}

    /** 上传结果 */
    public record UploadResult(String docId, String fileName, long size, int chunks) {}

    /** 已入库文档条目 */
    public record DocumentItem(String docId, String fileName, int chunks) {}

    /**
     * 全部知识体（管控台「知识库」页的卡片数据源）。
     *
     * @return 知识体列表；存储不可达时服务端仍会返回配置里的体（数字为 0 / stale）
     */
    @SuppressWarnings("unchecked")
    public List<KnowledgeBody> bodies() {
        Map<String, Object> resp = Mono.defer(() -> webClient.get()
                .uri("/admin/bodies")
                .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                .retrieve()
                .bodyToMono(Map.class))
                .onErrorMap(DomainPlaneErrors::fromTransport)
                .block(properties.getReadTimeout());
        if (resp == null || !(resp.get("bodies") instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(Map.class::isInstance)
                .map(o -> (Map<String, Object>) o)
                .map(m -> new KnowledgeBody(str(m.get("id")), str(m.get("name")), str(m.get("slug")),
                        str(m.get("index")),
                        m.get("documents") instanceof Number n ? n.longValue() : 0L,
                        m.get("chunks") instanceof Number n ? n.longValue() : 0L))
                .toList();
    }

    /**
     * 上传一个文档到指定知识体。
     *
     * @param content  文件字节
     * @param fileName 文件名（含扩展名，需在服务端白名单内）
     * @param replace  {@code true} = 该体索引内已存在同名文档时覆盖更新；{@code false} = 同名直接拒绝
     * @param bodyId   落到哪个知识体（见 {@link #bodies()}）
     */
    public UploadResult upload(byte[] content, String fileName, boolean replace, String bodyId) {
        if (content == null || content.length == 0) {
            throw new DomainPlaneException(ClientErrorCode.INVALID_ARGUMENT, "上传内容为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new DomainPlaneException(ClientErrorCode.INVALID_ARGUMENT, "文件名不能为空");
        }
        if (bodyId == null || bodyId.isBlank()) {
            throw new DomainPlaneException(ClientErrorCode.INVALID_ARGUMENT,
                    "bodyId 不能为空（文档落在知识体里，请先在管控台「知识库」页建一个）");
        }
        String body = bodyId.trim();
        log.info("[DomainPlane客户端] 上传知识库文档：文件={}，大小={} 字节，replace={}，知识体={}",
                fileName, content.length, replace, body);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });

        // defer：凭证在首次调用时才换取。放在链外同步 get() 的话，换凭证失败会直接抛在
        // 本方法调用处，onErrorMap 拦不到，接入方拿到的就不是带码的业务异常。
        Map<String, Object> resp = Mono.defer(() -> webClient.post()
                .uri(uri -> uri.path("/admin/bodies/{bodyId}/documents")
                        .queryParam("replace", replace)
                        .build(body))
                .header(ClientCredential.CREDENTIAL_HEADER, credential.get())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(Map.class))
                .onErrorMap(DomainPlaneErrors::fromTransport)
                .block(properties.getReadTimeout());

        if (resp == null) {
            throw new DomainPlaneException(ClientErrorCode.RESPONSE_UNREADABLE, "上传知识库文档无响应");
        }
        if (Boolean.FALSE.equals(resp.get("success"))) {
            // v2 的服务端把业务失败直接映射成非 200（上面 onErrorMap 已还原成带码异常）；
            // 这里留着是为兼容 v1 服务端那种"200 + success=false"的旧包装。
            throw DomainPlaneErrors.fromResponseBody(resp);
        }
        return new UploadResult(
                str(resp.get("docId")),
                str(resp.get("fileName")),
                resp.get("size") instanceof Number n ? n.longValue() : 0L,
                resp.get("chunks") instanceof Number n ? n.intValue() : 0);
    }

    /** 某个知识体里的文档列表 */
    @SuppressWarnings("unchecked")
    public List<DocumentItem> list(String bodyId) {
        if (bodyId == null || bodyId.isBlank()) {
            throw new DomainPlaneException(ClientErrorCode.INVALID_ARGUMENT, "bodyId 不能为空");
        }
        Map<String, Object> resp = Mono.defer(() -> webClient.get()
                .uri("/admin/bodies/{bodyId}/documents", bodyId.trim())
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
     * 删除知识体里的一个文档并释放其文件名（删除后同名可再次上传）。
     *
     * @return 是否命中并删除
     */
    public boolean delete(String bodyId, String docId) {
        if (bodyId == null || bodyId.isBlank()) {
            throw new DomainPlaneException(ClientErrorCode.INVALID_ARGUMENT, "bodyId 不能为空");
        }
        if (docId == null || docId.isBlank()) {
            throw new DomainPlaneException(ClientErrorCode.INVALID_ARGUMENT, "docId 不能为空");
        }
        Map<String, Object> resp = Mono.defer(() -> webClient.delete()
                .uri("/admin/bodies/{bodyId}/documents/{docId}", bodyId.trim(), docId)
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
