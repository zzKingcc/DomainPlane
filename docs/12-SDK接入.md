# SDK 接入

三个 SDK 都只走 HTTP，与服务端仅靠报文耦合。它们存在的意义不是"封装协议"，而是把三件容易写错的事做对：域绑定、凭证缓存、SSE 终止判断。

> 本项目**没有发布仓库**（未发 Maven Central，无公共私服）。下面的坐标必须先把模块装进你自己的仓库：
>
> ```bash
> mvn -pl domainplane-chat-client -am install
> ```

---

## 1. 三件套

| 模块 | 干什么 | 关键类 |
| --- | --- | --- |
| `domainplane-chat-client` | 发一轮对话、收事件、审批、停止 | `DomainPlaneAgentFactory`、`DomainPlaneAgent` |
| `domainplane-kb-client` | 上传 / 列表 / 删除文档 | `KnowledgeBaseClient` |
| `domainplane-tool-provider` | 把宿主的 `@Tool` 注册给服务端并接回调 | `ToolInstanceClient`、`ToolInstanceInvokeController` |

公共依赖链：`sdk-core`（配置）→ `client-core`（WebClient + 凭证 + 错误翻译）→ 具体 SDK。`tool-provider` 只依赖 `sdk-core`。

---

## 2. 配置

```yaml
domainplane:
  server: http://localhost:9527
  username: domainplane
  password: domainplane
```

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `domainplane.server` | `http://localhost:9527` | 一个 URL 取代 host + port |
| `domainplane.username` / `.password` | `domainplane` / `domainplane` | 服务端账号 |
| `domainplane.tools` | `false` | 是否把本进程的 `@Tool` 注册出去 |
| `domainplane.tool-instance.instance-id` | 无（必填） | 工具实例标识，重连必须沿用 |
| `domainplane.tool-instance.endpoint` | 按端口推导 | 服务端可达的回调地址 |
| `domainplane.client.connect-timeout` | `5s` | 连接超时 |
| `domainplane.client.read-timeout` | `10m` | 单次读超时 |
| `domainplane.client.answer-timeout` | `30m` | `ask` 侧等最终答案的总上限 |

三项基础配置都有默认值，**零配置也能启动**；连不上不会启动失败，第一次调用才报错。

自动装配靠 4 个 `AutoConfiguration.imports`（`client-core` / `chat-client` / `kb-client` / `tool-provider` 各一份），没有 `spring.factories`。

---

## 3. 对话 SDK

```java
@Resource
private DomainPlaneAgentFactory agentFactory;

// 先绑域（必须是可调用的叶子域），再调用
DomainPlaneAgent agent = agentFactory.forDomain("default.customer");

String answer        = agent.ask("session-001", "昨天的订单到哪了？");
Flux<String> tokens   = agent.stream("session-001", "展开讲讲");
Flux<AgentEvent> evts = agent.events("session-001", "刚才那条");
boolean stopped        = agent.stop("session-001");
```

| 方法 | 返回 | 用途 |
| --- | --- | --- |
| `ask(sessionId, text)` / `ask(sessionId, List<MessageContent>)` | `String` | 只要最终答案 |
| `stream(...)` | `Flux<String>` | 只要增量文本 |
| `events(...)` | `Flux<AgentEvent>` | 要完整事件流（工具调用、思考、计划） |
| `resume(sessionId, approved)` | `Flux<AgentEvent>` | 审批后继续 |
| `stop(sessionId)` | `boolean` | 幂等，返回是否设置成功 |
| `domainId()` | `String` | 当前绑的域 |

`ask` / `stream` / `events` 底层是同一条事件流，区别只在过滤。带图提问传 `List<MessageContent>`：

```java
agent.ask("s1", List.of(
    MessageContent.text("这张发票的金额是多少"),
    MessageContent.imageUrl("https://example.com/inv.jpg")));
```

`forDomain` 传 `null` / 空白 / `" default "` 都会归一化成根域，且同一域返回同一个实例（内部缓存）。

### 审批流程

```java
try {
    agent.ask("s1", "帮我退款");
} catch (ApprovalRequiredException e) {
    e.sessionId();   // 哪个会话
    e.domainId();    // 哪个域
    e.tools();       // List<ToolCall>：待审批的工具与参数（已掩码）
    e.traceId();
    // 展示给用户确认
    agent.resume("s1", true).subscribe(evt -> …);
}
```

`ApprovalRequiredException` 是**唯一**需要在调用侧 `catch` 的业务异常。`resume` 的 `sessionId` 必须与 `ask` 时一致；域已经绑在 agent 上，不需要再传。

### 终止判断

SDK 会校验流以 `DONE` / `STOPPED` / `ERROR` 收尾。只收到 `TOKEN` 就断流的，抛 `STREAM_TRUNCATED`，而不是当正常结束——否则"网络断了"会变成"模型只说了半句"，而调用方毫无察觉。

---

## 4. 知识库 SDK

```java
@Resource
private KnowledgeBaseClient kb;

KnowledgeBaseClient.UploadResult r =
    kb.upload(Files.readAllBytes(path), "售后政策.pdf", false, "default.customer");

List<KnowledgeBaseClient.DocumentItem> docs = kb.list();
boolean deleted = kb.delete(r.docId());
```

