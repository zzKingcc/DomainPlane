<!-- meta
title: DomainPlane 架构演化分析
brand: 演化
short: 架构演化分析
subtitle: 102 个提交、85 天，从一份 RAG 练习代码长成一个分层中间件
kicker: Analysis · Git 演化
chip1: 范围 · 2026-08-06 → 2026-10-19（已提交）
chip2: 体量 · 32 → 425 个 Java 文件
chip3: 方法 · 全量 git log + 四次决定性重构逐文件回看
conclusion: 演化不是"越写越多"，而是四次范式切换：单体 RAG 练习 → 模块化单体（08-24）→ 域模型入场（09-27）→ 命名与语义收敛（10-04），外加一次尚未入库的存储抽象升级（10-19 起，至今 21 天只在工作区）。真正的驱动力始终是同一个问题——「怎么让一套能力服务多个业务场景而不互相污染」，答案从"多模块"逐层收敛成"域"。
-->

## 规模曲线：四个台阶

| 里程碑 | 日期 | Java 文件 | 当次提交在做什么 |
| --- | --- | --- | --- |
| `b610678` | 2026-08-12 | 32 | 把 langchain 的隐式 ReAct 循环改成 LangGraph 显式图 |
| `8fb8706` | 2026-08-24 | 44 | 单体平铺目录拆成 6 个 Maven 模块 |
| `61da30f` | 2026-09-27 | 185 | 域模型入场，SDK 三个坐标，注解收敛 |
| `de9e751` | 2026-10-04 | 288 | 全量改名 stringer → DomainPlane（357 个文件） |
| `c49bc46` | 2026-10-19 | 425 | 管控台工程化收尾（最后一个提交） |
| 工作区 | 2026-10-29 | 543 | L1-L4 升级：142 改 + 144 增，未提交 |

规模的四次跃迁各有不同的性质：32→44 是**切分**（文件搬位置，总量不变）；44→185 是**建模**（域、SDK、注解、发布文档一起进来）；185→288 主要是**改名**（不是新功能）；288→425 是**填功能**（模型层、MCP、多模态、管控台）。看清这一点很重要——单看文件数增长会误判"架构一直在膨胀"，其实是三类完全不同的动作叠在一起。

## 演化时间线

<!-- @timeline -->
| 日期 | commit | 阶段 | 标题 | 描述 |
| --- | --- | --- | --- | --- |
| 2026-08-06 | cd1a59e | A | 空仓库 | Initial commit，只有 2 个文件，无 Java |
| 2026-08-08 | 1fb4121 | A | RAG 起步 | 文档切分策略、单文本模型、Redis 持久化记忆 |
| 2026-08-12 | b610678 | A | 范式切换 | 隐式 ReAct 循环 → LangGraph 显式图（单类 +265 行） |
| 2026-08-14 | fb4ed0f | A | 状态机 | 图节点状态机、授权调用确认、中断输出 |
| 2026-08-24 | 8fb8706 | B | 模块化 | 平铺 src/main/java → stringer-{common,core,infrastructure,capability,agent,example} |
| 2026-09-18 | cd03bf6 | C | 首个发布 | stringer 0.1.0 + README 重写 |
| 2026-09-19 | ee178b8 | C | 预发布 | v1.0-beta，文档与代码对齐 |
| 2026-09-27 | 61da30f | D | 域模型入场 | DomainAgent / Domains / DomainRegistry / @Tool 家族 + 9 份设计稿 |
| 2026-09-29 | 483a62b | D | 注解收敛 | 注解收口到 @Tool 家族，@ToolAdvanced 真正生效 |
| 2026-09-30 | fd0e3df | D | 兼容清零 | 清理全部兼容残留，文档以新设计为唯一出发点 |
| 2026-10-01 | 0b615c6 | E | 域树 | 扁平同层级域 → 父子域树（完整路径 + 沿链补齐） |
| 2026-10-01 | a235ac6 | E | 一域一索引 | 知识库改「一域一索引」，检索融合按来源数自适应 |
| 2026-10-02 | 8abc654 | E | 多格式 | pdf / docx / doc / excel 摄取全部落地 |
| 2026-10-02 | 8d5373f | F | 单实例守卫 | 把「会话协调状态全在进程内」变成启动期强制假设 |
| 2026-10-02 | 31bbb52 | F | fail-closed | 断点读失败改为 fail-closed，不再静默覆盖历史 |
| 2026-10-03 | 79bec65 | F | 删除顺序 | 删域先清记忆与检查点，再动索引；级联失败即中止 |
| 2026-10-03 | 79c9b3f | G | 双槽模型 | 向量档案单独存放，与对话模型彻底分开 |
| 2026-10-04 | de9e751 | G | 全量改名 | stringer → DomainPlane，357 个文件一次性 rename |
| 2026-10-08 | 6f50a63 | H | 适配路由 | 档案按服务商协议路由到对应 langchain4j 适配器 |
| 2026-10-08 | e912b7b | H | 增量记忆 | 会话记忆改增量存取，消除每条消息全量读写 |
| 2026-10-14 | a23418d | H | 端点探测 | 模型档案支持端点探测、控制台模型页重做 |
| 2026-10-14 | 64da40f | H | beta.2 | v1.0-beta.2 |
| 2026-10-19 | 8abf8dc | I | 管控台工程化 | P1：esbuild 脚手架，10 个页面的内联脚本全部抽出 |
| 2026-10-19 | 2b620ec | I | 信封归一化 | api 层三层信封归一化，10 个页面迁移完 |
| 2026-10-19 | c49bc46 | I | 侧边栏重排 | 按配置依赖链排序 |

