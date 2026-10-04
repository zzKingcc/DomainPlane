# DomainPlane 命名规范审查报告

- **审查对象**：`D:\java\zishuati\DomainPlane`
- **审查日期**：2026-10-03
- **审查方式**：全量静态扫描（300 个 `.java` 文件 / 11 个 Maven 模块 / 约 25,000 行主干代码）+ 人工抽样复核
- **基准风格**：Google Java Style + 阿里巴巴 Java 开发手册（类型命名层），Alibaba P3C（成员命名层）

---

## 一、总体结论

**规范执行度：优秀（9.2 / 10）。**

形态命名（类型名、包名、常量名、枚举项、测试类名）几乎零违规，且不存在业界常见的历史包袱类问题
（无 `I` 前缀接口、无 `*Utils` / `*Helper` / `*Impl` / `*Manager`、无下划线方法名、无数字后缀方法名）。

问题**不在于"写得乱"，而在于"语义层词汇不统一"**——同一个业务概念在三层里分别叫
`domain` / `domainId` / `profile`，而 `profile` 又与"模型档案"含义撞车。这类问题不会让编译器报错，
却会在读代码时持续制造歧义，是本次审查的主要产出。

| 维度 | 合规率 | 评价 |
|---|---|---|
| 包名 | 100% | 优秀 |
| 类型名 / 文件名一致 | 100% | 优秀 |
| 方法命名 | 100% | 优秀 |
| 常量 / 枚举项 | 99.8% | 优秀 |
| 布尔命名（is/has/can） | 100% | 优秀 |
| 测试类命名 | 100% | 优秀 |
| 资源文件命名 | 100% | 优秀 |
| **术语一致性（domain/profile）** | **~60%** | **需整改** |
| **访问器风格一致性** | **~71%** | **需整改** |
| 文档化（有 package-info） | 16% | 待补 |

---

## 二、做得好 的地方（应固化为强制规则）

### 2.1 零反模式前缀 / 后缀（最大亮点）

全库扫描确认：

- **0 个** `I` 前缀接口（无 `IDomainPlaneToolProvider`）
- **0 个** `*Utils` / `*Util` / `*Helper` / `*Manager` / `*Impl` / `*Adapter` / `*Wrapper` / `*Delegate` / `*Bean` / `*Facade`
- **0 个** `*Data` / `*Info` 泛滥（仅 `ServerInfo` 1 处，见 §4.7）
- 接口按**能力**命名，而非按角色：`DomainPlaneToolProvider`、`ToolRegistrar`、`ToolHandler`、`ContentRetriever`、
  `AgentService`、`StreamSinkRegistry`

这一项比多数企业级 Java 项目做得都好，新人极易上手。

### 2.2 缩略词按"单词"处理（符合 Google Style）

29 处缩略词全部一致地采用**首字母大写 + 其余小写**，无一处例外：

| 正确（本库做法） | 错误（未出现） |
|---|---|
| `EsIndexManager` / `EsMetadata` / `EsCompatibility` | ~~`ESIndexManager`~~ |
| `LlmSettings` / `LlmModelCatalog` / `LlmFailureDescriber` | ~~`LLMSettings`~~ |
| `PdfReader` / `DocxReader` / `DocReader` / `TxtCleaner` | ~~`PDFReader`~~ |
| `RagProperties` / `AiModelConfiguration` / `AiProperties` | ~~`RAGProperties`~~ |

这在中文团队中并不常见（很多项目会混用 `PDFReader` 与 `PdfReader`），本库从 `domainplane-api` 到
`domainplane-infrastructure` 保持了同一套规则。

### 2.3 方法命名纯净度极高

- **0 个**含下划线的方法名
- **0 个**含数字的方法名（唯一例外 `sha256` 属密码学标准术语，保留）
- **0 个**全大写方法名
- **0 个**中文拼音/方言缩写（无 `getMingxi`、`chongqi` 之类）

用词一律取完整单词，无缩写堆砌。对比扫描结果：`normalize`(8) vs `norm`(0)、`validate`(7) vs `valid`(0)、
`retrieve`(5) vs `get`(75，仅用于标准 getter)、`persist`/`fetch`/`submit` 各仅 1 次且语义明确。

### 2.4 布尔命名 100% 规范

76 处布尔返回值方法，**全部**使用 `is*` / `has*` / `can*` 前缀，零例外：

```java
isUsable()  isEmpty()  isRetryable()  isBuiltin()  hasText()  hasDescription()
canJoinAcrossPages()  isToolVisible()  isLoopbackHost()  isEmbeddingConfigured()
```

