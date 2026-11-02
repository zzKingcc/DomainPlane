<!-- meta
title: DomainPlane 架构实现分析
brand: 架构
short: 架构实现分析
subtitle: 11 个 Maven 模块、543 个 Java 文件，代码是怎么分层的、一轮对话怎么走完
kicker: Analysis · 架构实现
chip1: 分析对象 · DomainPlane v1.0-beta.2
chip2: 规模 · 371 主类 / 4.96 万行 / 543 个 Java 文件
chip3: 方法 · 逐文件核对 + pom 依赖实测 + 1230 例全量测试
conclusion: 分层是真的（依赖严格单向，domain/runtime/infrastructure 三个模块零反向 import），但不是文档写的"L0-L4 四段"——真实形状是 api ← common ← domain ← {infrastructure, runtime} ← server，infra 与 runtime 并列；SDK 五模块自成一支、只走 HTTP。唯一的结构性破口是 F05 的"去 ES 强依赖"没有收口：server 模块仍有 7 个文件直接 import ES 客户端，其中 ChunkExporter 绕过 KnowledgeStore 直接对索引做 search。
-->

## 仓库总览与规模

DomainPlane 是一个**请求级装配的 Agent 编排中间件**：同一个进程里跑多个业务场景，每轮对话带一个域（如 `default.customer.aftersale`）进来，服务端沿域链把工具、知识索引、提示词、模型四维能力装配到这一轮。域是贯穿全局的唯一抽象，也是越权边界。

| 维度 | 实测值 | 口径 |
| --- | --- | --- |
| Maven 构建单元 | 12（根 pom + 11 模块） | `pom.xml` `<modules>` |
| 主代码 | 371 类 / 49,609 行 | `src/main/java` 逐文件统计 |
| 测试代码 | 172 类 | `src/test/java` |
| 最大模块 | `domainplane-server` 148 类 / 24,830 行（占主代码 50%） | 同上 |
| 管控台端点 | 15 个 Controller / 100 处 Mapping | `controller/` 包 |
| 装配类 | 33 个文件：19 个 `@Configuration` + 11 个 `@ConfigurationProperties` + 2 个热替换代理 + 1 辅助 | `server/config/` |
| 后台定时任务 | 7 个 `@Scheduled`（实例判死 / 档案对账 / MCP 对账 / 指标采样 / 软删清理 / 版本巡检 / blob 回收） | 全仓 grep |
| 测试 | 1,230 例，0 失败 / 2 跳过，全量 4 分 27 秒 | `mvn -o test` 本次实跑 |

<!-- @layers -->
| 徽章 | 层 | 一句话职责 | 关键文件 |
| --- | --- | --- | --- |
| L0 契约 | domainplane-api | 唯一服务契约 `AgentService`、事件、注解、错误码、归档 SPI | AgentService, AgentEvent, ErrorCode |
| L0 底座 | domainplane-common | 异常体系、输入清洗、CJK 文本度量、指标、原子落盘 | InputSanitizer, MetricsRegistry, AtomicFiles |
| L1 领域 | domainplane-domain | 域无关的纯领域算法：记忆双约束、检索编排、融合算法、存储 SPI | DualConstraintChatMemory, CompositeRetriever, KnowledgeStore |
| L2 适配 | domainplane-infrastructure | 所有对外部世界的实现：ES/Qdrant、Redis、8 种格式摄取 | EsKnowledgeStore, QdrantKnowledgeStore, RedisCheckpointSaver |
| L2 内核 | domainplane-runtime | 编排引擎、工具体系、上下文装配、会话记忆、多模态 | AgentOrchestrationService, AgentGraphFactory, ToolRouter |
| L3 外壳 | domainplane-server | 装配、管控台、鉴权、模型层、MCP、知识库服务 | GraphConfiguration, AdminController, ModelAdapterRegistry |
| 消费侧 | sdk-core / client-core / chat-client / kb-client / tool-provider | 三个 SDK 只走 HTTP，不依赖 runtime/server | DomainPlaneAgent, KnowledgeBaseClient, ToolInstanceClient |

## 分层与依赖方向：实测，不是文档

文档把系统写成 L0 契约 / L1 领域与存储 / L2 运行时内核 / L3 服务端外壳 / L4 消费侧五层。实测依赖方向如下（逐条读 `pom.xml` 的内部 `<dependency>`，再对 `domainplane-{domain,runtime,infrastructure}` 全量 grep 反向 import）：

