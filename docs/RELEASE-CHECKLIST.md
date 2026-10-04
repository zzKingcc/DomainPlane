# 上线检查清单

两段：**发布前**（一次）与**部署后冒烟**（每个环境一次，约 15 分钟）。
命令里的地址按实际替换，默认 `http://localhost:9527`。

---

## 一、发布前（构建与产物）

- [ ] `mvn -o clean test` 通过（当前 25 个用例；`-o` 离线可跑）
- [ ] `mvn -o -pl domainplane-server -am -DskipTests package` 产出
      `domainplane-server/target/domainplane-v1.0-beta.1.jar`，**体积约 58MB**
      （若明显变大，先看是不是又把 `langchain4j-easy-rag` 或 lombok 带进来了：
      前者会经 Tika / onnxruntime 多出约 190MB，后者约 2MB）
- [ ] 版本号已更新且**三处一致**：根 `pom.xml`、各模块 `<parent>` 版本、`CHANGELOG.md`
      （接口回报的版本来自构建信息，无需改代码）
- [ ] `CHANGELOG.md` 写明本次变化与**升级注意事项**（是否要重建索引、配置是否兼容）
- [ ] `THIRD-PARTY-LICENSES.md` 与本次依赖变更对齐
- [ ] 容器交付：`docker build` 成功，`docker run` 后 `docker logs` 能看到启动横幅

## 二、部署后冒烟

### 1 进程与探针（1 分钟）

- [ ] `curl -s http://localhost:9527/health` → `{"code":0,"status":"UP",...,"version":"v1.0-beta.1"}`
      **不带任何凭证**（这是探针能工作的前提）
- [ ] 启动横幅信息自检：端口、工具数、ES / Redis 状态、**日志文件状态与开启命令**、账号状态
- [ ] K8s：`kubectl get pod` 的 READY 为 `1/1`；`replicas` 为 `1`

### 2 鉴权（2 分钟）

- [ ] 未登录访问 `/admin/settings` → 401 且响应体 `code=10002`
- [ ] 管控台登录（默认 `domainplane / domainplane`）成功；**从服务端地址打开**
- [ ] 侧栏「账号」页改密码 → 提示重新登录 → 用新密码登录成功
- [ ] 用旧凭证调 `/api/agent/health` → 401（改密码应使全部旧凭证失效）

### 3 管控台八页（3 分钟）

逐页打开，确认无报错、无空白、控制台无 404：

- [ ] 概览：工具数 / 域数量显示正常（0 时显示"未注册"而不是报错）
- [ ] 模型设置：两卡状态徽标正确；「测试连接」有结论文案
- [ ] 存储配置：「测试连接」返回 ES 版本档位与 IK 探测结果、Redis 拓扑
- [ ] 域空间：工具明细与统计条正常
- [ ] 在线实例：实例列表与状态
- [ ] 提示词设定：各域片段可编辑、预览显示沿链拼接全文（标出"拼接自"哪几段）
- [ ] 知识库：文档列表 / 上传 / 重建三个卡片
- [ ] 账号：账号信息与改密码入口

### 4 对话主链路（5 分钟）

- [ ] `POST /api/agent/chat`（带 `profile`）返回 SSE，能收到 `TOKEN` … `DONE`
- [ ] 触发一个**只读工具**：事件流出现 `TOOL_CALL` 与 `TOOL_RESULT`
- [ ] 触发一个**需审批工具**：出现 `INTERRUPT`；带同一 `profile` 调 `resume(approved=true)` 后继续到 `DONE`
- [ ] 审批拒绝路径：`resume(approved=false)` 后模型能看到拒绝并给出合理回复
- [ ] `POST /api/agent/stop/{sessionId}`：进行中的一轮收到 `STOPPED`
- [ ] 同一 `sessionId` 并发发两轮 → 第二个被拒（`30003`），不排队
- [ ] 带一个**不存在的域** → `10004`，且没有退回全量工具
- [ ] 带一个**存在但不可调用**的域（有子域的装配节点）→ `10010`，文案带出当前可调用集合
- [ ] 同一 `sessionId` 在两个不同域各跑一轮 → 记忆 / 断点 / 停止互不影响（域内唯一）
- [ ] **挂起未批就开新对话**：出现 `INTERRUPT` 后不调 `resume`，直接发新的 `chat` → **不被拒**（不再是 `30002`），
      该轮正常处理；随后再调 `resume` → 那个待授权动作**已作废**（断点已删，报 `30001`）
- [ ] **会话封顶**：把 `max-messages` 临时调小（如 4）跑满 → 再发一轮报 `30004 SESSION_MEMORY_FULL`，
      文案含当前条数与"更换 sessionId"；**换一个新 `sessionId` 立即恢复正常**；沿用旧 id 重试仍报 `30004`（粘性）
- [ ] 封顶拒绝**不调模型**：`GET /admin/metrics` 的 `chatRequests` 之外无工具/模型调用增长，且响应很快返回
- [ ] 启动日志能看到 Redis 自检结论（未开持久化 / 淘汰策略非 `noeviction` 时应告警但不阻断启动）
- [ ] `GET /admin/metrics`：`chatRequests` / `toolCalls` 随上面操作增长

### 5 知识库（3 分钟）

- [ ] 上传一篇 `.md`（用 `一、二、三、` 章节标题），**归属域**选一个子域 → 返回 `docId` 与切片数
- [ ] ES 里出现该域的索引 `domainplane_kb_<域>_<哈希>`；列表的「归属域」列显示正确
- [ ] 同名再传（同一域）→ 被拒（`60005`）；带 `replace=true` → 覆盖成功
- [ ] 提问一个只在文档里有的问题（用该域对话）→ 回答引用了来源
- [ ] 换**兄弟域**提问同一个问题 → 查不到（一域一索引，互不可见）
- [ ] 挂到父域的文档，**子域**能检索到（累加语义）
- [ ] 删除文档 → 列表消失，且同名可以再次上传
- [ ] 触发重建 → 返回 `indices` 与 `dimensions`；重建后列表为空（需重新上传）

### 6 工具实例（2 分钟）

- [ ] 启动一个工具实例 → `GET /admin/instances` 出现该实例、状态 `ONLINE`
- [ ] 停掉实例 → 约 40s（35s 判死窗 + 5s 扫描间隔）后状态变为已下线，其工具从「域空间」消失
- [ ] 重新启动实例 → 自动回到 `ONLINE`
- [ ] 强制下线某实例 → 响应含 `removedToolCount`；该实例再次心跳被拒（410）

### 7 运行时行为（1 分钟）

- [ ] `docker logs` / journalctl 里能看到审计行（`action=… operator=… result=… ip=…`）
- [ ] `systemctl restart domainplane`（或滚动重启）：在途 SSE **不被硬切**（优雅停机生效）
- [ ] 审计与环境一致性：改一次模型设置，审计里出现对应 `POST /admin/settings`

## 三、已知不覆盖项（本次发布明确不做）

- 多实例/集群：本版本单实例（见 `DEPLOYMENT.md` §6）
- 入口限流与并发上限：未实现（`20000` / `20001` / `20003` 不会发出）
- 指标无历史：`/admin/metrics` 为进程内快照，重启归零
