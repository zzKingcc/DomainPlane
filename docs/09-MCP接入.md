# MCP 接入

外部 MCP server 提供的工具，要先变成"平台工具"，才能被域看见、被审批、被模型调用。本文讲这条转换链、连接的生命周期、以及 OAuth 支持到哪一步。

---

## 1. 传输方式与协议版本

| 值 | 协议 | 现状 |
| --- | --- | --- |
| `STREAMABLE_HTTP` | MCP Streamable HTTP | 主推 |
| `LEGACY_SSE` | MCP 旧版 HTTP+SSE | 已实现，**未对真实旧 server 验证**；该传输在 2026-07-28 里已标废弃 |

传输由连接配置决定；**协议版本不用配，建连时自动判定**：

```
server/discover  ── 成功且 supportedVersions 含 2026-07-28 ──→ 无状态（逐请求声明）
       │
       └─ 回 -32601 / 404 / 400 无现代错误码 ────────────────→ initialize 握手（会话式）
       └─ 回 -32022 且 supported 里没有本平台实现的版本 ────→ 直接失败并报出服务端支持的版本
```

| 时代 | 握手 | 会话 | 每请求带什么 |
| --- | --- | --- | --- |
| 无状态（2026-07-28） | 无 | 无 `Mcp-Session-Id` | `params._meta` 里的 `protocolVersion` / `clientCapabilities` / `clientInfo`，HTTP 头 `MCP-Protocol-Version`、`Mcp-Method`（`tools/call` 另带 `Mcp-Name`） |
| 会话式（2025-03-26 ~ 2025-11-25） | `initialize` + `notifications/initialized` | `Mcp-Session-Id` | 会话头 |

判定结果是**服务端（origin）的属性**，一次建连只判一次；协商结果（版本、时代、服务端能力集）在管控台状态条和概览里显示。

**`resultType`**：无状态协议的所有 result 都带 `resultType`（`complete` / `input_required`），旧 server 不带、按 `complete` 处理。`input_required` 是多轮往返（MRTR）的中间结果，本平台未实现该模式，遇到会**如实返回失败**并说明原因，不会当空结果静默成功。

**未采纳的 2026-07-28 能力**：`subscriptions/listen`（服务端主动推送变更）——页面/协调器已是 15s 轮询 + 30s reconcile，长连接流的复杂度换不来实际收益；`ttlMs` / `cacheScope` 缓存提示——`tools/list` 每次建连只拉一次，没有可复用的窗口；CIMD（客户端元信息文档）——仍用 DCR，待授权服务器强制要求时再迁；`x-mcp-header`（把某个工具参数搬进 `Mcp-Param-*` 请求头）——需要把工具 schema 一路传到调用点，未接时这类参数只会出现在 `arguments` 里。`inputSchema` 按 JSON Schema 2020-12 原样透传（本平台不校验也不改写 schema）。

---

## 2. 连接配置

| 字段 | 说明 |
| --- | --- |
| `name` | 连接标识，同时是工具名前缀的一部分，改名等于换工具 |
| `url` | MCP server 地址 |
| `transport` | 上表二选一 |
| `headers` | 附加请求头（凭证通常放这里） |
| `callTimeoutMs` | 单次工具调用超时 |
| `enabled` | 开关；关掉即不桥接 |
| `approvalPolicy` | 审批策略，默认 `ALL` |
| `toolAllowlist` | 只桥接白名单里的工具；空 = 全部 |
| `oauth` | OAuth 相关配置 |
| `remark` | 备注 |

**凭据只回显 `hasHeaders` 布尔**，明文不回传。所以"这台连接到底配了什么头"在页面上看不到——这是对的，但排障时只能看服务端日志。

---

## 3. 工具怎么变成平台工具

```
MCP server 的 tools/list
  → 目录（McpToolCatalog）
  → 命名规范化：mcp__<连接名>__<原始工具名>
       · 非法字符（既不是字母、数字，也不是 _ - .）换成 _
       · 超 64 字符截断 + 追加哈希
       · 连接内撞名再追加 _2 / _3
  → McpToolBridge.publish
  → 注册为平台工具（instanceId = mcp:<连接名>）
  → 按域绑定授权可见
```

三件事需要留意：

- **命名带前缀是为了不撞名。** 本地工具用方法名，远程实例用 manifest 名，MCP 用三段式。模型看到的名字就是最终名。
- **截断加哈希不是美观问题。** MCP 工具名可以很长（尤其自动生成的），不加哈希会在截断后撞名，而撞名在注册期是硬失败。
- **工具先经过平台再授权。** 不存在"MCP 直连模型"的旁路：所有调用都过平台的可见性、审批、掩码、事件。

### 审批默认全开

`approvalPolicy` 缺省回落 `ALL`：MCP 工具**默认每次调用都要人工确认**。

理由：MCP 工具的行为由外部 server 定义，平台无法静态判断它是不是写操作。默认全审批是"不知道就保守"，要放宽得由部署方明确改。放宽的粒度是连接级，不是工具级——这一点与本地工具（方法级 `approval`）不同。

