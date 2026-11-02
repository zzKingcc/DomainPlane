<!-- meta
title: DomainPlane 代码审计报告
brand: 审计
short: 代码审计报告
subtitle: 安全 / 质量 / 并发 / 框架坑 / 依赖，23 项问题按 P0-P3 分级
kicker: Audit · Java Code Audit
chip1: 对象 · DomainPlane v1.0-beta.2（升级后工作区）
chip2: 规模 · 371 主类 / 4.96 万行 / 15 Controller
chip3: 基线 · 1,230 例测试全绿（实跑）
conclusion: 没有 P0——SQL 注入、ES 脚本注入、模板注入、反序列化 RCE 的经典入口在这份代码里基本被堵住了（ES 查询全参数化、索引名双重清洗、Jackson 无多态默认类型）。但有 5 项 P1 值得在对外暴露实例前处理，其中两项是同一个根因的两面：「默认口令 + 登录无限流」让任何能连上 9527 端口的人拿到完整 API 权限；而拿到凭证之后，远程工具注册端点是一个零校验、有回显的 SSRF。第三项与部署前提强相关：检查点用 Java 原生反序列化，Redis 不可信即等于 RCE。
-->

## 审计概览

| 项目 | 内容 |
| --- | --- |
| 审计对象 | `D:\java\zishuati\DomainPlane`（11 个 Maven 模块，含未提交的 L1-L4 升级） |
| 技术栈 | Java 21 · Spring Boot 3.5.7 · LangChain4j 1.18.1 · LangGraph4j 1.8.17 · Elasticsearch 9.4.4 · Redis(Lettuce) · POI 5.4.1 · PDFBox 3.0.8 · Jackson · Reactor |
| 审计维度 | 安全 / 代码质量 / 性能与并发 / 框架坑 / 依赖 |
| 审计时间 | 2026-10-29 |
| 审计方式 | 静态模式扫描 + 人工语义审计（逐条跟数据流判定可利用性）+ 全量构建验证 |
| 构建基线 | `mvn -o test`：12 个模块全部 SUCCESS，1,230 例测试 0 失败 / 2 跳过，耗时 4 分 27 秒 |

### 风险统计

| 级别 | 数量 | 说明 |
| --- | --- | --- |
| P0 严重 | 0 | 未发现可直接 RCE / 未授权数据读取的路径（前置条件均为"已持有凭证"） |
| P1 高危 | 5 | **S-02 / S-05 已于 2026-10-29 修复**（见第五节「修复记录」），剩余 3 项待在对外暴露前处理 |
| P2 中危 | 10 | 建议本迭代修复 |
| P3 低危 | 8 | 可排期优化 |

### 高风险项摘要

1. **S-01 默认口令 + 登录无限制（P1）**：`accounts-seed.json` 里明文写着 `domainplane/domainplane`，首启直接落盘；登录接口无失败计数、无退避、无 IP 限流。更关键的是"默认口令只拦变更类请求"这条保护**只对 Cookie 载具生效**，`/api/agent/**`（含工具注册、知识库上传/删除）用请求头载具时完全不拦。
2. **S-02 远程工具注册 = 有回显的 SSRF（P1）**：`POST /api/agent/tools/register` 的 `endpoint` 无任何白名单（无 scheme 校验、无内网/回环拦截、不限制重定向），服务端随后用 JDK HttpClient 直连并把响应体解析后回喂模型/调用方。
3. **S-03 检查点用 Java 原生反序列化（P1）**：`RedisCheckpointSaver` 用 `ObjectInputStream` 读 base64，键可由 `(域, sessionId)` 推出且 sessionId 由调用方给。能写 Redis 的人等于能构造 gadget 链。
4. **S-04 凭证永久有效（P1）**：签名凭证无 `exp`、无 `jti`、`/admin/logout` 只清 Cookie 不作废。唯一吊销手段是改密码（靠"密钥派生自密码哈希"实现）。
5. **S-05 未初始化时全站 fail-open（P1）**：账号文件不存在时 `/api/agent/**` 与 `/admin/**` 全部放行，且 `/admin/init` 免鉴权——任何人可抢注管理员账号。

