# DomainPlane 差异化痛点清单

> 写法：**他们怎么解决不了 → 我们怎么解决**。
> 每条都带代码/文档落点，可被追问时直接翻。
> ⚠️ 末尾「不能碰」一节列出**我们也没解决**的，别在对外材料里说漏。

---

## 一、工具接入与治理

### 1.1 工具住在平台里，就永远隔着网络

**他们**：Dify / 扣子的工具是**平台侧资产**——导入 OpenAPI、装插件、连 MCP。无论你的工具原本是一个 Spring I@ServiceI 方法，还是一个内部 gRPC 服务，要接进来都得被翻译成平台能理解的形态：HTTP 端点、OpenAPI 描述、或 MCP server。翻译就意味着：**参数 schema 与真实方法签名分离、网络跳数 +1、工具逻辑不在业务事务边界内**。

**我们**：工具就是业务进程里的一个方法。

IIIjava
@Tool(desc = "按订单号退款。仅在用户明确要求退款时调用",
      effect = Tool.Effect.WRITE, approval = Tool.Approval.ALWAYS)
public String refundOrder(@ToolParam("订单号，如 FR2024001") String orderNo, ...) { ... }
III

参数 schema 由**方法签名 + 注解**推导（IParamSchemaResolverI，在 Idomainplane-apiI），服务端本地扫描、远端实例上报、上报 JSON 渲染**共用这一棵树**——同一段工具代码搬到服务端进程，参数结构不变。工具可以直接读业务事务里的本地状态、复用已有的 I@TransactionalI 边界、访问内网数据库而无需开通网络策略。

> 落点：Idocs/DESIGN.mdI §5.1/§5.2，IParamSchemaResolverI

### 1.2 工具进程挂掉，平台侧往往没有"治理"这个概念

**他们**：平台把工具当插件/应用，工具**进程**的生死不在平台视野里。工具作者重启了进程、改了描述、扩了副本，平台侧要么无感（等下次调用超时），要么只能整体改配置。

**我们**：工具实例是一等公民，有完整生命周期状态机。

- 5s 心跳**整包上报**（本次声明为准，地址无条件校对）
- 35s 未收到心跳 → IDRAININGI → IONLINE/OFFLINEI
- **同名工具多副本**路由轮选，传输层失败自动换下一个（Iinvoke-max-attempts=2I），**业务失败不重试**
- 管控台对**单个实例**熔断（静音）/ 恢复 / 强制下线（返回 410 + 1h 保留窗），**不用改工具方任何代码**
- 实例下线**不截断会话**——会话属于域，不属于实例

> 落点：Idocs/DESIGN.mdI §5.3/§5.4，IToolRegistryI / IInstanceRegistryI / IInstanceLifecycleI

---

## 二、执行期强制：权限与审批

### 2.1 越权不是"少给一个工具"，而是必须让模型知道边界在哪

**他们**：Dify 的工具在应用发布时就钉死了；工作流里的条件分支只能决定**走哪条路**，不能决定"这个工具此刻可不可用"。终态通常是**静默剔除**——工具不传给模型，模型不知道它存在，于是要么改口说没有这个能力，要么编一个。

**我们**：越权是**显式拒绝 + 文本回灌**。

| 场景 | 码 | 行为 |
|---|---|---|
| 工具不在当前域 | I10001I | 拒绝执行，错误文本回灌给模型，**流不结束**，模型可换路径 |
| 域不存在 | I10004I | fail-fast，**绝不回退为全量工具** |
| 域存在但不是可调用单元 | I10010I | fail-fast，文案带出当前可调用集合与修法 |
| 工具已下线 | I80001I | 拒绝，不执行 |

关键区别：**不静默降级**。工具集每轮**实时读注册表，不缓存快照**——实例熔断、工具下线、域声明调整，下一轮立即生效。

> 落点：Idocs/DESIGN.mdI §4/§5.5，IToolRegistry.toolSpecificationsI 与 ItoolsRequiringApprovalI 同源

### 2.2 "点了批准"和"其实没批准"必须能区分

**他们**：Dify 的 HITL 依赖工作流节点 + 外部状态存储，多数形态下"用户没回复"和"用户拒绝了"在系统里是同一件事——挂着的任务既不会自己作废，也不会被清理，事后任何一次回调都可能把那个待授权动作执行掉。

**我们**：把"视为拒绝"写成**显式不变式**。

