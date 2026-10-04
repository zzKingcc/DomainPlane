# DomainPlane 实例文档：配置与接入

面向**部署方 / 接入方**的实操指南。讲清三件事：服务端怎么配、接入方怎么在代码里用、每个参数什么作用。
所有配置项与 API 都来自本仓库实现，可直接照抄。

---

## 1. 总览：一个服务端 + 三个按需引入的 SDK

DomainPlane 采用「中间件形态」：把重逻辑全部收在服务端，对外只给薄薄的 SDK。

| 交付物 | 模块 | 角色 | 你做什么 |
|---|---|---|---|
| 服务端 jar | `domainplane-server` | 承载编排 / 工具注册表 / 知识库 / ES·Redis·LLM 连接，暴露 HTTP+SSE | 自部署，通过管控台配置 |
| 对话 SDK | `domainplane-chat-client` | 极薄，只把调用转发到服务端 | 注入 `DomainPlaneAgent`（用 `DomainPlaneAgentFactory.forDomain(...)` 取） |
| 知识库 SDK | `domainplane-kb-client` | 文档上传 / 列表 / 删除 | 注入 `KnowledgeBaseClient` |
| 工具 SDK | `domainplane-tool-provider` | 把你进程里的工具注册给服务端，接收回调执行 | 方法上写 `@Tool`（或实现 `ToolInstanceContributor` 编程式声明） |

三个 SDK **相互独立、按需引入**，同时引多个也不冲突 —— 公共底座（`domainplane-client-core` 的 WebClient / 凭证 / 错误翻译 / 启动探测，`domainplane-sdk-core` 的契约与连接配置）是传递依赖，只装配一份。

> 环境要求：Java 21、Spring Boot 3.x。坐标均为 `com.zzkingcc`，版本 `v1.0-beta.1`（随 `domainplane.version`）。

### 1.1 按需要引几个依赖

| 你要做的事 | 引哪个坐标 | 拿到什么 |
|---|---|---|
| 调 AI：发起对话、拿答案 / 事件流 | `domainplane-chat-client` | 注入 `DomainPlaneAgentFactory`，`forDomain(...)` 取门面后用 `ask`/`stream`/`events`（见 §3） |
| 把文档传进知识库 | `domainplane-kb-client` | 注入 `KnowledgeBaseClient`，用 `upload` / `list` / `delete`（用法见 [SDK 使用手册 §3](SDK-USAGE.md)） |
| 把本进程的方法交给 Agent 调用 | `domainplane-tool-provider` | 方法上写 `@Tool`，打开 `domainplane.tools`（见 §4） |

- **工具能力默认关闭**：`domainplane.tools` 默认 `false`。未打开时不注册回调端点、不启动心跳、不建任何工具实例 Bean。
- **服务端连接只配一份**：`domainplane.server`（单个 URL）+ `domainplane.username` / `password`，三个 SDK 共用同一份（见 §3.2）。
- **公共异常与输入安全**随任一 SDK 传递，可直接复用 `ErrorCode` / `BaseException` / `InputSanitizer`。
- **Web 容器自备**：SDK 只用 Spring Web 的注解模型（`@RestController` / `@RequestBody`）与出站 `WebClient`，**不含任何容器**——自带 `spring-boot-starter-webflux` 仅服务于 SSE 出站调用。宿主原有的 Web 栈保持不变；要让服务端回调进来，宿主本来就需要一个可被访问的 Web 栈。
- 因此不要把 Web 容器声明进 SDK：Spring Boot 判定 Web 应用类型时，Reactive 分支要求「`DispatcherHandler` 在且 `DispatcherServlet` 不在」；SDK 一旦带上 `spring-boot-starter-web`，纯 WebFlux 宿主就会被判成 SERVLET，`DispatcherHandler` 相关装配随之失效。
- **非 Java 工具方**不必引 SDK：`domainplane-tool-provider` 不含内部实现，也可照 HTTP 协议自实现（见 §4）。

---

## 2. 服务端配置

服务端**固定监听 9527**。配置分两类：

- **管控台配置（落地为 `config/*.json`）**：账号、模型、存储、域提示词。推荐走管控台 `http://<host>:9527/admin.html`，保存即生效、无需重启。
- **yaml 兜底（`application.yaml` 里的 `domainplane.*`）**：管控台没填的字段会回落到 yaml；只有 `domainplane.settings.path` 这类部署参必须靠环境变量。

### 2.1 落盘目录 `domainplane.settings.path`

所有受保护资源（包括含 API Key 的文件）都落在 `<domainplane.settings.path>/config/`。

```yaml
# application.yaml
domainplane:
  settings:
    path: ${DOMAINPLANE_SETTINGS_PATH:/var/lib/domainplane/config}
```

| 参数 | 默认 | 作用 |
|---|---|---|
| `domainplane.settings.path` | `/var/lib/domainplane/config` | 配置与账号文件的落盘根目录。容器化时把该目录挂成卷即可持久化。**本机开发务必用环境变量覆盖**，否则会往系统盘根目录写。 |

> 为什么不用 ES/Redis 存这些：配置正是用来「找到 ES/Redis 的」。一旦存进中间件，地址填错就再也读不出来，管控台失联 → 死锁。文件是本机唯一无外部依赖的介质。

### 2.2 账号 `accounts.json`