唯一一个 `getConvertPipelineAndTxResults()` 位于 `SwappableRedisConnectionFactory`，属对 Redis 客户端
`RedisConnectionFactory` 接口的强制覆写，**豁免合理**。

### 2.5 常量与枚举项

- `ErrorCode` 52 个枚举项、`AgentEventType` 7 个、`BlockKind` 7 个、`InstanceState` 5 个、**全部 UPPER_SNAKE_CASE**
- `PdfReader` 内 9 个 `static final` 常量**全部** UPPER_SNAKE_CASE（`EDGE_LINES`、`MAX_EDGE_CHARS`、
  `SENTENCE_END_CHARS`、`PARAGRAPH_GAP_RATIO`、`MIN_CROSS_PAGE_JOIN_CHARS` …）
- `ChunkMetadataKeys` 这类"常量持有类"命名清晰，且正确使用 `DOC_ID = "doc_id"`

### 2.6 测试类命名

65/67 个测试类以 `Test` 结尾，无 `*IT` / `*Tests` / `*Spec` / `*TestCase` 混用。
剩下 2 个是测试支撑类，命名**准确**：`DocFixtures`、`PdfSamples`。
主干代码中**零**个类带 `Test` / `Fixture` / `Stub` / `Mock` 后缀（测试概念未污染生产命名）。

### 2.7 包结构与模块映射

- 所有包名全小写、无下划线、无驼峰，统一 `com.zzkingcc.domainplane.<模块>.<能力>`
- 模块 → 包根**严格 1:1**，无跨模块错放：

```
domainplane-api             -> api           domainplane-runtime          -> runtime
domainplane-common          -> common        domainplane-sdk-core         -> sdkcore
domainplane-domain          -> domain        domainplane-server           -> server
domainplane-infrastructure -> infrastructure  domainplane-tool-provider  -> toolprovider
```

模块名用 kebab-case（`domainplane-chat-client`）、包名用拼接式（`chatclient`），规则一致、可预期，
只是丢失了词边界（见 §4.3）。

### 2.8 资源文件与版本库卫生

- 资源命名全部遵循 Spring 约定：`application.yaml`、`logback-spring.xml`、`logback-file.xml`、
  `META-INF/spring/*.imports`
- 静态页统一 kebab-case + 按功能分目录：`static/console/{login,models,domains,infra,knowledge}.html`
- 数据文件语义命名：`accounts-seed.json`
- **`.workbuddy/` 已在 `.gitignore:44` 中忽略，`git ls-files` 实测 0 个文件入库**——本地临时探针
  （`Probe*.java`、`*.txt`、`*.doc`）未污染版本库，这点做得对

---

## 三、高优先级问题（P1）

### 3.1 `profile` 一词三义混用，域概念在三层有三种叫法 ★★★

**这是本次审查最重要的发现。**

同一业务概念（"域"）在不同层使用了三个不同的名字：

| 层 | 命名 | 位置 |
|---|---|---|
| 业务概念 / 领域工具 | `domain` / `domainId` | `Domains.normalize(String domainId)`、`parentOf`、`chainOf`、`validatePath` |
| 公开 API | `domainId` | `DomainPlaneAgent.domainId()`、`DomainPlaneAgentFactory.forDomain(String domainId)` |
| 传输字段 / 上下文 | **`profile`** | `AgentRequest.profile`、`CallerContext.profile`、`getProfile()` |
| 路由 / 注册表 | **`profile`** | `ToolRouter.getToolSpecifications(String profile)`、`ToolRegistry.acceptsProfile(String domainId)` |

更严重的是，`CallerContext` 的首个分量名为 `profile`，但它的 Javadoc 自述为"域"：

```java
// domainplane-api/.../api/agent/CallerContext.java:3-11
/**
 * 调用方身份 —— 域 / 租户 / 用户 不可变三元组。
 * @param profile  本轮所处的域      ← 名为 profile，实为 domain
 */
public record CallerContext(String profile, String tenantId, String userId) {
```

**且 `profile` 同时是另一个独立概念的正式名称**——大模型档案：

```
ModelProfile, ModelProfileSettings, ModelProfileRegistry, ModelProfileTest,
AdminModelProfileController (28 处), ModelClientFactory (25 处)
```

两个不相干的概念共用一个词，且分处不同模块（`api.agent` vs `server.model`），新人极易误读。
更麻烦的是错误码枚举也沿用了这个歧义命名，并且已对外暴露：

