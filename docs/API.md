# DomainPlane API 文档

适用对象：接入方（业务应用）、工具实例提供方、运维（管控台接口）。
设计背景与内部结构见 `DESIGN.md`。

---

## 1 通用约定

### 1.1 基地址与端口

服务端默认监听 `9527`。本文所有路径均为相对路径，示例基地址 `http://localhost:9527`。

### 1.2 鉴权

| 接口组 | 载具 | 说明 |
| --- | --- | --- |
| `/api/agent/**` | 请求头 `X-DomainPlane-Credential` | 凭证由 `POST /api/agent/login` 获取 |
| `/admin/**` | Cookie `domainplane_admin` | 无 Cookie 时回退读 `X-DomainPlane-Credential` 请求头，供程序化调用 |

免鉴权路径：`/api/agent/login`、`/admin/login`、`/admin/init`、`/admin/session`、登录页与静态资源（`/admin.html`、`/login.html`、`/console/**`）、`/error`、`/favicon.ico`。`OPTIONS` 请求一律放行。

此外 `GET /health`（存活探测）不属于上面任何一组接口——它不在被拦截的两个前缀之下，拦截器不会匹配到它，因此**天然免鉴权**。

拦截器另有两条**全局前置规则**，先于上面的免鉴权清单判定：

| 状态 | 行为 | 依据 |
| --- | --- | --- |
| 账号文件存在但无法解析 | **拒绝一切受保护请求**，返回 `10007 AUTH_STORE_CORRUPTED`（HTTP 503） | `CredentialAuthInterceptor:58-63` |
| 服务端尚无账号（未初始化） | **放行且不校验凭证**，首次 `POST /admin/init` 因此可达 | `CredentialAuthInterceptor:65-71` |

即"免鉴权"只在账号已初始化之后才是真正的鉴权边界。凭证无效时统一返回 `10002 AUTH_REQUIRED`（HTTP 401），`detail` 按"未携带凭证"与"凭证已失效"两种情形区分。

凭证格式：`base64url(payload) + "." + base64url(HMAC(派生密钥, payload))`；派生密钥由主密钥与当前密码哈希导出，**改密码后全部旧凭证立即失效**。凭证无有效期。

### 1.3 响应体

非流式失败响应（`ServerGlobalExceptionHandler` 输出）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | int | 错误码，见第 5 节 |
| `codeName` | String | 错误码枚举名，前端应以它分支 |
| `error` | String | 枚举默认文案 |
| `detail` | String | 具体原因（调试用） |
| `retryable` | boolean | 是否可退避重试 |
| `action` | String | 建议动作 |
| `traceId` | String | 排障标识 |
| `timestamp` | long | 毫秒时间戳 |

成功响应的结构不统一：`/admin/**` 多数返回自定义字段并带 `code=0`；表结构、文档列表等接口返回业务字段本身。前端不应假设统一的 `{code,message,data}` 包装。

唯一例外是 `40004 CLIENT_CANCELLED`（用户中断请求）：该分支不走统一构造，`retryable` 硬编码为 `false` 且**不带 `action` 字段**（`ServerGlobalExceptionHandler:207-222`）。

### 1.4 编码与状态码

- 请求与响应统一 UTF-8。
- 时间戳字段为毫秒整数。
- HTTP 状态码是**建议值**，业务判定一律以响应体或事件中的 `code` 为准。

### 1.5 存活探测 `GET /health`（免鉴权）

| 项 | 值 |
| --- | --- |
| 用途 | 容器编排与负载均衡的存活探针（K8s `httpGet`、Docker `HEALTHCHECK`、compose `healthcheck`） |
| 响应 | `{"code":0, "status":"UP", "service":"domainplane-server", "version":"v1.0-beta.1"}` |
| 语义 | **只表示进程能对外服务**，不检查 ES / Redis / 模型服务 |

进程存活与"依赖是否可用"是两件事：把依赖写进探针，会让刚部署、还没填配置的实例被判为不健康而反复重启；而已配置但依赖抖动时，重启进程也修不好依赖。依赖状态见启动横幅与管控台「存储配置」页。

---

## 2 接口 `/api/agent/**`

### 2.1 `POST /api/agent/login`

用账号密码换取凭证。响应体返回凭证，不写 Cookie。

请求：`{"username": "...", "password": "..."}`

响应：`{"code":0, "success":true, "username":"...", "credential":"...", "expiresAt":null}`

### 2.2 `GET /api/agent/health`

响应：`{"code":0, "status":"...", "service":"...", "version":"..."}`

### 2.3 `POST /api/agent/chat`

