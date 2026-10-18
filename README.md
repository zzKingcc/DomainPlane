# DomainPlane

基于 Java 的 AI 工作流编排启动器（Java-based AI Workflow Orchestration Starter）：单服务端，三个 SDK，把「大模型 + 工具 + 知识库 + 多模态」按请求所属的**域**实时装配成一次完整的 Agent 对话。

- 当前版本：`v1.0-beta.2`
- 技术栈：Java 21 · Spring Boot 3.5.7 · LangChain4j 1.18.1 · LangGraph4j 1.8.17 · Elasticsearch 9.x · Redis
- 许可证：[Apache License 2.0](LICENSE)
- 设计文档：[`docs/设计文档.md`](docs/设计文档.md)

## 项目简介

DomainPlane 是一个**请求级装配的 Agent 编排中间件**。域（Domain）是贯穿全局的唯一抽象：一棵点分全路径的域树（如 `default.customer.aftersale`），每一轮对话都带着域发起，服务端沿域链把工具、知识索引、提示词、模型四维能力装配到这一轮。同一个服务端可以同时服务多个业务场景，彼此靠域隔离。

整体由两部分组成：

- **服务端**（`domainplane-server`，含全部重逻辑）：Agent 编排内核、工具体系、模型层、知识库 RAG、多模态、MCP 接入、管控台与鉴权。对外暴露 HTTP + SSE。
- **三个 SDK**（仅通过 HTTP 报文与服务端交互）：对话 SDK、知识库 SDK、工具实例 SDK。

## 核心功能

- **域体系**：点分全路径域树；四维继承语义：工具 / 知识 / 提示词沿域链累加（挂父域，子域默认继承），模型就近覆盖；可调用单元必须是叶子域；删除域级联清理。
- **Agent 编排引擎**：基于 LangGraph4j 的 `agent → tools → review` 状态图；SSE 事件流（`TOKEN` / `THINKING` / `TOOL_CALL` / `TOOL_RESULT` / `INTERRUPT` / `STOPPED` / `ERROR` / `DONE` 八种事件）；人工审批中断与 `resume` 恢复；LangGraph 检查点断点续跑（24h TTL）；同会话串行闸门；失败回滚矩阵。
- **工具体系**：`@Tool` 等四条注解声明 → 扫描注册 → 按域实时裁剪 → 路由到本地 Bean 或远程工具实例 → 执行 → 审批；远程实例生命周期管理（注册 / 心跳 / 判死 / 下线）；敏感参数掩码。
- **模型层**：模型档案（端点族 × 模态两个正交维度）、服务商连接库、内置预设、能力探测（零成本探测 + 实测探测）、四族多模态端点客户端（生图 / 重排 / 语音合成 / 语音识别）；域绑定档案而非模型名。
- **会话记忆与检查点**：记忆只追加、不淘汰（默认永久，靠 Redis RDB+AOF 保底）；本轮给模型看多少由装配预算决定（默认 24k token，近况窗口 8 轮 / 摘要 2k / 卡片 5 张，装不下就逐级降级，永不拒绝开轮）；按轮归档、倒排历史索引、滚动摘要（默认关）、`searchHistory` / `readHistory` 历史检索工具。
- **知识库 RAG**：一域一索引（越权边界靠「查哪些索引」保证）；Elasticsearch 双轨召回（向量 kNN + BM25/IK 分词）；自适应分数制与 RRF 两种融合；层级衰减与 `title-boost`；注入字符预算收口。
- **文档摄取与切片**：8 种格式（txt / md / markdown / docx / doc / pdf / xls / xlsx）→ 统一 Block 模型 → 结构感知切分（撞标题提前断、句级重叠）→ 切片元数据；切片预览可导出 txt 人工核对。
- **多模态**：图片只传引用名不传 base64；透明底自动合成白底；`seeImage` 委派链路；生图 / 语音 / 转写 / 视频四类能力工具，两个独立执行池。
- **MCP 接入**：连接库管理；Streamable HTTP（2025-03-26）与 Legacy SSE 两种传输；MCP 工具目录桥接为平台工具（默认全审批）；OAuth 2.1 授权五步流程。
- **管控台**：零依赖前端 9 页（概览 / 模型 / 域空间 / 存储 / 提示词 / 知识库 / MCP / 实例 / 账号），85 个管理端点；ES / Redis 连接**热切换**（不重启、旧连接延迟销毁）。
- **鉴权与错误出口**：无状态 HMAC 签名凭证（不设有效期、改密即全失效）；BCrypt 账号落盘；55 个错误码 / 11 个段位，运维文案与用户文案双轨；全局唯一异常出口。
- **SDK 三件套**：`DomainPlaneAgent` 门面（先绑域再调用：`ask` / `stream` / `events` / `resume` / `stop`）；`KnowledgeBaseClient`（上传 / 列表 / 删除，检索归服务端）；工具实例 SDK（注解扫描 + 心跳注册 + 反向调用）。