## 总体结论

**代码质量高于同类项目均值，安全性受"单机 + 管理型产品"的定位约束。** 三件事做得比大多数项目好：ES/Qdrant 查询全参数化（无脚本拼接、无字段名注入）、索引名与域路径双重字符集校验、错误出口统一且区分用户文案与运维文案。真正的问题集中在**信任边界**上：整套设计默认"能访问端口的人就是部署方"，而默认口令把这个假设变成了现实风险；一旦越过这条线，二线问题（SSRF、反序列化）才开始计分。

建议：`S-01` 与 `S-02` 在对外暴露前必修；`S-03` 取决于部署形态（Redis 是否强制密码 + 仅监听内网），若否应视为 P0；其余按第四节排期。

## 问题明细

### S-01（P1）默认口令 + 登录无限流，且保护只覆盖 Cookie 载具

- **位置**：`domainplane-server/src/main/resources/accounts-seed.json:4-5`、`server/auth/AuthService.java:127-148`、`server/auth/CredentialAuthInterceptor.java:117-131`
- **问题描述**：种子账号以明文 `domainplane/domainplane` 随应用打包，首启由 `AccountStore.ensureInitialized` 落盘为 BCrypt 哈希；`login()` 除 BCrypt 校验外没有任何失败计数、锁定、退避，也没有 IP 维度限流。`CredentialAuthInterceptor.mustChangeDefaultCredential()` 在 `carrier != Carrier.COOKIE` 时直接 `return false`，即：默认口令状态下，走请求头载具的 `/api/agent/**` 全部放行（对话、工具注册、知识库上传与删除）。
- **风险说明**：任何能访问 9527 端口的人在部署方没改密码的窗口期内即可获得完整 API 权限——不是"只读"，可以注册远程工具（见 S-02）、上传/删除知识库、发起对话消耗模型额度。BCrypt cost=10 约 50-100ms 一次，单线程约 10-20 次/秒，口令是 11 字符常见词，字典攻击成本极低。
- **修复建议**：
  ```java
  // 1) 登录失败退避（进程内即可，单实例部署成立）
  private final Map<String, FailWindow> fails = new ConcurrentHashMap<>();
  private void assertNotThrottled(String username, String from) {
      FailWindow w = fails.get(username + "|" + from);
      if (w != null && w.count >= 5 && System.currentTimeMillis() < w.until) {
          throw new AuthException(ErrorCode.AUTH_FAILED, "失败次数过多，请稍后再试");
      }
  }
  // 2) 默认口令闸门覆盖两种载具：HEADER 载体也拦变更类请求，只放行 /api/agent/login
  //    /api/agent/health（程序化调用无法"改密码"，但也不该在默认口令下获得写权限）
  ```
  另建议容器启动时若仍为默认口令则只绑定 `127.0.0.1`（可配置项 `domainplane.auth.default-credential-localhost-only: true`）。
- **参考**：CWE-1188（Insecure Default）、CWE-307（Improper Restriction of Excessive Authentication Attempts）、OWASP A07:2021

### S-02（P1，已修复）远程工具注册端点是零校验、有回显的 SSRF

- **位置**：`server/tool/ToolManifest.java:71-73`（只判非空）、`server/tool/RemoteToolExecutor.java:115-120`、`server/controller/ToolRegistrationController.java`
- **问题描述**：注册接口接收 `endpoint` 字符串，仅校验非空；调用时直接 `HttpRequest.newBuilder(URI.create(endpoint))` 发出 POST，响应体被 `mapper.readTree` 解析后，`result` 分片渲染成工具结果回喂模型、`error` 文本直接回给调用方。

  ```java
  // RemoteToolExecutor.java:115
  httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
          .timeout(Duration.ofMillis(timeoutMillis))
          .header("Content-Type", "application/json; charset=UTF-8")
          .POST(HttpRequest.BodyPublishers.ofString(...))
          .build();
  ```
