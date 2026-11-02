# SDK 兼容性与多版本支持方案

> 本文说明 DomainPlane 对外接入形态的改造：为什么做、做成什么结构、分几步走。
> 读者：要接入 DomainPlane 的 Java 业务方（选 SDK）、DomainPlane 维护者（排期与施工）。
> 状态：已定方向（A+B 混合），待施工。

## 1. 要解决的问题

DomainPlane 的对外接触面目前只有一条：Java 17+ 的现代线 SDK（`domainplane-chat-client` / `domainplane-kb-client` / `domainplane-tool-provider` + 底座 `client-core`）。这带来两个具体障碍：

1. **存量 Java 11 业务系统接不进来**。契约模块 `domainplane-api` 大量使用 record（Java 16+），现代线 SDK 的 `Flux` 响应式门面也要求接入方理解 Reactor。工具实例 SDK 的目标用户——"把 `@Tool` 方法注册成 Agent 工具"的后台业务系统——恰恰是老 JDK 最集中的地方。
2. **非 Java 生态没有入口**。MCP/A2A 生态的主流是多语言，Java-only 的接入形态把 Node/Python/Go 用户挡在协议文档缺失之外。

本方案要同时打开这两个口子，且不拖累服务端。

另有一个贯穿全文的分发前提：**项目没有官方发布仓库**（未发布 Maven Central，无公共私服），Java 接入方必须自行构建、`mvn install` 到自己的本地仓库或企业私服后才能引用。兼容线设计（第 6 节）与落地步骤（第 8 节）都要在这个前提下成立。

## 2. 决策与依据

### 2.1 边界：兼容压力只在 SDK 侧

服务端是自部署组件，Java 21 + Spring Boot 3.5.x 维护线保持不变（21 为 LTS，支持至 2028）。**服务端不为接入方做任何版本妥协**——这是整套方案的前提，避免兼容需求反向侵蚀内核。

### 2.2 评估过并排除的路线

| 路线 | 排除理由 |
| --- | --- |
| 单 artifact 降基线到 Java 8/11 | `domainplane-api` 的 record 契约被服务端直接消费，降级等于把服务端一起拖回传统 Java；且违反"契约唯一、置于依赖图最底层"的项目约定 |
| Multi-Release JAR | 只解决字节码 minor 差异，不解决源码特性差异（record/var）；MR-JAR 运行时识别本身要求 JDK 9+，对 Java 8 用户无意义 |
| Java 8 兼容线 SDK | Oracle 公开更新 2022 年已断供，仍在 Java 8 的接入方自身背着未修 CVE；官方 SDK 支持它等于替一个断供运行时背书 |
| 只流 API 不做 SDK | 每个 Java 11 用户各自手写 HTTP + SSE 客户端（约 2–3 天/人），55 个错误码与 8 种事件的语义靠用户自己领悟，排障成本最终回到维护者 |

### 2.3 采用 A+B：官方兼容 SDK + Wire 契约流出

两条产品线各司其职：

| 产品线 | 形态 | 服务对象 |
| --- | --- | --- |
| 现代线（保持现状） | Java 17+，record + Reactor `Flux` | 新系统、Spring Boot 3 接入方 |
| 兼容线（新建） | Java 11 基线，阻塞式调用 + 迭代式事件流 | 存量 Java 11 业务系统 |
| Wire 契约（新建） | OpenAPI 3.1 + SSE 事件 schema + 错误码字典 | 非 Java 语言用户、Java 8 用户、任何想自建的人 |

依据：Java 11 是"官方可承诺"与"成本"的平衡点（LTS，Azul/Red Hat 等发行版维护至 2030 前后，接入方存量最大）；Reactor 3.8.x 至今仍 Java 8+ 兼容，LangChain4j 最低 JDK 17 但只服务端使用，均不构成 SDK 障碍。

## 3. 方案结构

```
domainplane-api（Java 21，服务端 + 现代线共用，不动）
   │  镜像（人工同步 + 契约测试看守）
   ▼
domainplane-wire（新模块，JDK 无关）
   OpenAPI 3.1 ｜ SSE 事件 schema ｜ 错误码字典 JSON
   │                              │
   │ 生成                          │ 生成
   ▼                              ▼
现代线 SDK（Java 17+）        兼容线 SDK（Java 11+）
```

三个新构件：