## 安装与运行

### 环境要求

| 依赖 | 要求 | 用途 |
| --- | --- | --- |
| JDK | 21+ | 编译与运行 |
| Maven | 3.6.3+ | 构建 |
| Redis | 任意可用版本 | 会话记忆、检查点、单实例守卫（未配置时服务可启动，但对话报 `DEPENDENCY_NOT_CONFIGURED`） |
| Elasticsearch | 9.x（官方兼容矩阵保证）；8.x 大概率可用（未实测）；7.x 不支持（缺 `dense_vector index:true`） | 知识库检索（未配置时启动期跳过灌库，可在管控台补填） |

对话模型不内置：需在管控台「模型设置」页自建档案（服务商连接 → 档案 → 能力探测 → 域绑定）后即可对话。

### 构建

```bash
mvn clean package
```

产物为平台无关的单文件 fat jar：

```
domainplane-server/target/domainplane-v1.0-beta.2.jar
```

### 本地运行

```bash
java -jar domainplane-server/target/domainplane-v1.0-beta.2.jar
```

默认监听 `9527` 端口。本机开发**要覆盖配置目录**，否则会写到系统盘根路径：

```bash
# Linux / macOS
java -jar domainplane-server/target/domainplane-v1.0-beta.2.jar \
  -DDOMAINPLANE_SETTINGS_PATH=$PWD/.dev/config

# Windows PowerShell
$env:DOMAINPLANE_SETTINGS_PATH="$PWD\.dev\config"
java -jar domainplane-server\target\domainplane-v1.0-beta.2.jar
```

也可以用 Maven 插件直接跑（需先安装内部模块依赖，或加 `-am` 一并构建）：

```bash
mvn -pl domainplane-server -am spring-boot:run
```

### Docker 运行

```bash
# 1. 先执行 mvn clean package 得到 jar，把 jar 与本 Dockerfile 放同一目录
docker build -t domainplane-server:v1.0-beta.2 .

# 2. 运行（config 卷必须挂：账号、模型、存储连接都在里面）
docker run -d --name domainplane -p 9527:9527 \
  -v domainplane-config:/var/lib/domainplane/config \
  -v domainplane-chunks:/var/lib/domainplane/chunks \
  domainplane-server:v1.0-beta.2
```

镜像内建 `HEALTHCHECK`，打免鉴权的 `GET /health`。覆盖端口用 `SERVER_PORT` 环境变量或 `--server.port=`。

### 启动后验证

```bash
curl http://localhost:9527/health
# {"code":0,"status":"UP","service":"domainplane-server","version":"v1.0-beta.2"}
```

浏览器打开管控台 `http://localhost:9527/console/login.html`，首次登录使用种子账号 `domainplane` / `domainplane`（首启落盘为 `accounts.json`，忘记密码的唯一出口是删除该文件重启）。

**首次配置顺序**（缺一步对话就跑不通）：

1. 「存储配置」页填 Redis 连接（必需）与 Elasticsearch 连接（知识库需要），保存即热切换生效；
2. 「模型设置」页建服务商连接 → 建模型档案 → 能力探测 → 把档案绑定到目标域（对话模型无内置，不绑域调用即报错）；
3. 「域空间」页确认目标域存在且为可调用的叶子域（根域 `default` 预置即可用）；
4. 「账号」页修改默认密码，默认口令下变更类操作会被拒（`AUTH_DEFAULT_CREDENTIAL`）。

## 配置说明

