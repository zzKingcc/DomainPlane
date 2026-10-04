# 多 LLM 接入设计（评审稿）

> ⚠️ **历史文档，请勿据此开发**：本文是「多 LLM 模型网关」的**历史设计评审稿**，已被 [`DESIGN.md`](DESIGN.md) §9 取代（§9.1 模型档案 / §9.2 「模型设置」全局配置 / §9.3 向量维度契约），仅作决策留档。下方正文已剔除评审与决策往返的过程叙述，只保留**仍然成立的技术规则**；凡与当前实现冲突之处，一律以 `DESIGN.md` 与源码为准。
>
> 覆盖范围：模型档案与解析。域级覆盖（审批 / 超时 / 配额）与知识空间绑定不在本文范围。

---

## 0 一句话方案

把"全局一个模型"拆成：

```
域 ──绑定──▶ 模型档案（别名）──解析──▶ 端点 + 客户端实例
```

- **模型档案**：别名 → 端点、Key、模型名、参数、降级链。**改端点不动域**。
- **域绑定档案**：域按**有序别名列表**绑定，首个为当前对话模型，其余留给多 agent / 降级。
- **调用期解析**：`域 → 别名列表 → 档案 → 客户端`，带指纹缓存与降级。

角色（`planner` / `summarizer` / `rerank`）未实现：域直接绑定 chat 档案，别名列表为将来的角色语义留位。

---

## 1 硬约束

新方案必须叠在既有机制之上，不能推翻它：

| 项 | 约束 |
| --- | --- |
| 委托代理 | `openAiChatModel` / `openAiStreamingChatModel` / `openAiEmbeddingModel` 三个 Bean 是 `LlmModelHolder` 的委托代理，热替换为 volatile 原子替换；**Bean 名与注入点不变**（`openAiEmbeddingModel` 的 4 个注入点零改动） |
| 全局配置 | 单份 `config/llm-settings.json`：一组 chat 参数 + 一组 embedding 参数 |
| 内核注入 | 内核在构造期注入一个 `StreamingChatModel`；**解析器缺失时退回它**，行为与"全局一个模型"完全一致 |
| 向量维度 | 维度取值的唯一入口是 `effectiveEmbeddingDimension()` |
| 冻结规则 | 模型在**执行单元开始时解析一次**，单元内不变（与域、提示词同一条冻结规则） |
| 主循环形状 | 只改 `agentNode` 里取模型的那一行；图结构、条件边、tools 节点、中断与 resume 不动 |

---

## 2 模型档案（Model Profile）

### 2.1 结构

```jsonc
// config/models.json
{
  "domainBindings": {                 // 域 → 别名【有序列表】；整体覆盖；空数组＝该域无可调用
    "customer-service": ["smart", "local-qwen"],
    "contract-review": ["smart"]
  },
  "profiles": {                       // 别名 →【非向量】档案（对话 / 生图 / 语音…）
    "smart": {
      "endpoints": ["chat"],          // 端点族：是否对话看是否含 "chat"；空 = 未声明
      "input": ["text"],
      "output": ["text"],
      "baseUrl": "https://api.example.com/v1",
      "apiKey": "******",
      "modelName": "gpt-4o",
      "temperature": 0.3,
      "maxTokens": 4096,
      "capabilities": ["streaming", "tools"],   // 布尔能力声明（见 2.3）
      "fallbacks": ["local-qwen"]               // 降级链：本档案失败后依次尝试同列表其它别名
    },
    "local-qwen": {
      "endpoints": ["chat"],
      "baseUrl": "http://localhost:11434/v1",
      "apiKey": "ollama",
      "modelName": "qwen2.5:7b",
      "temperature": 0.5,
      "maxTokens": 2048,
      "capabilities": ["streaming", "tools"],
      "fallbacks": []
    }
  },
  "embeddingProfiles": {              // 别名 →【纯向量】档案：单独存放，与 profiles 彻底分开
    "emb-bge": {
      "endpoints": ["embedding"],     // 向量：是否向量看是否含 "embedding"
      "baseUrl": "https://api.example.com/v1",
      "apiKey": "******",
      "modelName": "bge-m3",
      "dimensions": 1024              // 元数据不提供维度；留空则由实际模型兜底
    }
  },
  "embeddingAlias": "emb-bge"         // 全局单选的向量档案别名；null = 未配置
}
```