```text pom 内部依赖（实测）
domainplane-api            → 无内部依赖（langchain4j-core / reactor / jackson-annotations / slf4j）
domainplane-common         → api
domainplane-domain         → common（不直接依赖 api，api 靠 common 传递）
domainplane-infrastructure → common + domain      ← 适配器在 SPI 之上
domainplane-runtime        → api + common + domain ← 与 infrastructure 并列，全文无 infrastructure
domainplane-server         → api + common + domain + infrastructure + runtime
sdk-core                   → api + common
client-core                → sdk-core
chat-client / kb-client    → client-core
tool-provider              → api + common
```

**结论**：编译期分层是干净的——反向 import 命中数为 0，SDK 五模块从不接触 runtime/server，server 不反向依赖任何 SDK。但真实形状不是四段，而是：

```text
api ← common ← domain ← { infrastructure , runtime } ← server
```

`infrastructure` 不在 `domain` 同级之下，而在**其之上**（适配器实现 SPI，方向 infra → domain 是对的）；`runtime` 与 `infrastructure` 是兄弟，不是上下级。这一点值得写进文档，因为它决定了"能不能单独替换存储后端"这个问题：runtime 不认识 infra，所以换后端对编排内核零影响。

### 两处越界

| 越界 | 证据 | 性质 |
| --- | --- | --- |
| server 直接用 ES 客户端 | `server/knowledge/ChunkExporter.java:139,152` 直接 `esClient.search(...)`；`AdminController.java:38,83,108` 注入 `ElasticsearchClient`；`InfraSettingsHolder`、`SwappableElasticsearchTransport` 同 | F05「去 ES 强依赖」未收口：7 个 server 文件绕开 `KnowledgeStore` SPI |
| 接口下沉、实现上浮 | `BlobStore` 定义在 `runtime/storage/BlobStore.java:27`，实现 `server/storage/LocalBlobStore.java:39` | 内核定义存储接口、外壳提供实现，方向反了 |

## 服务端装配：19 个配置类怎么分工

`server/config` 不是一坨，按职责可以分成七组：

| 组 | 代表类 | 装什么 |
| --- | --- | --- |
| 总装 | `DomainPlaneServerAutoConfiguration` | `@ComponentScan` 4 个包 + `@Import` 9 个配置 |
| 模型 | `ModelProfileConfiguration` | 档案库 / 适配注册表 / 元数据 / 客户端工厂 / 端点客户端 / 默认模型解析 |
| 编排 | `GraphConfiguration` | 执行器、并发闸门、`OrchestrationDeps` 17 字段装配、摘要、提示词解析 |
| 记忆 | `MemoryConfiguration` | Redis 记忆 / 归档 / 倒排索引 / 向量索引 / 装配清单 / 检查点 |
| 存储 | `EsClientConfiguration` / `RedisClientConfiguration` / `StorageConfiguration` | 两套可热替换客户端 + blob 根目录 |
| 检索 | `RetrievalConfiguration` / `ChunkingConfiguration` | 通道提供者、`CompositeRetriever`、融合策略映射、切片参数全局取值 |
| 运维 | `SingleInstanceGuardConfiguration` / `StartupSelfCheckConfiguration` / `WebMvcConfig` | 单实例租约、启动自检、鉴权拦截器与 CORS |

### 热切换怎么做到"不重启"

ES / Redis 连接改配置要立即生效，且不能把正在跑的检索打断。做法是三段式：**注册 → 交换 → 延迟销毁**。

```java server/config/SwappableElasticsearchTransport.java:49-94
public SwappableElasticsearchTransport(InfraSettingsHolder holder) {
    this.holder = holder;
    this.delegate = build(holder.current().getEs());
    // 先建好 delegate 再注册，避免注册后立刻被并发 swap 覆盖
    holder.register(this);
}

@Override
public synchronized void swap() {
    InfraSettings.Elasticsearch cfg = holder.current().getEs();
    RestClientTransport fresh = build(cfg);
    RestClientTransport old = this.delegate;
    this.delegate = fresh;     // volatile 原子引用替换
    scheduleClose(old);        // GRACE_SECONDS = 30，让在途请求跑完
}
```