| 方法 | 说明 |
| --- | --- |
| `upload(byte[], fileName, replace)` | 挂根域 |
| `upload(byte[], fileName, replace, domain)` | 指定归属域；域决定哪些对话能检索到 |
| `list()` | 全量文档（`docId` / `fileName` / `chunks`） |
| `delete(docId)` | 删除，返回是否真删了 |

上传是 **multipart，字段名 `file`**，query 带 `replace` 与 `domain`。5MB 上限、8 种格式白名单在服务端校验。

**检索不在客户端暴露。** 检索是服务端的事：模型通过 `searchKnowledge` 工具自动取用。SDK 侧没有 `search()`，这是有意的——检索参数（查哪个域、查几片、怎么融合）是平台配置，不是调用方参数。

---

## 5. 工具实例 SDK

把宿主应用里的方法注册成 Agent 工具：

```yaml
domainplane:
  server: http://localhost:9527
  username: domainplane
  password: domainplane
  tools: true
  tool-instance:
    instance-id: order-service-01        # 重连必须沿用同一个
    endpoint: http://10.0.0.5:8081/domainplane/invoke
```

```java
@Component
public class OrderTools {

    @Tool(desc = "按订单号查询订单状态", domains = {"default.customer"}, effect = Tool.Effect.READ)
    public String queryOrder(@ToolParam("订单号") String orderNo) { … }
}
```

启动后自动扫描、注册、心跳（默认 5s，失败指数退避上限 20s）。破坏性操作配 `effect = DESTRUCTIVE, approval = Tool.Approval.ALWAYS` 走人工审批。

| 机制 | 值 / 位置 |
| --- | --- |
| 装配条件 | `domainplane.tools=true`（`@ConditionalOnProperty`） |
| 注册端点 | `POST /api/agent/tools/register` |
| 回调端点 | `POST /domainplane/invoke`（宿主侧） |
| 心跳 = 注册 | 同一个接口，重复调用即续期 |
| 被强制下线 | 服务端回 `410`，客户端停跳 |
| 依赖容器 | 只依赖 `spring-web`，宿主必须自带 MVC/WebFlux 容器，否则回调接不到 |

回调请求体：`{requestId, toolName, arguments, traceId}`；成功 `{requestId, success:true, result}`；失败 `{requestId, success:false, error, retryable}`。

`instance-id` 缺失会在启动时抛错——它是"重连认回自己"的唯一凭据，不能默认生成。

---

## 6. 报文契约

| 调用方 | 请求 | 凭证 |
| --- | --- | --- |
| chat-client | `POST /api/agent/chat`（JSON → SSE） | Header |
| chat-client | `POST /api/agent/resume?sessionId&approved&domain`（SSE） | Header |
| chat-client | `POST /api/agent/stop/{sessionId}?domain` | Header |
| kb-client | `POST /admin/kb/documents?replace&domain`（multipart `file`） | Header |
| kb-client | `GET /admin/kb/documents` | Header |
| kb-client | `DELETE /admin/kb/documents/{docId}` | Header |
| tool-provider | `POST /api/agent/tools/register` | Header |
| 以上全部（首次） | `POST /api/agent/login` `{username,password}` → `{credential}` | 无 |
| 服务端 → 工具实例 | `POST {endpoint}` | 无 |

凭证头名两端一致：`X-DomainPlane-Credential`。SDK 进程内缓存凭证，收到 401/403 清缓存并**重登一次**——只重登，不重发原请求。

裸 `curl` 与 SDK 完全等价，排障时优先用 `curl` 复现。

---

## 7. 错误与超时

| 情形 | SDK 行为 |
| --- | --- |
| 响应体 `code != 0` | 抛 `ServerRejectedException`，原样带服务端码、文案、`retryable` |
| HTTP 401/403 | 清凭证，重登一次；再失败抛 `CREDENTIAL_REJECTED` |
| 连接失败 / UnknownHost | `CONNECTION_REFUSED` |
| 读超时 | `TIMEOUT` |
| 流无终止事件 | `STREAM_TRUNCATED` |

超时分三层，别混：

| 层 | 键 | 默认 |
| --- | --- | --- |
| 建连 | `domainplane.client.connect-timeout` | 5s |
| 单次读 | `domainplane.client.read-timeout` | 10m |
| 一次问答 | `domainplane.client.answer-timeout` | 30m |

`read-timeout` 是 WebClient 的响应超时，会重置；`answer-timeout` 是 `ask` 的总上限，不重置。一轮里工具多、模型慢，卡的是后者的可能性更大。

---

## 8. 踩过的坑

**`forDomain` 的域必须是可调用叶子域。** 传父域会在第一轮报 `10010`，不是在绑定时报——因为可调用性是运行期判定的。

**`stop` 不能"停完继续"。** 停是终止本轮；要继续就发新一轮，历史还在。SDK 侧没有"暂停"。

**知识库 SDK 用 Header 调 `/admin/**`。** 这是靠服务端"Cookie 优先、Header 回退"的规则走通的，不是特例接口。

**工具实例的 `endpoint` 要能被服务端访问到。** `localhost` 只在同机部署时成立；跨机或过网关时必须显式填。

**`ask` 不会自动重试。** 平台不知道这次调用是否幂等，重试策略留给调用方。`retryable` 字段就是给这个判断用的。
