# OpenAI 风格 API 全景：通道、端点、模态

> 口径与时效：本文按 **2026-11** 的 OpenAI 官方文档整理，并标注了第三方兼容层的常见偏差。
> 这套接口两年内改过三次大形态（Assistants → Responses、Realtime Beta → GA、DALL·E → gpt-image），
> 退役与新增都快，**具体到某个模型支持什么，永远以该模型页的 Modalities / Endpoints 两栏为准**。

本文回答三件事：

1. **连接通道**有几种，各自的交互形态与适用场景；
2. **端点族**有哪些（路径、请求体、产出、现状）；
3. **输入 / 输出模态**在每个端点上的具体承载字段。

不回答：怎么调参效果最好、各家模型能力对比、价格。

---

## 1. 三种连接通道（先分这个，再谈模态）

同一个"能力"可能挂在不同的通道上。判断一个模型能不能用，第一步不是看它支持什么模态，
而是**看它的模态挂在哪条通道上** —— 这决定了你的客户端要不要换形态。

| 通道 | 传输 | 交互形态 | 典型端点 | 客户端复杂度 |
| --- | --- | --- | --- | --- |
| **A. 请求-响应** | HTTP POST，一次往返 | 发一个 JSON，收一个 JSON | `/chat/completions`、`/embeddings`、`/images/generations` | 最低 |
| **B. 请求-流式** | HTTP POST + SSE（`stream:true`）或二进制流 | 发一个 JSON，边生成边收增量 | `/chat/completions`、`/responses`、`/audio/speech` | 中：要处理分片与结束标记 |
| **B'. 请求-multipart** | HTTP POST `multipart/form-data` | 上传文件 + 表单字段 | `/audio/transcriptions`、`/images/edits`、`/files` | 中：要拼 multipart |
| **C. 持久连接** | WebSocket / WebRTC / SIP | 长连接 + 双向事件流，没有"一次请求一个响应" | `/v1/realtime`、`/v1/realtime/translations`、转录会话 | **最高**：要自建音频采集/播放、VAD、事件状态机 |

三条通道的边界很容易被误解，常见两个错误：

- **"它支持音频输出，所以能当 TTS 用"** —— 错。`gpt-audio` 系列的音频是**随对话一起返回**的
  （`choices[0].message.audio.data`），要指定音色得走 `audio.voice`；而
  `/audio/speech` 是**专用合成端点**，输入是纯文本、输出是一段音频二进制。两者的请求体、计费、
  音色语义都不同，不能互换。
- **"实时语音模型也能用 HTTP 调"** —— 错。`gpt-realtime` 系列只提供 C 通道，
  官方文档里没有任何 HTTP 对话端点；HTTP 打到兼容层通常得到 404，或者一个没有 `choices` 的 200 空壳。

---

## 2. 端点族总表

`/v1` 前缀省略。**状态**一栏是 2026-11 的现状。