- 首次启动  jar 内种子 `accounts-seed.json` 落盘，默认账号 `domainplane` / 密码 `domainplane`。
- 改密码走管控台「账号」页（密码是 BCrypt 哈希，手改 json 算不出哈希）。
- **忘记密码的唯一恢复手段：删除 `config/accounts.json` 后重启**，回落到种子。凭据安全边界＝文件系统访问控制。
- 凭证为签名式（HMAC）且不设有效期，改密码即让全部旧登录态失效。

### 2.3 模型设置 `llm-settings.json`（管控台「模型设置」页）

| 字段 | 作用 | 说明 |
|---|---|---|
| `chatBaseUrl` | 对话模型服务商地址（OpenAI 兼容） | 如 `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `chatApiKey` | 对话模型 API Key | 接口返回时自动脱敏 |
| `chatModelName` | 对话模型名 | 如 `qwen-plus` |
| `chatTemperature` | 采样温度 | 不填用模型默认 |
| `chatMaxTokens` | 单次最大 token | 不填用模型默认 |
| `embeddingBaseUrl` | 向量模型地址 | **留空则复用对话模型地址** |
| `embeddingApiKey` | 向量模型 Key | **留空则复用对话模型 Key** |
| `embeddingModelName` | 向量模型名 | 如 `text-embedding-v3`，**必须显式填**，否则向量能力视为未配置 |
| `embeddingDimensions` | 向量维度 | 留空＝用服务商默认维度；填写＝请求带 `dimensions` 并在测试时校验返回长度 |

> 改维度**必须重建全部知识库索引**，否则旧 mapping 维度不匹配会拒绝写入。测试连接时平台用返回向量的实际长度做精确裁决，不读 `model.dimension()`。

### 2.4 存储配置 `infra-settings.json`（管控台「存储配置」页）

ES 与 Redis **均可不填**——未配置时服务端照常启动（知识库索引本就是按需创建的，启动期无事可做）；直到真被调用才返回 `90005` 提示去配置。

**`es` 子节点：**

| 字段 | 默认 | 作用 |
|---|---|---|
| `host` | — | 主机名或 IP（**只填主机，不要带 `http://` 或端口**） |
| `port` | `9200` | HTTP 端口 |
| `scheme` | `http` | `http` / `https` |
| `username` | — | 未启用安全认证可空 |
| `password` | — | 未启用安全认证可空 |
| `connectTimeout` | `5000` | 连接超时（毫秒） |
| `socketTimeout` | `10000` | 读写超时（毫秒） |

**`redis` 子节点：**

| 字段 | 默认 | 作用 |
|---|---|---|
| `host` | — | 主机名或 IP（**不要带 `redis://` 或端口**） |
| `port` | `6379` | 端口 |
| `password` | — | 无密码模式可空 |
| `database` | `0` | 库号。默认 0（集群/云托管常只给 0 号库）。隔离靠 key 前缀 `domainplane:`，不靠库号。**换库号＝换数据源，历史会话不迁移** |

> ⚠️ **Redis 是会话记忆的唯一存储，请开启持久化**：`appendonly yes` + `appendfsync everysec`（最坏丢 1 秒）；
> 淘汰策略必须是 **`maxmemory-policy noeviction`**，否则内存紧张时 Redis 会直接淘汰记忆 key = 随机丢用户历史。
> 平台在启动期自检这两项并**只告警不阻断**（`RedisPersistenceAudit`）；受管 Redis 禁用 `CONFIG` 时该检查会跳过。
> 改成 `noeviction` 后写满会导致写入失败（对话报错），所以请同时按容量配置 `maxmemory` 并做用量告警
> —— 单会话上限约 60–120 KB（100 条 / 30k token）。

### 2.5 域提示词 `prompts.json`（管控台「提示词设定」页）

提示词**沿域链拼接**：从根域到当前域依次取出每段片段。根域 `default` 的片段是基底，后代的追加在后。
**没有 `base` 这一层** —— 根域的提示词就写在 `prompts["default"]`，与别的域同构。

```json
{
  "prompts": {
    "default":           "你是一个中文智能助手。回答精炼、有条理……",
    "default.customer":  "你是客服视角，只处理订单与售后……",
    "default.admin":     "你是管理视角，可看经营数据……"
  }
}
```

| 字段 | 作用 |
|---|---|
| `prompts` | 域（**完整路径**）→ 该域的片段。键名须与 `@Tool(domains = {...})` 里声明的域一致 |

于是 `default.customer.vip` 的生效提示词 = `default` + `default.customer` + `default.customer.vip` 三段依次拼接；
改根域一处，整条链生效。

**编写纪律（三条）**

1. **不写「我有哪些 / 没有什么能力」** —— 工具已按域过滤，写出来就成过期事实；
2. **不列举具体工具名** —— 域同时绑定【工具集 + 提示词】，而两者分处两地维护（工具在 `@Tool`、提示词在这里）。点名一个在该域不可见的工具，等于告诉模型"你有这个能力"、而它调不到，且**不抛异常、不报错、HTTP 仍 200**；
3. **本域片段只写本域专属的引导** —— 公共部分放根域，别在子域重复。

> 违反第 2 条时**启动期会打 WARN**（提示词 ↔ 工具可见性自检；只提醒、不阻断启动）。
> 判据是**该域沿链合成后的全文**，所以祖先域片段里点名的工具同样算数。
> 该自检只认"提示词里出现的工具名"，用业务语言描述能力（"查订单"）它覆盖不到 —— 刻意漏报，避免误报。

### 2.6 知识库的域（管控台「知识库」页）

上传时给文档选一个<b>归属域</b>，语义与工具注解同构：必须是**从根域出发的完整路径**，
挂在某域则其**全部后代域**都能检索到；不选 → 挂根域 `default`＝全域可见。**无通配写法**。

