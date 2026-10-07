# 示例 01 · 对话框

一个 controller + 一个聊天页面。调用方只说一句话，域与会话由后端补齐。

```bash
# 服务端必须先起来（SDK 有不可关闭的启动期连通性校验）
java -jar domainplane-server/target/domainplane-server-v1.0-beta.1.jar

mvn -pl examples/chat-window -am spring-boot:run
```

页面：http://localhost:9530

配置（`src/main/resources/application.yaml`）只有服务端地址与接入账号，
也可以用环境变量 `DOMAINPLANE_SERVER` / `DOMAINPLANE_USERNAME` / `DOMAINPLANE_PASSWORD` 覆盖。

> 服务端还需要 Redis（对话记忆与检查点存在那里）。没跑 Redis 时每轮对话都会返回
> `70004 CHECKPOINT_ERROR`，页面会把它当错误显示出来。

## 请求与返回

```http
POST /api/chat
{"message": "你好"}
```

请求体只有 `message`：域固定在 `ChatController.DOMAIN`（根域 `default`），
会话在进程内固定一个 UUID。两者都不经过 HTTP 边界，调用方没有传错的余地。

返回是 `text/event-stream`，每帧就是一条 `AgentEvent`，中间没有转换层：

```
data:{"type":"THINKING","sessionId":"...","content":"先看订单表…"}
data:{"type":"TOKEN","sessionId":"...","content":"你"}
...
data:{"type":"DONE","sessionId":"..."}
```

**每段都有标记，消费方按帧类型自行决定怎么展示**：`THINKING` 是模型的思考增量，
`TOKEN` 是答案增量，两者<u>不混在一条流里</u>，平台也不解析思考内容。
本页面把 `THINKING` 渲染成左侧细线的灰色小块（见 `app.js` 的 `appendThinking`），
答案一开始就把它压暗 —— 换个样式（折叠、隐藏、单独面板）只改这一处即可。
关掉档案里「输出思考」的模型不会产生 `THINKING` 帧，页面不需要为此分支。

## 用的 SDK 接口

```java
DomainPlaneAgent agent = agents.forDomain(DOMAIN);
Flux<AgentEvent> events = agent.events(sessionId, message);   // 冷流，订阅后才开始执行
```

## 日志

一轮的状态全在日志里，排查"发了没反应"直接看它：

```
[示例] 收到消息：域[default] 会话[<uuid>] 长度=5 字     ← 请求到底进来没有
[示例] 门面已就绪：agent=DomainPlaneAgent{domainId='default'}
[示例] 第 1 帧 TOKEN(模型增量输出) content=你 ...        ← 每一帧事件
[示例] 本轮结束：会话[<uuid>] 共 42 帧，耗时 3810ms     ← 收尾（或下面的失败行）
[示例] 本轮失败：会话[<uuid>] 已收 3 帧，耗时 120ms，原因：...
```

## 一处值得记住的坑

**对话 SDK 的凭证层是阻塞的**：`ClientCredential.get()` 内部 `block()`，而且是在 `events(...)`
里同步调用。WebFlux 的请求处理跑在 Netty 事件循环线程上，直接返回 `agent.events(...)`
会抛 `IllegalStateException: block() ... not supported in thread reactor-http-nio-N`。

解法是把"建流 + 订阅"整体挪到阻塞安全的线程池上：

```java
return Flux.defer(() -> agent.events(sessionId, message))
        .subscribeOn(Schedulers.boundedElastic())
        ...
```