| 端点 | 方法 | 请求体 | 产出 | 通道 | 状态 |
| --- | --- | --- | --- | --- | --- |
| `/chat/completions` | POST | JSON | 文本 / 工具调用 / 音频（视 `modalities`） | A / B | GA，不会下线 |
| `/responses` | POST | JSON | 文本 / 工具调用 / 内置工具结果 / 后台任务 | A / B | **GA，官方主推** |
| `/completions` | POST | JSON | 文本补全 | A / B | Legacy，仅老基座模型 |
| `/embeddings` | POST | JSON | 向量数组 | A | GA |
| `/images/generations` | POST | JSON | 图片（b64 或 url） | A | GA |
| `/images/edits` | POST | multipart | 编辑后的图片 | A | GA |
| `/images/variations` | POST | multipart | 变体图片 | A | Legacy（DALL·E 2 时代） |
| `/audio/speech` | POST | JSON | 音频二进制 | A / B（字节流） | GA |
| `/audio/transcriptions` | POST | multipart | 文本 / SRT / VTT / verbose JSON | A / B | GA |
| `/audio/translations` | POST | multipart | 英译文本 | A | GA |
| `/moderations` | POST | JSON | 分类标签与分数 | A | GA（免费） |
| `/files`、`/uploads` | POST / GET | multipart | 文件对象 / 分片上传 | A | GA |
| `/batches` | POST | JSON | 批任务（5 折价） | A（异步） | GA |
| `/fine_tuning/jobs` | POST | JSON | 微调任务 | A（异步） | GA |
| `/vector_stores` | POST | JSON | 托管向量索引（供 file_search） | A | GA |
| `/models` | GET | — | 清单（**大多只给 id**） | A | GA |
| `/v1/realtime` | WS / WebRTC / SIP | 事件 | 语音 + 文本 + 工具调用 | **C** | GA |
| `/v1/realtime/translations` | WS / WebRTC | 事件 | 实时翻译音频 + 转录 | **C** | GA |
| `/v1/realtime/transcription_sessions` | WS / WebRTC | 事件 | 流式转录增量 | **C** | GA |
| `/v1/realtime/client_secrets` | POST | JSON | 临时凭据 `ek_...` | A（换取 C 的门票） | GA |
| `/v1/videos` | POST | JSON | 视频 | A（异步） | **已下线**（2026-09-24，无替代） |
| `/assistants`、`/threads`、`/runs` | POST | JSON | 助手 / 会话 / 运行 | A | **已下线**（2026-08-26，迁 Responses + Conversations） |

**没有的原生端点**（很多"OpenAI 兼容"其实是别家在扩展）：

| 常被误认为 OpenAI 的端点 | 实际情况 |
| --- | --- |
| `/rerank` | OpenAI 从未提供；Cohere / Jina / 硅基流动等私有扩展 |
| `/videos/generations` | 从未有 OpenAI 标准；Sora 的 `/v1/videos` 也已下线 |
| 视频输入 content part | 规范里**没有**统一形态；`video_url` 是部分服务商自造 |
| `/audio/voices`、`/audio/clone` | 私有扩展（声音复刻之类） |

---

## 3. 模态矩阵

### 3.1 输入模态怎么传

对话类端点（`/chat/completions`）的 `messages[].content` 是**字符串或 part 数组**，
模态由 part 的 `type` 决定：

| 模态 | part 形态 | 说明 |
| --- | --- | --- |
| 文本 | `{"type":"text","text":"..."}` 或直接传字符串 | 字符串形态是简写；有服务商只吃其中一种 |
| 图片 | `{"type":"image_url","image_url":{"url":"https://… 或 data:image/png;base64,…","detail":"low\|high\|auto"}}` | `detail` 可选，影响计费与分辨率 |
| 音频 | `{"type":"input_audio","input_audio":{"data":"<base64 或 URL>","format":"wav\|mp3"}}` | 只给 base64 的实现最多；格式取值各家不一 |
| 文件 | `{"type":"file","file":{"filename":"x.pdf","file_data":"data:application/pdf;base64,…"}}` | 有的实现只认 `file_id`（先 `/files` 上传） |
| 视频 | **规范无统一形态** | 有的用 `video_url`，有的只收图片帧序列 |

`/responses` 的写法不同（`input` 里用 `input_text` / `input_image` / `input_file` / `input_audio`），
这是"看着像、字段不通用"的典型 —— 兼容层基本只实现了 chat 那一套。

### 3.2 输出模态怎么取

| 输出模态 | 端点 | 取值字段 |
| --- | --- | --- |
| 文本 | chat / responses | `choices[0].message.content`（流式：`choices[0].delta.content`） |
| 工具调用 | chat / responses | `message.tool_calls[]` / `output[]` 里的 function call |
| 音频 | chat + `modalities:["text","audio"]` | `choices[0].message.audio.data`（base64）+ `.transcript` |
| 音频 | `/audio/speech` | 响应体就是音频字节（不是 JSON） |
| 图片 | `/images/generations`、`/images/edits` | `data[0].b64_json` 或 `data[0].url` |
| 向量 | `/embeddings` | `data[].embedding`（float 数组，或 base64 视 `encoding_format`） |
| 分数 | **非 OpenAI**（rerank 扩展） | `results[].relevance_score` |
| 视频 | 已无官方端点 | — |

