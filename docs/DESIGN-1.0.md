# DomainPlane 设计总览（历史版本）

> ⚠️ **历史文档，已被取代**：本文是 DomainPlane 设计总览的**历史版本**（1.0 时代），已被 [`DESIGN.md`](DESIGN.md) 取代 —— 后者的 §1~§18 覆盖本文全部主题，仅作决策留档。当前实现以 `DESIGN.md` 与源码为准。
>
> 对应关系：本文 §1 → `DESIGN.md` §1/§2；§2 → `DESIGN.md` §4；§3 → `DESIGN.md` §3；§4 → `DESIGN.md` §5；§5 → `DESIGN.md` §5、`API.md` 与 `SDK-USAGE.md`。

---

## 1 定位与结构

DomainPlane 是 **Java 生态的 AI Agent 运行时中间件**：服务端承载全部重逻辑与治理，业务应用通过薄 SDK 调用，工具可以住在业务进程里。

```
                    ┌──────────────────────────────────────────┐
   业务应用          │  domainplane-server（服务端，可独立部署）      │
   ┌────────────┐   │                                          │
   │ @Tool 方法 │──┼──▶ 工具注册 / 心跳 / 副本 ◀── 工具实例上报   │
   └────────────┘   │                                          │
   ┌────────────┐   │  ┌────────────────────────────────────┐  │
   │DomainPlaneAgent│──┼─▶│ 域（Domain）：工具集 + 提示词 + 知识 │  │
   └────────────┘   │  └───────────────┬────────────────────┘  │
    (forDomain)     │                  ▼                        │
                    │   图编排 agent → 条件边 → tools → agent    │
                    │        │                    │             │
                    │        ▼                    ▼             │
                    │   模型（LLM）          知识库检索（ES）      │
                    │   记忆 / 检查点（Redis）                    │
                    └──────────────────────────────────────────┘
```

模块坐标（三个 SDK **按需引入、互不依赖**；同时引多个也不冲突，公共底座只装配一份）：

| 坐标 | 作用 |
| --- | --- |
| `domainplane-chat-client` | **对话 SDK**：`DomainPlaneAgent` 门面（`forDomain` → `ask` / `stream` / `events` / `resume` / `stop`） |
| `domainplane-kb-client` | **知识库 SDK**：`KnowledgeBaseClient`（文档上传 / 列表 / 删除，按域落到对应索引） |
| `domainplane-tool-provider` | **工具 SDK**：把本进程的 `@Tool` 方法注册给服务端（`domainplane.tools=true` 才装配） |
| `domainplane-client-core` | 客户端底座（传递）：WebClient / 凭证换取 / 错误翻译 / 启动期探测，被对话与知识库 SDK 共用 |
| `domainplane-sdk-core` | 契约 + 公共异常 + 连接配置（传递，接入方不直接引） |
| `domainplane-server` | 服务端，承载编排、注册表、管控台 |
| `domainplane-api` | 契约层（注解、事件、错误码）——只依赖 `jackson-annotations`，随 `sdk-core` 传递 |
| 其余（`common` / `domain` / `infrastructure` / `runtime`） | 内部实现，不单独交付 |

三个 SDK 读**同一份** `domainplane.*` 配置（服务端只有一个账号、工具注册表也只有一份），
且都只用 `spring-web` 的注解模型与出站 `WebClient`：**Web 容器始终归宿主**，引 SDK 不会改变宿主的 Web 栈判定。

---

## 2 核心概念：域（Domain）

> **域 = 一个可独立发布、可灰度、可计量、可授权的 Agent 能力单元。**
> 它是业务的 AI 切片，而不是技术上的工具集合。

**为什么它是核心抽象**：一次对话"用什么工具、哪个模型、哪份提示词与知识、什么策略"——这些差异全部由域承载；工具侧只回答"我允许被哪些域使用"。

### 2.1 双向声明、交集生效