`InfraSettingsHolder.apply()` 全程 `synchronized`：先 `fillBlankSecrets` → `rejectInvalid`（写盘前拦住"填了但不可能连上"）→ `store.save` → 逐个 `swappable.swap()`，单个 swap 失败只 `log.error` 不中断其余。旧实例进 `PENDING_CLOSE` 集合由单线程守护调度器延迟关闭，`close()` 时兜底关掉宽限期内没关掉的（防 `RestClient` 的非守护线程挂住 JVM 退出）。

## 一轮对话的端到端数据流

<!-- @flow -->
| 序 | 环节 | 做什么 | 落点 |
| --- | --- | --- | --- |
| 1 | HTTP 入口 | `POST /api/agent/chat` → 归一化 + 恶意输入检测；命中则返回固定话术（不写记忆、不调模型、不建断点） | ServerAgentController:70-116 |
| 2 | 域校验 | 域存在 ∧ 可调用 ∧ 不在软删宽限期，否则 400/409 | AgentOrchestrationService:511-551 |
| 3 | 登记流 | `streamSinks.register(key)` + `onDispose`（客户端断开即请求停止） | :648-657 |
| 4 | 过闸门 | `concurrencyGate.tryAcquire()` 取不到 → `SYSTEM_BUSY` 快速失败 | :661-668 |
| 5 | 提交执行 | 虚拟线程执行器，每轮一线程 | :671 |
| 6 | 同会话串行 | `tryMarkRunning` 失败即拒；失败路径先归档收尾 | :723-771 |
| 7 | 入参装配 | 视觉三路判定（INLINE/DELEGATE/UNAVAILABLE）、媒体暂存、拼 `UserMessage` | TurnInputFactory:124-400 |
| 8 | 写记忆 | 先记条数作回滚锚点，再 `memory.add(userMessage)` | :972-979 |
| 9 | 上下文装配 | 系统提示 + 近况窗口 + 摘要段 + 卡片段，预算 24k 逐级收窄 | TurnContextFactory:124-282 |
| 10 | 跑图 | `compiledGraph.stream(...)`，节点 agent →（exit / auto / review）→ tools → agent | AgentGraphFactory:182-205 |
| 11 | 出流 | 唯一出口 `StreamContext.emit` → SSE（8 种事件） | StreamContext:62-84 |
| 12 | 收尾 | 写最终回答 → 关轮归档 → 建倒排索引 → 附件续期 → 摘要排期 → 释放闸门 | TurnLifecycle:166-246 |

编排包 8 个类的分工边界很清楚，这是本轮升级（F07）的成果：

| 类 | 行数 | 只管一件事 |
| --- | --- | --- |
| `AgentOrchestrationService` | 1,203 | 公开入口 + 一轮的模板（runTurn） |
| `AgentGraphFactory` | 437 | 造图：三节点 + 条件边 + 工具可见性复核 + 等模型分片 |
| `TurnInputFactory` | 435 | 本轮入参：内容块 → UserMessage、视觉路由、媒体暂存 |
| `TurnLifecycle` | 415 | 一轮之后的收尾与持久化 |
| `TurnContextFactory` | 296 | 这轮给模型看多少 |
| `TurnFailureHandler` | 148 | 失败定性（停止 → 未配置 → 通用，三段固定优先级） |
| `OrchestrationDeps` | 87 | 17 字段 record，替代 8 级构造器阶梯 |
| `ConcurrencyGate` | 69 | 显式信号量闸门（虚拟线程下唯一的背压） |

## 校验与守护体系：五道闸

