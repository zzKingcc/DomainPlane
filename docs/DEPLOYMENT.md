# 部署与运维手册

面向部署方运维。设计与接口分别见 `DESIGN.md`、`API.md`。

---

## 1 部署形态与前置

| 项 | 要求 |
| --- | --- |
| 运行时 | JDK 21+（镜像已内置 JRE 21） |
| 端口 | 默认 `9527`，可用 `SERVER_PORT` 环境变量 / `-Dserver.port=` / `--server.port=` 覆盖 |
| 实例数 | **单实例**（见 §6） |
| 依赖 | Elasticsearch（9.x 已验证 / 8.x 可用 / 更低需自验，需 IK 分词器）、Redis 6+、一个 OpenAI 兼容模型服务（对话 + 向量） |
| 存储 | ES / Redis **可与业务共用**：Redis key 统一 `domainplane:` 前缀、ES 索引统一 `domainplane_` 前缀，连接各自独立 |

**依赖不是启动前置**：ES / Redis / 模型全都没配也能启动（知识库索引都是按需创建的，启动期无事可做），进去填完即生效。
这是刻意设计——否则会形成"起不来 → 管控台打不开 → 配置填不上"的死锁。

## 2 落盘与配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `domainplane.settings.path` | `/var/lib/domainplane/config` | 账号、模型设置、存储连接、域提示词 |
| `domainplane.logging.path` | `/var/log/domainplane` | 只在开启文件日志时使用 |
| `domainplane.export.path` | `/var/lib/domainplane/chunks` | 切片预览 txt（上传后每文档一个，供人工核对切点） |
| `domainplane.logging.level` | `INFO` | 根日志级别 |

- 三个目录都是**服务器绝对路径**，不随"从哪个目录启动"漂移；目录不存在时由写入方自动创建。
- 可用环境变量 `DOMAINPLANE_SETTINGS_PATH` / `DOMAINPLANE_LOG_PATH` / `DOMAINPLANE_EXPORT_PATH` 覆盖。
- **本机开发务必覆盖 `DOMAINPLANE_SETTINGS_PATH`**，否则会写到本盘根目录下的 `var/lib/...`。
- 落盘的四个 json：`accounts.json`（账号，BCrypt 哈希）、`llm-settings.json`（模型与 Key）、
  `infra-settings.json`（ES / Redis 连接）、`prompts.json`（域提示词）。

### 2.1 备份与恢复

**要备份的只有 `domainplane.settings.path` 这一个目录**——它是唯一状态，工具注册与域都不落盘。

```bash
# 备份（配置很小，直接打包即可；里面有 API Key，注意存放权限）
tar czf domainplane-config-$(date +%F).tgz -C /var/lib/domainplane config

# 恢复：停服务 → 覆盖目录 → 启服务（权限要与运行用户一致）
```

| 场景 | 处置 |
| --- | --- |
| 配置目录整体丢失 | 用最近备份恢复；无备份则回落种子账号，模型与存储配置需重填 |
| 只是忘了密码 | 删 `config/accounts.json` 后重启 → 回落 `domainplane / domainplane`（这是唯一的找回路径） |
| 账号文件读不出来（`10007`） | 服务端会拒绝一切受保护请求且**不开放初始化入口**；修好或删除该文件后重启 |

## 3 裸机部署（systemd）

```ini
# /etc/systemd/system/domainplane.service
[Unit]
Description=DomainPlane server
After=network-online.target

[Service]
User=domainplane
# 工作目录不影响落盘位置（默认已是绝对路径），显式写上便于排障
WorkingDirectory=/opt/domainplane
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai \
          -jar /opt/domainplane/domainplane-server.jar
# 需要文件日志时才加：--logging.config=classpath:logback-file.xml
Restart=on-failure
SuccessExitStatus=143        # SIGTERM 优雅退出

[Install]
WantedBy=multi-user.target
```

```bash
# 首次部署
sudo useradd -r -s /sbin/nologin domainplane
sudo install -d -o domainplane -g domainplane /opt/domainplane /var/lib/domainplane/config /var/lib/domainplane/chunks /var/log/domainplane
sudo install -o domainplane -g domainplane -m 644 domainplane-server.jar /opt/domainplane/
sudo systemctl daemon-reload && sudo systemctl enable --now domainplane
```

## 4 容器部署