### 服务端主要配置（`domainplane-server/src/main/resources/application.yaml`）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `server.port` | `9527`（`DOMAINPLANE_SERVER_PORT`） | HTTP 端口 |
| `domainplane.settings.path` | `/var/lib/domainplane/config`（`DOMAINPLANE_SETTINGS_PATH`） | 落盘配置目录（8 份 JSON） |
| `domainplane.export.path` | `/var/lib/domainplane/chunks`（`DOMAINPLANE_EXPORT_PATH`） | 切片预览 txt 导出目录 |
| `domainplane.logging.path` / `.level` | `/var/log/domainplane` / `INFO`（`DOMAINPLANE_LOG_PATH` / `DOMAINPLANE_LOG_LEVEL`） | 日志落盘目录与级别 |
| `domainplane.redis.*` | 默认未配置（端口 `6379`） | Redis 连接与连接池；也可只在管控台配（热切换） |
| `domainplane.elasticsearch.*` | 默认未配置（端口 `9200`，scheme `http`） | ES 连接；为空则启动期跳过灌库 |
| `domainplane.memory.*` | `assembly-budget: 24000`、`assembly-window-turns: 8`、`checkpoint-ttl: 24h` | 召回装配预算、近况窗口、检查点 TTL（`max-messages` / `max-tokens` 仍在，只作自检） |
| `domainplane.agent.*` | 核心 8 / 最大 32 / 队列 200 | 编排线程池 |
| `domainplane.instance.*` | 判死 35s、扫描 5s、调用超时 30s | 远程工具实例参数 |
| `domainplane.retrieval.*` | 双路并行、`top-n: 15`、注入 8 片 / 3000 字符 | 混合检索与融合参数 |
| `domainplane.rag.*` | 8 种扩展名白名单、单文档切片上限 2000 | 上传白名单与切片参数（`chunking.max-chars: 400` 等） |
| `domainplane.single-instance-guard` | `true` | 置 `false` 可临时绕过单实例守卫（不推荐） |

覆盖优先级：命令行 `--key` ＞ `-Dkey` ＞ 环境变量 ＞ 平台默认。

### 落盘配置文件（`settings.path` 下，共 8 份）

| 文件 | 内容 |
| --- | --- |
| `accounts.json` | 管理员账号（BCrypt 哈希，种子 `accounts-seed.json` 首启落盘） |
| `llm-settings.json` / `models.json` / `provider-connections.json` | 模型兜底设置 / 模型档案与域绑定 / 服务商连接库 |
| `infra-settings.json` | ES / Redis 连接（支持热切换） |
| `domains.json` / `prompts.json` | 域声明与可调用性 / 域提示词 |
| `mcp-connections.json` | MCP 连接与域绑定 |

### 平台默认路径（可用环境变量或同名 JVM 属性覆盖）

| 用途 | Linux | Windows | macOS |
| --- | --- | --- | --- |
| 配置 | `/var/lib/domainplane/config` | `%ProgramData%\DomainPlane\config` | `/Library/Application Support/DomainPlane/config` |
| 日志 | `/var/log/domainplane` | `%ProgramData%\DomainPlane\logs` | `/Library/Logs/DomainPlane` |
| 切片导出 | `/var/lib/domainplane/chunks` | `%ProgramData%\DomainPlane\chunks` | `…/DomainPlane/chunks` |

### SDK 侧配置

三个 SDK 共用前缀 `domainplane.*`（`DomainPlaneProperties`）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `domainplane.server` | `http://localhost:9527` | 服务端地址（一个 URL 取代 host + port） |
| `domainplane.username` / `domainplane.password` | `domainplane` / `domainplane` | 服务端账号 |
| `domainplane.tools` | `false` | 是否把本进程的 `@Tool` 注册给服务端（工具实例 SDK 开关） |
| `domainplane.tool-instance.instance-id` | — | 工具实例标识（重连必须沿用同一个） |
| `domainplane.tool-instance.endpoint` | 按本进程端口推导 | 工具调用回流地址，跨机 / 有网关时必须显式填写 |

## 目录结构与主要模块

