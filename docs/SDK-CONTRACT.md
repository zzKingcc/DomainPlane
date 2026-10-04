# SDK 契约表（注解与门面的完整参数）

> 用途：`@Tool` 注解体系与 `DomainPlaneAgent` 门面的完整契约。**§1~§6 即当前实现的全部形态**。
> 来源：`DESIGN-1.0.md` §4（工具体系）与 §5（消费侧 SDK）。
> 记法：**必填**列里标「是」的只有一个字段 —— 工具声明只有一个必填项。

---

## 1 `@Tool` —— 工具注册（写在方法上）

| 字段 | 类型 | 默认 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `desc` | `String` | — | **是** | 给模型的用途说明。写法建议：写清「何时调用 / 何时不要调用」，比参数描述更重要 |
| `value` | `String` | `""` | 否 | 工具名。留空取方法名；**全局唯一**，重名注册直接失败 |
| `domains` | `String[]` | `{}` | 否 | 可用域（**授权边界**）。每项都是**从根域出发的完整路径**（`default.sales.order`），无通配写法。判定按**累加**：命中该域或它的任一祖先即见，故挂在父域上其所有后代域都能用。留空＝挂根域 `default`＝全树可见；想收紧就显式写完整路径。可继承类级 `@ToolDomains` |
| `effect` | `Effect` | `READ` | 否 | `READ` / `WRITE` / `DESTRUCTIVE`。写与破坏性操作建议配 `approval` |
| `approval` | `Approval` | `NONE` | 否 | `NONE` / `ALWAYS`。当前只有这两种真正生效 |
| `approvalReason` | `String` | `""` | 否 | 展示给审批人的原因。`approval ≠ NONE` 时建议填写 |

**不在注解上的字段**

| 字段 | 当前位置 | 说明 |
| --- | --- | --- |
| `category` | `ToolSpec` / 上报 manifest，默认 `"default"` | 只用于管理页分组，不影响运行 |
| `version` | `ToolSpec` / 上报 manifest，默认 `"1.0.0"` | 仅登记展示，不参与路由 |
| `idempotent` | `ToolSpec`，默认 `true` | 仅登记展示 |
| `toModel` | `ToolSpec`，默认 `true` | 仅登记展示 |
| `condition` / `approverRoles` / `timeoutSeconds` | 工具描述符的审批块（`ToolDescriptor.Approval`） | **当前不生效**，只登记；条件式审批（按金额阈值等条件决定是否审批）没有实现 |

---

## 2 `@ToolParam` —— 参数说明（写在形参前 **或** 参数 DTO 的字段上）

| 字段 | 类型 | 默认 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `value` | `String` | `""` | 否（**强烈建议**） | 参数说明。不写能跑，但复杂参数（订单号、金额、日期）模型只能猜 |
| `name` | `String` | `""` | 否 | 参数名。留空取形参名；标在字段上时取字段名 |
| `required` | `boolean` | `true` | 否 | 是否必填。`Optional<T>` 自动视为非必填 |

**示例值 / 枚举白名单 / 脱敏参数名不写在 `@ToolParam` 上**，统一放方法级 `@ToolAdvanced`
（`example` / `allowValues` / `sensitive`，见 §4），一律按参数名对应。

> **两个载体、一个优先级**：形参注解 **>** 字段注解（就近覆盖）。
> 1~2 个简单参数用形参；3+ 参数、被多个工具复用、或有嵌套结构 → 用 `record` DTO 的字段注解（只写一次）。
> 两个载体产出**完全一致**的 schema：服务端本地 Bean 与工具实例侧<b>共用同一份 `ParamSchemaResolver` 产出参数树</b>，工具实例侧再经 `toWireSchema` 把同一棵树渲染成上报 JSON；DTO 递归展开成 `object`，数组元素（`List<DTO>`）也递归展开成 `items`，两侧对同一段工具代码给出完全一致的 schema。

---

## 3 `@ToolDomains` —— 类级默认域（写在类上）

| 字段 | 类型 | 默认 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `value` | `String[]` | `{}` | 否 | 该类里所有 `@Tool` 方法的默认可用域。方法级 `domains` 优先 |

---

## 4 `@ToolAdvanced` —— 高级可选（方法级）

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `example` | `String[]` | `{}` | 参数示例，**按参数名对应**（`{"orderNo=FR2024001"}` 形式），不做位置对齐 |
| `allowValues` | `String[]` | `{}` | 枚举白名单，按 `name=...` 形式给出 |
| `sensitive` | `String[]` | `{}` | 需要脱敏的参数名清单（日志 / 事件 / 审批 payload） |

> 这个注解存在的意义：**绝大多数工具不需要它**。放这里的字段都是"偶尔要写、但绝不能默认出现在每个工具上"的。

---

## 5 SDK 门面

### 5.1 入口