```bash
mvn -o -pl domainplane-server -am -DskipTests package          # 宿主先构建，不联网
cp domainplane-server/target/domainplane-v1.0-beta.1.jar . # 放到与 Dockerfile 同目录（构建上下文根）
docker build -t domainplane-server:v1.0-beta.1 .
docker run -d --name domainplane -p 9527:9527 \
  -v domainplane-config:/var/lib/domainplane/config \
  -v domainplane-chunks:/var/lib/domainplane/chunks \
  domainplane-server:v1.0-beta.1
docker logs -f domainplane
```
# 若只拿到 jar（不在仓库根）：把 domainplane-v1.0-beta.1.jar 与 Dockerfile 放同目录，直接 docker build 即可。

- **配置目录必须挂卷**（用命名卷，别 bind mount 到宿主目录——uid 不匹配会写不进去）。
- 日志默认走 stdout，`docker logs` 直接可用；要落盘再加
  `--logging.config=classpath:logback-file.xml` 并挂 `/var/log/domainplane`。
- 一套完整环境（含 Redis 与 ES）用仓库自带的 `docker-compose.yml`。

### 4.1 K8s 探针

```yaml
livenessProbe:
  httpGet: { path: /health, port: 9527 }
  initialDelaySeconds: 30
  periodSeconds: 30
readinessProbe:
  httpGet: { path: /health, port: 9527 }
```

`/health` 只表示"进程能对外服务"，**不检查 ES / Redis / 模型**：未配置是合法初始态，
把依赖纳入判定会让新部署的实例被判不健康而反复重启。`replicas` 只能填 `1`（见 §6）。

## 5 日志与审计

| 通道 | 位置 | 何时有 |
| --- | --- | --- |
| 控制台 | stdout | 始终（默认形态） |
| 全量日志 | `${domainplane.logging.path}/domainplane-server.log` | 开启文件形态后 |
| 错误日志 | 同上 `-error.log`（仅 ERROR，保留 60 天） | 同上 |
| **审计** | 同上 `-audit.log`（保留 180 天） | 同上 |

- 审计 logger 名为 `AUDIT`，格式 `action=… operator=… result=… ip=…`，**只记变更类请求**
  （`/admin/**` 的非 GET/HEAD/OPTIONS 与工具实例注册）。
- 容器/采集侧可按 logger 名分流：把 `logger=AUDIT` 送进独立的长期索引，其余按常规保留期处理。
- 日志路径与级别、脱敏红线、第三方降噪清单见 `DESIGN.md` 第 13 节。

## 6 单实例契约与扩容

**当前版本只支持单实例部署**，原因不在"能不能起两个进程"，而在状态：

- **会话协调状态全部在进程内存** —— 会话占用闸门、停止标志、流注册表都是 `ConcurrentHashMap`，
  没有跨实例协调。两个实例共用一个 Redis 时，**同一会话打到两边，两边的"占用"闸门都会通过** →
  并发执行 → 记忆丢更新、断点互相覆盖；
- **`stop` 可能停错实例** —— 停止标志设在收到请求的那个实例，真正在执行的那个看不到它，
  任务会跑完并写进记忆，而接口返回 `stopRequested: true`（**用户以为停了，其实没停**）；
- 工具注册表在进程内存 —— 两个实例各自维护一份，外部实例必须向**每一个**实例注册；
- 知识库导入是进程内串行锁 —— 多实例之间不互斥，会出现同名文档并发写入；
- 判死扫描是进程内定时器 —— 各扫各的，实例视图会分叉。

这类失败的共同点是**不报错**：没有异常、没有告警、日志上几乎看不出痕迹，
等发现时用户数据已经串了。因此启动时会做一次**单实例守卫** ——
在 Redis 上以 `SET NX` 抢一个带 TTL（90s）的运行权租约，抢不到就直接拒绝启动，
并提示只保留一个实例。优雅停机时会释放租约，因此正常重启不会被自己的租约挡住。

> 确实需要临时绕过（例如正把旧实例的租约等它自然过期）时：
> `domainplane.single-instance-guard=false`。**绕过之前请先确认 Redis 上真的只有一个 DomainPlane 在跑。**

因此：**纵向扩容（加 CPU / 内存 / 提高线程池）是当前唯一受支持的扩容方式**。
K8s 里 `replicas` 固定 `1`；滚动升级会短暂中断服务（每次只有一个实例，没有冗余）。
注意：由于没有冗余实例，滚动升级**不是**无损操作，升级期间服务不可用。

## 8 容量与限流现状

- **已实现**：编排线程池满时拒绝并回报 `SYSTEM_BUSY(20002)`；同一 `sessionId` 并发直接拒绝 `SESSION_BUSY(30003)`；
  会话记忆到上限拒绝新一轮 `SESSION_MEMORY_FULL(30004)`（**换 `sessionId`**，平台不代为切换）。
