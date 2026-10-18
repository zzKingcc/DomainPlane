package com.zzkingcc.domainplane.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 最小 MCP 客户端：只实现用到的三个方法（initialize / tools/list / tools/call）。
 *
 * 同时支持两种传输：
 * <ul>
 *   <li>{@link McpTransport#STREAMABLE_HTTP} —— 单一端点，响应可能是 JSON 或 SSE 流，
 *       会话由 {@code Mcp-Session-Id} 头维系；初始化后发 {@code notifications/initialized}。</li>
 *   <li>{@link McpTransport#LEGACY_SSE} —— 先 GET /sse 开流、服务端回 {@code endpoint} 事件给出
 *       动态 POST 地址，之后 POST 到该地址，响应经原 GET 流回传（按 JSON-RPC id 关联）。</li>
 * </ul>
 *
 * 不引任何 MCP SDK：只用 JDK HttpClient + Jackson，契合项目离线构建的常态。
 *
 * @author zzkingcc
 */
public class McpRpcClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpRpcClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final String baseUrl;
    private final McpTransport transport;
    /** 请求头提供者：每次请求现取（OAuth token 刷新后不重建 client 即生效） */
    private final Supplier<Map<String, String>> headerSupplier;
    private final Duration callTimeout;
    private final HttpClient http;

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

    /** tools/call 的结果 */
    public record McpCallResult(boolean success, String text, boolean isError) {
        public static McpCallResult ok(String text) {
            return new McpCallResult(true, text, false);
        }

        public static McpCallResult fail(String text) {
            return new McpCallResult(false, text, true);
        }
    }

    /** initialize：建会话并返回协议版本 */
    public String initialize() throws Exception {
        if (transport == McpTransport.LEGACY_SSE) {
            openLegacySse();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "domainplane", "version", "1.0"));
        JsonNode response = rpc("initialize", params);
        JsonNode result = requireResult(response);
        String negotiated = result.path("protocolVersion").asText(PROTOCOL_VERSION);
        // 能力声明：本平台不会反过来让 server 用模型，故如实上报不支持 sampling / roots / elicitation
        rpcNotification("notifications/initialized", Map.of());
        log.info("[MCP 客户端] {} 初始化完成（协商协议版本 {}）", baseUrl, negotiated);
        return negotiated;
    }

    /** tools/list：返回工具定义列表 */
    public List<McpToolInfo> toolsList() throws Exception {
        JsonNode response = rpc("tools/list", Map.of());
        JsonNode result = requireResult(response);
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
        JsonNode response = rpc("tools/call", params);
        JsonNode result = requireResult(response);
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
        return isError ? McpCallResult.fail(sb.toString()) : McpCallResult.ok(sb.toString());
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

    /** 发送一个请求并等待匹配 id 的响应 */
    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        long id = idGen.getAndIncrement();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.put("params", params);
        String payload = MAPPER.writeValueAsString(body);
        String endpoint = postEndpoint();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(callTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        headerSupplier.get().forEach(builder::header);
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return sendAndReceive(request, id);
    }

    /** 发送一个通知（无 id、无响应） */
    private void rpcNotification(String method, Map<String, Object> params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("params", params);
        String payload = MAPPER.writeValueAsString(body);
        String endpoint = postEndpoint();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(callTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        headerSupplier.get().forEach(builder::header);
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw httpError(response.statusCode(), response);
        }
    }

    /** 发送请求并解析响应（JSON 或 SSE；Legacy SSE 时从 GET 流按 id 取） */
    private JsonNode sendAndReceive(HttpRequest request, long id) throws Exception {
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
        if (sessionHeader != null) {
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
        return new IllegalStateException("MCP 请求返回 HTTP " + status
                + (text.isBlank() ? "" : "：" + abbreviate(text)));
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
            String msg = error.path("message").asText("未知错误");
            int code = error.path("code").asInt(0);
            throw new IllegalStateException("MCP 返回错误（code=" + code + "）：" + msg);
        }
        return response.path("result");
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
}