- `profiles` 只含用户自建的**非向量**档案；**纯向量**档案单独放在 `embeddingProfiles` ——
  向量模型是全局唯一单选、不参与域绑定，与"可绑到域按需调用"的对话 / 生图那类是两种东西。
  旧布局把两者混在 `profiles` 里（只靠 `endpoints` 区分），读盘时自动归一并写回文件。
- `embeddingAlias` 是**全局单选**：向量模型只有这一个，全部域共用，不进 `domainBindings`。
- 落盘结构**无 `type` 字段**；删除档案会级联摘掉所有域绑定（见第 7 节）。
- 新建档案**不自动绑定任何域**：绑定只能由管控台显式指定。
- `endpoints` 为空 = **未声明**（**不再默认补成 `chat`**）：类型只能来自提供商元数据或用户声明，
  猜成 chat 会让向量 / 生图模型被当成对话模型 —— 能绑到域、却当不了向量模型，且全程无报错。

### 2.2 端点族与用途的关系

| 档案 `endpoints` 含 | 用途 | 说明 |
| --- | --- | --- |
| `chat` | 对话链路（当前只做 chat） | 一个档案可服务多用途，靠 `endpoints` 声明它支持哪些端点族 |
| `embedding` | 向量（**只被知识空间引用**，见第 6 节；由 `embeddingAlias` **全局单选**、全部域共用） | 单独存放在 `embeddingProfiles`，不进域绑定 |
| `rerank` / `images` / `tts` / `asr` / `video` | 其它端点族（规划中） | 多模态按 `endpoints` 扩展，可多选 |

> 档案**没有 `type` 字段**，`isChat()` = `endpoints.contains("chat")`。

### 2.3 能力声明（`capabilities`）

**必须在档案里显式声明，而不是靠试错**：

| 能力 | 不声明的后果 |
| --- | --- |
| `streaming` | 被用于对话链路时无法逐帧下发 TOKEN |
| `tools` | 模型不调用工具 —— 只在配置里表达，并可在管控台校验 |
| `vision` | 多模态输入（未来）不可用 |

域绑定到"缺 `tools` 的档案"时，管控台应给**告警**（不是拒绝）：换了个便宜模型导致工具静默失效，是这类配置最容易踩的坑。

---

## 3 域绑定模型档案

### 3.1 绑定结构

```jsonc
// config/models.json 的 domainBindings 段：域 → 别名【有序列表】
{
  // 首个为当前对话模型，其余为降级 / 多 agent 备用
  "customer-service": ["smart", "local-qwen"],
  "contract-review": ["smart"]
}
// 空数组 / 缺省＝该域"无可调用"；整体覆盖
```

### 3.2 解析优先级（域 → 别名列表 → 档案）

1. **沿域链回落**：自身没绑就向上找最近一个绑定了模型的祖先域（回落的是**域链**，不是任何内置默认值）；纯逻辑在 `ModelProfileSettings.resolveAlong`，不依赖容器，可单测。
2. `domainBindings[domain]`：命中的别名<b>有序列表</b>按序试，首个 `isUsable()` 且 `isChat()` 的档案即命中。
3. 全链未绑 / 列表全不可用 → **不回落任何默认值**，由 `DefaultModelResolver` 抛 `NotConfiguredException`（`90005`）。

> 未显式绑定模型的域**直接报 `90005`，不静默降级到"猜一个能用的"** —— 那会让问题更隐蔽。管理面返回 `sourceDomain`，标明生效的绑定来自链上哪个域。

---

## 4 解析与缓存

### 4.1 解析链路

```
域
   │ ① 沿域链查绑定表（内存，变更即刷新）
   ▼
别名列表 alias
   │ ② 查档案注册表（ModelProfileRegistry）
   ▼
档案 + 指纹（baseUrl + modelName + 参数 + SHA-256(apiKey)）
   │ ③ 查客户端缓存：指纹未变 → 复用实例
   ▼
OpenAiChatModel / OpenAiStreamingChatModel 实例
```

### 4.2 缓存与失效

