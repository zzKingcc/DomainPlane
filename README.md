# DomainPlane

单服务端、三个 SDK，把「大模型 + 工具 + 知识库 + 多模态」按请求所属的**域**实时装配成一次完整的 Agent 对话。

![version](docs/assets/badge-version.svg)
![Java](docs/assets/badge-java.svg)
![Spring Boot](docs/assets/badge-spring-boot.svg)
[![license](docs/assets/badge-license.svg)](LICENSE)

| 项 | 值 |
| --- | --- |
| 技术栈 | Java 21 · Spring Boot 3.5.7 · LangChain4j 1.18.1 · LangGraph4j 1.8.17 · Elasticsearch 9.x · Redis |
| 文档 | [文档索引](docs/README.md) · [English](docs/README.en.md) · [架构总览](docs/02-架构总览.md) · [快速上手](docs/01-快速上手.md) |
| 最后更新 | 2026-11-02 |

## 目录

- [这个项目是什么](#这个项目是什么)
- [核心能力](#核心能力)
- [管控台](#管控台)
- [快速开始](#快速开始)
- [用起来](#用起来)
- [配置](#配置)
- [项目结构](#项目结构)
- [文档导航](#文档导航)
- [常见问题](#常见问题)
- [已知边界](#已知边界)
- [贡献](#贡献)
- [许可](#许可)

---

## 这个项目是什么

**给谁用**：需要在多个业务场景里同时用大模型、但不想为每个场景各部署一套 Agent 服务的团队。

**解决什么问题**：场景一多，"一场景一服务"就会逼你把工具体系、知识检索、会话记忆、模型接入、多模态、管控台重复实现好几遍，半年后变成几个互不兼容的 Agent 服务；反过来做"一个大服务 + 租户表"，租户键又会渗透进每一次查询、每一次装配和每一条历史数据的清理。

**怎么做到的**：把**域**（Domain）做成一个点分全路径字符串（如 `default.customer.aftersale`）。每轮对话带域进来，服务端沿域链把四维能力实时装配到这一轮：

| 维度 | 沿域链的合并语义 |
| --- | --- |
| 工具 | 累加（挂父域，子域默认继承） |
| 知识索引 | 累加（检索域 D = D 自身 + 祖先链上的索引） |
| 提示词 | 累加（根域片段即基底） |
| 模型 | 就近覆盖（子域绑了就盖掉父域） |

域同时决定会话状态键 `(域, sessionId)`。所以隔离不靠部署、也不靠过滤条件——越权需要"查错索引"，而索引名由域路径确定性派生；漏写一个 `WHERE domain = ?` 不会泄露，只会查不到。

**用起来是什么样**：起一个 jar，浏览器进管控台配好连接与模型档案，然后在 SDK 里 `forDomain("default.customer").ask(sessionId, "问题")`。同一个 jar 同时服务任意多个域。

---

## 核心能力

| 能力 | 一句话 | 详见 |
| --- | --- | --- |
| Agent 编排 | LangGraph4j 状态图 `agent → tools → review`；9 种 SSE 事件；审批中断 + `resume`；同会话串行；规划前置可选 | [04](docs/04-对话编排.md) |
| 工具体系 | `@Tool` 四条注解声明 → 扫描注册 → 按域裁剪 → 路由到本地 Bean 或远程实例 → 审批 → 敏感参数掩码 | [06](docs/06-工具体系.md) |
| 会话记忆 | 只追加不淘汰（默认永久）；本轮给模型看多少由装配预算决定（默认 24k，逐级降级，永不拒绝开轮） | [05](docs/05-记忆与上下文.md) |
| 知识库 RAG | 一域一索引；连接 / 知识体 / 域三层模型；向量 + BM25/IK 双轨召回；自适应分数制与 RRF | [07](docs/07-知识库.md) |
| 文档摄取 | 8 种格式 → 统一 Block → 结构感知切分；切片原文落 BlobStore，索引是可重建的派生数据 | [07](docs/07-知识库.md) |
| 多模态 | 图片只传引用名不传 base64；透明底自动合成白底；生图 / 语音 / 转写 / 视频四类能力工具 | [08](docs/08-模型与多模态.md) |
| MCP 接入 | Streamable HTTP 与 Legacy SSE；工具目录桥接为平台工具（默认全审批）；OAuth 2.1 | [09](docs/09-MCP接入.md) |
| 模型层 | 档案（端点族 × 模态两个正交维度）、能力探测、四族多模态端点客户端、协议可切换（OpenAI 兼容 / Anthropic） | [08](docs/08-模型与多模态.md) |
| 管控台 | 零依赖前端 11 页（10 个管理页 + 登录）、94 个方法级管理端点；ES / Redis 连接**热切换**（不重启、旧连接延迟销毁） | [10](docs/10-管控台.md) |
| 鉴权与错误出口 | 无状态 HMAC 签名凭证（改密即全端失效）；60 个错误码 / 11 段位，运维与用户文案双轨 | [11](docs/11-鉴权与错误码.md) |
| SDK 三件套 | 对话 / 知识库 / 工具实例，只走 HTTP，与服务端仅靠报文耦合 | [12](docs/12-SDK接入.md) |

---

## 管控台

所有配置都在浏览器里做，**改完即生效**——ES / Redis 连接热切换，不重启、不打断在途请求。11 个页面（含登录页）、94 个方法级端点，零依赖前端（同源部署，不引 CDN）。

### 域空间：一张卡看清一个域的全部能力

![域空间](docs/assets/screenshots/console-domains.png)

可调用模型、可见工具、绑定的 MCP 连接都摊在同一张卡上。根域 `default` 是**装配节点**（只把能力传给后代，自己不能当入口），叶子域才是**可调用单元**；"可见工具（含沿链继承）"直接说明这个域到底能干什么。

### 模型设置：向量模型与其余档案分开管

![模型设置](docs/assets/screenshots/console-models.png)

向量模型全局只能有一个（知识库的灌库与向量检索都依赖它），单独一栏；其余档案按类型排列，"被哪些域使用"直接标在卡上，改绑定不用跳页。上下文长度、是否回写思考过程都在卡片里就地改。

### 知识库：一个体两槽，落点一目了然

![知识库](docs/assets/screenshots/console-knowledge.png)

知识体表把关键字槽、向量槽、召回模式、绑定域、文档与切片数摊平；换向量模型时提供"重灌 / 清空 / 重建"三个显式动作，不静默重算。

### 存储配置：各自独立保存，保存即生效

![存储配置](docs/assets/screenshots/console-infra.png)

ES 与 Redis 两张卡各自独立保存、互不影响；口令留空表示不修改，表单里有"测试连接"和"保存并生效"。旧连接延迟销毁，在途请求不受影响。

### 其余六页

总览、提示词设定、MCP 连接、工具实例、会话档案、账号——逐页说明见 [文档首页](docs/README.md) 的"管控台"一节。

> 截图取自 `v1.0-beta.2`（2026-11-02 重拍），数据为演示数据。

---

## 快速开始

### 前置条件

| 依赖 | 要求 | 不满足会怎样 |
| --- | --- | --- |
| JDK | 21+ | 编译不过 |
| Maven | 3.6.3+ | 构建不了 |
| Redis | 任意可用版本 | 服务能启动，**对话报 `90005 DEPENDENCY_NOT_CONFIGURED`** |
| Elasticsearch | 9.x（7.x 不支持，缺 `dense_vector index:true`） | 服务能启动，知识库不可用 |

对话模型**不内置**，需要启动后在管控台自建（见下一节）。

### 三步跑起来

```bash
# 1. 构建（产物：domainplane-server/target/domainplane-v1.0-beta.2.jar）
mvn clean package

# 2. 启动（本机开发务必覆盖配置目录，否则写到系统盘根路径）
java -DDOMAINPLANE_SETTINGS_PATH="$PWD/.dev/config" -jar domainplane-server/target/domainplane-v1.0-beta.2.jar

# 3. 验证
curl http://localhost:9527/health
# {"code":0,"status":"UP","service":"domainplane-server","version":"v1.0-beta.2"}
```

> `-D` 必须写在 `-jar` **之前**。写在后面会变成传给应用的普通参数，静默不生效。

默认端口 `9527`。也可以用 Maven 插件直跑（需先 `install` 内部模块，或加 `-am` 一起构建）：

```bash
mvn -pl domainplane-server -am spring-boot:run
```

### 首次配置（缺一步对话就跑不通）

浏览器打开 <http://localhost:9527/console/login.html>，用种子账号 `domainplane` / `domainplane` 登录，然后：

1. **存储配置** → 填 Redis 连接（必需）与 Elasticsearch 连接（知识库需要），保存即热切换生效。
2. **模型设置** → 建服务商连接 → 建模型档案 → 跑能力探测 → 把档案绑定到目标域。
3. **域空间** → 确认目标域存在且是**可调用的叶子域**（根域 `default` 预置即可用）。
4. **账号** → 改掉默认口令（默认口令下变更类操作会被拒，错误码 `10008`）。

做完这四步就能对话了。

### Docker 运行

```bash
# 1. 先 mvn clean package，把 jar 与本 Dockerfile 放同一目录
docker build -t domainplane-server:v1.0-beta.2 .

# 2. 运行（config 卷必须挂：账号、模型、存储连接都在里面）
docker run -d --name domainplane -p 9527:9527 \
  -v domainplane-config:/var/lib/domainplane/config \
  -v domainplane-chunks:/var/lib/domainplane/chunks \
  -v domainplane-blobs:/var/lib/domainplane/blobs \
  domainplane-server:v1.0-beta.2
```

镜像内建 `HEALTHCHECK`，打免鉴权的 `GET /health`。端口用 `--server.port=` 覆盖。不挂 `config` 卷，重建容器等于账号回落种子、所有配置重填。

---

## 用起来

### 1. HTTP / SSE 直连

```bash
# 登录换凭证（响应体含 credential，同时下发 Set-Cookie: domainplane_admin）
CRED=$(curl -s -X POST http://localhost:9527/api/agent/login \
  -H "Content-Type: application/json" \
  -d '{"username":"domainplane","password":"domainplane"}' \
  | sed -n 's/.*"credential":"\([^"]*\)".*/\1/p')

# 发起一轮对话（SSE：TOKEN … → DONE）
curl -N -X POST http://localhost:9527/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "X-DomainPlane-Credential: $CRED" \
  -d '{"sessionId":"demo-1","domain":"default","message":"你好"}'

# 停止（不可恢复，幂等）
curl -X POST "http://localhost:9527/api/agent/stop/demo-1?domain=default" \
  -H "X-DomainPlane-Credential: $CRED"
```

被 `INTERRUPT` 事件挂起（等审批的工具）时，用 `POST /api/agent/resume?sessionId=...&approved=true&domain=...` 恢复——`domain` 必须与 chat 时一致。

### 2. 对话 SDK

> **本项目没有官方发布仓库**（未发 Maven Central，无公共私服）。下面的坐标必须先把对应模块装进你自己的仓库：
>
> ```bash
> mvn -pl domainplane-chat-client -am install
> ```

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

// 先绑域（必须是可调用的叶子域），再调用
DomainPlaneAgent agent = agentFactory.forDomain("default.customer");

String answer        = agent.ask("session-001", "昨天的订单到哪了？");   // 同步拿最终答案
Flux<String> tokens   = agent.stream("session-001", "展开讲讲");          // 逐字输出
Flux<AgentEvent> evts = agent.events("session-001", "刚才那条");          // 完整事件流

agent.stop("session-001");                                             // 幂等，返回是否设置成功
```

`ask` / `stream` / `events` 底层是同一条事件流。带图提问传 `List<MessageContent>`（`MessageContent.text(...)` / `imageUrl(...)`）。本轮需要人工审批时 `ask` 抛 `ApprovalRequiredException`，展示待审批工具后调 `agent.resume(sessionId, true)`。

### 3. 知识库 SDK

```xml
<dependency>
    <groupId>com.zzkingcc</groupId>
    <artifactId>domainplane-kb-client</artifactId>
    <version>v1.0-beta.2</version>
</dependency>
```

```java
@Resource
private KnowledgeBaseClient kb;

// 上传：域决定哪些对话能检索到它；留空 = 挂根域 = 全树可见
KnowledgeBaseClient.UploadResult r =
    kb.upload(Files.readAllBytes(path), "售后政策.pdf", false, "default.customer");

List<KnowledgeBaseClient.DocumentItem> docs = kb.list();
boolean deleted = kb.delete(r.docId());
```

支持 txt / md / markdown / docx / doc / pdf / xls / xlsx，单文件上限 5MB。**检索是服务端的事**：模型通过检索工具自动取用，SDK 侧没有检索入口。

### 4. 工具实例 SDK

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
  tools: true                        # 开启工具实例自动装配
  tool-instance:
    instance-id: order-service-01    # 重连必须沿用同一个
    endpoint: http://10.0.0.5:8081/domainplane/invoke   # 服务端可达的回流地址
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

启动后自动扫描注册并心跳；服务端按域把工具裁剪给模型，调用时反向 HTTP 回调本实例执行。破坏性操作配 `effect = Tool.Effect.DESTRUCTIVE, approval = Tool.Approval.ALWAYS` 走人工审批。

---

## 配置

### 最常改的几项

配置在 `domainplane-server/src/main/resources/application.yaml`，也可用环境变量覆盖。

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `server.port` | `9527`（`DOMAINPLANE_SERVER_PORT`） | HTTP 端口 |
| `domainplane.settings.path` | `/var/lib/domainplane/config`（`DOMAINPLANE_SETTINGS_PATH`） | 落盘配置目录（8 份 JSON） |
| `domainplane.storage.path` | `/var/lib/domainplane/blobs`（`DOMAINPLANE_STORAGE_PATH`） | Blob 根目录：切片原文与媒体附件 |
| `domainplane.export.path` | `/var/lib/domainplane/chunks`（`DOMAINPLANE_EXPORT_PATH`） | 切片预览 txt 导出目录 |
| `domainplane.logging.path` / `.level` | `/var/log/domainplane` / `INFO` | 日志落盘目录与级别 |
| `domainplane.redis.*` | 未配置（端口 `6379`） | Redis 连接与连接池；也可只在管控台配（热切换） |
| `domainplane.elasticsearch.*` | 未配置（端口 `9200`，`http`） | ES 连接；为空则启动期跳过灌库 |
| `domainplane.memory.*` | `assembly-budget: 24000`、`assembly-window-turns: 8`、`checkpoint-ttl: 24h` | 装配预算、近况窗口、检查点 TTL |
| `domainplane.agent.*` | 核心 8 / 最大 32 / 队列 200 | 编排线程池 |
| `domainplane.timeout.*` | 工具 30s / 视觉 60s / 能力 120s / 等模型 10min（分片 200ms） | 全平台"等多久算太长"的唯一口径 |
| `domainplane.retrieval.*` | 双路并行、`top-n: 15`、注入 8 片 / 3000 字符 | 混合检索与融合参数 |
| `domainplane.single-instance-guard` | `true` | 单实例租约守卫；置 `false` 仅用于人工接管（见[已知边界](#已知边界)） |
| `domainplane.auth.allow-remote-init` | `false` | 首次初始化是否允许非本机发起 |

完整约 114 个叶子键见 `application.yaml` 内注释——每一项都写了"为什么是这个默认值"。

覆盖优先级：命令行 `--key` ＞ `-Dkey` ＞ 环境变量 ＞ `application.yaml` 默认。

### 落盘文件（`settings.path` 下共 8 份）

| 文件 | 内容 |
| --- | --- |
| `accounts.json` | 管理员账号（BCrypt 哈希；首启由 `accounts-seed.json` 落盘） |
| `infra-settings.json` | ES / Redis 连接（支持热切换） |
| `provider-connections.json` | 服务商连接库（地址 + 密钥） |
| `models.json` | 模型档案与域绑定 |
| `llm-settings.json` | 模型兜底设置 |
| `domains.json` | 域声明与可调用性 |
| `prompts.json` | 域提示词 |
| `mcp-connections.json` | MCP 连接与域绑定 |

### 平台默认路径

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
| `domainplane.username` / `.password` | `domainplane` / `domainplane` | 服务端账号 |
| `domainplane.tools` | `false` | 是否把本进程的 `@Tool` 注册给服务端 |
| `domainplane.tool-instance.instance-id` | — | 工具实例标识（重连必须沿用同一个） |
| `domainplane.tool-instance.endpoint` | 按本进程端口推导 | 工具调用回流地址，跨机 / 有网关时必须显式填写 |

---

## 项目结构

```
DomainPlane/
├── docs/                          # 当前文档，入口 docs/README.md
├── docs-archive/                  # 旧的单篇长文档，仅作历史参考（已与代码脱节）
├── domainplane-api/               # 对外契约：AgentService / AgentEvent / 四条工具注解 / ErrorCode（42 类）
├── domainplane-common/            # 通用底座：异常体系、输入清洗、CJK 度量、原子落盘（16 类）
├── domainplane-domain/            # 纯领域算法与 SPI：记忆双约束、检索编排、融合（32 类）
├── domainplane-infrastructure/    # 对外部世界的实现：ES / Qdrant、Redis、8 种格式摄取（60 类）
├── domainplane-runtime/           # 运行时内核：编排、工具、上下文装配、取消（49 类）
├── domainplane-server/            # 服务端外壳：装配、管控台、鉴权、模型层、MCP（156 类）
├── domainplane-sdk-core/          # SDK 连接配置（1 类）
├── domainplane-client-core/       # SDK 底座：WebClient、凭证缓存、错误翻译（8 类）
├── domainplane-chat-client/       # 对话 SDK（4 类）
├── domainplane-kb-client/         # 知识库 SDK（2 类）
└── domainplane-tool-provider/     # 工具实例 SDK（12 类）
```

规模（2026-11-02 实测，`src/main/java` 非空白行）：**主代码 382 类 / 53,316 行，测试 188 类 / 26,426 行**。

依赖方向（只向上，不向下）：

```
api ← common ← domain ← { infrastructure , runtime } ← server
sdk-core → api；client-core → sdk-core；chat-client / kb-client → client-core；tool-provider → sdk-core
```

`infrastructure` 与 `runtime` 是**并列兄弟**，不是上下级：内核只认识 `domain` 里的 SPI，不认识任何适配器实现。这是"换存储后端不动编排内核"的机制来源。三个 SDK 与服务端**只通过 HTTP 报文耦合**。

---

## 文档导航

| 我想… | 看这份 |
| --- | --- |
| 先把服务跑起来并配到能对话 | [`docs/01-快速上手.md`](docs/01-快速上手.md) |
| 搞清代码分几块、为什么这么切 | [`docs/02-架构总览.md`](docs/02-架构总览.md) |
| 搞清某个模块的机制、取舍与坑 | [`docs/`](docs/README.md)（按模块分篇，一篇一个） |
| 查配置项、错误码 | [`docs/13-配置参考.md`](docs/13-配置参考.md) · [`docs/11-鉴权与错误码.md`](docs/11-鉴权与错误码.md) |
| 部署、备份、排障 | [`docs/14-运维与边界.md`](docs/14-运维与边界.md) |
| 改管控台前端 | [`docs/10-管控台.md`](docs/10-管控台.md) |

---

## 常见问题

**对话报 `90005 DEPENDENCY_NOT_CONFIGURED`**
Redis 没配。去管控台「存储配置」填连接后保存（热切换，不用重启）。

**对话报 `10010 DOMAIN_NOT_CALLABLE`**
你用的域是父域或未标记可调用。可调用单元必须是**叶子域**，在「域空间」页勾选可调用。

**对话报 `91001 MODEL_NO_VISION`**
这轮带了图片，但当前域没绑带视觉能力的模型档案。换模型，或改用文本提问。

**改配置不生效 / 配置写到了系统盘根目录**
`-D` 写在了 `-jar` 之后。JVM 参数必须在 `-jar` 之前，或改用环境变量 `DOMAINPLANE_SETTINGS_PATH`。

**管控台提示 `10008`**
还在用默认口令。默认口令下变更类操作会被拒，先在「账号」页改密码。

**改了 `frontend/src` 页面没变化**
管控台前端是 esbuild 构建产物入 git：`cd domainplane-server/frontend && npm run build`。不重新构建，jar 照常打包成功，只在运行时表现为旧页面。

**忘记管理员密码**
唯一出口是删掉 `config/accounts.json` 后重启，回落到种子账号 `domainplane` / `domainplane`。

**ES 用 7.x 行不行**
不行。缺 `dense_vector index:true`，索引建不出来。8.x 大概率可用但未实测。

---

## 已知边界

| 项 | 现状 |
| --- | --- |
| 多实例部署 | **不支持**。三个运行态注册表在 JVM 堆里；`single-instance-guard: false` 只是关掉守卫，不是支持多实例 |
| 入口限流 / 并发会话上限 | 错误码已定义（`RATE_LIMITED` / `CONCURRENT_LIMIT`），代码不发出 |
| 归档 / 记忆自动清理 | 无（只增不减是有意设计），只有删域会级联 |
| 滚动摘要 TTL | 无（有损数据按档案对待） |
| MCP `LEGACY_SSE` | 已实现，未对真实旧 server 验证 |
| PDF 深度解析 / 图片 OCR | 不做字号推断标题、表格还原、双栏、扫描件；不做 OCR |
| 官方 Maven 仓库 | 无，接入方必须自建 |

完整清单与设计理由见 [`docs/14-运维与边界.md`](docs/14-运维与边界.md)。

---

## 贡献

```bash
mvn clean package   # 全量构建
mvn test            # 全量测试（测试 / 主代码行数比 ≈48%，请保持不缩水）
```

提 PR 前自查：

- [ ] `mvn clean package` 通过，相关模块测试通过
- [ ] 新增 / 改动的行为有测试覆盖
- [ ] 涉及对外契约或错误码时，`docs/11-鉴权与错误码.md` 对应章节已同步
- [ ] 没有引入"静默降级"：能力缺失、越权、依赖未配置，一律显式报错

四条约定：

1. **契约唯一**：跨进程类型（请求 / 事件 / 工具元数据 / 错误码）只放 `domainplane-api`，禁止在 SDK 或服务端各拷一份。`AgentService` 有 runtime 与 chat-client 两个方向相反的实现，加方法时两边都要改。
2. **错误码集中登记**：新码加到 `api.code.ErrorCode`，按段位落位（10xxx 鉴权 / 20xxx 容量 / 30xxx 会话 / 40xxx 入参 / 50xxx 系统 / 60xxx 知识库 / 70xxx 记忆 / 80xxx 工具 / 90xxx 外部依赖 / 91xxx 多模态）；`userMessage` 与 `message` 双文案，对外只出 `userMessage`。
3. **文档一篇一模块**：`docs/` 下每篇说清一个模块的机制、取舍与坑，跨模块只指路不复述；旧的长文档在 `docs-archive/`，不再更新。
4. **提交**：基于 `main` 开分支，一个分支一件事；提交信息说明"改了什么 + 为什么"。

---

## 许可

[Apache License 2.0](LICENSE)