- **风险说明**：可把服务端当内网 HTTP 客户端用（云元数据、内网管理台、Redis/ES 的 HTTP 接口），且**有回显**：只要目标返回 JSON 且含 `success/result` 字段，内容会进模型上下文与 SSE 事件；即便是非 JSON 响应，`abbreviate(response.body())` 也会把前若干字节写进错误信息。与 S-01 叠加后，未改密码的实例等于对外开了一个带鉴权的内网代理。
- **修复建议**：
  ```java
  // 注册期：只允许 http/https、禁止凭据与内网地址
  private static void validateEndpoint(String endpoint) {
      URI u = URI.create(endpoint);
      if (u.getScheme() == null || !Set.of("http", "https").contains(u.getScheme().toLowerCase()))
          throw new DomainPlaneException(ErrorCode.INVALID_PARAMETER, "endpoint 必须是 http/https");
      InetAddress addr = InetAddress.getByName(u.getHost());
      if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
              || addr.isAnyLocalAddress())
          throw new DomainPlaneException(ErrorCode.INVALID_PARAMETER, "endpoint 不允许指向内网地址");
  }
  // 调用期：HttpClient 用 Redirect.NEVER，并把超时压到实例超时以内（已有）
  ```
  若确需支持内网工具实例（这是产品的正常用法），则把该限制改成部署级白名单 `domainplane.tool.endpoint-allowlist`，默认空 = 只能同机。
- **参考**：CWE-918（SSRF）、OWASP A10:2021

### S-03（P1）检查点使用 Java 原生反序列化

- **位置**：`domainplane-infrastructure/.../redis/checkpoint/RedisCheckpointSaver.java:246`、`:277`
- **问题描述**：断点整份列表经 `ObjectOutputStream` 序列化、base64 后存 Redis；读取侧 `new ObjectInputStream(new ByteArrayInputStream(bytes))` 直接反序列化。异常只被包装成 `CheckpointCorruptedException`，不构成防护。

  ```java
  try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
      return checkpointListSerializer.read(ois);
  } catch (Exception e) {
      throw corrupt(key, e);   // 抛异常 ≠ 防护：对象图在 read 时已经走完了
  }
  ```
- **风险说明**：能写 Redis（弱口令/无口令、SSRF 打 Redis、共享实例）即可投递 gadget 链达成 RCE。键形如 `domainplane:graph:checkpoint:{域}|{sessionId}`，`sessionId` 由调用方传入，不需要猜键就能预置载荷，等正常会话读取时触发。
- **修复建议**：优先换成有类型约束的序列化（Jackson + 显式类型白名单，`Checkpoint` 家族是有限闭集）；若 langgraph4j 的 `CheckpointListSerializer` 只能走 Java 序列化，则退一步加 `ObjectInputFilter`：
  ```java
  ObjectInputFilter filter = ObjectInputFilter.Config.createFilter(
          "com.zzkingcc.domainplane.**;dev.langchain4j.**;org.bsc.langgraph4j.**;java.util.*;java.time.*;!*");
  ois.setObjectInputFilter(filter);
  ```
  并把 Redis 连接强制要求密码（配置校验期拒绝空密码的远程 Redis）。
- **参考**：CWE-502（Deserialization of Untrusted Data）、OWASP A08:2021

### S-04（P1）凭证无过期、无 jti、登出不吊销

- **位置**：`server/auth/CredentialService.java:53-62`、`verify():73-97`、`server/controller/AuthController.java:80-89`
- **问题描述**：payload 只有 `{sub, iat}`，`verify()` 只比对 `sub` 与签名，从不校验 `iat`；响应里 `expiresAt: null`；`/admin/logout` 仅写一个 `Max-Age=0` 的 Cookie。
- **风险说明**：凭证一旦泄露（日志、明文 HTTP、`X-Forwarded-For` 场景下的中间人、浏览器保留的响应体）即**永久可用**，直到改密码。API 侧的凭证还会被 SDK 长期缓存。
- **修复建议**：`payload` 加 `exp`（如 12h）与 `jti`；`verify()` 校验 `exp`；服务端保留一个"已作废 jti"集合（Redis，TTL = 剩余有效期）供 logout 使用。若要维持"无状态"语义，至少加 `exp`——这不需要任何服务端存储。
- **参考**：CWE-613（Insufficient Session Expiration）、OWASP A07:2021