| 项 | 规定 |
| --- | --- |
| 缓存键 | **档案指纹**（含 Key 哈希）——Key 或端点一变，指纹变，自然拿到新实例，无需手工清缓存 |
| 旧实例 | 不立即销毁（可能有在途请求）；交给 GC，无长连接需显式关闭 |
| 单例性 | 同一指纹全局共享一个实例（模型客户端本身线程安全） |

### 4.3 降级链

```
主档案调用失败（连接失败 / 5xx / 超时）
   │ 换 fallbacks[0]
   ▼
备档案失败 → fallbacks[1] … 全部失败
   ▼
报 90003（LLM_UNAVAILABLE）或 90000（LLM_TIMEOUT），事件里带上"已尝试的档案清单"
```

- **只在"请求根本没成功"时降级**：已经流出部分 TOKEN 再切断会造成半截回答，不降级（与工具重试同一条原则：传输层失败才换，业务失败不换）。
- 降级链是**档案级**配置，不是域级 —— 换模型供应商是运维动作，不该要求动域。
- `fallbacks` 当前**只存不生效**（可存、可展示）。

---

## 5 运行时接入点

### 5.1 接口

内核与「模型档案」之间的唯一接口放在**内核侧**（不是 `domainplane-api`）—— `api` 没引 langchain4j，把模型类型塞进对外契约会污染 SDK 依赖：

```java
// domainplane-runtime：com.zzkingcc.domainplane.runtime.model
public interface ModelResolver {
    StreamingChatModel streamingChat(String domain);   // domain 为完整路径，空按根域
}
```

### 5.2 内核里的一处改动

`AgentOrchestrationService.agentNode` 的当前形态：

```java
StreamingChatModel model = resolveModel(context.profile());   // resolver 为空则返回构造期注入的字段
model.chat(request, handler);
```

规则：

- resolver 为 `null`（未装配档案体系）→ 用构造期注入的模型，行为与未接入前一致；
- resolver 已装配 → **不再回落**：解析失败如实抛出（域无可调用模型即 `90005`）；
- 模型在执行单元开始时解析一次，单元内冻结。

---

## 6 Embedding 的特殊约束（必须单独讲）

**chat 模型可以按域随便切，embedding 不行。** 原因：

```
embedding 维度 → ES 索引的 dense_vector dims（建索引时定死，改不了）
              → 已写入的向量全部按该维度生成
```

所以：

| 项 | 规定 |
| --- | --- |
| 谁能引用 embedding 档案 | **只有知识空间**，不是"域直接绑 embedding" |
| 一个知识空间的约束 | 一个知识空间 = 一个 embedding 档案 + 一个维度 + 一个索引 |
| 同时生效的 embedding | 同一时刻**只能有一个**：向量模型只有 `llm-settings.json` 里那**一套**，全局共用 |
| 索引粒度 | **一域一索引**（`domainplane_kb_<域路径哈希>`，索引跟着域走） |
| 切换 embedding | 等价于换维度 → **必须重建全部知识库索引**（四态预检 `NEEDS_REBUILD` / `DECLARED_MISMATCH` 等） |
| 当前做到哪 | **注册多个 embedding 档案（可切换），但同一时刻仍只有一个生效** —— 与现状一致，不引入维度冲突 |

**多 embedding 按域并用**的卡点是"维度一致性"，不是索引粒度（一域一索引早已具备）：所有索引都按同一套向量模型建，维度必须一致；要让 A 域用 128 维、B 域用 1024 维，得先把"域 → embedding 档案"这条绑定与"索引与档案成对创建"一起补上。

⚠️ **这一条是本设计里最容易做错的地方**：如果让"域按 chat 的方式绑 embedding"，模型 A 写入 1024 维、模型 B 查询 768 维，检索结果不会报错，只会**悄悄地全错**。

---

