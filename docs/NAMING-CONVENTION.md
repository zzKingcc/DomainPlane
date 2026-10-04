# DomainPlane 命名约定

> 本文只写**代码里已经做到的**约定 —— 每条都能在 `src/main/java` 里找到实例。
> 反例（曾经存在、已改掉的）列在文末「已修正的历史问题」，**不要重新犯**。
> 门禁建议见文末 §5。

## 1. 缩略词按单词处理

首字母大写 + 其余小写。**禁止** `ESIndexManager` / `LLMSettings` / `PDFReader`。

正确：`EsIndexManager` `EsMetadata` `EsCompatibility` `EsProbe` `LlmSettings`
`LlmModelHolder` `LlmFailureDescriber` `PdfReader` `DocxReader` `RagProperties`
`AiModelConfiguration` `CjkWidth` `KbIndexes` `TxtNormalizer`

标识符结尾的 `Id` 也是同一个规则：`domainId` `sessionId` `traceId` `instanceId`
`threadId` `requestId` `checkpointId` —— **不是** `ID`。

> 第三方方法签名（如 POI 的 `getStyleID()`）随第三方，不改。

## 2. 三个"文档"词根各管一段，别混用

这是本项目最容易踩的一条 —— 同一个业务概念历史上出现过三套叫法。

| 词根 | 管什么 | 实例 |
|---|---|---|
| `Ingest*` | **导入过程** | `IngestSource`（待导入的来源）、`IngestReport`（导入报告）、`IngestLimits`（导入限额） |
| `Document*` | **文档实体** | `DocumentProcessStrategy`、`DocumentIngestor`（导入文档的组件）、`DocumentItem` |
| `Doc*` | **具体文件格式** | `DocReader`（.doc）、`DocxReader`（.docx），与 `ExcelReader` / `PdfReader` / `MarkdownReader` 同列 |

**`IngestSource` 不能叫 `IngestDocument`**：同包已有 `DocumentIngestor`
（"导入文档的组件"），若再有 `IngestDocument`（"待导入的文档"），同一组词出现
**两种相反顺序**，读代码时分不清谁是谁。`IngestSource` 同时避开了与 langchain4j
`Document` 的撞车（`IngestSource` 的 javadoc 记录了这个决策）。

## 3. 「域」在代码里叫 domain；profile 只属于契约层

`domain` / `domainId` 是代码侧的正式词根。`profile` 出现在**传输层**，因为
它已写入 `docs/API.md` 与 `docs/SDK-CONTRACT.md`，是已发布的 HTTP / SDK 契约。

| 层 | 用词 | 实例 |
|---|---|---|
| 传输层 / 契约层 | **profile** | `AgentRequest.profile`（JSON 字段 `profile`）、`CallerContext.profile`、`ErrorCode.PROFILE_NOT_FOUND` |
| 代码实现层 | **domain** | `ToolRouter.knownDomains()`、`ToolRegistry.containsDomain()`、`ToolDescriptor.visibleIn(domainId)`、`StreamContext.domainId()` |
| 模型档案 | profile（"档案"义，正确） | `ModelProfile`、`ProfileData`、`profileRegistry` |

判定口诀：**看到 `ModelProfile*` 是档案，看到 `PROFILE_*` 错误码是域的契约名，
其余 `profile` 一律是历史契约层。**

## 4. 访问器：record 风格（去 `get` 前缀）

`sessionId()` 而非 `getSessionId()`。项目大量使用 record，新代码一律 record 风格。

**豁免（不能改）**：
1. `@ConfigurationProperties` 绑定 —— `DomainPlaneProperties.getServerUrl()` 等
2. 覆写第三方接口 —— `SwappableRedisConnectionFactory.getConvertPipelineAndTxResults()`
3. **Jackson 序列化契约** —— `AgentRequest.getSessionId()` / `ErrorCode.getCode()` /
   `AgentEvent.getType()`。这些是 JSON 出入参，改名会**静默丢字段**。
   `Lombok @Data` 生成的 getter 同理（`InfraSettings` / `LlmSettings`）。
4. **布尔方法一律 `is` / `has` / `can` 前缀**（Google Style），如 `isUsable()` /
   `isCallableDomain()`。这与"去 `get`"不冲突：`is*` 本身就是正确前缀。

> ⚠️ **不要给命令式动词加 `is` 前缀**。`stop()` / `delete()` / `verify()` / `tryAcquire()`
> / `requestStop()` 表达的是动作，加前缀会变成 `isStop()` 这种病句。
> 全库 150 个返回 `boolean` 的方法里有 59 个不带 `is` 前缀，**绝大多数是正确的**
> —— 它们要么是动作，要么是 record 访问器（`required()` / `binary()` / `atomic()`）。

## 5. 嵌套类型必须带业务前缀

裸 `Result` / `Binding` / `Data` / `Item` 这类通用词做嵌套类型名，会逼调用点写全限定名
（IDE 补全收益归零）。**类型名 = 宿主名 + 业务语义**。