| 元素 | 签名 | 说明 |
| --- | --- | --- |
| `DomainPlaneAgentFactory` | `DomainPlaneAgent forDomain(String domainId)` | 唯一的域绑定入口。`domainId` 为 `null`/空白 → 根域 `default`；返回的实例**可缓存复用**（线程安全） |

> 实现：坐标 `domainplane-chat-client`；`DomainPlaneAgentFactory.forDomain(domainId)` → `DomainPlaneAgent`
> （`DefaultDomainPlaneAgentFactory` / `DefaultDomainPlaneAgent`，按归一化域名缓存；`ChatAutoConfiguration` 暴露 `DomainPlaneAgentFactory` Bean）。

### 5.2 `DomainPlaneAgent` 方法

| 方法 | 参数 | 返回 | 说明 | 使用占比预估 |
| --- | --- | --- | --- | --- |
| `ask` | `String sessionId, String question` | `String` | 同步取最终答案。遇审批中断抛 `ApprovalRequiredException` | **~70%** |
| `ask` | `String sessionId, String question, String tenantId, String userId` | `String` | 同上，并声明归属（多租户计量/审计） | — |
| `stream` | `String sessionId, String question` | `Flux<String>` | 逐字输出（只含 TOKEN 内容） | ~25% |
| `stream` | `String sessionId, String question, String tenantId, String userId` | `Flux<String>` | 同上，带归属 | — |
| `events` | `String sessionId, String question` | `Flux<AgentEvent>` | 完整事件（工具调用、审批、错误、耗时） | ~5% |
| `resume` | `String sessionId, boolean approved` | `Flux<AgentEvent>` | 审批恢复。**域须与中断时一致**，否则按新域找不到断点（`30001`） | — |
| `stop` | `String sessionId` | `boolean` | 请求停止（幂等） | — |
| `domainId` | — | `String` | 返回本实例绑定的域，**永不为空** | — |

**约定**：`sessionId` 由调用方生成并保持稳定（同一会话复用同一个）；它是记忆与检查点的唯一键。

### 5.3 `ApprovalRequiredException`（`ask` 与 `stream` 都会抛）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sessionId` | `String` | 哪个会话被挂起 |
| `domainId` | `String` | 挂起时所处的域（`resume` 必须带同一个） |
| `tools` | `List<ToolCall>` | 待审批的工具调用清单：`name` / `arguments` / `requiresApproval` |
| `traceId` | `String` | 排障用 |

> 宿主拿到它就能直接弹确认框；用户点完 → `resume(sessionId, true/false)`。

### 5.4 `AgentEvent`（只有 `events` / `resume` 用得到）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | `AgentEventType` | `TOKEN` / `TOOL_CALL` / `TOOL_RESULT` / `INTERRUPT` / `STOPPED` / `ERROR` / `DONE` |
| `sessionId` | `String` | 会话 |
| `content` | `String` | 文本内容（`TOKEN` / 错误文案） |
| `payload` | `String` | 结构化载荷（JSON 字符串；`INTERRUPT` 时是待审批工具清单） |
| `code` / `codeName` | `Integer` / `String` | 仅 `ERROR`，前端应以 `codeName` 分支而非数字 |
| `traceId` | `String` | 仅 `ERROR` |
| `timestamp` | `long` | 事件生成时间 |

---

## 6 配置项（SDK 运行参数）

三个坐标**读同一份配置**：按需引入的只是能力，配置不随之分叉。

| 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `domainplane.server` | `String`（URL） | `http://localhost:9527` | 服务端地址，单个 URL（协议、主机、端口写在一起）；对话 / 知识库 / 工具实例共用 |
| `domainplane.username` / `password` | `String` | `domainplane` | 接入账号 |
| `domainplane.tools` | `boolean` | `false` | 是否把本进程的 `@Tool` 方法注册给服务端 |
| `domainplane.client.*` | — | — | 调用行为（`health-check-timeout` / `connect-timeout` / `read-timeout`），两个 SDK 共用 |

**不要求填的（有默认值，只在需要时改）**：健康检查/连接/读取超时、实例 id（自动生成）、端点（自动推导）、心跳周期、退避上限、请求超时。

### 6.1 会话标识（`sessionId`）的语义

- **域内唯一**：服务端的记忆、图检查点、停止与事件流都按 `(域, sessionId)` 隔离，
  因此同一个 `sessionId` 可以在不同域各用一份，互不影响（不同域的记忆不共存）。
- **不得包含 `|`**：它是内部状态键的分隔符，含它的 `sessionId` 会被入口拒绝（`40000`）。
- **同一域内必须稳定**：换 `sessionId` 等于换一段会话（记忆与断点都断）。
- `resume` 与 `stop` 必须携带与 `chat` 相同的域：断点存在 `(域, sessionId)` 下，
  用别的域 `resume` 找不到断点，用别的域 `stop` 也停不到。

---