## 7 接口设计（管理面）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/admin/model-profiles` | 档案列表（Key 脱敏，含 `endpoints` / `input` / `output` / `capabilities` / `usedByDomains`）+ `domainBindings`（域→别名列表） |
| POST | `/admin/model-profiles` | 创建/更新档案（`alias` 为键；字段为 `endpoints` / `input` / `output` / `baseUrl` / `apiKey` / `modelName` / `temperature` / `maxTokens` / `dimensions` / `capabilities` / `fallbacks`，**无 `type`**） |
| POST | `/admin/model-profiles/inspect` | <b>只读</b>提供商的 `GET /models` 判定端点族 / 模态 / 能力（不落盘、不发试探请求）；读不到的字段留空 |
| POST | `/admin/model-profiles/inspect-saved` | 用档案已存配置重新读取并写回档案（读不到的字段保留原声明；`alias` 走**查询参数**，别名可含 `/`，放进路径段会被 Tomcat 以 400 拒收） |
| POST | `/admin/model-profiles/test` | 测试连接（返回实测可用性 + 模型名；`alias` 同样走查询参数） |
| DELETE | `/admin/model-profiles` | 删除档案并**级联清理**所有域绑定（`alias` 走查询参数）；返回结果带出被摘掉绑定的域清单（这些域立即进入"无可调用"状态，由管控台明确提示） |
| PUT | `/admin/model-bindings/{domain}` | 设置域的可调用别名列表（整体覆盖，顺序即优先级；空＝解绑），返回 `sourceDomain` 标明生效绑定来自链上哪个域 |

---

## 8 观测与成本

| 项 | 做法 |
| --- | --- |
| 事件 | `AgentEvent` 在 `DONE` / `ERROR` 帧带上 `modelAlias` 与 `modelName`（**只加字段，不改语义**） |
| 日志 | 每次解析命中记 DEBUG（域 / 别名 / 指纹前 8 位）；降级与熔断记 WARN |
| 成本 | 每次对话按 `usage`（已在 `TokenUsageRecorder` 统计）挂到"域 + 档案"两个维度，先在日志与指标里体现，不做账单 |

---

## 附：与既有不变式的关系

| 不变式 | 本文如何遵守 |
| --- | --- |
| 三个模型 Bean 名与注入点不变（委托代理） | 完全保留；`openAiEmbeddingModel` 的 4 个注入点零改动 |
| 向量维度唯一入口 `effectiveEmbeddingDimension()` | 保留；多 embedding 只做"可切换"，入口语义不变 |
| 执行单元内冻结 | 模型在**单元开始时解析一次**，单元内不变（与域、提示词同一条冻结规则） |
| 主循环形状不变 | 只改 `agentNode` 里取模型的那一行 |
| 域不可绕过 | 解析的输入就是域：解析器存在时**未绑定的域直接抛 `NotConfiguredException`**（不回落任何默认），因此绝不会"用错模型却不自知" |

---

## 实现位置

| 位置 | 内容 |
| --- | --- |
| `domainplane-runtime` `runtime/model/` | `ModelResolver` 接口；内核可选 resolver + `resolveModel()` |
| `domainplane-server` `model/` | `ModelProfile`（3 维 schema：`endpoints` / `input` / `output` + `capabilities` + `dimensions`；`isChat()`＝`endpoints` 含 `chat`；含 `fingerprint()` / `supportsTools()` / `capabilityHint()`）、`ModelProfileSettings`（落盘结构 + `resolveAlong`）、`ModelProfileStore`（`config/models.json`）、`ModelProfileRegistry`（唯一真相，含后设覆盖与级联清绑定）、`ModelClientFactory`（按指纹缓存，每个别名只留当前指纹）、`DefaultModelResolver`（只认自建档案，未绑定 / 不可用抛 `NotConfiguredException`，无内置默认旁路）、`ModelCatalog`（只读提供商元数据判定类型 / 模态 / 能力，读不到留空不猜） |
| `domainplane-server` | `config/ModelProfileConfiguration`；`AdminModelProfileController`（`/admin/model-profiles*`、`/admin/model-bindings/{domain}`） |
| 管控台 | `console/models.html`：「模型档案」卡（列表 + 编辑 + 测试 + 删除）与「域 → 模型绑定」卡（域清单 × 当前绑定 × 下拉设置），规则文案写进页面 |

**没有内置 `default` 模型别名**：对话模型只来自自建档案，域未绑定即明确失败。`LlmModelHolder`（`llm-settings.json`）的对话模型仅作"解析器缺失"时的兜底与 `chatConfigured` 指示来源。