### S-05（P1，已修复）未初始化时全站 fail-open，且初始化入口可抢注

- **位置**：`server/config/WebMvcConfig.java:28-29`、`server/auth/CredentialAuthInterceptor.java:65-83`、`server/auth/AuthService.java:93-118`
- **问题描述**：账号文件不存在时拦截器按"未初始化"放行一切请求，只打一条 warn；`/admin/init` 免鉴权。守卫写得很细（区分"曾经初始化过现已读不到"和"账号文件损坏"都会 fail-closed），但"文件不存在"这一分支仍是任何人可抢注。
- **风险说明**：正常首次部署下这是必需功能；但任何能删掉 `accounts.json` 的角色（容器挂载权限、误操作、备份恢复）都会把实例退化成可被接管状态。
- **修复建议**：`/admin/init` 要求一次性引导令牌——首次启动时生成随机串写到 `settings.path/init-token.txt` 并打日志，初始化请求必须带上它；或者把 init 限制为仅回环地址可访问（`request.getRemoteAddr()` 判定）。
- **参考**：CWE-306（Missing Authentication for Critical Function）

### S-06（P2）MCP 远端可把 JSON-RPC 导向任意主机

- **位置**：`server/mcp/McpRpcClient.java:438-446`（`if (u.isAbsolute()) return endpointData;`）、`:344-352`
- **问题描述**：Legacy SSE 传输下，远端服务器在 `endpoint` 事件里返回的 URL 若是绝对地址，客户端直接采信并把后续所有 JSON-RPC 发过去，且携带为该连接配置的 headers（含 OAuth token）。用户配置的 `baseUrl` 有 `http/https` 校验（`McpConnectionStore:371`），但这条路径绕开了它。
- **风险说明**：管理员添加了一个恶意/被攻陷的 MCP server，即可借平台的出站能力访问内网，并顺手拿到 OAuth token 的明文外发机会。
- **修复建议**：对 `endpointData` 做与 baseUrl 相同的校验，并要求同源（或同 host）；跨源时拒绝并告警。
- **参考**：CWE-918

### S-07（P2）Qdrant 客户端是唯一跟随重定向的出站客户端

- **位置**：`infrastructure/kb/qdrant/QdrantHttpClient.java:87`（`.followRedirects(HttpClient.Redirect.NORMAL)`）
- **问题描述**：`NORMAL` 会跟随 301/302/307/308（https→https，不降级到 http）。其余出站客户端（模型端点、MCP、远程工具）都不跟随。
- **风险说明**：被攻陷或恶意的 Qdrant 可把请求导向内网 https 端点；作为"唯一跟随重定向"的异类，这个不一致本身就是维护隐患。
- **修复建议**：改为 `Redirect.NEVER`，或显式限制跳转次数并校验跳转目标 host 不变。
- **参考**：CWE-918

### S-08（P2）软删标记是裸 LinkedHashMap，跨线程读写无同步

- **位置**：`server/settings/DomainStore.java:87`（`private final Map<String, Long> pendingDeletes = new LinkedHashMap<>()`）、`:248 save()`、`:343-360 markDeleted/clearDeleted/isPendingDelete/pendingDeletes()`；全类零 `synchronized`
- **问题描述**：写侧是管控台 HTTP 线程（`markDeleted` → `saveDeclarations`），读侧是编排线程（`AgentOrchestrationService:530-537` 的开轮闸门）与删除调度器。`pendingDeletes()` 用 `Map.copyOf` 遍历该 map。

  ```java
  public void markDeleted(String domainId) { this.pendingDeletes.put(...); }   // 无锁
  public boolean isPendingDelete(String domainId) { return this.pendingDeletes.containsKey(...); }
  public Map<String, Long> pendingDeletes() { return Map.copyOf(this.pendingDeletes); } // 遍历中可能被改
  ```