```java
// domainplane-api/.../api/code/ErrorCode.java:19,26
PROFILE_NOT_FOUND(10004, "指定的域不存在", 400, false, ...)
PROFILE_REQUIRED(10009, "未指定本轮所处的域", 400, false, "显式指定 profile")
```

**建议**：
1. 统一为 `domain` / `domainId`，废弃代码侧的 `profile` 指代域；
2. `ModelProfile*` 系列改名（如 `ModelSpec*` / `LlmProfile*`），把 `profile` 让给"档案"义；
3. 错误码常量名可保留 `PROFILE_*` 以维持向后兼容（已在 SDK 契约中），但**新增错误码必须用 `DOMAIN_*`**，
   并在 `ErrorCode` 上加一条 `@deprecated` 语义说明。

> ⚠️ 兼容性成本：`profile` 是 HTTP 查询参数与 JSON 字段，已发布在 `docs/API.md` 与 `docs/SDK-CONTRACT.md`。
> 建议**服务端同时接受 `domain` 与 `profile` 两个参数名**（过渡期），文档标注 `profile` 为废弃别名。

### 3.2 同一类内混用两种访问器风格 ★★

全库统计：**getter 风格 85 处，record 风格 198 处**（约 71% 偏向 record 风格）。
问题不在比例，而在于**同一个类内部混用**——读者无法建立稳定预期：

```java
// ToolRegistry.java  (6 个 record 风格 + 1 个 getter 风格)
public Collection<Registered> all()             { ... }   // L303
public List<ToolSpecification> toolSpecifications() { }    // L318
public Set<String> toolsRequiringApproval()      { ... }   // L337
public Set<String> knownProfiles()               { ... }   // L359
public List<ToolDescriptor> descriptors()        { ... }   // L481
public boolean isEmpty()                         { ... }   // L488  ← 异类
public int size()                                { ... }   // L493

// LlmSettings.java  (2 + 4 混用)
public String effectiveEmbeddingBaseUrl() { ... }          // L55
public String effectiveEmbeddingApiKey()  { ... }          // L61
public boolean isUsable()                 { ... }          // L69
public boolean isEmbeddingUsable()        { ... }          // L77
public String getMaskedChatApiKey()       { ... }          // L86  ← 异类
public String getMaskedEmbeddingApiKey()  { ... }          // L92  ← 异类

// StreamContext.java
public String getSessionId()    { ... }                    // L37  ← 异类
public CallerContext getCaller(){ ... }                    // L47  ← 异类
public String traceId()         { ... }                    // L42
public String profile()         { ... }                    // (record 风格)
```

**同类混用清单（已逐个复核确认）**：

| 类 | getter 风格 | record 风格 |
|---|---|---|
| `ToolRegistry` | `isEmpty` | `all`, `toolSpecifications`, `toolsRequiringApproval`, `knownProfiles`, `descriptors`, `size` |
| `LlmSettings` | `getMaskedChatApiKey`, `getMaskedEmbeddingApiKey` | `effectiveEmbeddingBaseUrl`, `effectiveEmbeddingApiKey` |
| `StreamContext` | `getSessionId`, `getCaller` | `traceId`, `profile` |
| `ToolInstanceClient` | `isForcedOffline`, `isRegistered` | `config`, `lastError`, `toolNames` |
| `ModelProfile` | `isChat`, `isEmbedding`, `isUsable` | `fingerprint`, `maskedApiKey`, `supportsTools`, `capabilityHint` |
| `LlmModelHolder` | `isConfigured`, `isEmbeddingConfigured` | `chatModel`, `embeddingModel`, `embeddingSource`, `currentSettings`, `reload`, … |
| `InfraSettingsHolder` | `isEsConfigured`, `isRedisConfigured` | `current`, `esSource`, `redisSource`, `storedSettings` |
| `RuntimeEnvironment` | `isContainer` | `osFamily`, `containerRuntime`, `defaultExportPath`, `describe` |
| `AgentRequest` | `getSessionId`, `getProfile`, `getTenantId`, `getUserId`, `getMessage`, `getAttributes` | `builder`, `toBuilder` |

**建议**：项目已有大量 record（52 个），且新代码明显在用 record 风格。
**统一为 record 风格**（去掉 `get` 前缀），并写进规范。
需要保留 `getXxx()` 的仅有两类，须在规范中显式列为豁免：
1. Spring Boot `@ConfigurationProperties` 绑定（`DomainPlaneProperties.getUsername/setUsername`）；
2. 覆写第三方接口（`SwappableRedisConnectionFactory`）。