请求体 `AgentRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `sessionId` | String | 是 | 会话唯一键（**域内唯一**：不同域可以用同一个 sessionId，服务端状态按 `(域, sessionId)` 隔离；不得含 `\|`） |
| `message` | String | 是 | 用户消息 |
| `profile` | String | 否 | 域（**完整路径**，如 `default.sales`）；为空时归一化为根域 `default`。走 SDK 则用 `DomainPlaneAgentFactory.forDomain(...)` 在绑定时确定域，根本传不出空值；该域不存在（`10004`）或**不是可调用单元**（`10010`）都会被拒绝 |
| `tenantId` | String | 否 | 审计字段，写入日志；不承担隔离职责 |
| `userId` | String | 否 | 同上 |
| `attributes` | Map | 否 | 附加属性 |

响应：`text/event-stream`，事件见第 3 节。

> **会话记忆与上限**：记忆按 `(域, sessionId)` 存在 Redis，**只增不淘汰**（每轮存"提问 + 最终回答"，
> 工具调用与结果不入记忆）。到达上限（100 条 / 30000 token）后，该会话**拒绝新一轮**并返回
> `30004 SESSION_MEMORY_FULL` —— 拒绝发生在入口，**不写记忆、不建断点、不调模型**；判定是**粘性**的，
> 之后每次调用都会同样被拒。**平台不代为切换会话**：请由调用方换一个新的 `sessionId` 重新开始，
> 旧会话的数据仍然保留（默认不过期），只是不再接受写入。

### 2.4 `POST /api/agent/resume`

恢复一次因审批而中断的执行。

| 参数 | 位置 | 必填 | 说明 |
| --- | --- | --- | --- |
| `sessionId` | query | 是 | 会话键（域内唯一） |
| `approved` | query | 是 | boolean，是否批准待执行动作 |
| — | body | 是 | `CallerContext`，建议显式携带 `profile`；为空按根域 `default` 处理 |

约束：`profile` 必须与中断时一致 —— 断点存在键 `(域, sessionId)` 下，用别的域 resume **找不到断点**（`30001`）。响应同为 `text/event-stream`。

> **不回审批、直接发起新的 `chat` = 用户拒绝了那次审批**：新对话不会被拒（不返回 `30002`），
> 服务端会为该轮补一条占位回答（"（用户未确认，该次需要审批的操作已取消）"）让这一轮成为完整轮次，
> 并**删除断点** —— 那个待授权动作就此永久作废，之后也无法再被 `resume` 执行。

### 2.5 `POST /api/agent/stop/{sessionId}`

| 参数 | 位置 | 必填 | 说明 |
| --- | --- | --- | --- |
| `sessionId` | path | 是 | 会话键（域内唯一） |
| — | body | 否 | `CallerContext`；**停止必须带与 chat 相同的域**，省略按根域算（只能停根域上的会话） |

响应：`{"code":0, "sessionId":"...", "stopRequested":true}`

语义：仅置取消标志，由编排层在下一个检查点抛出并结束本轮，非抢占式。

### 2.6 `POST /api/agent/tools/register`

工具实例的注册与心跳（同一个端点，整包上报）。

请求体：

```json
{
  "instanceId": "order-svc-1",
  "endpoint": "http://10.0.0.12:8080",
  "manifest": [
    {
      "name": "queryOrder",
      "description": "查询订单",
      "category": "default",
      "version": "1.0.0",
      "domains": ["default.sales"],
      "sideEffect": "READ",
      "idempotent": true,
      "toModel": true,
      "requiresApproval": false,
      "approvalMode": "NONE",
      "approvalReason": "",
      "parameters": {}
    }
  ]
}
```

| 顶层字段 | 必填 | 说明 |
| --- | --- | --- |
| `instanceId` | 是 | 实例标识，缺失报 400 |
| `endpoint` | 是 | 工具调用回流地址（`POST /domainplane/invoke` 的完整 URL） |
| `manifest` | 是 | **必须是数组**，承载本次声明的全量工具；不是数组直接报 400 |

`manifest[]` 单项被服务端读取的字段（`ToolManifest:102-149`）：

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `name` | — | 必填；缺失的条目跳过，同名重复按首条处理 |
| `description` | `""` | |
| `category` | `default` | |
| `version` | `1.0.0` | |
| `domains` | 根域 | **授权边界**，完整路径；留空＝挂根域＝全树可见 |
| `sideEffect` | `READ` | 接受 `READ`/`WRITE`/`DESTRUCTIVE` 枚举名，也接受布尔（false=READ、true=WRITE） |
| `idempotent` | `true` | |
| `toModel` | `true` | |
| `requiresApproval` | `false` | 为 `false` 时审批字段全部忽略 |
| `approvalMode` | — | 留空或 `NONE` 且 `requiresApproval=true` 时按 `ALWAYS` 处理 |
| `approvalCondition` | `""` | |
| `approvalReason` | `""` | |
| `approverRoles` | 空 | 数组 |
| `approvalTimeoutSeconds` | `300` | |
| `parameters` | 空 | JSON Schema，对应 SDK 的 `ToolSpec.parameters` |

| 响应 | HTTP | 体 |
| --- | --- | --- |
| 受理 | 200 | `{"accepted":true, "toolNames":["..."]}` |
| 被强制下线 | 410 | `{"accepted":false, "reason":"force_offline"}` |
| 报文不合法 | 400 | `{"accepted":false, "reason":"...", "hint":"see docs/API.md §2.6"}` |
| 工具名与本地冲突 | 400 | `{"accepted":false, "reason":"...", "hint":"工具名与服务端本地工具冲突，请改名后重新心跳"}` |

400 分支**不走统一错误响应体**（无 `code`/`codeName`），判定依据是 `accepted=false`。

语义：本次上报即该实例的完整声明；未出现在本次 `manifest` 中的工具视为该实例已撤下。

---

## 3 流式事件契约

`chat` 与 `resume` 返回 `text/event-stream`，每帧数据为一个 `AgentEvent`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | String | 事件类型，见下表 |
| `sessionId` | String | 会话键（域内唯一；事件里回显调用方声明的值） |
| `content` | String | 主要载荷（文本或工具名），部分事件为 null |
| `payload` | String | 附加载荷（JSON 字符串），部分事件为 null |
| `code` | Integer | 错误码，仅 `ERROR` 非空 |
| `codeName` | String | 错误码枚举名，仅 `ERROR` 非空 |
| `traceId` | String | 仅 `ERROR` 非空 |
| `timestamp` | long | 毫秒时间戳 |

| `type` | `content` | `payload` | 说明 |
| --- | --- | --- | --- |
| `TOKEN` | 增量文本 | null | 流式输出片段 |
| `TOOL_CALL` | 工具名 | 参数 JSON | 模型决定调用某个工具 |
| `TOOL_RESULT` | 工具名 | 结果文本 | 工具返回 |
| `INTERRUPT` | null | 待授权工具列表 JSON | 命中审批，本轮挂起，需调 `resume` |
| `STOPPED` | null | null | 本轮被用户停止 |
| `ERROR` | 可读文案 | null | `code`/`codeName`/`traceId` 必填 |
| `DONE` | null | null | 本轮结束 |

约定：只有 `ERROR` 事件携带 `code`；过程事件不带。调用方必须显式处理 `ERROR` 事件——错误是通过事件表达的，不会抛出异常，HTTP 状态已是 200。

---

## 4 接口 `/admin/**`（管控台与运维）

### 4.1 账号

| 方法 | 路径 | 入参 | 响应要点 |
| --- | --- | --- | --- |
| POST | `/admin/init` | body `Credentials{username, password}` | `code`、`success`、`message`；仅无账号时可用 |
| POST | `/admin/login` | body `Credentials` | `code`、`success`、`username`、`credential`、`defaultCredential`；同时写 Cookie |
| POST | `/admin/logout` | — | `code`、`success` |
| GET | `/admin/session` | — | `code`、`initialized`、`corrupted`、`authenticated`、`accountFile`（未初始化或已认证时返回）、认证后追加 `username`、`lastLoginAt`、`lastLoginFrom`、`defaultCredential` |
| POST | `/admin/password` | body `PasswordChange{oldPassword, newPassword}` | `code`、`success`、`message`；成功后全部旧凭证失效 |

### 4.2 模型设置

| 方法 | 路径 | 入参 | 响应要点 |
| --- | --- | --- | --- |
| GET | `/admin/settings` | — | `chatBaseUrl`、`chatApiKeyMasked`、`chatApiKeySet`、`chatModelName`、`chatTemperature`、`chatMaxTokens`、`chatCapabilities`、`embeddingBaseUrl`、`embeddingApiKeyMasked`、`embeddingModelName`、`embeddingDimensions`、`embeddingCapabilities`、`chatConfigured`、`embeddingConfigured`、`settingsFile` |
| POST | `/admin/settings` | body `LlmSettings`；query `rebuildIndex`（默认 false） | 成功：`success`、`chatConfigured`、`embeddingConfigured`、`chatApiKeyMasked`、`chatApiKeySet`、`embeddingApiKeyMasked`、`rebuilt`、`message`；失败分支见下表 |
| DELETE | `/admin/settings/{kind}` | path `kind`（`chat` / `embedding`，大小写不敏感） | 成功：`success`、`chatConfigured`、`embeddingConfigured`、`chatApiKeyMasked`、`chatApiKeySet`、`embeddingApiKeyMasked`、`message`；`kind` 非法 → `40000 INVALID_PARAMETER`；持有者拒绝清空 → `success:false` + `error` |
| POST | `/admin/settings/test` | body `LlmSettings`（可空）；query `type`（`chat`/`embedding`，默认 `chat`） | 见下 |
| POST | `/admin/models` | body `LlmSettings`（可空）；query `type`（默认 `chat`） | 成功：`success`、`models`；失败：`success`、`message`、`error`、`detail` |

约定：`apiKey` 不回显，留空表示保持原值；向量模型的地址与 Key 留空时回落文本模型配置。

`POST /admin/settings` 的失败分支（`success:false`，均带 `error`，无 `code`）：

| 情形 | 追加字段 | 语义 |
| --- | --- | --- |
| 声明维度与实测不符 | `declaredDimension`、`newDimension` | 模型不支持该维度，直接拒绝保存，不受 `rebuildIndex` 影响 |
| 维度变化但未确认重建 | `requiresRebuild: true`、`indexDimension`、`newDimension` | 本次保存不生效，配置保持原样 |
| 索引重建失败 | `saved: true`、`rebuilt: false` + 三个 Key 状态字段 | **配置已落盘并生效**，仅索引重建失败，需另行重试 |

`POST /admin/settings/test` 的响应：

| `type` | 成功 | 失败 |
| --- | --- | --- |
| `chat` | `success`、`type`、`reply`、`message` | `success`、`type`、`message`、`error`、`detail` |
| `embedding` | `success`、`type`、`dimension`、`declaredDimension`、`indexDimension`、`message` | 同上（`dimension` / `declaredDimension` / `indexDimension` 仍会先写入） |

`POST /admin/models` 在地址或 Key 缺失时**不发起请求**，直接返回失败分支。

### 4.2.1 模型档案与域绑定（多 LLM）

模型档案（`config/models.json`）与「域 → 模型」绑定。对话模型只来自这里的自建档案，<b>没有内置 `default` 模型别名</b>：域未绑定任何可用档案 → 调用时抛 `NotConfiguredException`（`90005`）。

| 方法 | 路径 | 入参 | 响应要点 |
| --- | --- | --- | --- |
| GET | `/admin/model-profiles` | — | `code`、`profiles[]`（每项含 `alias`、`endpoints`、`input`、`output`、`baseUrl`、`modelName`、`apiKeyMasked`、`temperature`、`maxTokens`、`dimensions`、`capabilities`、`fallbacks`、`capabilityHint`、`usedByDomains`）、`domainBindings`（域→别名列表）、`settingsFile` |
| POST | `/admin/model-profiles` | body `ModelProfile`（`alias` / `apiKey` 必填，另含 `endpoints` / `input` / `output` / `baseUrl` / `modelName` / `temperature` / `maxTokens` / `dimensions` / `capabilities` / `fallbacks`） | `code`、`action`、`target`、`settingsFile`；`alias` 已存在则整体覆盖 |
| POST | `/admin/model-profiles/inspect` | body `{baseUrl, apiKey, modelName}` | `code`、`success`、`endpoints`、`input`、`output`、`capabilities`、`dimension`、`message`：<b>只读</b>提供商的 `GET /models` 判定端点族 / 模态 / 能力，<b>不发任何试探请求</b>；读不到的字段留空（不猜），由用户在管控台声明 |
| POST | `/admin/model-profiles/inspect-saved` | query `alias` | 用档案已存配置重新读取并<b>写回档案</b>（读不到的字段保留档案里原有的声明，不覆盖手工声明）；返回同上 + `alias` + `profile` |
| POST | `/admin/model-profiles/test` | query `alias` | `code`、`alias`、`success`、`reply`：用档案配置发一条极短请求验证连通 |
| DELETE | `/admin/model-profiles` | query `alias` | <b>级联清理</b>：删除档案并把它从所有域绑定里摘掉（摘空的域绑定一并移除）；返回 `code`、`action`、`target`、`settingsFile` |
| PUT | `/admin/model-bindings/{domain}` | path `domain`；body `{aliases:[...]}`（可空＝解绑） | `code`、`action`（`bound` / `unbound`）、`target`、`aliases`（解绑后该域当前生效列表）、`sourceDomain`（生效来源：本域自绑还是从哪个祖先继承）、`domainBindings`、`settingsFile`；`aliases` 整体覆盖，顺序即优先级；`default` 不能作为别名绑定；域未登记 → `40000 INVALID_PARAMETER` |

约定：
- 档案的 `endpoints` 可多选；<b>空 = 未声明</b>（历史上会被默认补成 `["chat"]`，现已取消 —— 猜成对话模型会让向量 / 生图模型被误用）。是否对话模型看 `endpoints` 是否含 `chat`（`isChat()`），向量看 `embedding`。**落盘结构无 `type` 字段**。
- `bind` 的 `aliases` 为空 / 全空白＝解绑，该域进入"无可调用"状态；列表首个为当前使用的对话模型，其余留给多 agent / 降级。
- `GET /admin/model-profiles` 的响应中不含 `builtinAlias` / `defaultAlias` / `builtinChat`。
- `chatConfigured`（`/admin/settings`）＝ `llm-settings` 的 chat 已配置 **或** 任一可用对话档案存在（`registry.hasChatModel()`）。

### 4.3 工具、域与提示词

| 方法 | 路径 | 入参 | 响应要点 |
| --- | --- | --- | --- |
| GET | `/admin/tools` | — | 工具描述符列表 `ToolDescriptor` |
| GET | `/admin/domains` | — | `stats`、`domains[]`、`globalTools`、`orphanPrompts`、`settingsFile`，见下 |
| POST | `/admin/domains` | body `{id, callable?}` | `code`、`action`（`created`）、`domain`、`removed`、`removedIndices`、`manualDomains`、`callableDomains`、`settingsFile`；`id` 缺失或建域被拒 → `40000 INVALID_PARAMETER` |
| PUT | `/admin/domains/{id}/callable` | body `{callable}` | `code`、`action`（`callable`）、`domain`、`removed`、`removedIndices`、`manualDomains`、`callableDomains`、`settingsFile`；根域不可置 `false`，域不存在或缺 `callable` → `40000 INVALID_PARAMETER` |
| DELETE | `/admin/domains/{id}` | — | `code`、`action`（`deleted`）、`domain`、`removed`（被一并删掉的子孙）、`removedIndices`、`manualDomains`、`callableDomains`、`settingsFile`；见下方清理语义 |
| GET | `/admin/prompts` | — | `prompts`、`domains[]`、`orphanPrompts`、`settingsFile`；`domains[]` 单项见下（编辑框里**只含已登记的域**） |
| POST | `/admin/prompts` | body `DomainSettings`（可空） | `success`、`message`、`settingsFile`；含**未登记**的域键直接 400 |
| GET | `/api/agent/domains` | — | `code`、`domains`、`details[{id, source, toolCount, callable}]`、`fallback`（根域标识）。**只返回可调用单元**；路径在 `/api/agent/**` 下，走请求头凭证 |

`GET /admin/domains` 的 `stats`：`domainCount`、`builtinCount`、`manualCount`、`derivedCount`、`toolCount`、`globalToolCount`、`approvalToolCount`、`missingPromptCount`。

`GET /admin/domains` 的 `domains[]` 单项：`name`、`source`、`sourceLabel`、`deletable`、`parentId`、`childrenCount`（全部后代）、`directChildCount`、`hasChildren`、`callable`、`toolCount`、`exclusiveToolCount`、`providerCount`、`approvalCount`、`sideEffects{READ,WRITE,DESTRUCTIVE}`、`hasPrompt`、`promptLength`、`promptPreview`、`tools[]`（单项含 `name`、`description`、`category`、`version`、`sideEffect`、`idempotent`、`toModel`、`requiresApproval`、`approvalMode`、`approvalReason`、`paramCount`、`provider`、`source`、`exclusive`）。

`GET /admin/prompts` 的 `domains[]` 单项：`name`、`chain`、`ancestors`、`tools[{name, source}]`、`providerCount`、`prompt`（本域片段）、`hasPrompt`、`promptLength`、`preview`（沿链拼接后的生效全文）。

域是一棵树，标识是**从根域 `default` 出发的完整路径**。域有三个**来源**（`BUILTIN` 根域 / `MANUAL` 人工创建 / `DERIVED` 工具声明派生），**同级、不构成等级**；差异只在生命周期——`MANUAL` 落盘重启仍在，`DERIVED` 重启随声明重建。登记时沿链补齐缺失祖先，不留悬空节点；删除**递归**带走全部子孙，不向上提升，**只有根域不可删**。

域有两种**角色**：

- **可调用单元**：能作为入口被调用，是"这一个 AI 切片"的入口；
- **装配节点**：只把工具 / 提示词 / 模型绑定 / 知识传给后代，不能直接当入口。

硬约束是**叶子规则**：**只有叶子域（没有子域）可以作为可调用单元**，一个域一旦有了子域就降级为装配节点。

判定方式是**读取时派生**（`isCallable = 显式标记可调用 ∧ 无子域`），因此绕不过去：无论标记来自管控台显式声明、工具声明派生、还是落盘文件里的历史值，只要该域有子域，它就不是可调用单元。写入侧存的是**意图**，读取侧算的是**生效**；`manualConfig()` 落盘写的也是生效值。

根域**没有豁免**，同样服从这条规则：整棵树只有根域时它是叶子、可调用；一旦往下建了子域，它也只是装配节点，此时"未指定域"（空 `profile` 归一化到根域）会返回 `10010`，调用方必须显式声明一个叶子域。

需要"某一层整体"的入口时，**另建一个没有子域的域**，而不是把父域本身变成入口。

`10004`（域不存在）与 `10010`（域存在但不是可调用单元）**分开**：前者说明名字写错或没建域，后者说明把有子域的域当成了入口。

`PUT /admin/domains/{id}/callable` 对**已有子域**的域请求 `callable=true` 会被拒绝（`40000 INVALID_PARAMETER`），文案说明"只有叶子域可以作为可调用单元"并列出该域当前的子域。

写入口对域的要求：**工具 manifest / 本地 `@Tool(domains=...)` 校验格式**（非法整包拒绝或启动失败）；**知识库归属域 / 提示词 / 模型绑定必须已登记**（否则会为不存在的域建索引、落盘孤儿配置并被后代继承）。

`DELETE /admin/domains/{id}` 的清理顺序是硬要求（`AdminDomainController:144-200`），除第 5 步外任一步失败都会中止且**域一个没动**：

1. 校验域存在、且不是根域（根域不可删）；牵连范围＝自身＋全部子孙；
2. 删该范围内各域的知识库索引（含切片预览文件），失败 → `60000 KNOWLEDGE_BASE_ERROR`，域未删除；
3. 失效对应索引的**检索器缓存**（被删索引留在缓存里会被旧检索器继续命中）；
4. 清**提示词**、**模型绑定**、**工具声明派生记录**（不清则同路径域将来重建会静默复活旧配置）；
5. 清 **Redis 会话记忆**与**图检查点**：对范围内每个域按 `{域}|` 前缀 `SCAN` 后删除（`RedisChatMemoryStore:122-124`、`RedisCheckpointSaver:156-178`）。会话记忆保留期默认永久，不清则重建域后旧对话历史原样复活；断点里存着"已授权待执行"的工具调用，不清则 24 小时内重建域可 `resume(approved=true)` 补执行掉当初被拦下的破坏性动作。此步失败只告警、**不阻断删域**；
6. 删域并落盘人工声明。

返回的 `removedIndices` 是第 2 步实际删掉的索引名列表。

域是一棵树，标识是**从根域 `default` 出发的完整路径**。域有三个**来源**（`BUILTIN` 根域 / `MANUAL` 人工创建 / `DERIVED` 工具声明派生），**同级、不构成等级**；差异只在生命周期——`MANUAL` 落盘重启仍在，`DERIVED` 重启随声明重建。登记时沿链补齐缺失祖先，不留悬空节点；删除**递归**带走全部子孙，不向上提升，**只有根域不可删**。

每个域在响应里带 `source` / `sourceLabel` / `deletable` / `parentId` / `childrenCount` / `directChildCount` / `hasChildren` / `callable`。其中 `callable` 是**生效值**（显式标记 ∧ 无子域），所以有子域的域在这里恒为 `false`。

### 4.4 在线实例

| 方法 | 路径 | 入参 | 响应要点 |
| --- | --- | --- | --- |
| GET | `/admin/instances` | — | `stats{instanceCount, onlineCount, mutedCount, drainingCount, forceOfflineCount, registeredToolCount, remoteReplicaCount, timeoutSeconds}`、`instances[]` |
| POST | `/admin/instances/{instanceId}/mute` | path `instanceId` | `success`、`instanceId`、`removedToolCount`、`message` |
| POST | `/admin/instances/{instanceId}/restore` | path `instanceId` | `success`、`instanceId`、`message` |
| POST | `/admin/instances/{instanceId}/offline` | path `instanceId` | `success`、`instanceId`、`removedToolCount`、`message` |

`instances[]` 单项字段：`instanceId`、`endpoint`、`state`（`ONLINE`/`MUTED`/`DRAINING`/`FORCE_OFFLINE`）、`stateLabel`、`rejecting`、`muted`、`lastSeen`、`silentMillis`、`toolNames`、`toolCount`、`digest`（前 12 位）。

熔断（`mute`）：标记 `MUTED` 并立即摘除其工具副本，**心跳继续受理**，随时可用 `restore` 解除——副本在它的下一次心跳时重建，实例自身重启同理会自动重连回来。

强制下线（`offline`）：标记 `FORCE_OFFLINE` 并立即摘除其工具副本，该实例下次心跳收到 410；不截断会话；标记在保留窗（默认 1h）内有效。

两者都不截断会话、不杀进程、不掐连接，差别只在是否连心跳一起拒绝；已空心跳的熔断实例会在超时窗后从在线表移除。

### 4.5 知识库

| 方法 | 路径 | 入参 | 响应要点 |
| --- | --- | --- | --- |
| GET | `/admin/kb/documents` | — | `code`、`count`、`exportDir`、`documents[{docId, fileName, chunks, domain, exportPath}]` |
| POST | `/admin/kb/documents` | `multipart/form-data`，`file`（必填）、`replace`（默认 false）、`domain`（可选，单个） | `code`、`success`、`docId`、`fileName`、`size`、`chunks`、`domain`、`sections`、`droppedLines`、`encoding`、`exportPath` |
| DELETE | `/admin/kb/documents/{docId}` | path `docId` | `code`、`success`、`docId`、`deleted` |
| GET | `/admin/kb/status` | — | `code`、`indexCount`、`documents`、`chunks`、`indices[{index, domain, documents, chunks}]` |
| POST | `/admin/kb/rebuild` | — | `code`、`success`、`indices`、`dimensions`、`message` |

`domain` 声明该文档**归属的域**，与 `@Tool(domains = {...})` 同构：必须是**从根域出发的完整路径**，
判定按**累加**——挂在某域则其**全部后代域**都能检索到；留空 / 不传 → 挂根域 `default`＝全域可见。
**无通配写法**，路径非法直接拒绝（静默会把文档写到一个树里不存在的域上，永远检索不到）。
一个文档只属一个域：知识库是**一域一索引**，域即索引。

约定：上传为同步（切片与向量化完成后才返回）；**同一域内**同名不区分大小写，默认拒绝，`replace=true` 先删后写；`rebuild` 会清空全部知识库索引，之后需重新上传文档。

上传只接受 `domainplane.rag.allowed-extensions` 白名单内的类型，当前是 `txt` / `md` / `markdown` / `docx` / `doc` / `pdf` / `xls` / `xlsx`。**文本类**（`txt` / `md` / `markdown`）按入口探测的编码（BOM → 严格 UTF-8 → GB18030）**统一转成 UTF-8** 后再入库，两种编码都判不出会直接拒绝该文件；**二进制类**（`docx` / `doc` / `pdf` / `xls` / `xlsx`）不做编码探测，原始字节直接交给对应的解析器 —— 猜错编码会把乱码静默灌进知识库，比直接报错更糟。

`sections`（识别到的标题数）、`droppedLines`（清洗删掉的行数）、`encoding`（实际识别的源编码）是切片质量诊断值 —— 批量上传时靠这几个数就能发现"这批文件切坏了"。`exportPath` 是切片预览 txt 的落盘路径（上传后自动导出到 `domainplane.export.path`，默认 `%ProgramData%\DomainPlane\chunks`），管控台只展示这个路径。

### 4.6 存储配置

| 方法 | 路径 | 入参 | 响应要点 |
| --- | --- | --- | --- |
| GET | `/admin/infra` | — | `es{}`、`redis{}`、`esConfigured`、`redisConfigured`、`esSource`、`redisSource`、`indexName`、`settingsFile` |
| POST | `/admin/infra` | body `InfraSettings` | `success`、`esConfigured`、`redisConfigured`、`esSource`、`redisSource`、`message`；ES 保存后追加 `requiresRebuild`、`indexDocCount`、`indexProbeError` |
| POST | `/admin/infra/test` | body `InfraSettings`（可空）；query `type`（`es`/`redis`，默认 `es`） | `success`、`type` 及各类型的探测结果字段 |

保存语义：整对象覆盖（单卡保存时另一卡需回填已存值）；口令不回显，留空表示保持原值；保存后连接热替换，无需重启。

### 4.7 运行指标

| 方法 | 路径 | 响应要点 |
| --- | --- | --- |
| GET | `/admin/metrics` | `code`、`uptimeSeconds`、`counters{chatRequests, resumeRequests, stopRequests, toolCalls, toolFailures}`、`runningSessions`、`registeredTools`、`heapUsedBytes`、`heapMaxBytes` |

指标为**进程内累计**：重启归零、不跨实例聚合（服务端只支持单实例）。要长期趋势与告警，把日志或本接口接进外部监控。实例维度的数量（在线 / 判死 / 强制下线 / 副本数）在 `GET /admin/instances` 的 `stats` 里。

---

## 5 错误码总表

| 码 | 枚举名 | 默认文案 | 可重试 | 建议 HTTP |
| --- | --- | --- | --- | --- |
| 0 | `OK` | ok | — | 200 |
| 10000 | `PERMISSION_DENIED` | 当前权限无法使用该能力 | 否 | 403 |
| 10001 | `TOOL_PERMISSION_DENIED` | 该工具不在当前域内 | 否 | 403 |
| 10002 | `AUTH_REQUIRED` | 未登录或凭证已失效 | 否 | 401 |
| 10003 | `AUTH_FAILED` | 账号或密码错误 | 否 | 401 |
| 10004 | `PROFILE_NOT_FOUND` | 指定的域不存在 | 否 | 400 |
| 10005 | `AUTH_NOT_INITIALIZED` | 服务端账号尚未初始化 | 否 | 409 |
| 10006 | `AUTH_ALREADY_INITIALIZED` | 账号已存在，初始化入口已关闭 | 否 | 409 |
| 10007 | `AUTH_STORE_CORRUPTED` | 账号文件损坏，无法读取。**此时服务端拒绝一切受保护请求**，直到文件被修复或删除后重启 | 否 | 503 |
| 10008 | `CALLER_CONTEXT_REQUIRED` | 缺少调用方身份（保留为理论码：`CallerContext.from` 与 `resume` 的必填 body 都不会产生 null） | 否 | 400 |
| 10009 | `PROFILE_REQUIRED` | 未指定本轮所处的域（保留为理论码：域为空会归一化为根域 `default`，而根域不可删除） | 否 | 400 |
| 10010 | `DOMAIN_NOT_CALLABLE` | 该域不是可调用单元（装配节点不能当入口）。文案带出当前可调用集合与修法 | 否 | 400 |
| 20000 | `RATE_LIMITED` | 请求过于频繁，请稍后再试（**保留未实现**：入口限流尚未落地，服务端不会发出此码） | 是 | 429 |
| 20001 | `LLM_RATE_LIMITED` | AI 服务繁忙，请稍后重试（**保留未实现**：大模型侧限流映射尚未落地，服务端不会发出此码） | 是 | 429 |
| 20002 | `SYSTEM_BUSY` | 系统繁忙，请稍后重试。编排线程池满或提交被拒时发出 | 是 | 503 |
| 20003 | `CONCURRENT_LIMIT` | 并发会话数已达上限（**保留未实现**：按调用方维度的并发闸门尚未落地，服务端不会发出此码） | 是 | 503 |
| 30000 | `ORCHESTRATION_FAILED` | 任务执行失败，请重试 | 是 | 500 |
| 30001 | `SESSION_NOT_FOUND` | 会话不存在或已过期 | 否 | 404 |
| 30002 | `SESSION_STATE_INVALID` | 会话状态异常，无法继续（**仅用于 `resume` 时断点不在待审批点**；挂起未批就开新对话视为用户拒绝，不走此码） | 否 | 409 |
| 30003 | `SESSION_BUSY` | 会话正在执行中，拒绝并发请求 | 否 | 409 |
| 30004 | `SESSION_MEMORY_FULL` | 会话记忆已达上限，该会话不再接受新消息；**换 `sessionId`** 开启新会话（平台不代为切换） | 否 | 409 |
| 40000 | `INVALID_PARAMETER` | 请求参数非法 | 否 | 400 |
| 40001 | `MISSING_REQUIRED_PARAMETER` | 缺少必填参数 | 否 | 400 |
| 40002 | `TYPE_MISMATCH` | 参数类型不匹配 | 否 | 400 |
| 40003 | `INPUT_REJECTED` | 输入内容不安全，已被拦截 | 否 | 400 |
| 40004 | `CLIENT_CANCELLED` | 用户已中断请求 | 否 | 499 |
| 40400 | `RESOURCE_NOT_FOUND` | 请求的资源不存在 | 否 | 404 |
| 50000 | `SYSTEM_ERROR` | 系统内部错误 | 是 | 500 |
| 50001 | `UNEXPECTED_ERROR` | 服务暂时不可用，请稍后重试 | 是 | 500 |
| 60000 | `KNOWLEDGE_BASE_ERROR` | 知识库服务异常 | 是 | 500 |
| 60001 | `KNOWLEDGE_SEARCH_ERROR` | 知识库检索失败 | 是 | 500 |
| 60002 | `KNOWLEDGE_INGEST_ERROR` | 知识库文档导入失败 | 否 | 500 |
| 60003 | `KNOWLEDGE_DEDUP_ERROR` | 知识库去重计算失败 | 否 | 500 |
| 60004 | `KNOWLEDGE_STRATEGY_NOT_FOUND` | 未匹配到文档处理策略 | 否 | 500 |
| 60005 | `KNOWLEDGE_DOCUMENT_DUPLICATE` | 知识库已存在同名文档 | 否 | 409 |
| 60006 | `KNOWLEDGE_UPLOAD_REJECTED` | 知识库文档上传被拒绝 | 否 | 400 |
| 70000 | `CHAT_MEMORY_ERROR` | 会话记忆服务异常 | 是 | 500 |
| 70001 | `CHAT_MEMORY_READ_ERROR` | 读取会话记忆失败 | 是 | 500 |
| 70002 | `CHAT_MEMORY_WRITE_ERROR` | 写入会话记忆失败 | 是 | 500 |
| 70003 | `CHAT_MEMORY_DELETE_ERROR` | 删除会话记忆失败 | 否 | 500 |
| 70004 | `CHECKPOINT_ERROR` | 图检查点读写失败 | 是 | 500 |
| 80000 | `TOOL_ERROR` | 工具调用异常 | 是 | 500 |
| 80001 | `TOOL_NOT_FOUND` | 未找到指定工具 | 否 | 404 |
| 80002 | `TOOL_DUPLICATE` | 工具名称重复注册 | 否 | 500 |
| 80003 | `TOOL_EXECUTION_FAILED` | 工具执行失败 | 是 | 500 |
| 90000 | `LLM_TIMEOUT` | 大模型接口响应超时 | 是 | 504 |
| 90001 | `EXTERNAL_SERVICE_TIMEOUT` | 外部服务调用超时 | 是 | 504 |
| 90002 | `SERVER_UNREACHABLE` | 无法连接 DomainPlane 服务端 | 是 | 503 |
| 90003 | `LLM_UNAVAILABLE` | AI 服务暂时不可用 | 是 | 503 |
| 90004 | `STORAGE_UNAVAILABLE` | 存储服务不可用 | 是 | 503 |
| 90005 | `DEPENDENCY_NOT_CONFIGURED` | 服务依赖尚未配置 | 否 | 503 |

区分要点：`90004`＝已配置但连不上（ERROR，可重试）；`90005`＝尚未配置（WARN，不可重试，提示去管控台补填）。`10008`/`10009`/`10004` 分别表示缺整份身份、缺域字段（**已归一化为根域 `default`，实际不触发**）、域不存在 —— 处置不同，不可合并。

---

## 6 接入方 SDK（starter）

三块能力三个坐标，**按需引入**；互相独立，同时引入也不冲突 —— WebClient / 凭证 / 启动探测是三者共用的底座，只装配一份。

| 坐标 | 能力 | 该坐标暴露的 Bean |
| --- | --- | --- |
| `domainplane-chat-client` | 对话（`ask` / `stream` / `events` / `resume` / `stop`） | `domainplaneAgentFactory` |
| `domainplane-kb-client` | 知识库（上传 / 列表 / 删除） | `domainplaneKnowledgeBaseClient` |
| `domainplane-tool-provider` | 工具注册（需显式打开 `domainplane.tools=true`） | `ToolInstanceClient` |

> `domainplane-client-core`（WebClient / 凭证 / 错误翻译 / 启动探测）与 `domainplane-sdk-core`（契约 + 连接配置）是**传递依赖**，接入方不直接引。

### 6.1 配置

前缀 `domainplane.*`（地址与账号，对话 / 知识库 / 工具实例共用同一份）：

| 键 | 默认值 |
| --- | --- |
| `server` | `http://localhost:9527` |
| `username` | `domainplane` |
| `password` | `domainplane` |
| `tools` | `false` |

前缀 `domainplane.client`（调用行为，两个 SDK 共用）：

| 键 | 默认值 |
| --- | --- |
| `health-check-timeout` | 5s |
| `connect-timeout` | 5s |
| `read-timeout` | 10m |

### 6.2 自动配置与 Bean

| 自动配置类（所在坐标） | 注册的 Bean |
| --- | --- |
| `DomainPlaneClientAutoConfiguration`<br>（`domainplane-client-core`） | `domainplaneWebClient`（`WebClient`，baseUrl 指向服务端）；`domainplaneClientCredential`（`ClientCredential`，凭证缓存与登录）；`domainplaneConnectivityCheck`（`SmartInitializingSingleton`，启动期探测，失败即中断启动） |
| `ChatAutoConfiguration`<br>（`domainplane-chat-client`） | `domainplaneAgentFactory`（`DomainPlaneAgentFactory`）：**对话唯一入口**，`forDomain(...)` → `DomainPlaneAgent`（`ask`/`stream`/`events`/`resume`/`stop`） |
| `KnowledgeBaseAutoConfiguration`<br>（`domainplane-kb-client`） | `domainplaneKnowledgeBaseClient`（`KnowledgeBaseClient`）：知识库管理 |

> 底层的 `AgentService` / `AgentServiceClient` 是 SDK 内部通道，**不作为 Bean 暴露** —— 对外只有 `DomainPlaneAgent` 一个入口。

### 6.3 方法签名

| 类 | 方法 |
| --- | --- |
| `DomainPlaneAgentFactory` | `forDomain(String domainId)` → `DomainPlaneAgent`（`null`/空白 → 根域 `default`；同域返回同一实例） |
| `DomainPlaneAgent` | `ask(sessionId, question[, tenantId, userId])` → `String`（遇审批抛 `ApprovalRequiredException`） |
| | `stream(...)` → `Flux<String>`；`events(...)` → `Flux<AgentEvent>` |
| | `resume(sessionId, approved)` → `Flux<AgentEvent>`；`stop(sessionId)` → `boolean`；`domainId()` → `String` |
| `ClientCredential` | `get()`、`invalidate()`、`login()`、`extractHttpStatus(Throwable)` |
| `KnowledgeBaseClient` | `upload(byte[], String fileName, boolean replace)` → `UploadResult(docId, fileName, size, chunks)`（不声明域 = 落到根域 `default` 的索引） |
| | `upload(byte[], String fileName, boolean replace, String domain)` → 同上，并声明归属域（完整路径；留空 = 根域） |
| | `list()` → `List<DocumentItem(docId, fileName, chunks)>` |
| | `delete(String docId)` |

客户端异常统一为 `DomainPlaneException`（携带 `ErrorCode`）；启动探测失败抛 `DomainPlaneStartupException`；
`ask` / `stream` 命中人工审批时抛 `ApprovalRequiredException`（带 `sessionId` / `domainId` / `tools` / `traceId`）。

---

## 7 工具实例（SDK）

> 坐标 `domainplane-tool-provider`，与对话 / 知识库 SDK 相互独立（见 §6）；开启开关是 `domainplane.tools=true`（不在本组前缀下）。

### 7.1 注解

工具实例侧只认一套注解：

- **`@Tool` 全家桶**：`desc` 为唯一必填，配套 `@ToolParam`（说明/名字/必填三字段）/ `@ToolDomains`（类级默认域）/ `@ToolAdvanced`（示例、枚举白名单、敏感参数，一律 `参数名=值`），审批写在 `@Tool(approval=..., approvalReason=...)` 上。这是工具声明的唯一写法，没有等价的其他组合。字段与示例见 [`SDK-USAGE.md`](SDK-USAGE.md) 与 [`SDK-CONTRACT.md`](SDK-CONTRACT.md)。

两种生效场景：

- **工具实例侧（本 SDK）**：方法所在类注册为 Spring Bean 即可，由 `ToolProviderScanner` 在装配期扫描注册；参数 schema 由方法签名推导，`@ToolParam` 补语义，`@Tool(approval=...)` 定审批。
- **服务端进程内**：任意 Spring Bean 即可（见 `INSTANCE.md` §5）。`DomainPlaneToolProvider` 为可选标记，实现了照样被扫到。

开关 `domainplane.tool-instance.scan-annotated`（默认 `true`）。

### 7.2 配置

服务端地址与账号读 `domainplane.server`（单个 URL，默认 `http://localhost:9527`）与 `domainplane.username` / `password`（默认 `domainplane` / `domainplane`），与对话 / 知识库 SDK 共用同一份。

前缀 `domainplane.tool-instance`：

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `domainplane.tools` | `false` | **开启开关不在本组**：必须显式写 `domainplane.tools=true`，否则整组配置不生效 |
| `scan-annotated` | true | 是否扫描 `@Tool` 注解方法并自动注册；关闭后只认 `ToolInstanceContributor` 编程式注册 |
| `instance-id` | — | 实例标识 |
| `endpoint` | 推导 | 本实例对外可达地址，服务端反向调用用；留空按 `http://localhost:{本进程端口}/domainplane/invoke` 推导，跨机部署必须显式填写 |
| `heartbeat-interval-seconds` | 5 | 心跳周期 |
| `max-backoff-seconds` | 20 | 连续失败退避上限 |
| `request-timeout-millis` | 10000 | 出站请求超时 |

### 7.3 核心类型

| 类型 | 说明 |
| --- | --- |
| `ToolInstanceClient` | `register(ToolSpec, ToolHandler)`、`start()`、`stop()`、`invoke(JsonNode)` |
| `ToolHandler` | `handle(String argumentsJson)` |
| `ToolSpec` | 工具声明（序列化为注册报文的 `manifest` 项） |
| `ToolInstanceConfig` | 实例身份配置 |
| `ToolRegistrar` | 注册扩展点 |
| `ToolProviderScanner` | 扫描 Spring 容器里带 `@Tool` 的方法，转成 `ToolSpec` + 反射执行体并注册 |

### 7.4 反向调用协议（服务端 → 实例）

实例侧端点：`POST /domainplane/invoke`

请求体：

| 字段 | 说明 |
| --- | --- |
| `requestId` | 请求标识 |
| `toolName` | 工具名 |
| `arguments` | 参数（JSON） |
| `tenantId` / `userId` / `traceId` | 调用上下文 |

响应体：

| 情况 | 响应 |
| --- | --- |
| 成功 | `{"requestId":"...", "success":true, "result":...}` |
| 业务失败 | `{"requestId":"...", "success":false, "error":"...", "retryable":true/false}` |

约定：实例必须**同步**返回；服务端仅在传输层失败（超时、连接失败）时换副本重试，业务失败直接返回给模型。
