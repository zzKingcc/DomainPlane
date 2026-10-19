# 接口信封对照表（P0 · 2026-10-19 静态扫描）

形态定义：**A** 只有 `code` · **B** `code` + `success` 双写 · **C** 只有 `success` · **D** 裸对象（无标识）

统计：A=15 · B=16 · C=11 · D=29

| 形态 | 方法 | 路径 | Java 方法 | 出处 |
|---|---|---|---|---|
| D | GET | `/admin/settings` | settings | `AdminController:139` |
| C | POST | `/admin/settings` | saveSettings | `AdminController:174` |
| C | DELETE | `/admin/settings/{kind}` | clearSettings | `AdminController:250` |
| C | POST | `/admin/settings/test` | testSettings | `AdminController:415` |
| C | POST | `/admin/models` | models | `AdminController:492` |
| D | GET | `/admin/tools` | tools | `AdminController:583` |
| D | GET | `/admin/domains` | domains | `AdminController:591` |
| D | GET | `/admin/instances` | instances | `AdminController:727` |
| C | POST | `/admin/instances/{instanceId}/mute` | muteInstance | `AdminController:790` |
| C | POST | `/admin/instances/{instanceId}/restore` | restoreInstance | `AdminController:808` |
| C | POST | `/admin/instances/{instanceId}/offline` | forceOfflineInstance | `AdminController:824` |
| A | GET | `/admin/kb/documents` | kbDocuments | `AdminController:867` |
| B | POST | `/admin/kb/documents` | kbUpload | `AdminController:895` |
| B | DELETE | `/admin/kb/documents/{docId}` | kbDelete | `AdminController:921` |
| A | GET | `/admin/kb/status` | kbStatus | `AdminController:935` |
| B | POST | `/admin/kb/rebuild` | rebuildKnowledgeIndex | `AdminController:948` |
| D | GET | `/admin/infra` | infra | `AdminController:979` |
| C | POST | `/admin/infra` | saveInfra | `AdminController:1020` |
| C | POST | `/admin/infra/test` | testInfra | `AdminController:1057` |
| D | GET | `/admin/prompts` | prompts | `AdminController:1139` |
| C | POST | `/admin/prompts` | savePrompts | `AdminController:1198` |
| D | POST | `/admin/domains` | create | `AdminDomainController:124` |
| D | PUT | `/admin/domains/{id}/callable` | setCallable | `AdminDomainController:149` |
| D | PUT | `/admin/domains/{id}/tool-call-echo` | setToolCallEcho | `AdminDomainController:178` |
| A | DELETE | `/admin/domains/{id}` | delete | `AdminDomainController:224` |
| A | GET | `/admin/mcp-connections` | list | `AdminMcpController:75` |
| B | POST | `/admin/mcp-connections` | save | `AdminMcpController:119` |
| B | DELETE | `/admin/mcp-connections` | delete | `AdminMcpController:186` |
| B | POST | `/admin/mcp-connections/{name}/toggle` | toggle | `AdminMcpController:208` |
| A | POST | `/admin/mcp-connections/{name}/test` | test | `AdminMcpController:237` |
| A | GET | `/admin/mcp-connections/{name}/tools` | tools | `AdminMcpController:267` |
| A | GET | `/admin/mcp-bindings` | bindings | `AdminMcpController:296` |
| B | PUT | `/admin/mcp-bindings/{domain}` | bindDomain | `AdminMcpController:315` |
| A | POST | `/admin/mcp-connections/{name}/oauth/start` | oauthStart | `AdminMcpController:342` |
| D | GET | `/admin/mcp-connections/{name}/oauth/callback` | oauthCallback | `AdminMcpController:431` |
| A | GET | `/admin/metrics` | metrics | `AdminMetricsController:32` |
| A | POST | `/admin/model-adapters/match` | match | `AdminModelAdapterController:53` |
| A | GET | `/admin/model-adapters` | list | `AdminModelAdapterController:87` |
| D | POST | `/admin/model-endpoints/image` | generateImage | `AdminModelEndpointController:54` |
| D | POST | `/admin/model-endpoints/rerank` | rerank | `AdminModelEndpointController:77` |
| D | POST | `/admin/model-endpoints/speech` | textToSpeech | `AdminModelEndpointController:102` |
| D | POST | `/admin/model-endpoints/transcribe` | transcribe | `AdminModelEndpointController:122` |
| D | POST | `/admin/model-endpoints/video` | createVideo | `AdminModelEndpointController:150` |
| A | POST | `/admin/model-endpoints/video/status` | videoStatus | `AdminModelEndpointController:164` |
| A | GET | `/admin/model-profiles` | list | `AdminModelProfileController:93` |
| D | POST | `/admin/model-profiles` | save | `AdminModelProfileController:140` |
| D | PUT | `/admin/model-profiles/tuning` | updateTuning | `AdminModelProfileController:237` |
| D | PUT | `/admin/model-profiles/return-thinking` | updateReturnThinking | `AdminModelProfileController:294` |
| D | PUT | `/admin/model-profiles/context-window` | updateContextWindow | `AdminModelProfileController:342` |
| D | DELETE | `/admin/model-profiles` | delete | `AdminModelProfileController:575` |
| B | POST | `/admin/model-profiles/test` | test | `AdminModelProfileController:601` |
| D | POST | `/admin/model-profiles/detect` | detect | `AdminModelProfileController:638` |
| C | POST | `/admin/model-profiles/detect-all` | detectAll | `AdminModelProfileController:672` |
| D | PUT | `/admin/model-bindings/{domain}` | bind | `AdminModelProfileController:726` |
| B | PUT | `/admin/embedding-model` | selectEmbeddingModel | `AdminModelProfileController:772` |
| B | POST | `/admin/embedding-model/rebuild` | rebuildKnowledge | `AdminModelProfileController:846` |
| B | POST | `/admin/embedding-model/purge` | purgeKnowledge | `AdminModelProfileController:870` |
| B | POST | `/admin/provider-connections/resolve` | resolve | `AdminProviderConnectionController:134` |
| B | POST | `/admin/init` | init | `AuthController:44` |
| B | POST | `/admin/login` | login | `AuthController:60` |
| D | POST | `/admin/logout` | logout | `AuthController:81` |
| D | GET | `/admin/session` | session | `AuthController:94` |
| B | POST | `/admin/password` | changePassword | `AuthController:102` |
| B | POST | `/api/agent/login` | agentLogin | `AuthController:129` |
| A | GET | `/api/agent/domains` | list | `DomainController:46` |
| A | GET | `/health` | health | `HealthController:28` |
| D | GET | `/api/agent/health` | health | `ServerAgentController:45` |
| D | POST | `/api/agent/chat` | chat | `ServerAgentController:70` |
| D | POST | `/api/agent/resume` | resume | `ServerAgentController:127` |
| D | POST | `/api/agent/stop/{sessionId}` | stop | `ServerAgentController:146` |
| D | POST | `/api/agent/tools/register` | register | `ToolRegistrationController:52` |

> 判定依据是方法体内是否出现 `put("code"` / `put("success"`。
> 一个方法里出现多种返回分支（成功走 success、失败走 code）时，本表取「出现了哪些键」的并集，
> 实际分支差异要在 P2 逐个复核。