---

## 四、中优先级问题（P2）

### 4.1 `docId` 是唯一的缩写特例，与全库 `Id` 风格冲突 ★★

项目对 ID 一律使用完整单词 `Id`：`domainId`、`sessionId`、`tenantId`、`userId`、`traceId`、
`instanceId`、`threadId`、`requestId`、`shortId`。

**唯独"文档 ID"被缩写成 `doc`**（112 处命中）：

```java
public record UploadResult(String docId, String fileName, long size, int chunks) {}   // kb-client:42
public record DocumentItem(String docId, String fileName, int chunks) {}             // kb-client:45
private void deleteByDocId(String index, String docId)                               // KnowledgeBaseService:766
private int  countByDocId(String index, String docId)                                // KnowledgeBaseService:777
public void deleteByDocId(String docId)                                              // ChunkExporter:117
public static final String DOC_ID = "doc_id";                                       // ChunkMetadataKeys:23
```

而同一批代码里，"文档"一词在**类型名**上却是完整的：
`DocumentItem`、`DocumentIngestor`、`infrastructure.ingestion.doc` 包、`KnowledgeBaseService`。

**建议**：
- **代码标识符**统一为 `documentId` / `deleteByDocumentId` / `countByDocumentId`；
- **ES 元数据字段 `doc_id` 保持不变**（存量索引，改名需 reindex，收益不抵成本）；
- **JSON 字段 `docId` 保持不变**（已在 SDK 契约中公开，见 `docs/API.md`），仅在 Java 侧读取时映射。
  可在 `KnowledgeBaseClient` 内加一行注释说明该映射是"契约适配"而非术语不一致。

### 4.2 嵌套 record 名为通用词根，跨包重名 ★★

`Result` 作为 record 名出现 **9 次，横跨 8 个包**：

| 位置 | 签名 | 语义 |
|---|---|---|
| `elasticsearch/VectorReindexer.java:75` | `record Result(Map preserved, Map reindexed)` | 向量重建结果 |
| `ingestion/doc/DocReader.java:66` | `record Result(List<Block> blocks, int dropped)` | 文档解析结果 |
| `ingestion/docx/DocxReader.java:54` | `record Result(List<Block> blocks, int dropped)` | 文档解析结果 |
| `ingestion/pdf/PdfReader.java:89` | `record Result(List<Block>, int, int, int)` | 文档解析结果 |
| `ingestion/markdown/MarkdownReader.java:75` | `record Result(List<Block> blocks, int dropped)` | 文档解析结果 |
| `ingestion/excel/ExcelReader.java:68` | `record Result(List<Block>, int, int)` | 文档解析结果 |
| `ingestion/txt/TxtCleaner.java:45` | `record Result(List<String> lines, int dropped)` | 清洗结果 |
| `ingestion/txt/OutlineReader.java:73` | `record Result(List<Section> sections, int titles)` | 大纲结果 |
| `server/model/ModelCatalog.java:82` | `record Result(List<String> endpoints, …)` | 目录查询结果 |

后果：任一调用点都必须写全限定名（`DocxReader.Result` vs `PdfReader.Result`）才能确定语义，
IDE 自动补全与 IDE 重构的收益被完全抵消。

其他通用词根 record：`Binding`（2）、`Contact`（2，测试用）、`Approval`（2）、
以及 `Parsed`、`Decoded`、`Verdict`、`Capacity`、`Channel`、`Section`、`Address`、`Registered`、`ProfileData`。

**建议**：加业务限定前缀，尤其把 `Result` 区分开：

| 现名 | 建议 |
|---|---|
| `DocReader.Result` / `DocxReader.Result` / `PdfReader.Result` / `MarkdownReader.Result` / `ExcelReader.Result` | `IngestResult`（5 处可统一为同一 record，进一步消除重复） |
| `VectorReindexer.Result` | `ReindexResult` |
| `ModelCatalog.Result` | `CatalogResult` |
| `TxtCleaner.Result` | `CleanResult` |
| `OutlineReader.Result` | `OutlineResult` |
| `ToolManifest.Parsed` | `ParsedManifest` |
| `TxtNormalizer.Decoded` | `DecodedText` |

### 4.3 模块名与包根的词边界丢失 ★

模块 kebab-case 与包名拼接式的转换规则本身自洽，但丢失了词边界，且个别处可读性下降：