### 3.3 一张矩阵看清"哪个端点吃什么、吐什么"

| 端点 | 输入 text | 输入 image | 输入 audio | 输入 video | 输出 text | 输出 image | 输出 audio | 输出 vector |
| --- | :-: | :-: | :-: | :-: | :-: | :-: | :-: | :-: |
| `/chat/completions`（文本模型） | ✅ | ✅ | ⚠️ 视模型 | ⚠️ 视实现 | ✅ | ❌ | ❌ | ❌ |
| `/chat/completions`（omni/audio 模型） | ✅ | ✅ | ✅ | ⚠️ | ✅ | ❌ | ✅ `modalities` | ❌ |
| `/responses` | ✅ | ✅ | ⚠️ | ⚠️ | ✅ | ✅ 内置工具 | ❌ 文档未覆盖 | ❌ |
| `/embeddings` | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| `/images/generations` | ✅ 提示词 | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/images/edits` | ✅ | ✅ + mask | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/audio/speech` | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ 二进制 | ❌ |
| `/audio/transcriptions` | ✅ 可选 prompt | ❌ | ✅ file | ❌ | ✅ | ❌ | ❌ | ❌ |
| `/moderations` | ✅ | ✅ | ❌ | ❌ | 标签 | ❌ | ❌ | ❌ |
| `/v1/realtime`（C 通道） | ✅ | ✅ 视模型 | ✅ 流 | ❌ | ✅ 事件 | ❌ | ✅ 事件 | ❌ |

符号：✅ 规范内；⚠️ 取决于模型或实现，规范没给统一形态；❌ 不适用。

---

## 4. `/chat/completions` 详解（兼容层的事实标准）

### 4.1 请求骨架

```json
{
  "model": "gpt-5.6-sol",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "hi"}
  ],
  "stream": false,
  "max_completion_tokens": 512
}
```

### 4.2 常用参数

| 参数 | 语义 | 坑 |
| --- | --- | --- |
| `model` | 模型 id，必须与 `/models` 里的一致 | 中转常要求带 vendor 前缀（`openai/gpt-…`） |
| `messages` | 角色 + content（字符串或 part 数组） | 部分网关只吃数组、部分只吃字符串 |
| `stream` | `true` 走 SSE | 有的网关不支持流式，回 400 或直接返回整包 |
| `stream_options.include_usage` | 末尾补一个 usage 分片 | 不支持时会被拒或忽略 |
| `max_tokens` / `max_completion_tokens` | 输出上限 | **新模型只认后者**；旧的传前者直接 400 `unsupported_parameter` |
| `temperature` / `top_p` / `seed` / `stop` | 采样控制 | 推理模型常忽略或拒收 temperature |
| `response_format` | `{"type":"json_object"}` / `{"type":"json_schema",…}` | 只给了 `json_object` 的实现多，schema 少 |
| `tools` / `tool_choice` / `parallel_tool_calls` | 函数调用 | 兼容层普遍支持，但参数 schema 校验强度不一 |
| `reasoning_effort` | 推理强度（`none/low/medium/high/xhigh/max` 之类） | 各家取值集合不同，写错直接 400 |
| `modalities` + `audio` | 打开音频输出：`["text","audio"]` + `{"voice":"alloy","format":"wav"}` | 必须配支持音频的模型；不配就是 400 |
| `store` / `metadata` | 服务端留存该次响应 | 第三方多半忽略 |
| `user` / `safety_identifier` | 终端用户标识 | 仅官方有效 |

### 4.3 响应骨架

```json
{
  "id": "chatcmpl-…",
  "object": "chat.completion",
  "choices": [{
    "index": 0,
    "message": {"role": "assistant", "content": "hi"},
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 9, "completion_tokens": 2, "total_tokens": 11}
}
```