- Iapproval = ALWAYSI → 发 IINTERRUPTI 帧 + 中断图，断点落 Redis，**服务端重启后仍可恢复**
- 挂起未批，用户直接开新对话 → **视为拒绝**：该轮照常处理，补一条占位回答让这一轮完整，**同时删掉断点**（待授权动作永久作废）
- IresumeI 必须携带与 IchatI 相同的域，跨域 resume 报 I30001I；**按本次域重新校验工具可见性**（工具中途下线也拦下）
- ⚠️ 敏感参数 I@ToolAdvanced(sensitive={"phone"})I 在**工具调用事件与审批 payload 里掩码，执行仍用原值**——审批的人看到的是脱敏值，不是"看不到内容"

> 落点：Idocs/DESIGN.mdI §5.5/§6，IApprovalRequiredExceptionI / IServerAgentControllerI 入口处理

### 2.3 提示词里点了工具，工具却不可见——这是配置错误，不是运行期惊喜

**他们**：提示词与工具集是两份独立配置，平台不校验二者一致性。写错就是运行期才发现。

**我们**：**启动期自检**。提示词里点名的工具必须在对应域可见，否则 WARN（不阻断，因为自检绝不能把服务端拉不起来）。域字符串也收口：工具 manifest 的 IdomainsI 与 I@Tool(domains=)I **格式非法整包拒绝/启动失败**；KB 归属域 / 提示词 / 模型绑定**必须已登记**。

> 落点：IPromptToolConsistencyAuditI，Idocs/DESIGN.mdI §4「写入收口」

---

## 三、知识库：入库与检索

### 3.1 "支持 PDF/Word"和"能切出可用结构"是两件事

**他们**：平台侧统一走通用文档解析（Tika 或等价物），输出**格式无关纯文本**。实测这条路对 docx 丢标题层级、对 pdf 丢字号——而这两样恰好是切片唯一要用的东西。切出来的块没有结构，检索时"这条讲的是第几章"就丢了。

**我们**：两层结构，**格式适配层读结构 + 通用切片层复用**。

| 格式 | 分节依据 | 关键点 |
|---|---|---|
| docx / doc | 标题（大纲级别 / IParagraph.getLvl()I） | 旧 I.docI 认标题**只能靠 IgetLvl()I**（语言无关）；样式名本地化只能作正向信号，两路顺序不能反 |
| pdf | 页（I文件名 > 第N页I） | PDFBox 抽行拿文本 + 字号 + 坐标 + 页码；页眉页脚靠**数字归一化后跨页同位置重复**判定；跨页断句归起始页 |
| excel | 工作表 | IWorkbookFactoryI 按文件头认型；公式格必须传 IFormulaEvaluatorI；合并格按区域回填 |
| txt | 正则猜（**数字编号必须带分隔符**才算标题） | 只两级；重叠单位是句 |
| md | 自研解析 | — |

文本类入口**探测一次编码**（BOM → 严格 UTF-8 → GB18030），判不出即拒，不猜；**二进制类绝不解码**。表格由 IMarkdownTableI 统一渲染成 markdown，docx/doc/excel 共用一份。

> 落点：Idocs/MULTI-FORMAT-INGESTION-DESIGN.mdI、Idocs/TXT-INGESTION-DESIGN.mdI、IIngestDocumentI / IBlockI / IBlockSplitterI

### 3.2 跨域检索：多路结果混在一起"看起来成功"，其实召回已经塌了

**他们**：一个知识库一个索引，全库检索或元数据过滤。做多份知识库合并召回时，常见做法是**混池后过滤**——两路各回 Top-N，混进不该来的文档会把本域结果挤掉，融合之后过滤就只剩一两条。检索"成功"了，答案质量塌了，**且不报错**。

**我们**：域边界在**召回通道层**确定（查哪些索引），不是召回后过滤。

- 一域一索引，索引名 IKbIndexes.nameOfI（域路径哈希，Idomainplane_kb_*I），按需建
- 检索域 D → 通道 = IchainOf(D)I 上每个索引 × 两路模态（向量 + BM25）
- 索引不存在时通道**安静返回空**（IignoreUnavailableI），不预探测——省掉 N 次往返

### 3.3 多索引融合：分池化 BM25 是一个静默的错误答案

**他们**：多路结果合并常直接加权求和。但 **BM25 的 idf 用本索引的文档频率计算**，同一个词在不同索引里量纲不同——分池化后做 min-max，会把"小索引里稀有词被抬高的分"当成 max，把其余结果压扁。系统正常返回，排序全错。