- **未实现**：入口限流（`RATE_LIMITED 20000`）、大模型侧限流映射（`LLM_RATE_LIMITED 20001`）、
  并发会话数上限（`CONCURRENT_LIMIT 20003`）—— 这三个码已定义但代码中不会发出。
- 什么情况下真的需要入口限流：**多调用方共用一套服务端、且单轮对话可能长时间占用编排线程**时。
  单租户自用、调用方可控的场景，靠线程池拒绝（`SYSTEM_BUSY`）已经能兜住，不必急着上。
  需要对外暴露且无法约束调用方并发时再补，届时优先做"按调用方维度的并发闸门"，而不是简单 QPS 阈值。

### 8.1 Redis 容量的硬要求（记忆是长期存储）

会话记忆**默认不过期**（`domainplane.memory.ttl=null`），到上限后只拒绝写入、不回收数据。所以：

- **必须**给 Redis 设 `maxmemory-policy noeviction` —— 否则内存紧张时 Redis 直接淘汰记忆 key = 随机丢用户历史；
- 但 `noeviction` 下写满会导致**写入失败**（对话报错，`70002`），因此 `maxmemory` 必须按容量预估配置，
  并对 `used_memory / maxmemory` 做 **80% 告警**（这是可用性前提，不是优化项）；
- 量级参考：单会话上限约 **60–120 KB**（100 条 / 30k token，JSON 序列化后）；**1 万个封顶会话 ≈ 0.6–1.2 GB**；
- **必须**开持久化：`appendonly yes` + `appendfsync everysec`（最坏丢 1 秒）。启动期自检会检查这两项并告警
  （`RedisPersistenceAudit`，只提醒不阻断；受管 Redis 禁用 `CONFIG` 时跳过）。
- 归档/清理目前**没有自动机制**：封顶会话的数据要清只能手工 `DEL domainplane:chat:memory:*`（或按需自建清理任务）。

## 9 故障排查

| 现象 | 先看什么 |
| --- | --- |
| `/admin/**` 全 401，但登录成功 | 是否从服务端地址（9527）打开：凭证是 Cookie，跨站不携带 |
| 启动横幅显示"未配置" | 正常初始态；去管控台「模型设置」/「存储配置」填写 |
| 调用返回 `90005` | 依赖未配置（不可重试）→ 去填；返回 `90004` 则是配了但连不上（真故障） |
| 中文检索效果差但不报错 | 很可能缺 IK 分词器：ES 会**静默降级**成默认分词器，索引照建、灌库照成功。用「存储配置」页的测试连接看 IK 探测结果 |
| 换了向量模型后灌库被拒 | 向量维度三处不同源（测试 / 运行时 / 索引）。改维度后必须到「知识库」页「触发重建」（会清空内容，需重新上传） |
| 某域检索为空但知识库有文档 | 文档上传到了别的域。一域一索引：检索只查「该域 + 祖先域」链上的索引 |
| 工具"看不到" | 请求带的 `profile` 与工具声明的 `domains` 不一致；域不存在报 `10004`，域存在但不是可调用单元（父域只做装配）报 `10010` |
| 工具实例显示已下线 | 心跳超 35s 未到（≈心跳周期 5s×7）；查实例网络与 `domainplane.instance.*` 配置 |
| 审批后点了同意却说会话不存在 | 该会话的待审批断点被清理过（例如中途发起过新的 `chat` —— 那会被视为"用户拒绝"，断点即作废）；重新发起一轮 |
| 某会话突然一直报 `30004` | 该会话记忆已达上限（100 条 / 30k token ≈ 50 轮）。这是**永久**的：记忆只增不淘汰，**换一个 `sessionId`** 才能继续（平台不代为切换） |
| 重启后用户历史全没了 | Redis 没开持久化（`RedisPersistenceAudit` 启动时会告警）。记忆是唯一存储，必须 `appendonly yes`（+ `appendfsync everysec`） |
| 部分会话记忆莫名消失 | Redis 用了会淘汰 key 的策略（如 `allkeys-lru`）：`maxmemory-policy` 必须是 `noeviction` |
| 磁盘增长快 | 检查日志总量上限（全量 2GB / 错误 1GB / 审计 1GB）与保留天数 |

## 10 安全边界（登记备查，待安全专项）

以下项在当前版本按约定**只登记不处理**，部署前需自行评估：

- 配置目录内的凭据（模型 API Key、ES / Redis 口令）为明文 JSON；
- `accounts.json` 可读即等价于可伪造凭证 —— 安全边界落在文件系统权限上；
- 登录不限流、不锁定；
- 跨域为全放行（`allowedOriginPatterns("*")` + `allowCredentials(true)`）；
- 工具实例反向调用端点 `/domainplane/invoke` 不带凭证，属客户端侧信任边界。