流式时每个分片是 `choices[0].delta`，最后可能有 `data: [DONE]`。
**判断"对话真的通了"必须看 `choices`**：网关对一个它不支持的模型，完全可能回
`200 {"status_message":"Success."}` 这种没有 `choices` 的空壳（本项目实测撞到过）。

### 4.4 常见错误语义

| 状态 | `error.code` / 文案 | 含义 | 该不该重试 |
| --- | --- | --- | --- |
| 400 | `unsupported_parameter` / `Unsupported parameter: 'max_tokens'` | 参数名不对 | 换名重试一次 |
| 400 | `invalid_request_error` + `content must be a list` | content 形态不对 | 换形态重试一次 |
| 400 | `model_not_found` / `does not exist` | 模型名或路由错 | 不重试 |
| 401 / 403 | `invalid_api_key` / `permission_denied` | 鉴权或额度 | 不重试 |
| 404 | — | 端点不存在（兼容层没实现） | 不重试 |
| 429 | `rate_limit_exceeded` | 限流 | 按 `Retry-After` 退避 |
| 5xx | — | 上游故障 | 有限重试 |

---

## 5. `/responses`（官方主线，兼容层普遍没有）

| 维度 | `/chat/completions` | `/responses` |
| --- | --- | --- |
| 输入字段 | `messages[]` | `input`（字符串或 item 数组） |
| 有状态 | 无（每次全量传） | `store` + `previous_response_id` / `conversation` |
| 内置工具 | 只有函数调用 | `web_search` / `file_search` / `code_interpreter` / `computer_use` / `mcp` |
| 长任务 | 无 | `background: true` + 轮询 / webhook |
| 流式事件 | 分片 `delta` | 事件流：`response.output_text.delta`、`response.output_audio.delta`… |
| 输出 | `choices[0].message` | `output[]` item 列表 |

结论：**新项目官方建议 Responses；但如果目标是"接一堆第三方/中转"，只能按 Chat Completions 写**。
本平台也只走 chat 那一套。

---

## 6. `/embeddings`

```json
{"model":"text-embedding-3-small","input":["a","b"],"dimensions":512,"encoding_format":"float"}
```

| 字段 | 说明 |
| --- | --- |
| `input` | 字符串或字符串数组（批量） |
| `dimensions` | 3 系列支持降维；**改了就跟已有索引不兼容** |
| `encoding_format` | `float`（默认）或 `base64`（省带宽，要自己解码） |
| 产出 | `data[i].embedding`：浮点数组；`index` 对应输入顺序 |

两个硬约束：**维度必须与索引一致**；**2xx 不代表产出了向量**（网关兜底常回 200 空对象）。

---

## 7. `/images/*`

| 端点 | 请求体 | 关键参数 | 产出 |
| --- | --- | --- | --- |
| `/images/generations` | JSON | `model`、`prompt`、`n`、`size`、`quality`、`background`、`output_format`、`response_format` | `data[].b64_json` 或 `data[].url` |
| `/images/edits` | multipart | `image`（可多张）、`mask`、`prompt`、`size` | 同上 |
| `/images/variations` | multipart | `image`、`n`、`size` | Legacy（DALL·E 2） |

现状：DALL·E 2/3 已退役（2026-05-12），当前是 `gpt-image` 系列；Responses 里也能通过
image generation 工具出图。

注意：**`/images/generations` 会真花钱**（每调用生成一张）。探测"支不支持生图"时，
省略 `size` 让服务商取默认是最小代价的做法。

---

## 8. 音频三条路

### 8.1 `/audio/speech`（TTS，JSON → 二进制）

```json
{"model":"gpt-4o-mini-tts","input":"你好","voice":"alloy","response_format":"wav","speed":1.0}
```

