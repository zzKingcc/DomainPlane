package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 最小 MCP 客户端：只实现用到的几个方法（协议协商 / tools/list / tools/call）。
 *
 * <p>同时支持三代协议语义：</p>
 * <ul>
 *   <li><b>无状态（2026-07-28 及以后）</b> —— 没有 {@code initialize} 握手、没有 {@code Mcp-Session-Id}；
 *       每个请求在 {@code params._meta} 里声明协议版本与客户端能力，HTTP 上另带
 *       {@code MCP-Protocol-Version} / {@code Mcp-Method}（{@code tools/call} 还带 {@code Mcp-Name}）。
 *       服务端必须实现 {@code server/discover}，客户端可先调它拿到版本与能力。</li>
 *   <li>{@link McpTransport#STREAMABLE_HTTP} 的<b>会话式</b>（2025-03-26 ~ 2025-11-25）—— {@code initialize}
 *       握手，会话由 {@code Mcp-Session-Id} 头维系，握手后发 {@code notifications/initialized}。</li>
 *   <li>{@link McpTransport#LEGACY_SSE}（2024-11-05）—— 先 GET /sse 开流、服务端回 {@code endpoint}
 *       事件给出动态 POST 地址，之后 POST 到该地址，响应经原 GET 流回传（按 JSON-RPC id 关联）。</li>
 * </ul>
 *
 * <p>时代判定按规范的建议做：HTTP 上先用现代请求（{@code server/discover}）试水，只有
 * "服务端不认这个方法" 才退回 {@code initialize}；认得的现代错误码（{@code -32020}/{@code -32022}）
 * 说明它说的是现代协议，不许当旧 server 处理。判定结果是 <b>server（origin）的属性</b>，
 * 不是单个请求的，所以一次建连只探测一次。</p>
 *
 * <p>不引任何 MCP SDK：只用 JDK HttpClient + Jackson，契合项目离线构建的常态。</p>
 *
 * @author zzkingcc
 */
public class McpRpcClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpRpcClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 会话式协议首选版本（既有行为，改它等于更换所有存量 server 的握手口径） */
    private static final String LEGACY_PROTOCOL_VERSION = "2025-03-26";
    /** 无状态协议版本：本平台只实现这一版，探测到别的现代版本就明确报错而不是硬猜 */
    static final String MODERN_PROTOCOL_VERSION = "2026-07-28";
    /** 会话式协议实现过的版本（服务端只支持这些时，退回 initialize 仍有意义） */
    private static final Set<String> LEGACY_VERSIONS = Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

    private static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    private static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    private static final String META_CLIENT_CAPS = "io.modelcontextprotocol/clientCapabilities";
    private static final String VERSION_HEADER = "MCP-Protocol-Version";
    private static final String METHOD_HEADER = "Mcp-Method";
    private static final String NAME_HEADER = "Mcp-Name";

    /** 2026-07-28 的版本不匹配错误（旧编号 -32004）；旧 server 收到 server/discover 回的是 -32601 */
    private static final int CODE_UNSUPPORTED_VERSION = -32022;

    private final String baseUrl;
    private final McpTransport transport;
    /** 请求头提供者：每次请求现取（OAuth token 刷新后不重建 client 即生效） */
    private final Supplier<Map<String, String>> headerSupplier;
    private final Duration callTimeout;
    private final HttpClient http;

    /** 协议时代（决定要不要握手、要不要带会话头） */
    public enum Era {
        /** 2026-07-28 起：逐请求声明版本与能力，无会话 */
        MODERN,
        /** 2025-11-25 及以前：initialize 握手 + 会话 */
        LEGACY
    }

    /**
     * 协商结果：用哪一版协议 + 服务端声明的能力集。
     * 能力只取顶层键（原值），管控台要展示「server 声明了哪些能力」，不替 server 解释细节。
     */
    public record Negotiated(String protocolVersion, Map<String, Object> capabilities, Era era) {
        public boolean modern() {
            return era == Era.MODERN;
        }
    }

    /** 协商后确定的版本（无状态时是逐请求 {@code _meta} 的值，会话式时是 initialize 的应答值） */
    private volatile String protocolVersion;
    /** 是否走无状态语义；只在 {@link #negotiate()} 里翻一次，之后建连期间不变 */
    private volatile boolean modern;

    /** Streamable HTTP 的会话 id（initialize 后由响应头带回） */
    private volatile String sessionId;
    /** Legacy SSE 的动态 POST 地址（GET /sse 的 endpoint 事件给出） */
    private volatile String sseEndpoint;
    /** Legacy SSE：GET /sse 流上按 id 收集的响应 */
    private final Map<Long, ArrayBlockingQueue<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile boolean sseRunning;
    /**
     * Legacy SSE 的响应流。
     *
     * 读线程会一直阻塞在 {@code readLine()} 上，光把 {@code sseRunning} 置 false 是叫不醒它的
     * （它要等下一行数据才回到循环条件）。必须持有流引用并在 {@link #close()} 里关掉，
     * 让读操作抛异常退出，否则每次重连都泄漏一条悬挂连接与一个常驻线程。
     */
    private volatile java.io.InputStream sseStream;

    private final AtomicLong idGen = new AtomicLong(1);

    public McpRpcClient(String baseUrl, McpTransport transport, Map<String, String> headers, int callTimeoutMs) {
        this(baseUrl, transport, () -> headers == null ? Map.of() : headers, callTimeoutMs);
    }

    public McpRpcClient(String baseUrl, McpTransport transport, Supplier<Map<String, String>> headerSupplier,
                        int callTimeoutMs) {
        this.baseUrl = baseUrl;
        this.transport = transport;
        this.headerSupplier = headerSupplier;
        this.callTimeout = Duration.ofMillis(callTimeoutMs <= 0 ? 30000 : callTimeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** 单个工具的定义（tools/list 的结果项） */
    public record McpToolInfo(String name, String description, JsonNode inputSchema, JsonNode annotations) {
    }

    /**
     * tools/call 的结果。
     *
     * <p>{@code raw} 是响应里的 {@code result} 节点原样（管控台试跑要展示"响应原文"，
     * 排障时常需要看 content 之外的 structuredContent / 自定字段）。执行器只读 {@code text}，
     * 不看 raw，避免把大对象带进模型上下文。</p>
     */
    public record McpCallResult(boolean success, String text, boolean isError, JsonNode raw) {
        /** 兼容构造：不带原文（既有调用方与测试） */
        public McpCallResult(boolean success, String text, boolean isError) {
            this(success, text, isError, null);
        }

        public static McpCallResult ok(String text) {
            return new McpCallResult(true, text, false);
        }

        public static McpCallResult fail(String text) {
            return new McpCallResult(false, text, true);
        }
    }

    /** 会话式协议 initialize 的协商结果（旧签名保留，既有调用方与测试不受影响） */
    public record InitializeResult(String protocolVersion, Map<String, Object> capabilities) {
    }

    // ==================== 协议协商 ====================

    /**
     * 建连协商：先按无状态协议探测，探不通再走 initialize 握手。
     *
     * <p>探测顺序不可颠倒：现代 server 不吃 {@code initialize}（它缺 {@code _meta}，会被判
     * 请求不合法），旧 server 不吃 {@code server/discover}（回 -32601），两边都能靠"错误长什么样"区分。</p>
     */
    public Negotiated negotiate() throws Exception {
        if (transport == McpTransport.LEGACY_SSE) {
            // HTTP+SSE 传输在 2026-07-28 里已废弃，那上面不存在无状态语义
            return legacyNegotiate();
        }
        Negotiated modernResult = tryModernProbe();
        if (modernResult != null) {
            return modernResult;
        }
        return legacyNegotiate();
    }

    private Negotiated legacyNegotiate() throws Exception {
        InitializeResult r = initializeResult();
        return new Negotiated(r.protocolVersion(), r.capabilities(), Era.LEGACY);
    }

    /**
     * 用 {@code server/discover} 探测服务端时代。
     *
     * @return 协商成功的无状态结果；{@code null} 表示"这是旧 server，去走 initialize"
     * @throws McpAuthChallengeException 401 + 挑战头：授权挑战必须原样上抛，不能吞成"旧 server"
     * @throws IllegalStateException     确认是现代 server，但没有双方都支持的版本
     */
    private Negotiated tryModernProbe() throws Exception {
        try {
            return modernProbe(MODERN_PROTOCOL_VERSION);
        } catch (McpAuthChallengeException e) {
            throw e;
        } catch (McpRpcError e) {
            if (e.code() != CODE_UNSUPPORTED_VERSION) {
                log.debug("[MCP 客户端] {} 的 server/discover 未实现（code={}），按会话式协议处理",
                        baseUrl, e.code());
                return null;
            }
            List<String> supported = stringList(e.data().path("supported"));
            if (supported.contains(MODERN_PROTOCOL_VERSION)) {
                // 服务端自报支持我们首选的那版：只可能是偶发不一致，重试一次
                return modernProbe(MODERN_PROTOCOL_VERSION);
            }
            if (supported.stream().anyMatch(LEGACY_VERSIONS::contains)) {
                log.debug("[MCP 客户端] {} 是现代 server，但只支持旧版本 {}，退回 initialize",
                        baseUrl, supported);
                return null;
            }
            throw new IllegalStateException("MCP 服务端只支持协议版本 " + supported
                    + "，本平台实现 2026-07-28（无状态）与 2025-03-26（会话），没有共同版本");
        } catch (Exception e) {
            // 400/404/500、非 JSON 体、连接抖动：一律当成"探不出来"，让 initialize 自己报真实错误
            log.debug("[MCP 客户端] {} 的 server/discover 探测未成立（{}），按会话式协议处理",
                    baseUrl, e.getMessage());
            return null;
        }
    }

    /** 发一次 server/discover；应答里没有我们实现的版本就返回 null（交给 initialize） */
    private Negotiated modernProbe(String version) throws Exception {
        JsonNode result = requireResult(modernRpc("server/discover", Map.of(), null, version));
        List<String> supported = stringList(result.path("supportedVersions"));
        if (!supported.contains(MODERN_PROTOCOL_VERSION)) {
            log.debug("[MCP 客户端] {} 的 server/discover 未声明 {}（实际 {}），退回 initialize",
                    baseUrl, MODERN_PROTOCOL_VERSION, supported);
            return null;
        }
        Map<String, Object> caps = toMap(result.path("capabilities"));
        this.protocolVersion = MODERN_PROTOCOL_VERSION;
        this.modern = true;
        log.info("[MCP 客户端] {} 协商为无状态协议 {}（服务端支持 {}，能力 {}）",
                baseUrl, MODERN_PROTOCOL_VERSION, supported, caps.keySet());
        return new Negotiated(MODERN_PROTOCOL_VERSION, caps, Era.MODERN);
    }

    /** initialize：建会话并返回协商后的协议版本（旧签名保留，既有调用方不受影响） */
    public String initialize() throws Exception {
        return initializeResult().protocolVersion();
    }

    /**
     * 会话式协议的 initialize：建会话并返回完整协商结果（协议版本 + 服务端能力集）。
     * 已协商为无状态协议时不允许再调 —— 那版规范里没有这个方法。
     */
    public InitializeResult initializeResult() throws Exception {
        if (modern) {
            throw new IllegalStateException("已协商为无状态协议 " + protocolVersion + "，不存在 initialize 握手");
        }
        if (transport == McpTransport.LEGACY_SSE) {
            openLegacySse();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", LEGACY_PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "domainplane", "version", "1.0"));
        JsonNode response = legacyRpc("initialize", params);
        JsonNode result = requireResult(response);
        String negotiated = result.path("protocolVersion").asText(LEGACY_PROTOCOL_VERSION);
        Map<String, Object> caps = toMap(result.path("capabilities"));
        // 能力声明：本平台不会反过来让 server 用模型，故如实上报不支持 sampling / roots / elicitation
        rpcNotification("notifications/initialized", Map.of());
        this.protocolVersion = negotiated;
        log.info("[MCP 客户端] {} 初始化完成（协商协议版本 {}，能力 {}）",
                baseUrl, negotiated, caps.keySet());
        return new InitializeResult(negotiated, caps);
    }

    // ==================== 业务方法 ====================

    /** tools/list：返回工具定义列表 */
    public List<McpToolInfo> toolsList() throws Exception {
        JsonNode result = requireResult(rpc("tools/list", Map.of()));
        JsonNode tools = result.path("tools");
        List<McpToolInfo> out = new ArrayList<>();
        if (tools.isArray()) {
            for (JsonNode t : tools) {
                out.add(new McpToolInfo(
                        t.path("name").asText(),
                        t.path("description").asText(""),
                        t.path("inputSchema").isMissingNode() ? MAPPER.createObjectNode() : t.path("inputSchema"),
                        t.has("annotations") ? t.path("annotations") : null));
            }
        }
        return out;
    }

    /** tools/call：调用一个工具，返回文本结果（非文本块渲染为占位说明） */
    public McpCallResult toolsCall(String toolName, String argumentsJson) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        JsonNode args = MAPPER.createObjectNode();
        if (argumentsJson != null && !argumentsJson.isBlank()) {
            try {
                args = MAPPER.readTree(argumentsJson);
                if (!args.isObject()) {
                    args = MAPPER.createObjectNode();
                }
            } catch (Exception e) {
                log.warn("[MCP 客户端] 工具参数不是合法 JSON，按空参数发送: {}", abbreviate(argumentsJson));
                args = MAPPER.createObjectNode();
            }
        }
        params.put("arguments", args);
        // 工具名要做 Mcp-Name 头（无状态协议要求），故按名传下去
        JsonNode result = requireResult(rpc("tools/call", params, toolName));
        // 2026-07-28 的所有 result 都带 resultType；旧 server 不带，按 complete 处理（规范明确要求）
        if ("input_required".equals(result.path("resultType").asText("complete"))) {
            // MRTR（多轮往返）本平台未实现：如实报"续不了"，不能把它当空结果静默成功
            return new McpCallResult(false,
                    "服务端要求补充输入（resultType=input_required，"
                            + inputRequestsBrief(result) + "），本平台未实现多轮往返（MRTR），无法续跑",
                    true, result);
        }
        boolean isError = result.path("isError").asBoolean(false);
        JsonNode content = result.path("content");
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(block.path("text").asText(""));
                } else {
                    // 非文本块（image/resource/audio）：不静默丢，渲染一行占位说明
                    String mime = block.path("mimeType").asText("未知类型");
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append("[非文本块: ").append(mime).append("，本平台暂不支持展示]");
                }
            }
        }
        return isError
                ? new McpCallResult(false, sb.toString(), true, result)
                : new McpCallResult(true, sb.toString(), false, result);
    }

    /** 关闭：停掉 Legacy SSE 读流并释放 HttpClient（每个客户端独有一个连接池/选择器） */
    @Override
    public void close() {
        sseRunning = false;
        // 关流是叫醒读线程的唯一办法：它阻塞在 readLine() 上，等不到下一行就不会回头看标志位。
        // 只置标志 + 关 HttpClient 时，那条悬挂连接与那个线程会一直留着，每次重连再泄漏一份。
        java.io.InputStream stream = sseStream;
        sseStream = null;
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {
                // 流已经断了，关不掉也不影响语义
            }
        }
        try {
            http.close();
        } catch (Exception ignored) {
            // HttpClient 关闭失败不影响语义（JDK Cleaner 仍会兜底回收）
        }
    }

    // ==================== 内部：JSON-RPC 传输 ====================

    /** 发送一个请求并等待匹配 id 的响应（按当前时代分派） */
    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        return rpc(method, params, null);
    }

    /**
     * @param name 资源名（{@code tools/call} 的工具名），无状态协议要放进 {@code Mcp-Name} 头；
     *             会话式协议忽略它
     */
    private JsonNode rpc(String method, Map<String, Object> params, String name) throws Exception {
        if (modern) {
            return modernRpc(method, params, name, protocolVersion);
        }
        return legacyRpc(method, params);
    }

    /** 会话式：POST + 会话头 */
    private JsonNode legacyRpc(String method, Map<String, Object> params) throws Exception {
        long id = idGen.getAndIncrement();
        String payload = MAPPER.writeValueAsString(envelope(id, method, params));
        HttpRequest.Builder builder = baseBuilder(postEndpoint());
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return sendAndReceive(request, id, false);
    }

    /**
     * 无状态：{@code _meta} 里声明版本与能力，头部带 {@code MCP-Protocol-Version} / {@code Mcp-Method}
     * （工具名进 {@code Mcp-Name}），不发会话头 —— 那版规范已经没有会话了。
     */
    private JsonNode modernRpc(String method, Map<String, Object> params, String name, String version)
            throws Exception {
        long id = idGen.getAndIncrement();
        Map<String, Object> withMeta = new LinkedHashMap<>(params);
        withMeta.put("_meta", modernMeta(version));
        String payload = MAPPER.writeValueAsString(envelope(id, method, withMeta));
        HttpRequest.Builder builder = baseBuilder(baseUrl)
                .header(VERSION_HEADER, version)
                .header(METHOD_HEADER, method);
        if (name != null && !name.isBlank()) {
            builder.header(NAME_HEADER, name);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return sendAndReceive(request, id, true);
    }

    private static Map<String, Object> envelope(long id, String method, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.put("params", params);
        return body;
    }

    /**
     * 逐请求元数据。{@code clientCapabilities} 空对象是如实声明：本平台不能让服务端反向用到
     * 采样 / 根目录 / 交互式提问，能力集里就不写这些键。
     */
    private static Map<String, Object> modernMeta(String version) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(META_VERSION, version);
        meta.put(META_CLIENT_INFO, Map.of("name", "domainplane", "version", "1.0"));
        meta.put(META_CLIENT_CAPS, Map.of());
        return meta;
    }

    private HttpRequest.Builder baseBuilder(String endpoint) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(callTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        headerSupplier.get().forEach(builder::header);
        return builder;
    }

    /** 发送一个通知（无 id、无响应）—— 只属于会话式协议 */
    private void rpcNotification(String method, Map<String, Object> params) throws Exception {
        String payload = MAPPER.writeValueAsString(envelopeNotification(method, params));
        HttpRequest.Builder builder = baseBuilder(postEndpoint());
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw httpError(response.statusCode(), response);
        }
    }

    private static Map<String, Object> envelopeNotification(String method, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("params", params);
        return body;
    }

    /**
     * 发送请求并解析响应（JSON 或 SSE；Legacy SSE 时从 GET 流按 id 取）。
     *
     * @param stateless 无状态请求不认会话头：双时代 server 可能回一个 {@code Mcp-Session-Id}，
     *                  但后续请求本来就不该带它，记下来只会误导排障
     */
    private JsonNode sendAndReceive(HttpRequest request, long id, boolean stateless) throws Exception {
        if (transport == McpTransport.LEGACY_SSE) {
            ArrayBlockingQueue<JsonNode> queue = new ArrayBlockingQueue<>(1);
            pending.put(id, queue);
            try {
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() / 100 != 2 && response.statusCode() != 202) {
                    throw httpError(response.statusCode(), response);
                }
                JsonNode got = queue.poll(callTimeout.toMillis() + 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (got == null) {
                    throw new TimeoutException("等待 MCP 响应超时（id=" + id + "）");
                }
                return got;
            } finally {
                pending.remove(id);
            }
        }
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String sessionHeader = response.headers().firstValue(SESSION_HEADER).orElse(null);
        if (!stateless && sessionHeader != null) {
            sessionId = sessionHeader;
        }
        if (response.statusCode() / 100 != 2) {
            throw httpError(response.statusCode(), response);
        }
        // 响应必须与请求 id 对得上，且必须带 result 或 error。
        // 少这两道校验时，一个 {} 会让 toolsList 静默返回 0 个工具，
        // bridge.publish 拿空目录覆盖注册表，该连接的工具在模型侧全部消失，且不报任何错。
        JsonNode node = parseJsonRpcBody(response.body());
        JsonNode idNode = node.path("id");
        if (!idNode.isMissingNode() && !idNode.isNull() && idNode.asLong() != id) {
            throw new IllegalStateException(
                    "MCP 响应 id 不匹配（请求 " + id + "，响应 " + idNode.asText() + "）");
        }
        if (!node.has("result") && !node.has("error")) {
            throw new IllegalStateException(
                    "MCP 响应缺少 result / error 字段：" + abbreviate(response.body()));
        }
        return node;
    }

    /**
     * HTTP 非 2xx → 异常分流：
     * <ul>
     *   <li>401 且带 {@code WWW-Authenticate} 头 → {@link McpAuthChallengeException}（server 声明了认证方案，走 OAuth 授权）；</li>
     *   <li>响应体是 JSON-RPC 错误 → {@link McpRpcError}（带 code/data：无状态协议的时代判定要靠它，
     *       400 也可能是 {@code -32022} 版本不匹配而不是"这接口不存在"）；</li>
     *   <li>其余（含不带挑战头的 401，通常是静态 token 配错）→ {@link IllegalStateException}（调用方提示改配置）。</li>
     * </ul>
     */
    private static RuntimeException httpError(int status, HttpResponse<?> response) {
        String challenge = response.headers().firstValue("WWW-Authenticate").orElse(null);
        if (status == 401 && challenge != null && !challenge.isBlank()) {
            return new McpAuthChallengeException(challenge,
                    "MCP 请求返回 HTTP 401，服务器要求认证（WWW-Authenticate: " + challenge + "）");
        }
        Object body = response.body();
        String text = body == null ? "" : String.valueOf(body);
        JsonNode error = rpcErrorOf(text);
        if (error != null) {
            return new McpRpcError(error.path("code").asInt(0),
                    error.path("message").asText("未知错误"),
                    error.path("data"),
                    "MCP 请求返回 HTTP " + status);
        }
        return new IllegalStateException("MCP 请求返回 HTTP " + status
                + (text.isBlank() ? "" : "：" + abbreviate(text)));
    }

    /** 从响应体里取出 JSON-RPC 的 {@code error} 节点；不是 JSON-RPC 错误就返回 null */
    private static JsonNode rpcErrorOf(String body) {
        if (body == null) {
            return null;
        }
        String text = body.strip();
        if (!text.startsWith("{")) {
            return null;
        }
        try {
            JsonNode error = MAPPER.readTree(text).path("error");
            return error.isObject() ? error : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从响应体里取出 JSON-RPC 响应（可能是纯 JSON，也可能是 SSE 流里最后一条 message） */
    private JsonNode parseJsonRpcBody(String body) throws Exception {
        String ct = body == null ? "" : body.strip();
        if (ct.startsWith("{")) {
            return MAPPER.readTree(ct);
        }
        // SSE：取最后一条 data: 负载
        String last = null;
        for (String line : ct.split("\n", -1)) {
            line = line.strip();
            if (line.startsWith("data:")) {
                last = line.substring(5).strip();
            }
        }
        if (last == null) {
            throw new IllegalStateException("MCP 响应既不是 JSON 也不是 SSE message：" + abbreviate(body));
        }
        return MAPPER.readTree(last);
    }

    private JsonNode requireResult(JsonNode response) {
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new McpRpcError(error.path("code").asInt(0),
                    error.path("message").asText("未知错误"), error.path("data"));
        }
        return response.path("result");
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String v = item.asText("");
                if (!v.isBlank()) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> out.put(e.getKey(),
                    e.getValue().isNull() ? null : MAPPER.convertValue(e.getValue(), Object.class)));
        }
        return out;
    }

    /** input_required 里带了哪些待补输入，只数个数给运维看，不替它解释语义 */
    private static String inputRequestsBrief(JsonNode result) {
        JsonNode reqs = result.path("inputRequests");
        if (reqs.isArray() || reqs.isObject()) {
            return reqs.size() + " 项 inputRequests";
        }
        return "inputRequests 未给出";
    }

    /** Streamable HTTP 的 POST 地址 = baseUrl 本身；Legacy SSE = GET /sse 拿到的动态 endpoint */
    private String postEndpoint() {
        if (transport == McpTransport.LEGACY_SSE) {
            if (sseEndpoint == null) {
                throw new IllegalStateException("Legacy SSE 尚未完成 /sse 握手，无法发送请求");
            }
            return sseEndpoint;
        }
        return baseUrl;
    }

    /** Legacy SSE 握手：GET /sse 开流，读取 endpoint 事件得到动态 POST 地址并启动流读线程 */
    private void openLegacySse() throws Exception {
        if (sseRunning) {
            return;
        }
        String sseUrl = baseUrl;
        HttpRequest request = HttpRequest.newBuilder(URI.create(sseUrl))
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw httpError(response.statusCode(), response);
        }
        sseRunning = true;
        final java.io.InputStream stream = response.body();
        // 记下流引用：close() 靠关它来叫醒阻塞在 readLine() 上的读线程
        sseStream = stream;
        Thread reader = new Thread(() -> readSseStream(stream), "mcp-sse-" + baseUrl);
        reader.setDaemon(true);
        reader.start();
        // 等待 endpoint 事件（握手必须在发送 initialize 之前拿到动态 POST 地址）
        long deadline = System.currentTimeMillis() + 10000;
        while (sseEndpoint == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        if (sseEndpoint == null) {
            sseRunning = false;
            throw new IllegalStateException("Legacy SSE 握手超时：未在 10s 内收到 endpoint 事件");
        }
    }

    /** Legacy SSE 流读线程：按事件解析，endpoint 事件记下 POST 地址，message 事件按 id 分发 */
    private void readSseStream(java.io.InputStream stream) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String event = null;
            String line;
            while (sseRunning && (line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        dispatchSse(event, data.toString());
                        data.setLength(0);
                        event = null;
                    }
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                }
            }
        } catch (Exception e) {
            if (sseRunning) {
                log.warn("[MCP 客户端] Legacy SSE 流读取结束: {}", e.getMessage());
            }
        }
    }

    private void dispatchSse(String event, String data) {
        if ("endpoint".equals(event)) {
            // data 是相对或绝对地址，拼到 baseUrl 的 host 上
            sseEndpoint = resolveSseEndpoint(data);
            return;
        }
        if ("message".equals(event) || event == null) {
            try {
                JsonNode node = MAPPER.readTree(data);
                JsonNode idNode = node.path("id");
                if (idNode.isNumber()) {
                    long id = idNode.asLong();
                    ArrayBlockingQueue<JsonNode> queue = pending.get(id);
                    if (queue != null) {
                        queue.offer(node);
                    }
                }
            } catch (Exception e) {
                log.warn("[MCP 客户端] Legacy SSE 消息解析失败: {}", abbreviate(data));
            }
        }
    }

    private String resolveSseEndpoint(String endpointData) {
        try {
            URI u = URI.create(endpointData);
            if (u.isAbsolute()) {
                return endpointData;
            }
            // 相对地址：拼到 baseUrl 上
            URI base = URI.create(baseUrl);
            return base.resolve(endpointData).toString();
        } catch (Exception e) {
            return endpointData;
        }
    }

    private static String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "(空)";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /**
     * JSON-RPC 错误（带 code / data）。
     *
     * <p>继承 {@link IllegalStateException} 是为了不惊动既有调用方与测试的 catch 面；
     * 单独的 code/data 是为了无状态协议的时代判定 —— {@code -32022} 和 {@code -32601}
     * 是两件完全不同的事（"版本不配" vs "没这个方法"），只看文案区分不出来。</p>
     */
    static final class McpRpcError extends IllegalStateException {

        private final int code;
        private final JsonNode data;

        McpRpcError(int code, String message, JsonNode data) {
            super("MCP 返回错误（code=" + code + "）：" + message);
            this.code = code;
            this.data = data == null ? JsonNodeFactory.instance.missingNode() : data;
        }

        McpRpcError(int code, String message, JsonNode data, String prefix) {
            super(prefix + "（code=" + code + "）：" + message);
            this.code = code;
            this.data = data == null ? JsonNodeFactory.instance.missingNode() : data;
        }

        int code() {
            return code;
        }

        JsonNode data() {
            return data;
        }
    }
}