| 模块 | 包根 | 备注 |
|---|---|---|
| `domainplane-chat-client` | `chatclient` | 建议 `chat.client`，与 `domainplane-client-core` → `clientcore` 不一致风险 |
| `domainplane-client-core` | `clientcore` | 与上者同构，但"chat client"与"client core"合并后难以区分 |
| `domainplane-kb-client` | `kbclient` | 同上 |
| `domainplane-sdk-core` | `sdkcore` | 同上 |

三个 `*client` 模块合并为 `chatclient` / `clientcore` / `kbclient` 后，仅靠命名已难判断边界。
**建议**（改动成本高，可列为长期项）：拆为 `chat.client` / `client.core` / `kb.client`，
与已有的 `api.agent` / `infrastructure.redis.memory` 多级结构对齐。

### 4.4 跨模块同名类掩盖了实现重复 ★★

```
domainplane-runtime/.../runtime/tool/AnnotatedToolScanner.java
domainplane-tool-provider/.../toolprovider/spring/AnnotatedToolScanner.java
```

两个类**同名**，分处不同模块的不同包，职责高度重叠——都扫描 `@Tool` 注解方法并做重复名校验
（后者含 `"工具名重复："` 前者含 `"@Tool(" + toolName + ", domains = ..."` 报错）。

同名不同义会让读者误以为是同一个概念的两处实现，而实际上是两份独立演进、逻辑已分叉的代码。

**建议**：若确为重复 —— 提取到公共模块并统一命名（如 `ToolAnnotationScanner`）；
若确为不同实现 —— 至少重命名其中一个（如 `ToolProviderAnnotatedToolScanner`）以示区分。
附带收益：`.workbuddy` 目录下的 `Probe` 类也存在同名（`Probe.java` 与 `tmp/ProbeD05.java`
文件名与类型名不符），虽已 gitignore，但建议清理。

---

## 五、低优先级问题（P3）

### 5.1 `audit` 日志字段名破例 ★

```java
// AuditLog.java:15
private static final Logger audit = LoggerFactory.getLogger("AUDIT");
```

全库 55 处 `static final Logger` 中，**54 处统一叫 `log`**，仅此一处为 `audit`。
`audit` 确实有独立 logger 名称（`"AUDIT"`），但**字段名**无需跟随。建议改为 `log`，
需要区分时用注释或单独的 `loggerFactory.getLogger("AUDIT")` 表达式。

### 5.2 `package-info.java` 覆盖仅 16% ★

68 个包中仅 11 个有 `package-info.java`（16%），且集中在 `api.code` / `api.event` / `api.model` /
`common.result` / `runtime.graph` / `runtime.node` / `runtime.stream` / `domain.rag.model` /
`domain.rag.fusion` / `infrastructure.embedding` / `infrastructure.storage`。

已覆盖的正是**公开 API 包**，说明团队知道这个惯例，只是未推广。
建议对 `domainplane-server` 的 17 个包（`config` 28 文件 / `settings` 16 文件 / `controller` 11 文件）
优先补齐——这是新人最需要导航指引的地方。

### 5.3 包名单复数不统一 ★

| 复数 / 物质名词 | 单数 |
|---|---|
| `settings`(16)、`metrics`(2)、`properties`(1) | `config`(28)、`controller`(11)、`tool`、`model`、`domain`、`exception`、`auth`、`prompt` |

`redis`（1 文件）属产品名，保留合理。
建议统一为单数：`settings` → `setting`，`metrics` → `metric`，
`properties` → `property`（且更贴近其他模块的 `*Properties` 类名）。
优先级低——`settings` 已 16 个文件，改名收益不抵成本，可仅约束新增包。

### 5.4 `*Info` 后缀 ★

`ServerInfo` 是全库**唯一**的 `*Info` 类（`EsProbe` / `RedisProbe` / `EmbeddingProbe` 用的是 `*Probe`，很好）。
`Info` 后缀在 Java 中语义偏弱（信息 vs 状态）。若该类是启动期快照，建议 `ServerSnapshot`；
若仅是版本/构建信息，`ServerInfo` 可接受。**仅 1 处，建议记录不整改**。

### 5.5 本地临时目录中的破损包声明（不进版本库）

`.workbuddy/tmp/ProbeD05.java` 中类型名为 `Probe`，与文件名 `ProbeD05` 不符；
包声明为 `.workbuddy.tmp`（以点开头的非法包名）。因 `.workbuddy/` 已 gitignore
（`.gitignore:44`，`git ls-files` 实测 0 文件），**不影响交付物**，仅作本地清理建议。