**一域一索引**：每个域一个独立 ES 索引（索引名 域路径派生，如 `domainplane_kb_default_sales_1a2b3c4d`），
首次往该域上传文档时自动创建。所以"文档归属哪个域"＝"它落在哪个索引里"。

**域对知识仍是两层约束**（与工具不同）：

1. **工具层** —— 检索工具本身声明到哪些域（`@Tool(domains=...)`），决定"这个域的对话能不能检索"；
2. **内容层** —— 文档上传到哪个域，决定"能检索时查到哪些文档"。

检索域 D 时只查「D + 全部祖先」链上的索引（与工具可见性同一套累加语义）——
域边界 "查哪些索引"保证，不再做元数据过滤。链上某个祖先域还没有索引时那条通道安静返回空。
删域会连带清掉该域及全部子孙的：知识库索引与切片预览文件、检索器缓存、提示词、模型绑定、工具声明记录，
以及 **Redis 里的会话记忆与图检查点**（按 `{域}|` 前缀扫描删除）。

最后一项不是顺手清理而是必需的：会话记忆默认**永不过期**，不清掉的话同路径域将来重建，
这段对话历史会原样复活；图检查点里存着"已挂起、等待人工授权"的工具调用，
不清掉的话删域后 24 小时内同路径域重建并 `resume(approved=true)`，那个当初被拦下的破坏性动作会被补执行掉。
删域因此意味着**该域的对话历史一并消失**。

一域一索引还有个副作用上的好处：域之间的知识物理隔离，删域＝删索引，干净利落。

### 2.7 yaml 兜底与可调参数

除上面四份 json 外，`application.yaml` 里还有这些 `domainplane.*` 参数（多数有默认值，按需调）：

| 配置键 | 默认 | 作用 |
|---|---|---|
| `domainplane.ai.prompt.base` / `domainplane.ai.prompt.prompts` | 空 | 提示词的 yaml 兜底（管控台优先级更高） |
| `domainplane.memory.max-messages` | `100` | 会话记忆条数上限（≈50 轮问答）。**只增不淘汰**，到上限后入口拒绝新一轮（`30004`） |
| `domainplane.memory.max-tokens` | `30000` | 会话记忆 token 估算上限（同上，只约束入口；最终回答永远可写） |
| `domainplane.memory.ttl` | **永久**（null） | 记忆保留期。记忆是**长期存储**（靠 Redis RDB+AOF 保住），默认不过期；设了值则成为保留期 |
| `domainplane.memory.checkpoint-ttl` | `24h` | 断点（待审批会话）保留时长 |
| `domainplane.agent.core-pool-size` / `max-pool-size` / `queue-capacity` | `8` / `32` / `200` | 编排线程池（阻塞式执行，不可复用公共池） |
| `domainplane.instance.timeout-seconds` | `35` | 实例心跳判死窗（≈心跳周期×7） |
| `domainplane.instance.invoke-timeout-ms` | `30000` | 单次远程工具调用超时 |
| `domainplane.instance.invoke-max-attempts` | `2` | 单次调用最多试几个副本（仅传输层失败时换副本） |
| `domainplane.retrieval.*` | — | 混合检索（每索引召回条数、权重、`rrf-k`、TopN 等） |
| `domainplane.rag.ingest-pool-size` / `ingest-queue-capacity` | `2` / `16` | 知识库导入线程池与队列。队列满时上传被拒（并入 `60006`） |
| `domainplane.rag.ingest-lock-wait-seconds` | `60` | 等待"同域导入串行锁"的时长，超时即拒（一个域同时只导入一份，避免同名文档并发写入） |
| `domainplane.rag.max-file-size` / `allowed-extensions` | `5MB` / `txt,md,markdown,docx,doc,pdf,xls,xlsx` | 单文件大小上限与扩展名白名单 |
| `domainplane.rag.max-chunks-per-document` | `2000` | 单文件切片数上限，超限**直接拒绝并提示拆分**（不切一半留下） |
| `domainplane.rag.chunking.max-chars` / `overlap-sentences` / `min-chars` | `400` / `1` / `60` | 切片长度上限（撞标题就提前断，是上限不是固定长度）、重叠句数、过短片的丢弃阈值 |
| `spring.servlet.multipart.max-file-size` | `5MB` | **容器侧**上传上限，`domainplane.rag.max-file-size` 必须与它一致 |
| `spring.servlet.multipart.max-request-size` | `6MB` | 整个 multipart 请求体上限（含 boundary 与 part 头），须**大于** `max-file-size` |
| `spring.mvc.async.request-timeout` | `-1`（不限） | SSE 异步请求超时。不设时容器默认 30s，且那是**整轮总时限**，慢回答会被拦腰掐断 |
| `domainplane.single-instance-guard` | `true` | 单实例守卫：启动时在 Redis 上抢运行权租约，抢不到即拒绝启动（详见 `DEPLOYMENT.md` §6） |
| `domainplane.logging.path` / `domainplane.logging.level` | `/var/log/domainplane` / `INFO` | 文件日志目录与级别（默认只输出控制台） |
| `redis.timeout` / `redis.pool.*` | — | Redis 连接池与命令超时（调优用，不在管控台） |

> ⚠️ yaml 缩进错位**不报错只会静默失效**。所有 `domainplane.*` 都是 2 空格缩进；`domainplane.logging.path` 必须在 `domainplane` 下，错写成 `domainplane.rag.logging.path` 就作废。