- 响应体是**音频字节**，不是 JSON；`Content-Type` 通常是 `audio/mpeg` / `audio/wav`。
- 支持流式字节（边合成边返回），有的实现用 `stream_format` 区分 SSE 与音频流。
- 音色由 `voice` 决定，**与对话模型的 `audio.voice` 不是同一套管理方式**。

### 8.2 `/audio/transcriptions`（ASR，multipart）

| 字段 | 说明 |
| --- | --- |
| `file` | 音频文件（wav/mp3/m4a/webm…） |
| `model` | `whisper-1` / `gpt-4o-transcribe` 等 |
| `language`、`prompt` | 可选提示 |
| `response_format` | `json` / `text` / `srt` / `vtt` / `verbose_json` |
| `timestamp_granularities[]` | 词级 / 段级时间戳（仅 verbose_json） |

### 8.3 `/audio/translations`

同 multipart，产出统一翻成英文文本；参数比 transcriptions 少。

### 8.4 对话里带音频（`gpt-audio` / omni 系列）

- 输入：`content` 里放 `input_audio` part；
- 输出：`modalities:["text","audio"]` + `audio:{voice,format}`；
- 取音频：`choices[0].message.audio.data`（base64）+ `.transcript`。

### 8.5 三条语音输出路径怎么选

| 需求 | 用哪条 | 为什么 |
| --- | --- | --- |
| 固定文本转语音（配音、播报） | `/audio/speech` | 便宜、无对话开销、直接拿二进制 |
| 对话中顺带说话（语音助手一问一答） | chat + `modalities` | 与文本回复同一次调用，音色可配 |
| 全双工、可打断、低延迟 | Realtime（C 通道） | 只有它支持流式输入 + VAD + 打断 |

---

## 9. Realtime：唯一的持久连接通道

### 9.1 三种传输

| 传输 | 适用 | 入口 |
| --- | --- | --- |
| **WebRTC** | 浏览器 / 移动端直接采播音频 | `POST /v1/realtime/calls`（SDP 交换） |
| **WebSocket** | 服务端音频管道（电话、广播接入） | `wss://api.openai.com/v1/realtime` |
| **SIP** | 电话网络接入 | 由平台侧配置 |

浏览器/移动端不要直塞长期 API Key：先 `POST /v1/realtime/client_secrets` 换一个临时凭据（`ek_…`）。

### 9.2 端点

| 端点 | 用途 |
| --- | --- |
| `/v1/realtime` | 语音智能体会话（听 + 想 + 说 + 调工具） |
| `/v1/realtime/translations` | 持续翻译（不做助手轮次） |
| `/v1/realtime/transcription_sessions` | 只转录，不要模型回复 |
| `/v1/realtime/client_secrets` | 换临时凭据 |
| `/v1/realtime/calls` | WebRTC 建连 |

### 9.3 会话配置（GA 形态）

```
session.type = "realtime"
session.audio.input  = { format: "pcm16" | "g711_ulaw" | "g711_alaw", sample_rate, turn_detection: { type: "server_vad", ... } }
session.audio.output = { format: {...}, voice: "..." }
session.modalities   = ["text", "audio"]
```

输出音频主流是 **PCM16 / 24kHz / 单声道**（各家略有差异），走事件分片回传，不是一整段文件。

### 9.4 事件（Beta 与 GA 名字不同，兼容层多为 Beta 风格）

| 方向 | GA 事件 | 说明 |
| --- | --- | --- |
| 客户端 → 服务端 | `session.update` | 改会话配置（音色、模态、VAD） |
| | `input_audio_buffer.append` | 追加一段音频（base64） |
| | `input_audio_buffer.commit` | 手动提交一轮（push-to-talk） |
| | `response.create` | 主动请求一次回复 |
| 服务端 → 客户端 | `response.output_audio.delta` | 输出音频分片（Beta 叫 `response.audio.delta`） |
| | `response.output_audio_transcript.delta` | 音频对应的文字增量 |
| | `response.output_text.delta` | 纯文本增量 |
| | `response.done` | 本轮结束 |
| | `input_audio_buffer.speech_started` / `.speech_stopped` | VAD 事件 |