**我们**：**按实际有命中的来源数自适应切换融合策略**。

- ≤ 1 个来源 → IDefaultFusionStrategyI（分数制，min-max 归一化 + 乘法增益）
- ≥ 2 个来源 → IRrfFusionStrategyI（排名制，Iscore(d) = Σ w_L / (k + rank_L(d))I，k=10）——**只看名次，天然免疫跨索引 idf 不可比**
- 判据是"**有命中的**来源数"而非"通道里出现过几个来源"（通道按域链无条件解析，祖先域没文档时根本没索引，按通道数判会让任何非根域一律走 RRF）
- RRF 权重**均摊**：每张表 Iw = 模态权重 / 该模态表数I，**空表不占份额**；再乘 Iancestor-decay^depthI（默认 0.7，本域自有知识优先于从祖先继承的），同模态内重新归一化
- 增益是**乘法** I分 × (1+boost)I 不是加分——RRF 分约 0.01 量级，加 0.15 会是它的十几倍、直接统治排序
- **排序稳定性**：融合分相同按 Ichunk_seqI 升序再按内容哈希——**同一问题两次检索必须给同一份答案**

> 落点：Idocs/DESIGN.mdI §8.3，IAdaptiveFusionStrategyI / IRrfFusionStrategyI / IDefaultFusionStrategyI

### 3.4 中文关键词检索的阈值不能凭直觉调

**他们**：默认分词 + 默认 Iminimum_should_matchI，中文长问句经常零命中，且表现为"检索不到"，看不出是阈值问题。

**我们**：**在真实 ES 上量出来的值**。Iik_smartI 把「会员退款的时效说明」切成 5 个词（助词「的」也占额度），I60%I 要求命中 3 个 → 正常问题**零命中**；I30%I 及以下等于没限制。定 I50%I，有效区间 40%~50%。字段权重：Itext^1.0I / Itext.standard^0.8I（standard 分词救英文数字标识符，实测能命中 ISKU-10086I）/ Isection_path^1.5I / Isection_title^2.0I。

**空结果与服务不可用是两态分离**：前者返回"未检索到相关内容"，后者返回"知识库检索服务当前不可用"——不会把故障说成没资料。

> 落点：Idocs/DESIGN.mdI §8.3，Iminimum-should-matchI / Ivector-min-score=0.35I

---

## 四、会话与记忆

### 4.1 记忆满了就静默截断 = 用户永远不知道自己丢了什么

**他们**：平台侧多按 token 预算截断或摘要历史。用户看到的是"AI 好像忘事了"，没有任何可编程的信号。

**我们**：**显式拒绝 + 零副作用**。

- 双约束：Imax-messages=100I 且 Imax-tokens=30000I，**只增不淘汰**
- 到上限 → 入口拒绝该轮，I30004 SESSION_MEMORY_FULLI，**不写记忆、不清断点、不调模型**
- 拒绝是**粘性**的：只要还满着就每次拒绝
- **平台不代为换 sessionId**（换 sessionId 是业务语义，平台无权替用户决定）
- 上限只约束入口——**最终回答永远可写**（否则留下"有问无答"的孤立提问）

> 落点：Idocs/DESIGN.mdI §6

### 4.2 会话隔离的键放在哪，决定了权限撤销时会发生什么

**他们**：Iuser_idI / Iconversation_idI 隔离是会话面的，权限变化不联动——人调岗了，历史对话还在；或者会话串了，跨租户看得见别人的上下文。

**我们**：状态键 = **I(域, sessionId)I**（ISessionKeysI，分隔符 I|I）。不同域可以用同一个 IsessionIdI 而互不影响；域一致性**由键承载**，不依赖任何"会话→域"的进程内映射表（那种表重启即丢）。

**删域时级联清理 7 处**：注册表 + Idomains.jsonI、该域及全部子孙的 ES 索引、导出切片预览 txt、提示词、模型绑定、工具声明记录、检索器缓存，**外加 Redis 里该域的记忆与检查点**（IdeleteByDomainI，SCAN 而非 KEYS）。不清理的后果是：同路径域重建后**幽灵历史复活**，且 24h 内重建还能 resume 出当初被拦下的破坏性动作。

**索引删不干净就拒绝删域**——顺序不能反，域先没了索引就成了没人认领的孤儿。

> 落点：Idocs/DESIGN.mdI §4/§8.1/§16，IdeleteByDomainI