### 2.8 启动顺序（硬约束）

**先起服务端（9527），再起接入方应用（8080 等）。** 客户端 starter 在启动期就换取凭证并探测 `GET /api/agent/health`，连不上即中断启动（与 Redis/Nacos 一致，无关闭开关）。服务端正在灌库导致探测超时，可调大 `domainplane.client.health-check-timeout`。

---

## 3. 客户端接入：在你的应用里调 AI

### 3.1 引入依赖

```xml
<dependency>
    <groupId>com.zzkingcc</groupId>
    <artifactId>domainplane-chat-client</artifactId>
    <version>v1.0-beta.1</version>
</dependency>
```

引入即自动装配（注册于 `AutoConfiguration.imports`），**无需** `@ComponentScan` 覆盖内部包。你只需配服务端地址。

本坐标只做对话；公共底座（WebClient / 凭证 / 启动探测）与公共异常随它传递，无需额外配置。要传文档再加 `domainplane-kb-client`、要提供工具再加 `domainplane-tool-provider`（见 §1.1）。

### 3.2 application.yml 配置

地址与账号只写一份，三个 SDK 与工具实例共用（见 §4.2）：

```yaml
domainplane:
  server: ${DOMAINPLANE_SERVER:http://localhost:9527}   # 服务端地址（含协议与端口）
  username: ${DOMAINPLANE_USERNAME:domainplane}            # 接入账号（服务端账号）
  password: ${DOMAINPLANE_PASSWORD:domainplane}            # 接入密码
  client:
    health-check-timeout: 5s                          # 启动连通性探测超时
```

| 参数 | 默认 | 作用 |
|---|---|---|
| `domainplane.server` | `http://localhost:9527` | 服务端地址（含协议与端口）。对话 SDK / 知识库 SDK / 工具实例共用 |
| `domainplane.username` | `domainplane` | 接入账号。与服务端账号一致（默认种子即 `domainplane/domainplane`） |
| `domainplane.password` | `domainplane` | 接入密码。**不每次请求携带**：启动期用它们换签名凭证并缓存，失效才自动重登一次 |
| `domainplane.client.health-check-timeout` | `5s` | 启动期探测服务端可达性的超时 |
| `domainplane.client.connect-timeout` | `5s` | HTTP 连接超时 |
| `domainplane.client.read-timeout` | `10min` | HTTP 读超时（SSE 长连接，0＝不超时） |

### 3.3 注入 DomainPlaneAgent 并调用（唯一入口）

消费侧只有一个入口：`DomainPlaneAgentFactory.forDomain(...)` 拿到已绑定域的 `DomainPlaneAgent`。
**域是接线动作**，不在每次调用的参数里 —— 所以"忘传域"写不出来。

```java
@Service
public class OrderService {
    private final DomainPlaneAgent agent;                 // 已绑定域，可缓存复用（线程安全）

    public OrderService(DomainPlaneAgentFactory factory) {
        this.agent = factory.forDomain("default.customer");  // null / 空白 → 根域 default
    }

    /** 只要最终答案（大多数场景） */
    public String ask(String sessionId, String question) {
        return agent.ask(sessionId, question);
    }

    /** 逐字输出 */
    public Flux<String> stream(String sessionId, String question) {
        return agent.stream(sessionId, question);
    }
}
```

三种方法都有带归属的重载（`ask/stream/events(sessionId, question, tenantId, userId)`）。
审批命中时 `ask` / `stream` 抛 `ApprovalRequiredException`，`getTools()` 就是待审批清单，可直接渲染成确认框。

### 3.4 三种消费方式怎么选

| 方法 | 返回 | 适用场景 |
|---|---|---|
| `ask` | `String` | 只要最终答案（约七成场景）。内部把 `TOKEN` 增量拼成整段文本；被 `stop` 时返回已产出的部分；**阻塞**，受 `domainplane.client.read-timeout` 约束 |
| `stream` | `Flux<String>` | 逐字上屏（只含模型文本增量） |
| `events` | `Flux<AgentEvent>` | 要看工具调用 / 审批中断 / 错误码，或自行控制超时与背压（长任务优先用它） |

> 底层的 `AgentRequest` / `CallerContext` 是**裸 HTTP 契约**（见 `API.md` §2），SDK 使用者不需要直接构造它们；
> 需要无 SDK 接入时（非 Java 应用、网关联调）才用得上。**`attributes` 扩展属性只在裸 HTTP 通道可用**。

### 3.5 订阅事件流（AgentEvent）

```java
agent.events(sessionId, question).subscribe(event -> {
    switch (event.getType()) {
        case TOKEN     -> out.append(event.getContent());     // 模型增量输出，需自行拼接
        case TOOL_CALL -> showToolCalling(event.getContent());// content=工具名，payload=参数JSON
        case TOOL_RESULT -> {/* 结果已回喂模型，通常不展示 */}
        case INTERRUPT -> askForApproval(event.getPayload()); // 挂起等人工确认 → 调 resume
        case STOPPED   -> log.info("用户停止");
        case ERROR     -> handleError(event.getCode(), event.getCodeName(), event.getTraceId());
        case DONE      -> finish();
    }
});
```

> 只想拼文本，用 `agent.ask(...)` 或 `agent.stream(...)` 更省事（见 §3.4）；`events` 是"要自己处理全部事件"时的入口。

**事件类型（AgentEventType）：**