```
DomainPlane/
├── pom.xml                        # 聚合工程（Spring Boot 3.5.7 parent，Java 21）
├── Dockerfile                     # 服务端镜像（temurin:21-jre，默认端口 9527）
├── docs/
│   ├── 设计文档.md                # 唯一设计说明（F01–F16 十六个功能块）
│   └── 功能划分与介绍顺序.md      # 模块实测数据与讲解排期
├── domainplane-api/               # 对外契约：AgentService / AgentEvent / ToolDescriptor /
│                                  #   四条工具注解 / ErrorCode / 归档 SPI（38 类 / 2,613 行）
├── domainplane-common/            # 通用底座：异常体系、输入消毒、CJK 分词、原子落盘、内存指标（16 类）
├── domainplane-domain/            # 纯领域规则：检索融合策略、会话记忆、知识检索入口（15 类）
├── domainplane-infrastructure/    # 基础设施：ES 索引与检索器、文档摄取切片、Redis 记忆与检查点（44 类）
├── domainplane-runtime/           # 运行时内核：编排引擎、工具注册与路由、上下文装配、取消（35 类）
├── domainplane-sdk-core/          # SDK 连接配置 DomainPlaneProperties（1 类）
├── domainplane-client-core/       # SDK 底座：WebClient、凭证缓存、错误翻译（8 类）
├── domainplane-chat-client/       # 对话 SDK，唯一入口 DomainPlaneAgent（4 类）
├── domainplane-kb-client/         # 知识库 SDK：上传 / 列表 / 删除（2 类）
├── domainplane-tool-provider/     # 工具实例 SDK：注解扫描、注册与心跳、反向调用端点（12 类）
└── domainplane-server/            # 服务端外壳：装配、管控接口、鉴权、设置落盘、模型层、
                                   #   MCP、多模态（123 类 / 20,968 行，占全项目约 50%）
```

模块依赖方向（依赖只向上，不向下）：

```
api → common → domain → infrastructure → runtime → server
sdk-core → api；client-core → sdk-core；chat-client / kb-client → client-core；tool-provider → sdk-core
```

三个 SDK 与服务端**只通过 HTTP 报文耦合**。契约（`domainplane-api`）位于依赖图最底层，是服务端与三个 SDK 共用的唯一真相源：同一份 `AgentEvent`、同一份 `ErrorCode`，避免跨进程分叉。

## 使用范例

### 1. HTTP / SSE 直接调用

```bash
# 登录换取凭证（响应体含 credential，也可用 Set-Cookie: domainplane_admin）
CRED=$(curl -s -X POST http://localhost:9527/api/agent/login \
  -H "Content-Type: application/json" \
  -d '{"username":"domainplane","password":"domainplane"}' | sed -n 's/.*"credential":"\([^"]*\)".*/\1/p')

# 发起一轮对话（SSE 事件流：TOKEN... → DONE）
curl -N -X POST http://localhost:9527/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "X-DomainPlane-Credential: $CRED" \
  -d '{"sessionId":"demo-1","domain":"default","message":"你好"}'

# 停止（不可恢复，幂等）
curl -X POST "http://localhost:9527/api/agent/stop/demo-1?domain=default" \
  -H "X-DomainPlane-Credential: $CRED"
```

被 `INTERRUPT` 事件挂起（待审批工具）时，用 `POST /api/agent/resume?sessionId=...&approved=true&domain=...` 恢复，`domain` 必须与 chat 时一致。

### 2. 对话 SDK（`domainplane-chat-client`）

```xml
<dependency>
  <groupId>com.zzkingcc</groupId>
  <artifactId>domainplane-chat-client</artifactId>
  <version>v1.0-beta.2</version>
</dependency>
```

```yaml
domainplane:
  server: http://localhost:9527
  username: domainplane
  password: domainplane
```

```java
@Resource
private DomainPlaneAgentFactory agentFactory;

// 先绑域（必须是叶子域），再调用
DomainPlaneAgent agent = agentFactory.forDomain("default.customer");

String answer = agent.ask("session-001", "昨天的订单到哪了？");   // 同步拿最终答案
Flux<String> tokens = agent.stream("session-001", "展开讲讲");   // 逐字输出
Flux<AgentEvent> events = agent.events("session-001", "刚才那条"); // 完整事件流

agent.stop("session-001");
```

`ask` / `stream` / `events` 底层是同一条事件流。带图提问传 `List<MessageContent>`（`MessageContent.text(...)` / `MessageContent.imageUrl(...)` 等）。

### 3. 知识库 SDK（`domainplane-kb-client`）

```xml
<dependency>
  <groupId>com.zzkingcc</groupId>
  <artifactId>domainplane-kb-client</artifactId>
  <version>v1.0-beta.2</version>
</dependency>
```

```java
@Resource
private KnowledgeBaseClient knowledgeBaseClient;

// 上传（域决定哪些对话能检索到；留空 = 挂根域 = 全树可见）
KnowledgeBaseClient.UploadResult result =
    knowledgeBaseClient.upload(Files.readAllBytes(path), "售后政策.pdf", false, "default.customer");

knowledgeBaseClient.list();            // 已入库文档
knowledgeBaseClient.delete(docId);     // 删除并释放文件名
```

