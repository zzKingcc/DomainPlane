# 页面 → 接口调用清单（P0 · 2026-10-19）

| 文件 | 调用的接口（方法 路径） |
|---|---|
| `account.html` | `request /admin/session`<br>`jsonRequest /admin/password` |
| `domains.html` | `jsonRequest /admin/domains/`<br>`jsonRequest /admin/model-bindings/`<br>`jsonRequest /admin/mcp-bindings/`<br>`jsonRequest /admin/domains`<br>`request /admin/model-profiles`<br>`request /admin/mcp-bindings`<br>`request /admin/mcp-connections`<br>`request /admin/domains` |
| `infra.html` | `request /admin/infra`<br>`jsonRequest /admin/infra/test?type=`<br>`jsonRequest /admin/infra` |
| `instances.html` | `request /admin/instances`<br>`jsonRequest /admin/instances/` |
| `knowledge.html` | `request /admin/domains`<br>`request /admin/settings`<br>`request /admin/kb/documents`<br>`request /admin/kb/documents/`<br>`request /admin/kb/rebuild` |
| `login.html` | `request /admin/session` |
| `mcp.html` | `request /admin/mcp-bindings`<br>`request /admin/domains`<br>`request /admin/mcp-connections`<br>`jsonRequest /admin/mcp-connections`<br>`jsonRequest /admin/mcp-connections/`<br>`jsonRequest /admin/mcp-connections?name=`<br>`request /admin/mcp-connections/`<br>`jsonRequest /admin/mcp-bindings/` |
| `models.html` | `request /admin/settings`<br>`request /admin/model-profiles`<br>`jsonRequest /admin/embedding-model`<br>`jsonRequest /admin/provider-presets`<br>`jsonRequest /admin/provider-connections/resolve?name=`<br>`jsonRequest /admin/models?type=chat`<br>`jsonRequest /admin/model-adapters/match`<br>`jsonRequest /admin/model-profiles/tuning`<br>`jsonRequest /admin/model-profiles/return-thinking`<br>`jsonRequest /admin/model-profiles/context-window`<br>`jsonRequest /admin/model-profiles`<br>`jsonRequest /admin/model-profiles?alias=`<br>`jsonRequest /admin/model-profiles/detect?alias=`<br>`jsonRequest /admin/provider-connections`<br>`jsonRequest /admin/provider-connections?name=` |
| `overview.html` | `request /admin/domains` |
| `prompts.html` | `request /admin/prompts`<br>`jsonRequest /admin/prompts` |

> 只收字面量路径；`Console.request(someVar)` 这类变量调用未计入。