**这也是为什么 HTTP 探针测不出实时语音模型**：能力挂在事件流上，HTTP 面既没有对话端点，
也没有"取音频"的字段。正确做法是新建一条 C 通道客户端，而不是放宽 HTTP 超时。

---

## 10. 鉴权、限流、错误对象

| 项 | 形态 |
| --- | --- |
| 鉴权头 | `Authorization: Bearer sk-…`（项目 Key 也可用 `OpenAI-Project` / `OpenAI-Organization` 指定归属） |
| 临时凭据 | `ek_…`，由 `client_secrets` 换取，只用于 Realtime 建连 |
| 幂等 | `Idempotency-Key`（POST 重试安全） |
| 限流头 | `x-ratelimit-limit-requests` / `-remaining-requests` / `-reset-requests`、`retry-after` |
| 错误体 | `{"error":{"message","type","param","code"}}` |

`type` 的常见值：`invalid_request_error`、`authentication_error`、`permission_error`、
`not_found_error`、`rate_limit_error`、`server_error`。**分类要按 `code`/`type` 判，别只按状态码** ——
同为 400，"参数名不对"和"模型不存在"的处理方式完全不同。

---

## 11. 兼容性光谱：官方 / 中转 / 国产兼容层

这张表是本项目最该看的：同一句"OpenAI 兼容"，落到具体能力上差得很远。

| 能力 | 官方 OpenAI | 主流中转 | 国产兼容层（DashScope 等） |
| --- | --- | --- | --- |
| `/chat/completions` | ✅ | ✅ | ✅ |
| content 数组（图文） | ✅ | ✅ 多数 | ✅ |
| `input_audio` | ✅ 部分模型 | ⚠️ 常只收 base64/URL 之一 | ✅ 部分模型（omni） |
| `file` part（PDF） | ✅ | ⚠️ 少见 | ⚠️ 用 `file` 或私有字段 |
| `max_completion_tokens` | ✅ | ⚠️ 只认 `max_tokens` 的多 | ⚠️ 视模型 |
| `response_format: json_schema` | ✅ | ⚠️ 多数只有 `json_object` | ⚠️ |
| 流式 | ✅ | ✅（有的不支持 `include_usage`） | ✅ |
| `/embeddings` | ✅ | ✅ | ✅（路径可能是 `/compatible-mode/v1`） |
| `/images/generations` | ✅ | ⚠️ 有的只代理不实现 | ✅ 私有模型 |
| `/images/edits` | ✅ | ⚠️ 少 | ✅ 部分 |
| `/audio/speech` | ✅ | ⚠️ 少 | ✅ 私有 TTS |
| `/audio/transcriptions` | ✅ | ⚠️ 少 | ✅ 私有 ASR |
| `/rerank` | ❌ | ⚠️ 有的扩展 | ✅ 私有扩展（如 `gte-rerank`） |
| `/responses` | ✅ | ❌ 基本没有 | ⚠️ 少数跟进 |
| Realtime（C 通道） | ✅ | ❌ | ✅ 但需专用 `wss://…/api-ws/v1/realtime` |
| 视频生成 | ❌ 已下线 | ⚠️ 各家私有 | ✅ 私有异步任务 |

**三条读法**：

1. **"兼容"通常只覆盖 `/chat/completions` 的文本部分**。媒体、向量、音频越往后越不可靠。
2. **私有端点（rerank、声音复刻、视频）不是 OpenAI 规范**，别拿规范去校验它们的存在性。
3. **同一个模型 id 在官方能开音频、在中转可能只是"接了文本那条路"**。能力必须以实测为准。

---

## 12. 用最小请求判断"到底支持什么"

这是本项目探测器的判据来源，也适用于手工排障。