支持 txt / md / markdown / docx / doc / pdf / xls / xlsx，单文件上限 5MB。检索是服务端的事：模型通过检索工具自动取用，SDK 侧无检索入口。

### 4. 工具实例 SDK（`domainplane-tool-provider`）

把宿主应用里的方法注册成 Agent 工具：

```xml
<dependency>
  <groupId>com.zzkingcc</groupId>
  <artifactId>domainplane-tool-provider</artifactId>
  <version>v1.0-beta.2</version>
</dependency>
```

```yaml
domainplane:
  server: http://localhost:9527
  username: domainplane
  password: domainplane
  tools: true                       # 开启工具实例自动装配
  tool-instance:
    instance-id: order-service-01   # 重连必须沿用同一个
    endpoint: http://10.0.0.5:8081/domainplane/invoke  # 服务端可达的回流地址
```

```java
@Component
public class OrderTools {

    @Tool(desc = "按订单号查询订单状态", domains = {"default.customer"}, effect = Tool.Effect.READ)
    public String queryOrder(@ToolParam("订单号") String orderNo) {
        // ... 业务逻辑
    }
}
```

启动后自动扫描注册并心跳；服务端按域把工具裁剪给模型，调用时反向 HTTP 回调本实例执行。破坏性操作配 `effect = Tool.Effect.DESTRUCTIVE, approval = Tool.Approval.ALWAYS` 走人工审批。`/domainplane/invoke` 不自带鉴权，实例端口暴露面由接入方自行收敛。

## 贡献指南

### 本地构建与测试

```bash
mvn clean package          # 全量构建
mvn test                   # 全量测试（当前测试 / 主代码行数比 ≈44%）
```

修改后请保证：编译通过、相关模块测试通过。契约类改动（`domainplane-api`）需同步检查服务端与三个 SDK 两侧。`AgentService` 有 runtime 与 chat-client 两个方向相反的实现，加方法时两边都要改。

### 约定

1. **契约唯一**：跨进程类型（请求 / 事件 / 工具元数据 / 错误码）只放 `domainplane-api`，禁止在 SDK 或服务端各拷一份。
2. **错误码集中登记**：新码加到 `api.code.ErrorCode`，按段位（10xxx 鉴权 / 20xxx 容量 / 30xxx 会话 / 40xxx 入参 / 50xxx 系统 / 60xxx 知识库 / 70xxx 记忆 / 80xxx 工具 / 90xxx 外部依赖 / 91xxx 多模态）落位；`userMessage` 与 `message` 双文案，对外只出 `userMessage`。
3. **文档唯一**：设计说明只维护 `docs/设计文档.md`（按 F01–F16 分章），不新建第二份设计文档；只写代码可验证的事实，推测项单列「观察点」。
4. **代码规模口径**：行数按 `src` 下 `.java` 非空行（含注释）统计，与其他口径（含空行的物理行）不可混用。
5. **派生值不进存储**：可调用性、融合分等读取时计算；派生数据（索引）可重建，有损数据（滚动摘要）按档案对待。
6. **提交**：基于 `main` 开分支，一个分支一件事；提交信息说明「改了什么 + 为什么」，不携带与改动无关的格式化。

### 提 PR 前自查

- [ ] `mvn clean package` 通过
- [ ] 新增 / 改动的行为有测试覆盖（当前测试 / 主代码行数比 ≈44%，请保持不缩水）
- [ ] 涉及对外契约或错误码时，`docs/设计文档.md` 对应章节已同步
- [ ] 没有引入「静默降级」：能力缺失、越权、依赖未配置，一律显式报错

## 已知边界（v1.0-beta.2）

| 项 | 现状 |
| --- | --- |
| 多实例部署 | 不支持，单实例守卫强制拦截 |
| 入口限流 / 并发会话上限 | 错误码已定义（`RATE_LIMITED` / `CONCURRENT_LIMIT`），代码不发出 |
| 归档 / 记忆自动清理 | 无，需手工清理；只有删域会级联 |
| 滚动摘要 TTL | 无（有损数据按档案对待） |
| MCP `LEGACY_SSE` | 已实现，未对真实旧 server 验证 |
| PDF 深度解析 | 不做字号推断标题 / 表格还原 / 双栏 / 扫描件 |
| 图片 OCR | 不做 |

详细设计理由、易错点与技术债清单见 [`docs/设计文档.md`](docs/设计文档.md)。