| 关系 | 谁定 | 说明 |
| --- | --- | --- |
| 工具 → 域 | **工具侧**（授权） | 工具作者最清楚适用范围与副作用 |
| 域 → 工具 | 域侧（选择） | 在授权范围内决定用不用、是否加审批 |
| 域 → 模型 / 提示词 / 知识 / 记忆 | 域侧 | 这些信息工具侧不持有 |

**生效规则**：

| # | 规则 |
| --- | --- |
| 1 | 工具可用域每项都是**从根域出发的完整路径**；留空 → 挂在根域 `default`；**无通配写法** |
| 2 | 判定按**累加**：工具声明命中该域或它的任一祖先即见。挂父域则所有后代域可用 |
| 3 | 越界即失败：调用未授权工具 → `10001` 域外拒绝，不静默剔除 |

```java
@ToolDomains("default.order")                // 类级默认域（方法级 domains 可覆盖）
public class OrderTools {

    @Tool(desc = "按订单号退款。仅在用户明确要求退款时调用",
          effect = Tool.Effect.WRITE,
          approval = Tool.Approval.ALWAYS,
          approvalReason = "退款需人工确认")
    public String refundOrder(@ToolParam("订单号，如 FR2024001") String orderNo,
                              @ToolParam("退款金额，单位：元") BigDecimal amount) { ... }
}
```

### 2.2 根域 `default`

启动期幂等预置、**不可删除**（它是整棵域树的起点）。留空的工具与未指定域的调用都归一化为它——
所以**不存在"无归属的工具"与"无域的调用"**。归一化这一步不报错；但它本身能否作为入口，
仍受下面的叶子规则约束（有子域时返回 `10010`）。

它同时是**基层域**：工具可见性、知识库、提示词、模型绑定四个维度都沿域链累加/回落，
因此写在根域上的内容对整棵树生效，后代域无需重复配置。

### 2.3 两种角色：可调用单元与装配节点

域既是"能力包"，也可能只是"给后代配能力的地方"。这两种用途在**能否作为入口**上必须分开：

| 角色 | 含义 | 谁能当 |
| --- | --- | --- |
| **可调用单元** | 能作为入口被调用，是"这一个 AI 切片"的入口 | **叶子域**（没有子域），且显式标记为可调用 |
| **装配节点** | 只把工具 / 提示词 / 模型绑定 / 知识传给后代，不能直接当入口 | 有子域的域（一律）；叶子域中被显式标为装配节点的 |

硬约束是**叶子规则**：**只有叶子域可以作为可调用单元**，一个域一旦有了子域就降级为装配节点。
根域没有豁免——整棵树只有根域时它是叶子、可调用；一旦往下建了子域，它也只是装配节点。
需要"某一层整体"的入口时，另建一个没有子域的域。

判定在**读取时派生**（`显式标记 ∧ 无子域`），不在各写入路径上维护标记：标记来源有管控台声明、
工具声明派生与落盘历史值三处，任何一处漏改都会破掉规则；派生只有一个判据实现，绕不过去。

- 入口判据只认注册表里的可调用性：域不存在报 `10004`，存在但不是可调用单元报 `10010`。
- 域的"全集"（注册表 ∪ 工具声明派生）只用于展示与排障；客户端清单 `/api/agent/domains` 只返回可调用域。
- 存量落盘里"有子域却标着可调用"的历史值，由启动期一次性归一写成生效值（装配节点）。

---

## 3 一次对话的链路

```
业务代码
  │  factory.forDomain("customer-service").ask(sessionId, question, tenantId, userId)
  ▼
DomainPlaneAgent ──HTTP SSE──▶ ServerAgentController ──▶ AgentOrchestrationService
                                                            │
        ┌───────────────────────────────────────────────────┘
        ▼
   ┌─────────┐   条件边    ┌─────────┐
   │  agent  │───────────▶│  tools  │──┐   （tools 回边是固定边，无条件）
   │ (LLM)   │◀───────────└─────────┘  │
   └────┬────┘   exit / auto / review  │
        │                               │
        ▼                               ▼
   输出 TOKEN 事件              工具执行（本地 Bean 或远端工具实例）
                                      │
                                      ├─ 需要审批 → INTERRUPT 事件 → 挂起等待 resume
                                      └─ 域外工具 → 拒绝（10001），文本回灌不结束流
```