| 类型 | content | payload | 含义 / 调用方动作 |
|---|---|---|---|
| `TOKEN` | 增量文本 | — | 模型增量输出，拼接后展示 |
| `TOOL_CALL` | 工具名 | 参数 JSON | 模型要调工具，仅展示用 |
| `TOOL_RESULT` | 工具名 | 结果文本 | 工具执行完毕，结果已回喂模型 |
| `INTERRUPT` | — | 待授权工具列表 JSON | **挂起等人审**。引导用户确认后调 `resume(sessionId, approved)` |
| `STOPPED` | — | — | 被 `stop()` 主动停止，不可恢复 |
| `ERROR` | 可读文案 | — | **只有 ERROR 带 `code`**，前端据此决定行动；`traceId` 用于上报排障 |
| `DONE` | — | — | 本轮正常结束 |

**AgentEvent 字段：** `type`（必读）、`sessionId`、`content`、`payload`、`code`（仅 ERROR）、`codeName`（仅 ERROR，前端应以此定义常量而非硬编码数字）、`traceId`（仅 ERROR）。

### 3.6 resume 与 stop

```java
// 用户批准/拒绝后恢复被 INTERRUPT 挂起的会话
// 域 门面绑定，自动原样带上；resume 会据此重新校验待执行工具是否仍可见
agent.resume(sessionId, approved);

// 主动停止（不可恢复）；返回 false = 该会话已被停止或被拒（幂等）
boolean triggered = agent.stop(sessionId);
```

不进 `DomainPlaneAgent` 的两件事：`CallerContext`（谁在调）与 `AgentRequest`（本轮请求原文）属于**裸 HTTP 契约**。
平台信任调用方声明的域，越权判断（角色→域）在宿主侧做 —— 取得门面时的那个域字符串，就是宿主自己做鉴权后得出的结论。

---

## 4. 工具实例接入：把你的工具交给服务端

工具即「给一段参数 JSON，还一段结果文本」的无状态能力。两种方式任选：

- **远程（推荐，接入方用）**：在本进程用 `domainplane-tool-provider` SDK 周期注册，服务端回调你暴露的 `/domainplane/invoke` 执行。
- **本地（服务端自带）**：工具随服务端进程部署，用 `@Tool` 注解声明、  `AnnotatedToolScanner` 扫描。见 §5。

### 4.1 引入依赖

```xml
<dependency>
    <groupId>com.zzkingcc</groupId>
    <artifactId>domainplane-tool-provider</artifactId>
    <version>v1.0-beta.1</version>
</dependency>
```

独立坐标，与对话 / 知识库 SDK 互不依赖（见 §1.1）——只想当工具方、不调 AI 的进程就只引它。必须打开 `domainplane.tools`：引了 jar 不等于要当工具提供方。

本 SDK 不自带 Spring 容器，也刻意不引 Web 容器：它只要求宿主进程里存在一个能被服务端访问到的 Web 栈（Spring MVC 或 WebFlux 均可）。

### 4.2 application.yml 配置

```yaml
domainplane:
  tools: true                                           # 默认 false：引了 jar 不等于要当工具提供方
  server: http://localhost:9527                          # 与对话 / 知识库 SDK 共用同一份（见 §3.2）
  username: ${DOMAINPLANE_USERNAME:domainplane}
  password: ${DOMAINPLANE_PASSWORD:domainplane}
  tool-instance:
    instance-id: ${DOMAINPLANE_INSTANCE_ID:order-svc-01}   # 重连必须沿用同一个
    # endpoint 留空即自动推导（见下），跨机部署必须显式写服务端可达的地址
    # endpoint: ${DOMAINPLANE_INSTANCE_ENDPOINT:http://10.0.0.5:8081/domainplane/invoke}
    heartbeat-interval-seconds: 5
```

| 参数 | 默认 | 作用 |
|---|---|---|
| `domainplane.tools` | `false` | 是否启用工具实例。会起心跳线程并暴露 HTTP 端点，属显式选择 |
| `domainplane.server` | `http://localhost:9527` | 服务端地址。**与对话 / 知识库 SDK 共用同一份**——服务端只有一份工具注册表、一个账号，指向不同服务端属破坏性配置 |
| `domainplane.username` / `domainplane.password` | `domainplane` | 接入账号（同服务端账号），用于登录换凭证 |
| `instance-id` | — | 实例标识。**重连必须沿用同一个**，否则服务端留下摘不掉的旧副本 |
| `endpoint` | 自动推导 | 工具调用回流地址：服务端 POST 到这里执行工具。留空时推导为 `http://localhost:{本进程端口}/domainplane/invoke`（端口取 `local.server.port`，缺失则取 `server.port`）；**路径必须是 `/domainplane/invoke`** |
| `heartbeat-interval-seconds` | `5` | 心跳周期。服务端判死窗默认 `35s`（≈心跳×7），退避上限必须小于它 |
| `max-backoff-seconds` | `20` | 心跳连续失败时的退避上限（指数退避：5→10→20 封顶，避免重启风暴）。**必须小于判死窗**，否则会出现"退避还没到就已被判死" |
| `request-timeout-millis` | `10000` | 单次 HTTP 超时 |

**关于 `endpoint` 的自动推导**：进程只能知道自己的端口，无法知道自己"从服务端看过去"是什么地址（NAT、容器网络、网关前缀都在它的视野之外），所以推导只假设**服务端与工具实例同机**，得出 `http://localhost:{端口}/domainplane/invoke`。