- **风险说明**：`LinkedHashMap` 并发写会破坏内部链（丢条目、成环导致遍历死循环），`Map.copyOf` 遍历期间被改会抛 `ConcurrentModificationException` 或被当作空快照。业务后果是**软删闸门漏判**：宽限期内本应拒绝新轮的域被放行，或在飞会话未被停掉就开始级联删除。
- **修复建议**：换成 `ConcurrentHashMap` 并在 `save()`/`commitRollback()` 上加 `synchronized`；`pendingDeletes()` 用 `Map.copyOf(new ConcurrentHashMap<>(...))` 之外的稳定快照（`Map.copyOf` 对 CHM 是安全的）。
- **参考**：CWE-362（Race Condition）

### S-09（P2）PDF 解析无内存/页数上限，5MB 输入可撑爆堆

- **位置**：`infrastructure/ingestion/pdf/PdfReader.java:107`（`Loader.loadPDF(content)`）
- **问题描述**：上传上限 5MB（容器 5MB / 业务 5MB 已对齐），但 PDFBox 未设 `MemoryUsageSetting`、未限制页数；切片数上限 `max-chunks-per-document: 2000` 在**切完之后**才判（`AbstractDocumentProcessStrategy:219-237`），OOM 先发生。
- **风险说明**：构造一个 5MB 内、页数极多或对象流递归的 PDF，可让导入线程 OOM，甚至拖垮整个 JVM（导入池 2 线程，共享堆）。
- **修复建议**：
  ```java
  // 1) 解析期限制：流式 + 内存上限
  try (PDDocument doc = Loader.loadPDF(content,
          MemoryUsageSetting.setupMixed(64L * 1024 * 1024))) {
      if (doc.getNumberOfPages() > MAX_PAGES) throw new KnowledgeBaseException(...);
  }
  // 2) 切分期早停：每产出一片就判上限，不要切完再判
  ```
- **参考**：CWE-400（Uncontrolled Resource Consumption）

### S-10（P2）上传只校验扩展名，无内容嗅探

- **位置**：`server/knowledge/KnowledgeBaseService.java:539-582`、`requireSupported():814-826`
- **问题描述**：白名单按扩展名判定后即交给对应解析器，不校验魔数/容器结构。`.pdf` 里塞 zip、`.docx` 里塞任意二进制都会进入 POI/PDFBox。POI 自带 `ZipSecureFile` 压缩比保护，PDFBox 没有（见 S-09）。
- **风险说明**：解析器健壮性成为唯一防线；旧版 `.doc`（HWPF）与 Excel 分支历史上漏洞较多。
- **修复建议**：按扩展名做最小魔数校验（`%PDF-`、`PK\x03\x04`、`\xD0\xCF\x11\xE0`），不匹配即拒；并对解析异常做统一归类（当前会把底层异常文本带回）。
- **参考**：CWE-434（Unrestricted Upload of File with Dangerous Type）

### S-11（P2）Cookie 无 Secure 且凭证在响应体里回传

- **位置**：`server/controller/AuthController.java:148-155`（`writeCookie`，注释明确放弃 Secure）、`:74`（`result.put("credential", credential)`）
- **问题描述**：Cookie 有 `HttpOnly` + `SameSite=Lax`，但没有 `Secure`（为兼容 http 部署，理由写在注释里）；同时登录响应体里也返回凭证给前端脚本。
- **风险说明**：HttpOnly 的意义被响应体回传削弱（XSS 可直接读 `fetch` 结果）；http 部署下 Cookie 可被同网段嗅探。二者叠加让 S-04 的"永久凭证"风险放大。
- **修复建议**：Cookie 按 `request.isSecure()` 或配置项动态加 `Secure`；管控台前端改为不再需要读凭证（既然已有 Cookie），从登录响应里去掉 `credential`（仅 `/api/agent/login` 返回）。
- **参考**：CWE-614（Sensitive Cookie Without Secure Attribute）、CWE-1004

### S-12（P2）Redis JSON 序列化开启多态默认类型，白名单偏宽