### 4.3 停机与停会话：不能只把 HTTP 断了

**他们**：平台侧"停止"通常表现为客户端断开订阅，服务端是否真的停下取决于编排层有没有实现取消传播。

**我们**：IstopI 置取消标志，由 IagentNode.onPartialResponseI 与 ItoolsNodeI **在工具执行前检查后抛出**——工具不会执行到一半才被打断。随后**回滚本轮记忆、清检查点、推 ISTOPPEDI、结束流**。同一 IsessionIdI 不允许并发，已在执行时直接 I30003I 拒绝，**不排队**。

> 落点：Idocs/DESIGN.mdI §5.5/§6，ICancellationRegistryI / IStreamSinkRegistryI

---

## 五、模型选择

### 5.1 "给不同用户配不同模型"在平台侧是配置问题，不是代码问题

**他们**：Dify 的模型可见性与配置权绑在**工作空间角色**上。要按终端用户选模型，只能靠工作空间隔离、API 密钥路由或网关层间接实现——都是"绕"，不是"做"。扣子止步于企业成员级，终端用户只能用 Bot 创建者选定的模型。

**我们**：域 → **有序别名列表**（IdomainBindingsI），取首个可用。

- 沿域链回落：自身没绑就向上找最近的绑定
- 整体覆盖语义，**空 = 解绑** → INotConfiguredExceptionI，**不回落默认**（悄悄用便宜的模型是最难查的一类问题）
- 档案 schema **三维正交、无 ItypeI 字段**：IendpointsI + IinputI/IoutputI + IcapabilitiesI + IdimensionsI
- 删档案 = **级联清绑**并返回 IusedByDomainsI，不留悬空绑定

> 落点：Idocs/DESIGN.mdI §9.1，Idocs/MULTI-LLM-DESIGN.mdI，Imodels.jsonI

---

## 六、可编程的错误契约

### 6.1 "HTTP 200 + 一句中文提示"是集成噩梦

**他们**：Dify 的流式响应出错时 HTTP 状态**已经发了 200**，错误只混在事件流里；再包一层工具调用，客户端连"这是错误还是正常结果"都判不了。

**我们**：IErrorCodeI 五元组 —— IcodeI / ImessageI / IhttpStatusI / **IretryableI** / **IactionI**，三条出口同源（SSE 事件码、非流式响应体、SDK 异常）。**IretryableI 是关键**：客户端据此决定退避重试还是直接提示，不用解析文案猜。

I10xxxI 权限 / I20xxxI 限流容量 / I30xxxI 编排会话 / I40xxxI 入参 / I50xxxI 系统 / I60xxxI 知识库 / I70xxxI 记忆检查点 / I80xxxI 工具 / I90xxxI 外部依赖。响应体带 ItraceIdI，MDC 贯穿入口到出口。

**已定义但未实现的三码在文档里明写"服务端不会发出"**（I20000I / I20001I / I20003I）——不拿没实现的码充门面。

> 落点：Idocs/API.mdI §5，IErrorCodeI，IServerGlobalExceptionHandlerI

### 6.2 长回答被容器 30 秒腰斩，客户端把半截答案当成功

这条不是竞品对比，是我们自己踩出来并修掉的，写在这里是因为它代表一类**"不报错、结果悄悄错"**的问题：

- MVC 栈下没配 Ispring.mvc.async.request-timeoutI → 落到 Tomcat 默认 30s，且那是**整轮总时限**不是空闲重置
- 客户端 IDONEI 落进 Idefault -> {}I 被静默吞掉 → **半截答案当成功返回**
- 修法：Irequest-timeout: -1I + 客户端校验终止事件

> 落点：Iapplication.yamlI、IDefaultDomainPlaneAgentI

---

## 七、交付形态

### 7.1 平台是 Python 栈，Java 团队要跨进程、跨语言、跨发布节奏

**他们**：Dify / 扣子是独立平台（Python 为主）。工具在平台侧插件，Java 业务侧要额外维护一层对接。

**我们**：Java 21 / Spring Boot 3.5.7，与业务同栈。**三个 SDK 按需引入、互不依赖**：

| 模块 | 什么时候引 |
|---|---|
| Idomainplane-chat-clientI | 要对话 |
| Idomainplane-kb-clientI | 要传文档 |
| Idomainplane-tool-providerI | 要当工具方（需 Idomainplane.tools=trueI） |
| Idomainplane-client-coreI / Idomainplane-sdk-coreI | 传递依赖，I@ConditionalOnMissingBeanI 只装配一份 |