| 想确认 | 打哪 | 判据 | 假阳性来源 |
| --- | --- | --- | --- |
| 能对话 | `POST /chat/completions`，`"hi"` + `max_completion_tokens:16` | `choices[0].message` / `.delta` 存在 | 网关回 `200 {"status_message":"Success."}` 空壳 |
| 能看图 | 同上 + `content:[text, image_url]` | 同上 | 回 200 但没读图（只当文本处理） |
| 能听音频 | 同上 + `input_audio`（合法 WAV base64） | 同上 | 样本本身不合法 → 400，被误判"不支持" |
| 能读文件 | 同上 + `file` part（合法 PDF） | 同上 | 同上 |
| 能出图 | `POST /images/generations`，不带 `size` | `data[0].b64_json` / `.url` | 网关兜底 200 空对象 |
| 能 TTS | `POST /audio/speech`，`input:"a"`、`voice:"alloy"` | 响应体非空且不是 JSON 错误体 | 用对话模型的 `audio` 能力冒充 |
| 能 ASR | multipart `/audio/transcriptions` + 短 WAV | 200 且返回文本字段 | 端点存在但模型名不匹配 |
| 能向量 | `POST /embeddings` | `data[0].embedding` 是非空数组 | 200 空对象 / 回了 chat 回执 |
| 能重排 | **别用 OpenAI 规范判** | 私有端点，404 是常态 | 把 404 当成"模型不行" |

配套的三条硬规则（本项目已落地）：

- **2xx 不等于产出**：必须验响应形状；
- **超时按端点分级**：chat 数十秒、生图可达数分钟；
- **只有端点确实不存在（404 / 405 / 415 / 501）才写"不支持"**，并会把 embedding 自动撤销；400 / 422 / 5xx / 429 / 超时 / 网络一律记"未确认"、不写"不支持"：一次抖动不该删掉昨天还能用的能力。

---

## 13. 与本平台端点族的映射

| 平台端点族 | OpenAI 路径 | 通道 | 备注 |
| --- | --- | --- | --- |
| `chat` | `/chat/completions` | A / B | 主模型，输入模态全挂这里 |
| `embedding` | `/embeddings` | A | 维度按实测建索引 |
| `images` | `/images/generations` | A | 也可扩展 `/images/edits` |
| `tts` | `/audio/speech` | A / B | 与 chat 的 `modalities` 音频**不互通** |
| `asr` | `/audio/transcriptions` | A（multipart） | |
| `rerank` | 无标准 | A | 私有扩展，探测默认当作不可判 |
| `video` | 无（Sora 已下线） | — | 只能等元数据或手动声明 |
| （未实现） | `/v1/realtime` | **C** | 平台目前没有 WebSocket 音频通道 |

参数注入点：标准字段（`temperature` / `max_tokens` 等）与私有参数都由管控台写在「自定义参数」JSON 里，原样拼进请求体（见 `docs/08-模型与多模态.md`）。

---

## 14. 速查表

```
通道：A 请求-响应 / B 请求-流式(SSE, multipart) / C 持久连接(WS/WebRTC/SIP)
对话：POST /chat/completions      {model, messages, stream, max_completion_tokens}
      产出 choices[0].message(.content / .tool_calls / .audio.data)
新主线：POST /responses           {model, input, tools, store}（兼容层普遍没有）
向量：POST /embeddings            {model, input, dimensions} → data[].embedding   # 上游规范含 dimensions；本平台不下发 dimensions，维度取模型实测值
生图：POST /images/generations    {model, prompt, size?} → data[].b64_json|url
改图：POST /images/edits          multipart(image[], mask?, prompt)
TTS ：POST /audio/speech          {model, input, voice, response_format} → 二进制
ASR ：POST /audio/transcriptions  multipart(file, model, response_format)
实时：wss /v1/realtime（client_secrets → calls/连接）事件：input_audio_buffer.append /
      response.output_audio.delta / response.output_text.delta / response.done
模态：输入 text|image_url|input_audio|file(规范) ；输出 text|audio(modalities)|image|embedding
没有的：/rerank、视频生成、video content part —— 都不是 OpenAI 规范
```
