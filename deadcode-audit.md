# 无用 / 过时代码 / 冗余代码检测报告

范围：全仓 `src/main` 的 `.java`（224 个文件），按 模块 → 子包 分组。
方法：脚本高精度扫描（未用 import / 未用私有方法 / 死常量 / 重复常量）+ 逐包引用追踪。

> **判定口径（重要）**：`docs/`、`README` 等 `.md` 文档**不计入引用**。文档声明不算"有人在用"。
> 唯一保留依据是 **Java 代码侧的真实引用**（main / test）或运行时契约。
> 据此复核后，第七轮初版报告把多项误标为"疑似死代码"的成员，实际都是契约或预留，**均应保留**。

排除：`src/test` 不删。

---

## 一、已删除（内部死代码，零风险，已编译验证）

| 模块 | 子包 | 位置 | 说明 |
|---|---|---|---|
| domainplane-runtime | orchestration | `AgentOrchestrationService.describe(Throwable)` | 私有方法全类无调用 |
| domainplane-infrastructure | elasticsearch.retriever | `EsMetadata.TEXT` | `static final String` 全仓零引用 |
| domainplane-infrastructure | ingestion.doc | `DocReader.BODY_OUTLINE_LEVEL` | 私有常量全仓零引用 |

另清理未使用 import 12 处（`EsIndexManager`、`AgentOrchestrationService`、`AnnotatedToolScanner`、`AccountStore`、`AiProperties`、`DomainConfiguration`、`SwappableRedisConnectionFactory`、`DomainSettingsStore`、`InfraSettingsStore`、`LlmSettingsStore`）。
验证：`mvn -o -B test-compile` → BUILD SUCCESS。

## 二、复核后保留（曾被误标为死代码）

按 `.md` 不计引用的新口径逐项复核，以下**均有 Java 侧真实依据，保留不动**：

- `ErrorCode` 8 个零显式引用的常量（`RATE_LIMITED`/`LLM_RATE_LIMITED`/`CONCURRENT_LIMIT`/`CHAT_MEMORY_ERROR`/`TOOL_ERROR`/`TOOL_DUPLICATE`/`TOOL_EXECUTION_FAILED`/`DOMAIN_REQUIRED`）
  → **运行时反查契约**：`ErrorCode.of(int)` 遍历 `values()`，被 SDK 客户端 `DomainPlaneErrors:137`、`DefaultDomainPlaneAgent:236` 用于把服务端返回的数字码反解成枚举。删掉任一常量，`of(20000)` 即返回 `null`。
- `ParamSchemaResolver.toWireSchema` → `SchemaUnificationTest:61/76` 断言其输出，是 schema 一致性校验的基准。
- `ParamSchemaResolver.hasDescription` → 被同文件 `toWireSchema:406` 内部调用。
- `CjkWidth.countChars` → `CjkWidthTest:49/62` 断言，且 `Chunker:158` 注释显式指引使用。
- `InputSanitizer.detectDetails` → `InputSanitizerTest:205` 断言"与 isMalicious 同源防漂移"的一致性校验 API。
- `Domains.isDefault` → `DomainSemanticsTest:40/41` 断言域语义。
- `AgentRequest.messageLength()` / `ToolCallPayload.toolCount()` → 被 Lombok `@ToString.Include` 引用。
- `SensitiveMasker.isMasked` → `SensitiveMaskerTest:53/64` 断言；属"拒绝盲批"安全契约，**已实现、待宿主接线**，非死代码。
- `InstanceState.OFFLINE` → 状态机图终态（类注释第 8/10-11 行引用），有 `label` 与 `isRejecting` 语义，设计完整、尚未接线。
- `ToolSpec.withDomains/withCategory/withSideEffect/withIdempotent/withToModel`
  → 与 `withApproval` 同属 record wither 组；对应字段均被 `toManifest()` 写入上报 manifest，属**有效对外契约**，仅仓内暂未使用。
- `ToolInstanceConfig.of()`、`ToolInstanceClient` 健康检查方法 → SDK 给工具实例开发者的公开 API。
- `DomainPlaneAgent.ask/stream/resume/stop/domainId` → 对外门面。
- `DomainPlaneProperties.getServer()` / `isTools()` → **Spring `@ConfigurationProperties(prefix="domainplane")` relaxed binding 的标准 getter**，框架隐式调用；删了会导致 `domainplane.server` / `domainplane.tools` 配置失效。源码搜不到引用属正常。
- `AgentRequest.of()` → 与 `builder()` 并存的工厂入口。
- 各异常类少参构造器 → 异常 API 的标准重载形态。

## 三、冗余复核：均为有意设计，保留

- `RetrievalScoreKeys.RAW_SCORE` → 注释明写"保留别名因为融合/排序相关的键都归本类管"（模块内聚）；`EsMetadata.RAW_SCORE` 指向唯一真值 `ChunkMetadataKeys.RAW_SCORE`。链式别名是刻意的边界收敛，非重复定义。
- `BlockChunking` 与 `TxtSplitter` 的 `DEFAULT_MAX_CHARS/MIN_CHARS/OVERLAP` → 注释明写"两条独立管线，谁也不该因为对方改参数而变化"（有意解耦）。
- `MAPPER`（11 个类各持实例）、`HOLDER`（3 个类）、`CREDENTIAL_HEADER`（3 模块）→ 各模块独立持有，非重复代码。

## 四、重构残留（干净）

`CallerContext` / `tenantId` / `userId` / `租户` 在全仓 main 的 Java 引用已归零（`AuditLog` 中 Spring `ServletRequestAttributes` 属正常）。全仓无 `@Deprecated`、无 TODO/FIXME。"过时"类问题不存在。

---

## 结论

真正可安全删除的只有 **3 处死代码 + 12 处未用 import**（已执行完毕）。其余"零引用"成员经复核全部是运行时契约、对外 SDK 能力或待接线预留，激进删除会破坏 SDK 兼容性、schema 一致性校验与安全语义。

**方法论教训**：只扫 main 的静态扫描会把"被 test 断言、被同文件内部调用、被框架隐式消费（Spring `@ConfigurationProperties` / Jackson）、作为运行时反查契约、是对外 SDK 能力"的成员统统误判为死代码。判定删除前必须：① 全仓（含 test）grep；② 读该成员上下注释确认设计意图；③ 确认无反射 / 枚举遍历 / 框架绑定等隐式消费；④ `.md` 文档声明不算引用。