- 同机部署 / 本地联调：不用配。
- 跨机、容器、前面有网关：**必须显式配置**，否则地址在服务端侧指向服务端自己，注册会成功、心跳也正常，但工具一被调用就失败。
- 兜底：服务端在收到注册时会比较 `endpoint` 的主机与注册来源 IP，发现"上报 loopback 但来源不是本机"时会打出明确 WARN，把这类静默故障提前暴露出来。

### 4.3 声明工具：注解式（推荐）

在任意 Spring Bean 的方法上写 `@Tool`，SDK 在装配期扫描并注册。方法签名即参数 schema，注解即治理策略，方法体即执行逻辑：

```java
@Component
public class OrderTools {

    // ① 只读：客服域可见，参数 schema  签名推导
    @Tool(desc = "按订单号查询订单状态。用户追问自己订单的发货/物流情况时调用",
            value = "queryOrder", domains = {"customer"})
    public String queryOrder(@ToolParam("订单号，如 FR2024001") String orderNo) {
        return orderService.statusOf(orderNo);
    }

    // ② 写操作：声明副作用 + 每次调用前中断等人工确认
    @Tool(desc = "按订单号退款。仅在用户明确要求退款时调用",
            value = "refundOrder", domains = {"admin"},
            effect = Tool.Effect.WRITE,
            approval = Tool.Approval.ALWAYS, approvalReason = "退款需人工确认")
    public String refundOrder(@ToolParam("订单号") String orderNo,
                              @ToolParam("退款金额，单位：元，必须 ≤ 订单实付金额") BigDecimal amount) {
        return orderService.refund(orderNo, amount);
    }
}
```

要点：

- **参数绑定**：调用时按参数名从模型的参数 JSON 里取值并转成声明类型；取不到就抛 `缺少必填参数: xxx`（  SDK 包装成工具失败原因回喂模型）。
- **参数名来源**：优先 `@ToolParam.name`，其次编译期元数据。Spring Boot 父 pom 默认开了 `-parameters`；普通 Maven 工程没开时**启动期直接报错**并提示补 `@ToolParam(name=...)`——用 `arg0` 注册出去只会让模型拿错 key，这种错必须留在启动期。
- **返回值**：`String` 原样回喂模型，其余类型序列化成 JSON。
- **开关**：`domainplane.tool-instance.scan-annotated`（默认 `true`）。关掉则只认 §4.4 的编程式注册。
- 注解字段语义与 `ToolSpec` 完全一致，见 §4.5；`@ToolParam` / `@ToolAdvanced` 字段见 §5 的注解表。
- **高级可选**写在方法级 `@ToolAdvanced` 上，一律 `参数名=值`、不做位置对齐：
  `@ToolAdvanced(example = {"orderNo=FR2024001"}, allowValues = {"channel=SMS|APP"}, sensitive = {"phone"})`。
  三者分别让模型看到示例、把渠道约束成枚举、把手机号的值在事件与审批 payload 里掩码成 `***`。

### 4.4 声明工具：编程式（工具清单要在启动期动态拼装时用）

实现 `ToolInstanceContributor`（Spring Bean 即可），在 `contribute` 里登记：

```java
@Component
public class OrderTools implements ToolInstanceContributor {
    @Override
    public void contribute(ToolRegistrar registrar) {
        registrar
            // ① 全域可用：挂在根域（不声明 ＝ 同样挂根域）
            .register(
                ToolSpec.of("queryWeather", "查询某城市天气，用户问天气时调用",
                        ToolSpec.schema(
                            Map.of("city", Map.of("type","string","description","城市名，如 杭州")),
                            "city"))
                    .withCategory("通用").withSideEffect("READ").withDomains("default"),
                this::queryWeather)

            // ② 域专属 + 有副作用 ⇒ 需人工二次确认
            .register(
                ToolSpec.of("closeOrder", "关闭一笔订单，用户明确要求取消时调用",
                        ToolSpec.schema(
                            Map.of("orderNo", Map.of("type","string","description","订单号"),
                                   "reason",  Map.of("type","string","description","关闭原因")),
                            "orderNo", "reason"))
                    .withCategory("订单").withDomains("default.order")
                    .withSideEffect("WRITE")
                    .withApproval("ALWAYS", "关单不可逆，需人工确认"),
                this::closeOrder);
    }

    private String queryWeather(String argumentsJson) {
        String city = JsonPath.read(argumentsJson, "$.city");   // 自行解析参数 JSON
        return city + "：多云转晴，26℃（示例数据）";
    }
    private String closeOrder(String argumentsJson) { /* 换成你的 Service 调用 */ return "已关闭"; }
}
```

> 两种方式可以共存。**重名时编程式覆盖注解式**（后注册生效），需要临时改写某个工具声明时不必动业务方法。

### 4.5 ToolSpec 字段

`ToolSpec` 不可变，链式 `with*` 返回新实例。

| 字段 | 来源 | 作用 |
|---|---|---|
| `name` | `of(name,..)` | 工具名，**全局唯一**；同名＝同一逻辑工具的多个副本 |
| `description` | `of(name, desc)` | 给 LLM 的用途说明（写清「何时调用/何时不要调用」比参数描述更重要） |
| `category` | `withCategory` | 管理页分类，**不参与任何过滤** |
| `version` | 默认 `1.0.0` | 语义化版本 |
| `domains` | `withDomains(..)` | 可用域（授权边界）。每项为**完整路径**；留空＝挂根域 `default`＝全树可见；无通配 |
| `sideEffect` | `withSideEffect` | `READ` / `WRITE` / `DESTRUCTIVE` |
| `idempotent` | 默认 `true` | 是否幂等（当前只登记展示，不参与重试判定） |
| `toModel` | 默认 `true` | 结果是否回填 LLM（当前只登记展示） |
| `requiresApproval` + `approvalMode` + `approvalReason` | `withApproval(mode, reason)` | 是否需要人工二次确认；`mode` 留空按 `ALWAYS` 处理 |