1. **`domainplane-wire`**：不含可执行 Java 代码（或仅含 schema 校验工具），只放机器可读契约。它是 api 的镜像而非替代，"契约唯一"约定不破——api 仍是 Java 侧真相源，wire 是它的跨语言投影。
2. **`domainplane-client-compat`**（一期合并为一个 artifact，二期按反馈拆分）：Java 11 基线的三个门面兼容实现 + 阻塞传输层 + 从 wire schema 生成的 DTO。
3. **契约测试**：同一份报文样本，现代线、兼容线、wire schema 三方反序列化/序列化断言一致，漂移即 CI 红。

## 4. 兼容线 SDK 设计

### 4.1 约束与决策

**难点**：Java 11 没有 record（不可变 DTO）、没有 var（可用性）、没有 sealed/pattern matching；接入方普遍不接受拉入 Reactor。

**决策**：

- DTO 全部从 wire schema 用 jsonschema2pojo 生成传统类（getter + builder），不手写、不共用 api——生成规则保证与 schema 严格一致，人工只维护 schema 一份。
- 传输层用 `java.net.http.HttpClient`（JDK 11 正式 API），SSE 用 `BufferedReader` 逐行解析 `text/event-stream`（`data:` 行 + 空行分帧，约 100 行代码）。
- 门面 API 镜像现代线的方法语义，但签名降维：

| 现代线（17+） | 兼容线（11+） |
| --- | --- |
| `String ask(sessionId, msg)` | `String ask(sessionId, msg)`（同名同义，本来就是阻塞语义） |
| `Flux<String> stream(...)` | `void stream(sessionId, msg, TokenHandler)`（回调） |
| `Flux<AgentEvent> events(...)` | `Iterator<AgentEvent> events(...)` 或 `EventStream` 迭代器 |
| `resume` / `stop` | 同名同义 |

### 4.2 tool-provider 兼容版（最高优先级）

工具实例 SDK 的宿主侧三件——注解扫描、心跳注册、反向调用端点——在 Java 11 上零障碍：

- 注解扫描：反射 + `@Tool` 注解（兼容版在 wire 层重新声明注解，不依赖 api 模块）。
- 心跳与注册：`HttpClient` 定时任务。
- 反向调用端点：JDK 内置 `com.sun.net.httpserver.HttpServer`，**不引入任何第三方 HTTP 容器**，接入方零额外依赖。

### 4.3 代价（如实登记）

- **双实现**：每个能力要写两份（现代线 + 兼容线），之后每加功能成本 ×1.6。
- **契约测试是三线的一致性的唯一保障**，没有它双线漂移只是时间问题。
- 兼容线功能是现代线的**子集**：只承诺 ask/stream/events/resume/stop + 工具注册 + 知识库上传/列表/删除。管理端点（85 个管控接口）不进兼容线，由 wire 的 OpenAPI 覆盖。

## 5. Wire 契约内容

| 件 | 内容 |
| --- | --- |
| OpenAPI 3.1 | agent 端点（chat/stream/resume/stop）+ 全部管理端点，请求/响应/错误码引用 |
| SSE 事件 schema | 8 种事件（TOKEN / THINKING / TOOL_CALL / TOOL_RESULT / INTERRUPT / STOPPED / ERROR / DONE）的 JSON 结构与字段约束 |
| 错误码字典 | 从 `ErrorCode.java` 提取 55 个码为 JSON（code / userMessage / message / retryable / 段位），各线共用同一份 |
| 多语言示例 | curl + Node + Python 最小可跑样例（ask / stream / 工具注册） |

## 6. 版本、发布与分发

- 兼容线 artifact 独立语义版本（如 `domainplane-client-compat`），**不跟服务端同版本号**；README 明示支持矩阵（兼容线 SDK v1.x ↔ 服务端版本区间）。
- **未知事件降级是兼容线的契约承诺**：遇到未识别的 event type，记录 traceId 后跳过，不抛错——服务端新增事件类型不会炸老客户端。现代线保持严格反序列化，两者差异是有意设计，文档需明示。
- 新错误码同样处理：未知 code 落入 `UNKNOWN` 桶并带原始 code，不抛未知枚举异常。

### 6.1 分发与安装（无官方仓库）

现状：项目未发布 Maven Central，也没有公共私服。所有 Java 构件（现代线 SDK、兼容线 SDK、服务端自身）都要由接入方**自行安装到自己的仓库**后才能使用。分三种场景：

**场景一：接入方本地试用**

```bash
git clone <fork 或内部镜像地址> && cd DomainPlane
mvn -pl domainplane-chat-client -am install   # 按需装目标 SDK，-am 带上依赖链
```

