# 管控台页面清单（P0 · 2026-10-19 静态扫描）

| 文件 | URL | 标题 | 关键 DOM 锚点（前 8 个） |
|---|---|---|---|
| `account.html` | `/console/account.html` | 账号 · DomainPlane 管控台 | `#sidebar` `#acName` `#acLastAt` `#acLastFrom` `#acPwd` `#oldPassword` `#newPassword` `#newPassword2` |
| `domains.html` | `/console/domains.html` | 域空间 · DomainPlane 管控台 | `#sidebar` `#domainCount` `#newDomain` `#newDomainCallable` `#createDomainBtn` `#refreshBtn` `#domainList` `#bindModal` |
| `infra.html` | `/console/infra.html` | 存储配置 · DomainPlane 管控台 | `#sidebar` `#reloadBtn` `#infraStatus` `#card-es` `#esBadge` `#esDirty` `#esHost` `#esPort` |
| `instances.html` | `/console/instances.html` | 熔断工具调用 · DomainPlane 管控台 | `#sidebar` `#statOnline` `#statOnlineNote` `#statReplica` `#statReplicaNote` `#statTools` `#statToolsNote` `#statTimeout` |
| `knowledge.html` | `/console/knowledge.html` | 知识库 · DomainPlane 管控台 | `#sidebar` `#embStatus` `#fileInput` `#uploadBtn` `#replaceChk` `#domainSelect` `#uploadResult` `#docList` |
| `login.html` | `/console/login.html` | 登录 · DomainPlane 管控台 | `#modeSub` `#loginForm` `#confirmField` |
| `mcp.html` | `/console/mcp.html` | MCP 连接 · DomainPlane 管控台 | `#sidebar` `#connCount` `#createBtn` `#refreshBtn` `#connList` `#editModal` `#editTitle` `#editClose` |
| `models.html` | `/console/models.html` | 模型设置 · DomainPlane 管控台 | `#sidebar` `#openConnBtn` `#addModelBtn` `#settingsFileHint` `#embeddingCount` `#embeddingList` `#modelCount` `#modelList` |
| `overview.html` | `/console/overview.html` | 概览 · DomainPlane 管控台 | `#sidebar` `#statChat` `#statChatNote` `#statEmb` `#statEmbNote` `#statTools` `#statToolsNote` `#statDomains` |
| `prompts.html` | `/console/prompts.html` | 提示词设定 · DomainPlane 管控台 | `#sidebar` `#orphanBox` `#refreshBtn` `#domainBody` `#editorCard` `#edDomain` `#edTools` `#domainText` |

> 锚点取自页面里的静态 `id`；由 JS 动态生成的节点不在表内，P3 冒烟需要时另补。