## 决定性重构 I：单体 → 模块化（8fb8706）

这是全项目第一次结构性动作，也是唯一一次"不改功能只改形状"的提交。`git show --stat` 显示 `pom.xml` 一次性 ±160 行、新增 6 个模块 pom，原 `src/main/java/com/zxcSpringAI/**` 全部搬进模块并按职责重新分包：

| 原位置 | 新位置 | 语义变化 |
| --- | --- | --- |
| `src/main/java/com/zxcSpringAI/aiService/AgentOrchestrationService.java` | `stringer-agent/.../agent/orchestration/` | 编排从"AI 服务"里独立成一层 |
| `src/main/java/com/zxcSpringAI/tools/ToolRouter.java` | `stringer-agent/.../agent/tools/` | 工具与编排同层，仍是"运行时"概念 |
| `src/main/java/com/zxcSpringAI/common/**` | `stringer-common/**` | 异常、清洗、计量独立，成为最底层 |
| `.../core/spi/{ChatMemoryStore,CheckpointSaver,ContentRetriever,ToolProvider}.java` | `stringer-core/.../core/spi/` | **SPI 第一次成为独立模块**，这是后来一切可替换设计的地基 |
| `.../infrastructure/{checkpoint,ingestion,memory}/**` | `stringer-infrastructure/**` | 外部实现与 SPI 分离，方向固定为 infra → core |
| `.../capability/service/{RagService,WeatherService}.java` | `stringer-capability/**` | 业务能力单独一模块（后来被删除） |
| `src/main/resources/test.txt`（314 行） | 删除 | 原型期样本数据出仓 |

值得注意的两个细节：

1. **`stringer-core` 里只有 7 个文件**（4 个 SPI + 注解 + package-info），但它是整个架构的支点。后续 8 次重构（域模型、存储抽象、MCP 桥接）全部建立在这条"内核定义接口、外层提供实现"的轴上。
2. **`stringer-example` 模块同时建、后来又被删**（`6c1698f` 移除 example 模块）。这是典型的"先留个出口，等 SDK 成熟后砍掉"——最终出口变成了 `domainplane-chat-client` / `kb-client` / `tool-provider` 三个真实 SDK。

## 决定性重构 II：域模型入场（61da30f）

`61da30f` 是整个项目语义密度最高的一次提交：新增 `DomainAgent` / `DomainAgentFactory` / `Domains` / `DomainRegistry`（199 行）/ `@Tool` 四注解家族 / `ParamSchemaResolver`（258 行），同时 `AnnotatedToolScanner` 被整体重写（260 行改动），并一次性堆进 9 份设计文档（`DOMAIN-MODEL.md` 314 行、`SDK-REDESIGN.md` 501 行、`INTERVIEW-PREP.md` 988 行……）。

它解决的是模块化之后暴露出来的真问题：模块分开了，但**一次调用该拿哪些工具、查哪些知识、用哪个模型**仍然散在配置和代码分支里。域作为"三维坐标"一次性收掉了这件事：

```text
调用 = (域, sessionId)
域 → 沿链累加：工具可见性、知识索引、提示词
域 → 就近覆盖：模型绑定
域 → 必须叶子：可调用单元
```

此后所有提交都在把这三条语义钉死：`0b615c6`（扁平 → 父子树）、`c896c05`（工具可见性/知识库/提示词统一为沿链累加）、`0f3aeb5`（模型绑定改为沿域链回落的就近覆盖）、`f9336cb`（只有叶子域可调用，根域不再豁免）。到 `f9336cb` 时，"四维继承"才完整。

## 决定性重构 III：改名与语义收敛（de9e751）

`de9e751` 动 357 个文件，其中绝大多数是**纯 rename**：`{stringer-api => domainplane-api}/pom.xml`、`com/zzkingcc/stringer/**` → `com/zzkingcc/domainplane/**`。它没有改逻辑，但影响很大——所有外部文档、SDK 用法、Docker 镜像名、日志前缀一次性换掉。

这次提交前后还夹着一次**语义清理**（`fd0e3df`「清理全部兼容残留，文档以新设计为唯一出发点」、`c8f76b6`「统一 prompt-chain 命名，去掉 legacy Profile 术语」）。这两步合起来说明一件事：项目在 10-04 前后主动放弃了"向后兼容旧命名"的选项，选择让文档与代码只描述一种现实。代价是外部使用者要跟着改，收益是此后所有讨论只有一个词汇表——这对一个语义密集的中间件是划算的。

## 决定性重构 IV：未入库的存储抽象升级（2026-10-19 → 10-29）