- **位置**：`server/config/RedisClientConfiguration.java:51-60`（`activateDefaultTyping(validator, NON_FINAL)` + `GenericJackson2JsonRedisSerializer`）
- **问题描述**：有 `BasicPolymorphicTypeValidator` 白名单（这是对的），但放行 `com.zzkingcc.domainplane.**`、`java.util.**`、`java.lang.**`。
- **风险说明**：白名单内任一"setter 有副作用"的类都会成为 gadget 起点；`java.lang.*` 的放行面明显宽于实际需要（实际只用到 `String`/包装类型）。有 `RedisClientPolymorphicTypeTest` 覆盖，说明这是被审过的点，但放行粒度仍可收紧。
- **修复建议**：把 `java.lang.*` 收窄为 `java.lang.{String,Integer,Long,Boolean,Double}`，`java.util.*` 收窄为实际用到的集合类型。
- **参考**：CWE-502

### 低危项（P3）

| ID | 位置 | 问题 | 风险 | 修复建议 | 参考 |
| --- | --- | --- | --- | --- | --- |
| S-13 | `runtime/cancellation/CancellationRegistry.java:36,51-53` | `requestStop` 对任意 `sessionKey` 都会 `computeIfAbsent` 建标志，`/api/agent/stop/{sessionId}` 的 sessionId 由调用方给 | 循环不同 sessionId 可让该 map 无界增长（每条约 100B，需百万级才有实质影响） | stop 前校验会话存在（查归档/检查点键），或加"最近 N 分钟"的 LRU 上限 | CWE-770 |
| S-14 | `server/controller/ToolRegistrationController.java:39,116` | `warnedEndpoints` 按 instanceId 无界 put | 同上，需大量注册 | 改为只在状态变化时记录，或加容量上限 | CWE-770 |
| S-15 | `infrastructure/ingestion/DocumentIngestor.java:69` | 文件名（用户可控）直接拼进日志 message，未走 `LogSanitizer`（同模块 `EsKeywordSearcher:96` 已用） | 含 `\n` 的文件名可伪造日志行 | 统一走 `LogSanitizer.describeUserText` | CWE-117 |
| S-16 | `server/auth/AuthService.java:242` | `sessionInfo` 在**未认证**分支也返回 `accountFile` 绝对路径 | 泄露服务器目录结构，辅助定位/替换账号文件 | 只在 `authenticated` 分支返回 | CWE-200 |
| S-17 | `server/knowledge/ChunkExporter.java:46-47,198-211` | 文件名只过滤反斜杠、正斜杠、冒号、星号、问号、双引号、尖括号与竖线，未处理控制字符、Windows 保留名（CON/NUL）、尾随点与空格 | 目录穿越不可行（分隔符已被替换），但可能写出异常文件或抛 `InvalidPathException`（在 try 之外，直接 500） | 追加控制字符过滤 + 保留名兜底 + 把 `pathOf` 移进 try | CWE-22（不成立）/ CWE-20 |
| S-18 | `.github/workflows/security.yml:45` | `-DfailBuildOnCVSS=9`，只有 CVSS≥9 才阻断；`-DfailOnError=false` | 7-8.9 的高危依赖漏洞不阻断 CI，扫描自身失败也不阻断 | 降到 7.0，并让扫描失败默认判红 | CWE-1104 |

### 质量与并发（不计入安全分级）