| 道 | 守什么 | 位置 | 失败姿态 |
| --- | --- | --- | --- |
| 启动期 | 单实例租约抢不到 | `SingleInstanceGuard:86-107` | 中断启动（"多实例的失败方式是不报错的错误结果"） |
| 启动期 | 知识后端能力不满足召回策略 | `KnowledgeBackendValidator:57-125` + `KnowledgeBackendsConfiguration:226-230` | 抛 60007，启动失败 |
| 写入期 | 域路径字符集 / 链上重复 / 只能叶子可调用 | `Domains.validatePath:106-112`、`DomainRegistry:266-276` | 400 |
| 写入期 | 存储配置"填了但不可能连上" | `InfraSettingsHolder.rejectInvalid:522-532` | 拒绝保存 |
| 写入期 | 域配置已损坏时禁止覆盖写 | `DomainStore.assertWritable:332-338` | 抛异常（写回等于抹掉全部人工域） |
| 运行期 | 工具执行前重新核对域可见性 | `AgentGraphFactory:457-478` → `ToolRouter:138-142` | fail-closed，区分 TOOL_PERMISSION_DENIED / TOOL_NOT_FOUND |
| 运行期 | 存储读失败不降级为空 | `KnowledgeStore.exists/deleteCollection/write` 全部 fail-closed；`RedisCheckpointSaver` 读失败抛 `CheckpointCorruptedException` | 抛，不返回空 |
| 自检 | 提示词声明的工具 vs 实际可见工具 | `PromptToolConsistencyAudit` + `StartupSelfCheckConfiguration:37-43` | 只告警 |

这套体系有个一致的取向：**凡是"失败"与"空结果"会长得一样的路径，一律抛异常**。这是整个代码库最稳的一条工程纪律，第五节会展开它的代价。

## 架构评估

<!-- @callout ok 优势一：越权边界是结构性的，不靠过滤条件 -->
> 检索域 D 时只查 D 自身与祖先链上的索引（`DomainChannelProvider` 通道 = 域链 × 2 路），文档归属由"上传到哪个域的索引"决定，`metadata.domain` 不参与过滤。这意味着漏写一个 `WHERE domain = ?` 不会造成越权——越权需要"查错索引"，而索引名由域路径确定性派生（`KbIndexes.nameOf`）。同理，工具可见性在装配期按域裁剪、在执行前再复核一次。

<!-- @callout ok 优势二：内核不认识外部世界 -->
> `domain` 只有 32 个类，全是纯算法与 SPI（记忆双约束、检索编排、融合、存储契约）；`runtime` 全文没有 infra 依赖。换向量库（ES → Qdrant）这件事在代码里表现为"新增 kb/qdrant 包 + 注册一个 backend"，编排内核零改动——本次升级的 `kb/qdrant` 9 个类不需要碰 `runtime` 任何一行。

<!-- @callout warn 代价一：外壳偏胖，且开始回吸 -->
> `domainplane-server` 占主代码 50%（148 类 / 24,830 行），其中 15 个 Controller 有 100 处 Mapping，最大的 `AdminController` 1,512 行、`AdminModelProfileController` 1,177 行。更麻烦的是它开始反向吸收本该在 infra 的东西：`ChunkExporter` 直接 `esClient.search`，`AdminController` 直接注入 `ElasticsearchClient`——SPI 之上又长了一条旁路，换后端时这两处要单独改。

<!-- @callout warn 代价二：域语义分散在三处，靠注释维系 -->
> 域的"声明"在 `DomainStore`（落盘 + 版本快照 + 软删标记）、"生效判据"在 `DomainRegistry.isEffectiveCallable`（显式标记 ∧ 无子域）、"工具声明史与撤销态"在 `ToolDomainRegistry`。三者一致靠 `DomainConfiguration:35-92` 的装配顺序和大量注释维持，没有编译期约束。新人改域相关逻辑，改错一处不会报错，只会在某个边角（比如心跳把撤销的域加回来）表现为偶发问题。

## 结论

1. **分层可被外人验证**，不是口号：pom 依赖单向、零反向 import、SDK 完全靠 HTTP 解耦。文档需要修正的是层级描述（infra 与 runtime 并列）。
2. **域的抽象密度很高**：一个字符串（点分路径）同时决定了工具可见性、知识索引、提示词链、模型绑定和会话状态键（`(域, sessionId)`）。这是整个设计最省的地方，也是最贵的地方——省掉了全套租户表，代价是所有相关逻辑都必须对"域链"有正确理解。
3. **本次升级把"可替换"落到了存储层**（KnowledgeStore / KeywordSearcher / VectorSearcher + StoreCapabilities + FanOutStore），但收口没做完：server 侧还有 7 个文件直连 ES 客户端，两处接口/实现分层反了。
4. **单实例内存态是最大结构约束**：`CancellationRegistry`、`StreamSinkRegistry`、`InstanceRegistry` 都在 JVM 堆里，`SingleInstanceGuard` 用启动期租约把这条约束显式化——这既诚实地承认了现状，也把多实例化推到了"重写这几处"的量级。