**三个 SDK 都不引 Web 容器**——把容器写进 SDK 会让纯 WebFlux 宿主被误判成 SERVLET 而失去 IDispatcherServletI 装配，这是真实踩过的坑。

### 7.2 IforDomain(...)I 绑定域，写不出"忘记传域"的代码

IprofileI 是 per-request 参数，但门面在**取得实例时**就绑死域：

IIIjava
DomainPlaneAgent agent = factory.forDomain("customer-service");  // 绑一次，线程安全可复用
agent.ask(sessionId, question, tenantId, userId);             // 方法签名里没有 profile
III

裸 HTTP 入口仍接受 IprofileI，但 SDK 侧把这个易错点从 API 上消掉了。

> 落点：Idocs/DESIGN.mdI §5.1

---

## 八、运维与可观测

| 项 | 他们 | 我们 |
|---|---|---|
| 管控台 | 平台自带（面向平台成员） | 8 页（概览/模型设置/存储配置/域空间/在线实例/提示词设定/知识库/账号），零依赖不引 CDN |
| 运行指标 | 平台指标 | IGET /admin/metricsI：累计请求数、运行中会话、注册工具数、堆内存、实例维度副本数——**可接进已有监控** |
| 存储探测 | — | ES 缺 IK 分词器在「测试连接」里判读（可用/确认未安装/未探测**三态**） |
| 存储自检 | — | IRedisPersistenceAuditI 启动自检 IappendonlyI / IappendfsyncI / InoevictionI，**只告警不阻断** |
| 启动自检 | — | 提示词点名的工具必须在该域可见，否则 WARN |
| 配置热替换 | 重启生效 | ES/Redis 连接热替换，口令不回显，**无需重启** |
| 停机 | — | 优雅停机（等上限 30s）：先停收新请求，在途请求含 SSE 长连接收尾后再退出 |
| 审计 | 平台操作审计 | 独立 IAUDITI logger，Iaction/operator/result/ipI 固定 key 形式，采集侧按空格等号切分即可 |
| 零配置启动 | 依赖必须先配齐 | 未填 ES/Redis/模型**也能启动**，缺配置只在**调用时**报明确错误（I90005I）并指向该去哪一页填 |

> 落点：Idocs/DESIGN.mdI §13/§17，Idocs/API.mdI §4.7

---

## ⚠️ 不能碰：我们也没解决的

写对外材料时这四条**必须主动排除**，否则会被懂行的人一问就穿：

| 项 | 真实状态 |
|---|---|
| **按终端用户身份鉴权** | 唯一授权维度是**域**。ItenantIdI/IuserIdI 只透传给远程工具执行（IRemoteToolExecutor:96-97I）与日志，**不参与任何判定**。用户→域的映射仍要业务后端做 |
| **部门/岗位/项目/数据范围/临时授权** | 无主体、无属性、无策略引擎。IDESIGN.md §4I 明写"域是工具可见性的**唯一维度**，不叠加第二个权限维度" |
| **记忆策略体系** | 无按部门/角色/敏感级别的保留周期、聚合范围、脱敏规则；键含 IsessionIdI → 跨会话共享天然做不到。ttl 是全局永久 |
| **对话与工具调用的统一审计** | IAuditLogI 只记 I/adminI 变更类 + 工具实例注册，**不记对话与工具调用**；账号体系是"单一账号，无角色、无权限分级"（IDESIGN.md §11I） |
| 集群 / 多实例 | **明确不支持**。ISingleInstanceGuardI 启动时 ISET NXI 抢租约，抢不到即拒绝启动（会拦启动，与只告警的自检不同） |
| MCP | 未实施（IDESIGN-1.0.mdI §6 T9） |
| 入口限流 / 并发上限 | 码已定义（I20000I/I20001I/I20003I）但**代码不发出** |

---

## 一句话定位

> **DomainPlane 不是身份中台，是"能力层的请求级装配与执行期强制"。**
> 业务后端持身份、组织、数据范围 → 映射到域 → DomainPlane 在每一轮对话上**实时裁剪工具/提示词/模型/知识，并在越权时显式拒绝而不是静默降级**。
> 对标点不要放在 Dify 的 RBAC（那条线你输且它确实有成员级知识库权限），要放在 **"发布期静态装配 vs 请求级动态装配"**——这是架构级的差异，短期改不进来。