| ID | 级别 | 位置 | 问题 | 建议 |
| --- | --- | --- | --- | --- |
| Q-01 | P2 | `runtime/orchestration/AgentOrchestrationService.java`（1,203 行） | 已从 2,330 行拆到 1,203，但仍有 7 个保留兼容构造器（约 190 行）与两个轮次主体（约 410 行）在类内 | 兼容构造器已被 `OrchestrationDeps` 取代，建议标注 `@Deprecated` 并在下个大版本删除；轮次主体可再抽 `RoundRunner` |
| Q-02 | P2 | `server/knowledge/ChunkExporter.java:139,152`、`AdminController.java:38,83,108` | 绕过 `KnowledgeStore` SPI 直接使用 `ElasticsearchClient`，F05「去 ES 强依赖」未收口 | 给 `KnowledgeStore` 补 `readByDocId` 之外的分页读/按序号读（已有 `readByDocId`），ChunkExporter 改走 SPI |
| Q-03 | P2 | `runtime/storage/BlobStore.java:27` 接口 / `server/storage/LocalBlobStore.java:39` 实现 | 接口在内核、实现在外壳，方向反了 | 接口留在 runtime 是合理的（内核需要抽象），但应把 `StorageConfiguration` 的装配契约写进 runtime 的 SPI 文档，或把接口移到 `api` 模块与 `KnowledgeStore` 对齐 |
| Q-04 | P3 | `domain/rag/retriever/CompositeRetriever.java:181` | 串行模式每次检索 `Executors.newSingleThreadExecutor` 新建线程池（虽 try-with-resources 不泄漏） | 复用注入的执行器配单线程限流，或缓存一个 serial 执行器 |
| Q-05 | P3 | `domain/rag/retriever/CompositeRetriever.java:206-211` | 全通道失败抛 `IllegalStateException`，会落到通用 500 分支，丢失"知识库不可用"这一可操作语义 | 抛 `KnowledgeBaseException(ErrorCode.KB_UNAVAILABLE, ...)`，让调用方/模型看到"服务不可用"而非"未知错误" |

## 修复优先级建议

1. **对外暴露前必修（P1）**：S-01（默认口令 + 限流 + 闸门覆盖 HEADER 载具）、S-02（endpoint 白名单）、S-04（`exp`）。
2. **与部署前提绑定，需先确认（P1）**：S-03（Redis 是否强制密码 + 仅内网；否则按 P0 处理）、S-05（是否有预置 `accounts.json` + settings 目录权限）。
3. **本迭代（P2）**：S-06、S-08（并发正确性优先于其余）、S-09、S-12、Q-01、Q-02。
4. **排期（P2/P3）**：S-07、S-10、S-11、S-13~S-18、Q-03~Q-05。

## 修复记录（2026-10-29）

本轮只动 `S-02` 与 `S-05`，其余问题保持原样（未修复也未降级）。

### S-02：回流地址准入 + 失败响应体不回显

新增 `server/tool/ToolEndpointPolicy.java`，在**注册期**与**调用期**各校验一次：

| 规则 | 拦什么 | 为什么不误伤 |
| --- | --- | --- |
| scheme 只允许 http/https | `file:`、`gopher:` 等 | 工具实例本来就只走 HTTP |
| 拒绝 `user:pass@` | 借主机名解析做混淆 | 正常实例不会带凭据 |
| 拒绝 `0.0.0.0` / `::` | 通配地址（多数系统等价于连本机） | 正常实例填的是可达的具体地址 |
| 拒绝 `169.254.0.0/16`、`fe80::/10`、`100.100.100.200`、`fd00:ec2::254`、`metadata*` | 云元数据（SSRF 最有价值的目标） | 没有业务工具跑在元数据地址上 |
| 可选白名单 `domainplane.tool.endpoint.allowed-hosts`（精确 host 或 `*.suffix`） | 只能连指定实例 | 默认空 = 不限，保持现有部署行为 |

**刻意不禁内网与回环**：工具实例几乎都跑在内网、常常与服务端同机，一刀切等于废掉产品主功能。改把"收益端"掐掉——失败响应体不再回喂调用方：

- 非 2xx：只回状态码 + "响应体详见服务端日志"，body 只进 WARN 日志；
- 非 JSON：只回 `Content-Type`，内容同样只进日志。

另外把 `HttpClient` 的重定向显式设为 `NEVER`（JDK 默认就是 NEVER，写明是为了让"不跟随"成为可见约定，而不是依赖默认值）。

**验证**：`ToolEndpointPolicyTest` 8 例、`RemoteToolExecutorEndpointGuardTest` 4 例（含真起本地 HttpServer 返回 500 + 敏感标记，断言标记不出现在工具结果里）。