**执行单元内冻结**：一次 `chat` 及其全部 `resume` 内，域与提示词冻结；`resume` 必须用中断时的域。
**主循环形状不变**：`agent → 条件边(exit|auto|review) → tools → agent`。

---

## 4 工具体系

### 4.1 声明（唯一入口：`@Tool` 全家桶）

| 注解 | 字段 | 默认 | 何时写 |
| --- | --- | --- | --- |
| `@Tool` | `desc` | **必填** | 总是（模型靠它决定何时调用） |
| | `value` | 方法名 | 想换工具名时 |
| | `domains` | 继承类级 → `default` | 与类级不同时 |
| | `effect` | `READ` | 写 / 破坏性操作 |
| | `approval` / `approvalReason` | `NONE` / `""` | 需人工确认时 |
| `@ToolParam` | `value` | **必填**（参数说明） | 总是 |
| | `required` | `true` | 可选参数时 |
| `@ToolDomains` | `value` | `{}` → `default` | 同类工具同属一域时写一次 |
| `@ToolAdvanced` | `example` / `allowValues` / `sensitive` | 空 | 需要示例值、枚举白名单、脱敏时（按「参数名=值」） |

### 4.2 参数 schema：两端同一棵树

`ParamSchemaResolver`（在 `domainplane-api`）是**唯一真相**：方法签名 + 注解 → `ToolDescriptor.Param` 树。
服务端本地扫描、远端工具实例上报、上报 JSON 渲染**共用这一棵树**，所以"同一段工具代码搬到服务端进程"参数结构不变。

| 类型 | 展开 |
| --- | --- |
| record / 普通类 | `object` + 递归 `properties` |
| `List<T>` / 数组 | `array` + `items`（元素结构） |
| 枚举 | `string` + `enum` |
| `Optional<T>` | 非必填 |

防护：嵌套深度上限 `MAX_DEPTH`（5）+ 循环引用检测。

```java
// DTO 载体：字段上的 @ToolParam 与形参上的等价，形参优先
public record OrderQuery(@ToolParam("订单号，如 FR2024001") String orderNo,
                         @ToolParam("是否返回明细") Boolean detail) {}

@Tool(desc = "按条件查询订单。用户追问发货/物流时调用")
public OrderVO query(OrderQuery args) { ... }
```

### 4.3 治理

| 能力 | 说明 |
| --- | --- |
| 域可见性 | 交集生效，域外调用拒绝（`10001`）走文本回灌，不结束流 |
| 审批中断 | `approval = ALWAYS` → 产出 `INTERRUPT` 事件挂起；`ask`/`stream` 抛 `ApprovalRequiredException`（带待审批工具清单） |
| 敏感脱敏 | `@ToolAdvanced(sensitive = {"phone"})` → 工具调用事件与审批 payload 中的值被掩码（执行仍用原值） |
| 心跳与副本 | 工具实例按心跳保活，服务端判死摘除；同名工具多副本负载均衡 |
| 启动自检 | 提示词里点名的工具必须在对应域可见，否则启动期 WARN（不阻断） |

---

## 5 消费侧 SDK

### 5.1 唯一入口：`DomainPlaneAgent`

```java
DomainPlaneAgent agent = factory.forDomain("customer-service"); // 绑定一次，可复用（线程安全）

String answer       = agent.ask(sessionId, question, tenantId, userId);    // ① 只要答案
Flux<String> tokens = agent.stream(sessionId, question, tenantId, userId); // ② 逐字输出
Flux<AgentEvent> ev = agent.events(sessionId, question, tenantId, userId); // ③ 工具/中断细节

agent.resume(sessionId, approved).subscribe();   // 审批后恢复
boolean stopped = agent.stop(sessionId);
```