参数 schema 用 `ToolSpec.schema(properties, required...)` 便捷构造（本质就是 JSON Schema 的 `type/properties/required`），也可传 record DTO 的 JSON 结构。

### 4.6 ToolHandler 实现

```java
@FunctionalInterface
public interface ToolHandler {
    String handle(String argumentsJson) throws Exception;   // 参数 JSON → 结果文本
}
```

- 入参是服务端原样转发的参数 JSON（可能为空对象 `{}`）。
- 返回文本会原样回喂模型。
- **不抛异常也能表达失败**：返回「订单不存在」这类说明文字对模型更友好；抛异常 = 执行失败（异常信息回喂模型，不回抛给用户）。
- 有副作用的工具（关单、退款）**自己防重**：模型可能因上下文重复调用同一工具。

### 4.7 回调端点 `/domainplane/invoke`

服务端要执行工具时，POST 到 `endpoint`（即你进程的 `/domainplane/invoke`）。路径固定，不要改——改了会「注册成功但一调用就 404」。HTTP 层永远返回 200，业务失败写在响应的 `success=false` 里，以区分「实例不可达（该换副本重试）」和「工具执行失败（直接回喂模型）」。

`endpoint` 不填时  SDK 按本进程端口推导，但推导出的主机固定是 `localhost`：**只有当服务端与工具实例在同一台机器上时它才是对的**。跨机部署请显式填写服务端可达的地址（见 §4.2）。

#### 安全边界：端点不自带鉴权， 接入方负责

以下是**刻意的产品契约，不是缺陷**，写在这里以免被误当成漏洞报告或"顺手加上"：

| 事项 | SDK 的立场 |
| --- | --- |
| `/domainplane/invoke` 不校验任何凭证 | SDK 不内置鉴权。实例端口的暴露面 **接入方**负责收敛：需要鉴权时，在你自己的 Spring 应用里注册 `HandlerInterceptor` / `Filter` 校验服务端来源即可，SDK 不阻拦 |
| 服务端不校验 `endpoint` 指向的地址 | 允许内网地址、回环地址。工具实例与 DomainPlane 同机、或同处一个内网，是**常见的部署形态**（含本地开发），一刀切拒绝内网会误伤绝大多数正常部署 |
| 调用方可绕过审批与域过滤 | 服务端的审批闸门与域可见性校验建立在"调用来自服务端"这一前提上。直连实例端口等于绕过它 —— 所以**端口暴露面就是安全边界**，这是接入方的部署责任 |

一句话：**把实例端口当作只在可信网络内可达的服务端口**。需要更强隔离时，用网络策略（安全组 / NetworkPolicy）限制可达来源，而不是指望 SDK 内置鉴权。

---

## 5. 另一种形态：工具随服务端部署（本地 Bean 工具）

> **两侧写法完全一致**：工具在业务进程（工具实例）时用 §4.3，工具随服务端部署时用本节。
> 同一段代码在两种形态之间搬迁，一个字都不用改 —— 区别只在工具实例需要 `/domainplane/invoke` 回调端点，本地 Bean 工具不需要。

若工具直接放在服务端进程内（而非独立实例），用注解声明， 服务端启动期扫描，不暴露 `/domainplane/invoke`：

```java
@Component
public class LocalTools {                                  // 任意 Spring Bean 即可

    @Tool(desc = "按订单号查询订单状态",
        value = "queryOrder",
        domains = {"default.customer"})           // 可用域（完整路径）；留空＝挂根域＝全树可见
    public String queryOrder(@ToolParam(name = "orderNo", value = "订单号", required = true) String orderNo) {
        return "...";
    }
}
```

> `DomainPlaneToolProvider` 是**可选标记**：实现了照样被扫到，不实现也不影响注册——与工具实例 SDK 的规则一致。
> 唯一例外是工具方法所在的类被 AOP 代理且注解没留在代理方法上时，实现该接口可确保被扫到（SDK 侧遇到这种情况会打 WARN 提示）。

注解字段与 `ToolSpec` 语义一致，便于「工具从哪来」对模型与管控台透明：

| 注解 / 字段 | 作用 |
|---|---|
| `@Tool#desc` | 给 LLM 的用途说明（**唯一必填**） |
| `@Tool#value` | 工具名，留空取方法名，全局唯一 |
| `@Tool#domains` | 可用域（授权边界）。每项为**完整路径**，判定累加（命中该域或其任一祖先即见）；留空＝挂根域＝全树可见；无通配 |
| `@Tool#effect` | `Effect.READ` / `WRITE` / `DESTRUCTIVE` |
| `@Tool#approval` + `approvalReason` | 二次确认：`NONE`（默认）/ `ALWAYS`。`ALWAYS` 时**每次调用前中断等授权**；需「金额超阈值才审批」的条件式审批尚未落地 |
| `@ToolParam(value, name, required)` | 参数语义：`value` 是参数说明（推荐写法），`name` 覆盖参数名，`required` 默认 `true` |
| `@ToolDomains` | 类级默认域；方法级 `domains` 就近覆盖 |
| `@ToolAdvanced(example, allowValues, sensitive)` | 写法一律 `参数名=值`，**不做位置对齐**：`example` 进参数说明（模型据此更会构造参数）；`allowValues` 成为模型可见 schema 的 `enum` 白名单（比自然语言约束可靠）；`sensitive` 让该参数的**值**在工具调用事件与审批 payload 里显示为 `***`（执行仍用原值） |