工具级清单只用于**收缩可见范围**（`toolAllowlist`），不用于放宽审批。

---

## 4. 连接生命周期

```
启动 → McpCoordinator.init（后台线程）→ 逐个建连
每 30s → reconcile：拉起未连的、重新盖章工具目录、清掉已删连接
失败 → 冷却 60s 后再试
```

| 行为 | 细节 |
| --- | --- |
| 启动不阻塞 | 建连在后台，连不上不影响服务启动 |
| 重盖章 | 每轮 reconcile 都会重拉工具目录并 diff，工具增减会同步到注册表 |
| 域名变更 | 连接改名 = 旧工具全部下线 + 新工具上线 |
| 测试 | `POST /admin/mcp-connections/{name}/test` 手动触发一次连通性检查 |
| 试跑 | `POST /admin/mcp-connections/{name}/tools/call` 真实调用一个工具。**不是沙箱**：本地推导的副作用不是 READ 时必须显式带 `confirm=true`，每连接 60 秒最多 10 次，写操作留 AUDIT 日志；试跑不受工具白名单限制（白名单管的是"模型能不能看见"） |
| 事件 | `GET /admin/mcp-connections/{name}/events` 最近建连 / 调用 / 授权 / 撤销 / 配置变更，内存环按连接分桶保留 100 条，重启即清空；长期留痕看 AUDIT 日志 |

"重盖章"是这套机制里最容易被忽略的部分：MCP server 的工具有可能随版本变化，如果只在启动时桥接一次，server 升级后平台还挂着一批已经不存在的工具。

---

## 5. OAuth

支持程度：

| 项 | 支持 |
| --- | --- |
| 授权码 + PKCE（S256） | 是 |
| refresh_token 续期 | 是 |
| 动态客户端注册（DCR） | 是（`grant_types` = authorization_code / refresh_token） |
| client_credentials | 否 |

流程：

```
POST /admin/mcp-connections/{name}/oauth/start   → 返回授权地址
   （浏览器跳转、用户授权）
GET  /admin/mcp-connections/{name}/oauth/callback → 换 token 并落盘
DELETE /admin/mcp-connections/{name}/oauth/token  → 撤销本地凭据并按无凭据重连
```

回调地址必须与 MCP server 侧登记的一致，否则换 token 会失败。token 落盘位置随连接配置，`headers` 里的静态凭证与 OAuth token 二选一——同时配时以 OAuth 为准（运行期会刷新，静态头会过期）。

撤销只删本地 token，不调用授权服务器的撤销端点（RFC 7009 不是所有服务端都实现）：授权服务器侧的会话可能到它自己的过期时间前仍然有效。管控台只回显 token 元信息（是否有效 / 过期时间 / scope / 有无 refresh_token），凭据本体不进任何响应。

---

## 6. 与本地工具、远程实例的差别

| 维度 | 本地 Bean | 远程实例（tool-provider） | MCP |
| --- | --- | --- | --- |
| 注册时机 | 启动扫描 | 实例主动注册 + 心跳 | 后台建连 + 30s 对账 |
| 执行位置 | 本进程 | 宿主进程（HTTP 回调） | 外部 server |
| 审批默认 | `approval = NONE` | 未声明时按 `effect != READ` 推导 | `ALL` |
| 参数结构 | 由注解反射 | manifest 声明 | server 的 inputSchema |
| 可见域 | 注解 / binder | manifest 的 `domains` | 平台域绑定 |
| 掉线判定 | 不适用 | 35s 无心跳 | 调用失败 / reconcile 失败 |

参数结构这一行是主要差异：本地与远程实例都有"平台侧的类型信息"，能做校验和掩码；MCP 的参数模式来自外部 server，平台只做透传与掩码（按名字匹配），不做类型校验。

---

## 7. 常见故障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 工具列表空 | 连接没启用 / URL 不通 / allowlist 过滤掉了 | 看 `POST /{name}/test` 与服务端日志 |
| 工具名突然变了 | 连接改名，或 server 侧工具改名 | 改名是破坏性操作，历史记录里旧名会失效 |
| 调用报审批但页面没弹 | `approvalPolicy` 是 `ALL`，但前端没处理 INTERRUPT | 前端要渲染待审批清单 |
| OAuth 回调 400 | 回调地址与 server 侧登记不一致 | 核对 |
| 工具能列出但调不通 | 参数 schema 不匹配 / 超时太短 | 调 `callTimeoutMs`，看上游错误 |

---

## 8. 边界

- `LEGACY_SSE` 已实现但**没有对真实旧 server 验证**，生产接入前要自己压一遍。
- 不支持 MCP 的 resources / prompts / sampling，只接 tools。
- 工具目录变更会让历史会话里的旧工具名失效（工具名是模型调用与记录的主键）。
- MCP 侧的凭证由平台保管，但**平台不做凭证轮换提醒**——token 过期表现为调用失败，不是提前告警。