**仍未解决的部分（诚实登记）**：内网任意 `host:port` 仍可被探测（策略只拦元数据与非法 scheme）。要收敛到"只能连这几台"，需部署方配置 `allowed-hosts`；这属于部署选择，不是代码默认能替用户决定的。

### S-05：未初始化时 fail-closed + 初始化只允许本机

| 改动 | 位置 | 行为变化 |
| --- | --- | --- |
| 未初始化放行清单收窄到健康探测 | `CredentialAuthInterceptor` | `/api/agent/**` 与 `/admin/**` 一律 409 `AUTH_NOT_INITIALIZED`；`/api/agent/health` 仍放行（SDK 启动期连通性校验） |
| 初始化只接受本机 | `AuthController.init` | 默认拒绝非回环来源，403 `PERMISSION_DENIED`；`domainplane.auth.allow-remote-init=true` 可放开 |
| 反代场景判定 | `AuthController.isLocalRequest` | 先看连接对端是否回环（伪造不了），再看 `X-Forwarded-For` 首跳；公网经代理进来的请求不会被误判成本机 |

引导路径不受影响：`/admin/init`、`/admin/session`、静态资源在 `WebMvcConfig` 的 exclude 清单里，根本不进拦截器。

**验证**：`CredentialAuthInterceptorUninitializedTest` 5 例、`AuthControllerInitLocalOnlyTest` 5 例（含"127.0.0.1 但 XFF 是公网"必须拒）。

### 回归

全量 `mvn -o test`：12 模块、1,252 例（原 1,230 + 本次新增 22 例）、0 失败，BUILD SUCCESS。

## 附录

### 已核查为"不成立"的项（不必占修复预算）

| 候选问题 | 结论 | 依据 |
| --- | --- | --- |
| Elasticsearch 脚本注入 | 不成立 | `EsVectorSearcher:58-62` 脚本 source 是字符串常量，向量走 `params` |
| ES / Qdrant 查询拼接注入 | 不成立 | 关键词走 DSL `multiMatch` 参数化；Qdrant filter 用 Jackson 节点构造 `key/match.value` |
| 索引名注入 / 通配越权 | 不成立 | `KbIndexes:33` 字符集清洗 + 路径 hash；`Domains.SEGMENT_PATTERN` 限死 `[A-Za-z0-9_-]` |
| Jackson 任意类型实例化 | 不成立（有白名单） | 无 `enableDefaultTyping()` 无参调用、无 `@JsonTypeInfo(use=Id.CLASS)`；Redis 侧有 `BasicPolymorphicTypeValidator` |
| 反序列化 RCE（配置文件/HTTP 入参） | 不成立 | 全仓无 XStream / SnakeYAML unsafe load / `readObject` 直读入参 |
| Actuator 端点暴露 | 不成立 | 无 `spring-boot-starter-actuator` 依赖 |
| ThreadLocal 泄漏 | 不成立 | `TraceId:54`、`TokenUsageRecorder:27,153` 均有 `remove()`；其余由调用方 close |
| 线程池无界 | 基本不成立 | 除虚拟线程执行器（背压由 `ConcurrencyGate` 显式给出且只有 `startRound` 一处提交）外，工具池 / 导入池 / 检索池全部有界 + Abort 策略 |
| 目录穿越（导出文件名） | 不成立 | 分隔符与盘符已被替换，无法成路径 |

### 依赖版本核对建议

`elasticsearch-java 9.4.4`、`langchain4j 1.18.1`、`langgraph4j 1.8.17`、`poi 5.4.1`、`pdfbox 3.0.8` 均为较新版本，本次离线环境无法查询公告库，未做 CVE 断言。建议在有网环境跑一次 `mvn -Psecurity verify` 并核对 `owasp-suppressions.xml`（当前**零条实际抑制**，是好事）。

### 局限

- 未做运行时验证（未启动服务做黑盒测试），所有可利用性判断基于静态数据流。
- 未审计前端静态资源（`server/src/main/resources/static/console/**` 与 `frontend/src`）的 XSS/DOM 注入面。
- 未审计 8 种文档解析器内部的第三方库行为，只审计了调用面的资源限制。