> 参数结构靠反射推导（类型→JSON Schema），语义靠 `@ToolParam` 补。建议每个工具收一个 record DTO 入参，参数注解集中落在 DTO 上，签名与 schema 都更规整。

---

## 6. 域（domain）机制一句话讲清

域是**一棵树**，标识是从根域 `default` 出发的**完整路径**（`default.sales.order`）。它同时绑定
**工具集 + 提示词 + 知识范围 + 模型**，四个维度共用一套**沿链累加**语义：祖先的内容做后代的基底。

- 工具可见性**唯一维度**就是域；判定按累加——声明命中该域或它的任一祖先即见，故挂在父域上的工具其所有后代域都能用。
- 域有两种**角色**：**可调用单元**（能作为入口）与**装配节点**（只给后代配工具 / 提示词 / 模型 / 知识，不能直接调）。硬约束是**叶子规则**：**只有叶子域（没有子域）才能作为可调用单元**，一个域一旦有了子域就降级为装配节点；根域同样服从这条规则（整棵树只有根域时它可调用）。需要"某一层整体"的入口时，另建一个没有子域的域。管控台「域空间」可切换叶子域的可调用性；对已有子域的域点"设为可调用"会被拒绝并说明原因。
- 域有三个**来源**（根域 / 人工创建 / 工具声明派生），**同级、不构成等级**；差异只在生命周期（人工落盘、派生重启随声明重建）。登记时**沿链补齐**，写下 `domains = {"default.customer"}` 即整条链一并建出，不留悬空节点。
- 删除**递归**带走全部子孙，不向上提升；**只有根域不可删**。删除时连带清理：知识库索引与切片预览文件、提示词、模型绑定、工具声明记录。
- 每次请求的域**留空则归一化为根域**；非空但不存在报 `10004`，存在但不是可调用单元报 `10010`——绝不静默回退成全量工具。
- **没有通配写法**：全域可见的写法就是挂根域。
- 越权判断（角色→域映射）在宿主侧：平台信任调用方声明的域，只校验「域是否存在且可调用」。
- **会话状态按 (域, sessionId) 隔离**：同一个 `sessionId` 可以在不同域各用一份，记忆 / 断点 / 停止 / 事件流互不影响；`stop` 与 `resume` 必须带与 `chat` 相同的域。

### 6.1 会话记忆：只增不淘汰，到上限就换会话

会话记忆（`domainplane:chat:memory:{域}|{sessionId}`）存的是**对话面**：每轮的用户提问 + 最终文字回答。工具调用与工具结果**不进记忆**（它们只在检查点里）。

- **只增不淘汰**：到达上限（`max-messages`=100 条 / `max-tokens`=30000）之前不会丢弃任何历史 ——
  静默丢最旧的消息会让用户"以为还记得"。
- **到上限 → 拒绝新一轮**：入口判定不通过就返回 `30004 SESSION_MEMORY_FULL`，并且**零副作用**
  （不写记忆、不清断点、不调模型）。判定是**粘性**的：只要记忆还满着，之后每次都用同一个码拒绝。
- **平台不代为切换**：`sessionId` 自始至终 调用方提供，满了 调用方**自己换一个新 id** 重新开始。
  旧会话的数据保留在 Redis 里（默认不过期），只是不再接受写入。
- **上限只约束"能不能开新一轮"**，不约束"能不能收尾"：最终回答永远允许写入，
  否则会留下有问无答的孤立提问。所以一轮结束后总量可能略微超过上限。
- **挂起未批 + 用户直接开新对话 = 用户拒绝了那次审批**：服务端**不返回 `30002`**，而是补一条占位回答后
  **取消那个待审批动作**（断点一并删除，避免事后被 resume 执行）。

---

## 7. 端到端最小跑通

> 仓库内没有示例应用，下面是最短的一条可用路径：服务端 + 接入方应用（同时扮演客户端与工具实例）。

1. 起服务端：`java -jar domainplane-v1.0-beta.1.jar`（默认 9527，裸机 / 容器部署见 `DEPLOYMENT.md`）。
2. 打开管控台 `http://localhost:9527/admin.html`，用种子账号 `domainplane` / `domainplane` 登录（§2.2），
   在「模型设置」与「存储配置」两页填好模型、ES、Redis（§2.3 / §2.4），保存即生效。
3. 在「域空间」建一个域，如 `default.customer`（§6）。它必须是**可调用单元**才能当入口。
4. 写接入方应用：引 `domainplane-chat-client`（§3.1），配好 `domainplane.server` / `username` / `password`（§3.2），
   注入 `DomainPlaneAgentFactory` 取该域的门面（§3.3），`ask(sessionId, question)` 即可发起一轮对话。
5. 验证工具：同一个应用再引 `domainplane-tool-provider`、打开 `domainplane.tools=true`（§4.1 / §4.2），
   用 `@Tool` 声明几个方法（§4.3）。启动后到管控台「在线实例」页确认实例在线、工具已进注册表。
6. 验证知识库：管控台「知识库」页上传一份 md，**归属域**选到某个域（或留 `default` 让全域可见），
   再用该域的对话去检索（§2.6）。

> 工具回流地址 `endpoint` 留空即按本进程端口推导，只在服务端与工具实例**同机**时成立；
> 跨机部署必须显式填写（§4.2）。