现成例子：`DomainDeleteResult`（域）· `ProfileDeleteResult`（模型档案）·
`DocParseResult` / `PdfParseResult` / `CatalogResult`（各 reader / 目录）·
`DomainBinding`（域→模型别名）· `ScanBinding`（工具实例侧的方法绑定）·
`ChannelResult` · `ProfileCheck`→`DomainCheck`

**加前缀前先确认形状**：同名同形状才考虑合并成一个类型；分量不同
（`DocReader.Result` 2 个分量、`PdfReader.Result` 4 个、`ExcelReader.Result` 3 个）
**合并不了**，只能各加前缀。

## 6. 作用域遮蔽：嵌套名不得与同包顶层类同名

```java
// server/settings/DomainStore.java
public static class DomainDeclarations { ... }   // 落盘结构
// server/settings/DomainSettings.java
public class DomainSettings { ... }             // 域 → 提示词片段
```

改名之前两者**都叫 `DomainSettings`**。后果不是"不好看"，而是：
`DomainStore` 内的简单名解析到嵌套那份，`AdminController` 通过 import 解析到顶层那份
—— **同一个词在同一个包里指两个结构**。读代码时看到 `DomainSettings` 根本不知道是哪个。

**规则**：新增嵌套类型前，先确认同包没有同名顶层类。宁可长一点也别撞。

## 7. 常量与字段

- `static final` → `UPPER_SNAKE_CASE`。**唯一两类豁免**：`serialVersionUID`（JDK 约定）、
  `Logger` 字段（全库 55 处，54 处叫 `log`，1 处 `audit` 因其 logger 名不同）。
- 普通字段 → `lowerCamelCase`，全库无 `_` 结尾。
- 方法名 `lowerCamelCase`，**禁止**下划线与数字后缀（`sha256` 属密码学标准术语，保留）。
- 测试类以 `Test` 结尾；测试支撑类用 `Fixtures` / `Samples`（`DocFixtures` / `PdfSamples`）。
- 跨模块同名类要加区分：`ToolRouter` 与 `ToolProviderScanner` 分别是
  "服务端本地 Bean 工具" 与 "工具实例侧上报远端"，不是一份实现的两处副本。

## 8. 门禁建议（Checkstyle）

```xml
<module name="TypeName"/>
<module name="MethodName"/>
<module name="MemberName"/>
<module name="LocalVariableName"/>
<module name="ConstantName"/>          <!-- log 字段需 exempt -->
<!-- 卡 ESIndexManager / PDFReader 这类连写 -->
<module name="AbbreviationAsWordInName">
  <property name="allowedAbbreviationLength" value="1"/>
</module>
```

ArchUnit 可加两条：`noClasses().that().areInterfaces().should().haveSimpleNameStartingWith("I")`
与禁止 `*Utils` / `*Helper` 结尾（当前全库 0 例，加了是防退化）。

---

## 已修正的历史问题（勿重新犯）

| 问题 | 修正 |
|---|---|
| `server/settings` 包内嵌套 `DomainSettings` 遮蔽同名顶层类 | → `DomainDeclarations` |
| 9 处嵌套 `Result` 跨 8 个包（分量不同，合并不了） | 各加业务前缀（§5） |
| `DeleteResult` 两份（域 / 模型档案） | `DomainDeleteResult` / `ProfileDeleteResult` |
| `Binding` 两份（域→模型 / 方法→参数元数据） | `DomainBinding` / `ScanBinding` |
| `IngestDocument` 与 `DocumentIngestor` 同词反序 | `IngestSource`（§2） |
| `InfraSettings.Es` 全库唯一单字母类型名 | `InfraSettings.Elasticsearch`（`getEs()`/JSON 字段 `es` 是契约，不动） |
| `hasProfile` 与 `acceptsProfile` 在 `ToolRegistry`/`ToolRouter` 各是**字节相同的重复方法** | 塌成一个 `containsDomain` |
| `ToolRouter` 一个类里混用"域"与"Profile"两套词 | 全部改 `domain`（§3） |
| `AnnotatedToolScanner` 跨模块同名 | toolprovider 侧 → `ToolProviderScanner` |
| `cpId` 与 `checkPointId` 同一概念两种缩写 | 局部变量 → `checkpointId`（`config.checkPointId()` 是框架方法，不动） |
| `ToolProviderScanner.AnnotatedToolScanner` 上方两段 javadoc 重复 | 合并为一段 |

## 明确不整改的（已裁定）

- **契约层的 `profile`** —— 已发布在 `docs/API.md`，是 HTTP / SDK 契约的一部分。
  代码内部一律用 `domain`，契约层保持 `profile`，这条边界是刻意的。
- **`ModelProfile*` 系列不改名** —— "档案"义用词正确，与契约层的 `profile` 语义不同
  恰恰是有意义的区分。
- **`getFilename()`**（`kb-client/KnowledgeBaseClient:78`）—— 覆写 Spring
  `ByteArrayResource` 的方法签名，不可改。同文件的 record 分量叫 `fileName` 是对的。
- **`List<Param> params`** —— 类型名单数、字段名复数，符合 Java 惯例。