`c49bc46` 之后**没有任何提交**，而工作区里有 286 处改动（142 改 + 144 增，+7,392 / −5,674 行）。按目录看，这次升级做了四件事：

| 方向 | 可见证据 | 性质 |
| --- | --- | --- |
| 存储抽象（F05） | 删除 `infrastructure/elasticsearch/**`（含 7 个类 + 4 个测试），新增 `infrastructure/kb/es`（7 类）与 `infrastructure/kb/qdrant`（9 类）；`domain/rag/store` 新增 16 类 SPI | 去 ES 强依赖，首个第二后端落地 |
| 编排拆分（F07） | `runtime/orchestration` 从 2 个类变 8 个（GraphFactory / TurnInputFactory / TurnLifecycle / TurnContextFactory / TurnFailureHandler / OrchestrationDeps / ConcurrencyGate）；`AgentOrchestrationService` 2,330 → 1,203 行 | 把 1 个巨型服务拆成模板 + 5 个协作者 |
| 工具域（F08） | 新增 `runtime/domain/ToolDomainRegistry`（声明史 + 撤销态分离） | 修"心跳把已撤销的域加回来" |
| 模型适配（F10） | `resources/adapters/` 下 29 份 `.json.txt` 归档进 `disabled/`，只留 `deepseek-flash.json` + `siliconflow-embedding.json` 启用 | 停止"替每家厂商写适配"，改样板 + 用户自助填参 |

这次升级的形态很特别：**它不是一次提交，而是一整段没有入库的工作**。从工程风险看这是当前最该处理的问题（见下节）。

## 演化规律

| 维度 | 08-06 原型 | 10-19 现状 | 驱动力 |
| --- | --- | --- | --- |
| 包名 | `com.zxcSpringAI`（无组织含义） | `com.zzkingcc.domainplane`（域名 + 产品） | 开源化 |
| 模块数 | 1（平铺） | 11（严格单向） | 拆分 |
| 场景隔离 | 代码分支 / 配置项 | 域树（点分路径，四维继承） | **需求反向驱动**：一套能力服务多场景 |
| 工具声明 | Spring `@Tool` 单注解 | 四注解家族 + 域声明 + 审批标注 + schema 自动产出 | schema 双端统一 |
| 检索 | 单索引 + ES 脚本打分 | 一域一索引 + 双路召回 + 可插拔融合 + 可换后端 | 越权边界 + 后端可替换 |
| 存储耦合 | 直接 `ElasticsearchClient` | SPI（`KnowledgeStore` / `KeywordSearcher` / `VectorSearcher` + `StoreCapabilities`） | 去强依赖（未完全收口） |
| 并发模型 | 平台线程池（core 8 / max 32） | 虚拟线程 + 显式 `ConcurrencyGate` | 每轮一个线程等模型 |
| 会话状态键 | 裸 sessionId | `(域, sessionId)` | 同域隔离，防跨域串号 |
| 测试规模 | 个位数 | 1,230 例（12 模块全绿） | 回归压力 |
| 文档形态 | README | 设计文档（16 块 / 225KB）+ 4 份分阶段改造计划 | 先设计后实现 |

规律有三条值得记下来：

1. **先拆分，再建模，最后收敛命名**。顺序颠倒会有两种失败：先建模会得到一个"一个模块里的超大模型"，先收敛命名会反复改名。这个项目的顺序是对的。
2. **需求反向驱动基础层**。域树（业务需求）逼出了 SPI（基础设施抽象）：因为要"一域一索引、后端可换"，`KnowledgeStore` 才必须存在。反过来做的项目通常会把 ES 类型泄漏到业务层，然后改不动。
3. **每次范式切换都伴随一次大规模文档动作**。`61da30f` 带 9 份设计稿、`de9e751` 带 20+ 份文档改动、本轮升级带 4 份 20 万字的改造计划。文档在这个项目里不是产物，是设计的执行载体。

## 结论与风险

<!-- @callout red 风险一：21 天的工作没有入库 -->
> `c49bc46`（10-19）之后所有 L1-L4 升级都在工作区。单次 `git checkout`、一次误 stash drop、一台机器坏掉，损失的是"存储抽象 + 编排拆分 + 工具域 + 模型适配"四块升级（+7,392 行）。这是当前最高优先级工程风险，与代码质量无关。

<!-- @callout warn 风险二：提交信息编码不一致，考古成本高 -->
> 10-04 之前的提交信息是 GBK 字节流（`git log` 在 UTF-8 终端下全是乱码，必须加 `--encoding=GBK` 才能读）。这直接抬高"为什么当初这么改"的追溯成本——上面这份时间线里 10-04 前的阶段名，全部是重新解码后人工读出来的。

<!-- @callout info 结论 -->
> 这条演化线的价值不在"用了什么技术"，而在于它记录了一个判断被反复验证的过程：**多场景共用一个 Agent 平台，靠的不是配置项和代码分支，而是把业务边界提升成第一类对象（域），再让所有能力沿这个边界装配**。四次重构里前三次都在为这个判断铺路，第四次（存储抽象）是它的直接推论——边界既然是一域一索引，后端就应该可换。