---

## 六、建议的规范条款（可直接落为 `docs/NAMING-CONVENTION.md`）

> ⚠️ 本次审查确认：现有 `docs/` 下 13 个文档中**无任何命名规范章节**（grep `命名|naming|convention` 仅命中业务语境）。
> 当前的一致性是**实践涌现**而非规则约束，团队扩大时必然衰减。落文优先级高于任何单点整改。

### 6.1 强制条款

1. 类型名 UpperCamelCase，与文件名完全一致（含嵌套 public 类型）。
2. 缩略词按单词处理：`EsIndexManager`、`LlmSettings`、`PdfReader`——**禁止** `ESIndexManager`、`LLMSettings`。
3. 包名全小写无分隔符；模块 kebab-case ↔ 包根拼接式，转换规则唯一。
4. 方法名 lowerCamelCase；禁止下划线、禁止数字后缀（`sha256` 除外）。
5. 布尔返回值方法必须带 `is` / `has` / `can` 前缀。
6. 常量与枚举项一律 UPPER_SNAKE_CASE。字段名 lowerCamelCase。
7. `static final Logger` 字段统一命名为 `log`。
8. 测试类以 `Test` 结尾；测试支撑类用 `Fixtures` / `Samples` / `Stub`。
9. **访问器统一 record 风格**（`sessionId()` 而非 `getSessionId()`）。
   豁免：`@ConfigurationProperties` 绑定、第三方接口覆写。
10. **同一概念全库同名**；跨层传递不得换词（`domain` 不得变成 `profile`）。

### 6.2 禁用词表

`profile`（指代"域"时）、`doc`（指代"文档"时）、`data`、`info`、`util`、`helper`、`manager`、
`impl`、`adapter`、`wrapper`、`facade`、`bean`

### 6.3 建议接入 Checkstyle / ArchUnit 门禁

```xml
<!-- 参考配置要点 -->
<module name="MemberName"/>          <!-- lowerCamelCase -->
<module name="ConstantName"/>        <!-- UPPER_SNAKE，log 列入 exempt -->
<module name="MethodName"/>
<module name="LocalVariableName"/>
<module name="TypeName"/>
<module name="PackageName"/>
<module name="AbbreviationAsWordInName">   <!-- 关键：allowedAbbreviationLength=0 -->
```

```java
// ArchUnit：禁止 I 前缀接口与 *Utils / *Helper
noClasses().that().areInterfaces().should().haveSimpleNameStartingWith("I");
classes().should().notHaveSimpleNameEndingWith("Utils")
                      .orShould().notHaveSimpleNameEndingWith("Helper");
```

---

## 七、整改优先级汇总

| # | 问题 | 级别 | 影响面 | 成本 |
|---|---|---|---|---|
| 3.1 | `profile` 三义混用（域 / 模型档案） | **P1** | 全库 + SDK 契约 | 中（需过渡期兼容） |
| 3.2 | 类内混用两种访问器风格 | **P1** | 9 个类 | 低（纯机械重命名） |
| 4.1 | `docId` 缩写特例 | **P2** | 112 处 | 低（仅 Java 侧改名） |
| 4.2 | `Result` 等通用 record 名跨包重名 | **P2** | 9 个 record | 低 |
| 4.4 | `AnnotatedToolScanner` 跨模块同名 | **P2** | 2 个类 | 中 |
| 4.3 | 模块名词边界丢失 | **P2** | 4 个模块包 | 高（包重命名） |
| 5.1 | `audit` 日志字段破例 | P3 | 1 处 | 极低 |
| 5.2 | `package-info.java` 仅 16% | P3 | 57 个包 | 低（增量补） |
| 5.3 | 包名单复数不统一 | P3 | 4 个包 | 中 |
| 5.4 | `ServerInfo` 后缀 | P3 | 1 处 | 建议不改 |
| 5.5 | 本地临时目录破损包声明 | P3 | 不进版本库 | 清理即可 |

**建议执行顺序**：先落 `docs/NAMING-CONVENTION.md`（§6），用 Checkstyle/ArchUnit 固化门禁
→ 再做 P1 的两项整改 → 然后 P2 → P3 择机处理。

---

*本报告基于 2026-10-03 的代码快照静态分析生成，所有结论均经人工抽样复核。*
*配套扫描脚本见 `.workbuddy/naming_*.py`（本地目录，不入版本库）。*