`forDomain(...)` 在**取得实例时**绑定域，门面上的方法签名里没有 profile 参数——写不出"忘记传域"的代码。
`ask` / `stream` 遇审批抛 `ApprovalRequiredException`，遇错误抛携带 `ErrorCode` 的 `DomainPlaneException`。

### 5.2 配置

```yaml
domainplane:
  server: http://localhost:9527   # 服务端地址（含协议与端口）
  username: domainplane              # 接入账号（默认即为 domainplane，常可不写）
  password: domainplane              # 接入密码（同上）
  tools: true                     # 把本进程的 @Tool 注册给服务端（默认 false）
```

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `domainplane.server` | `http://localhost:9527` | 服务端 URL；未写端口时按协议取默认（http 80 / https 443） |
| `domainplane.username` / `domainplane.password` | `domainplane` | 接入账号 |
| `domainplane.tools` | `false` | 启用工具实例（会在本进程起心跳线程并暴露回调端点） |
| `domainplane.client.*` | 见 `DESIGN.md` | 调用超时（高级，几乎不改） |
| `domainplane.tool-instance.*` | 见 `DESIGN.md` | 实例 id / 回调地址 / 心跳（跨机部署才动） |

### 5.3 知识库 SDK：`KnowledgeBaseClient`

引 `domainplane-kb-client` 即自动装配，与对话 SDK 相互独立（地址与账号读同一份 `domainplane.*`）：

```java
KnowledgeBaseClient.UploadResult r = kb.upload(bytes, "员工手册.pdf", false);      // 落到根域 default
kb.upload(bytes, "销售政策.docx", true, "default.sales");                          // 声明归属域
List<KnowledgeBaseClient.DocumentItem> docs = kb.list();
kb.delete(r.docId());                                                             // 删除后同名可再传
```

**这里没有检索接口**：检索与上下文注入是服务端内部行为，模型经检索工具自动取用。
文档归属的域决定**哪些对话能检索到它**——挂在该域即其全部后代域可见，留空 = 挂根域 = 全域可见，无通配写法。
上传是同步的（切片与向量化完成后才返回），文件名需在服务端白名单内。

### 5.4 裸 HTTP

`POST /api/agent/chat`（SSE），请求体为 `AgentRequest`：`sessionId` / `message` / `profile`（即域）/ `tenantId` / `userId`。
`AgentService` 是内核契约，SDK 内部持有其远程实现；消费侧入口只有 `DomainPlaneAgent`。

---

## 6 路线图（历史快照）

| 项 | 内容 | 状态 |
| --- | --- | --- |
| **域 S4 装配接管** | 模型 / 提示词 / 知识从全局迁入域，沿域链累加；全局值降级为 `default` 域装配初值 | 已落地（`DESIGN.md` §4：四个维度共用同一套沿链累加语义） |
| **T4 多 LLM** | 模型档案 + 域绑有序别名列表（设计留档见 [`MULTI-LLM-DESIGN.md`](MULTI-LLM-DESIGN.md)） | 已落地（`DESIGN.md` §9.1） |
| **域 S5 域内选择与覆盖** | 域 `include` / `exclude` + 覆盖审批 / 超时 / 配额 | 未实施 |
| **T3 扩展点** | 检索 / 记忆 / 检查点 SPI（`FusionStrategy` 已可插拔） | 部分可插拔（`DESIGN.md` §8.3） |
| **T8 契约演进** | 错误码接口化、事件加 usage/node、HTTP 版本化、幂等键 | 未实施 |
| **T1 / T2 / T5 / T6 / T7 / T9** | 状态外置、租户数据面、检索深化、编排升级、MCP、可观测 | 未实施 |
| **重写 example** | 接入示例重新编写 | `domainplane-example` 已移除（`DESIGN.md` §2） |
| **skill 系统** | 能力包形态待 1.0 后定（暂缓） | 未实施 |