然后在接入方 pom 里按 `com.zzkingcc:domainplane-chat-client:<源码内版本>` 引用。注意安装的是**源码内版本号**（当前 `v1.0-beta.2`），接入方升级 = 重新拉源码重新 install。

**场景二：企业内私服分发**（推荐的真实使用形态）

接入方通常不是开发者各自 clone，而是由团队把构件 deploy 到 Nexus/Artifactory：

```bash
mvn -pl domainplane-chat-client -am deploy \
  -DaltDeploymentRepository=internal::default::http://nexus.internal/repository/maven-releases
```

私服场景必须自行管理三件事：版本命名规范（建议加内部后缀，如 `v1.0-beta.2-internal-1`，避免与后续源码版本号混淆）、兼容矩阵台账（哪个 SDK 版本配哪个服务端版本）、源码出处（哪个 commit/tag 构建的）。

**场景三：兼容线的特殊约束**

兼容线 SDK 产物 baseline 是 Java 11，意味着：**接入方至少需要一次 JDK 11+ 的构建**（install/deploy 在 JDK 8 构建机上做不了，产物也跑不了）。若接入方运行环境只有 JDK 8——连安装这一关都过不去，这类用户直接走 wire 契约自建（见第 5 节），这也正是"Java 8 不背 SDK"决策的闭环：不是不想，是分发上就不可达。

**Wire 契约的获取不受此限**：schema、错误码字典、示例全部是随源码分发的纯文件，非 Java 用户 clone 即用，无需任何构建步骤。

## 7. CI 矩阵

| 线 | JDK | 内容 |
| --- | --- | --- |
| 现代线 | 17 / 21 | 编译 + 全量单测 |
| 兼容线 | 11 | 编译 + 全量单测 |
| 契约测试 | 任一 | 三线报文一致性断言 |
| wire schema 校验 | 任一 | OpenAPI lint + 错误码字典与 `ErrorCode.java` 对账 |

## 8. 落地步骤

1. **提取 wire 契约**：从 api 现有类型镜像出 OpenAPI + 事件 schema + 错误码字典；建契约测试脚手架。这一步不写任何 SDK 代码，先让"真相源的投影"成立。
2. **tool-provider 兼容线**：注解 wire 化 + `HttpClient` 注册心跳 + `HttpServer` 反向调用端点。目标用户最老 JDK 的系统最先通。
3. **kb-client 兼容线**：上传/列表/删除，阻塞式。
4. **chat-client 兼容线**：ask / stream / events / resume / stop + 未知事件降级。
5. **wire 文档站 + 安装 quickstart**：OpenAPI 渲染 + curl/Node/Python 示例；Java 侧写清无官方仓库前提下的安装路径（本地 `mvn install` 与企业私服 `deploy` 两套命令，现代线与兼容线各一份，兼容线注明 JDK 11+ 构建机要求）。
6. **回填与拆分**：按接入反馈决定 `client-compat` 是否拆为三个独立 artifact；同步更新 README 接入指南与《设计文档》F16 章节（涉及对外契约，按项目约定必须回写）。

## 9. 已知陷阱清单

| 陷阱 | 对策 |
| --- | --- |
| 双线版本漂移（服务端加事件，兼容线炸） | 契约测试 CI 红线 + 未知事件/未知错误码降级策略 |
| wire schema 与 api 手工同步遗漏 | 错误码字典对账进 CI（`ErrorCode.java` ↔ JSON 逐条 diff） |
| Java 11 接入方的传递依赖冲突 | 兼容线只依赖 JDK + Jackson 2.x（Java 8 基线），不引 Spring/Reactor |
| "兼容线 = 二等公民"导致功能永远滞后 | 落地步骤第 6 步明示：新功能必须双线对齐或明确登记为兼容线不做 |
| tool-provider `/invoke` 端点无鉴权（既有问题） | 兼容版文档中明示暴露面由接入方收敛，不新增默认开放面 |
| 无官方仓库，用户 install 的来源与版本不可控 | 安装 quickstart 固定构建命令与 JDK 要求；私服指引含版本命名规范与兼容矩阵台账；README 依赖示例旁必须标注"需先 install" |

## 10. 工作量参考

| 项 | 估算 |
| --- | --- |
| wire 契约 + 契约测试脚手架 | 3–5 天 |
| tool-provider 兼容线 | 3–4 天 |
| kb-client + chat-client 兼容线 | 各 2–3 天 |
| 文档站 + 多语言示例 | 2–3 天 |
| 合计 | 约 2–3 周，之后每功能 ×1.6 维护成本 |
