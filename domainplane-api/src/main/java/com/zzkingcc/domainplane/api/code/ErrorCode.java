package com.zzkingcc.domainplane.api.code;

import lombok.Getter;

/**
 * DomainPlane 统一状态码
 *
 * <p><b>两套文案，别混用：</b></p>
 * <ul>
 *   <li>{@link #getMessage()} —— 运维/日志文案。可以写内部术语（"图检查点读写失败"里的
 *       "图"指的是编排状态图），给人排障用，<b>不要直接展示给终端用户</b>。</li>
 *   <li>{@link #getUserMessage()} —— 对外文案。只说"发生了什么 + 该做什么"，
 *       不含实现细节。所有对外出口（SSE 的 {@code ERROR} 帧、HTTP 响应体的 {@code error}
 *       字段）一律取这一份。</li>
 * </ul>
 *
 * <p>没显式给对外文案的码（多数 4xx：参数错、域不存在等），{@code userMessage} 等于
 * {@code message} —— 那些码本来就是写给人看的。真正需要区分的是 5xx：它们的
 * {@code message} 描述故障部位，{@code userMessage} 描述用户该怎么办。</p>
 *
 * @author zzkingcc
 * @see 错误码总表
 */
@Getter
public enum ErrorCode {

    // ===== 成功 =====
    OK(0, "ok", 200, false, "无"),

    // ===== 10xxx 权限与鉴权 =====
    PERMISSION_DENIED(10000, "当前权限无法使用该能力", 403, false, "提示需要更高权限或引导登录"),
    TOOL_PERMISSION_DENIED(10001, "该工具不在当前域内", 403, false, "提示该能力未对当前场景开放"),
    AUTH_REQUIRED(10002, "未登录或凭证已失效", 401, false, "重新登录以获取新凭证"),
    AUTH_FAILED(10003, "账号或密码错误", 401, false, "确认账号与密码后重试"),
    DOMAIN_NOT_FOUND(10004, "指定的域不存在", 400, false,
            "检查请求携带的 domain 与工具声明的 domains 是否一致"),
    AUTH_NOT_INITIALIZED(10005, "服务端账号尚未初始化", 409, false, "打开管控台完成首次初始化"),
    AUTH_ALREADY_INITIALIZED(10006, "账号已存在，初始化入口已关闭", 409, false, "直接登录管控台"),
    AUTH_STORE_CORRUPTED(10007, "账号文件损坏，无法读取", 503, false,
            "检查或删除 config/accounts.json 后重启服务",
            "服务账号配置已损坏，请联系管理员"),
    AUTH_DEFAULT_CREDENTIAL(10008, "管理员仍在使用初始默认口令", 403, false,
            "在管控台「账号」页修改管理员密码后再继续操作",
            "管理员尚未修改初始密码，服务端暂不接受变更类操作"),
    DOMAIN_REQUIRED(10009, "未指定本轮所处的域", 400, false, "显式指定 domain"),
    DOMAIN_NOT_CALLABLE(10010, "该域不是可调用单元", 400, false,
            "改用可调用的叶子域，或在管控台「域空间」把该域标记为可调用"),

    // ===== 20xxx 限流与容量 =====
    RATE_LIMITED(20000, "请求过于频繁，请稍后再试", 429, true, "退避后重试"),
    LLM_RATE_LIMITED(20001, "AI 服务繁忙，请稍后重试", 429, true, "退避后重试"),
    SYSTEM_BUSY(20002, "系统繁忙，请稍后重试", 503, true, "稍后重试"),
    CONCURRENT_LIMIT(20003, "并发会话数已达上限", 503, true, "稍后重试"),

    // ===== 30xxx 编排与会话 =====
    ORCHESTRATION_FAILED(30000, "任务执行失败，请重试", 500, true, "重新发起一轮对话"),
    SESSION_NOT_FOUND(30001, "会话不存在或已过期", 404, false, "新建会话"),
    SESSION_STATE_INVALID(30002, "会话状态异常，无法继续", 409, false, "新建会话"),
    SESSION_BUSY(30003, "会话正在执行中，拒绝并发请求", 409, false, "等待当前任务结束，或先 stop 再重发"),
    SESSION_MEMORY_FULL(30004, "会话记忆已达上限，该会话不再接受新消息", 409, false,
            "更换 sessionId 开启新会话（平台不代为切换，sessionId 由调用方提供）"),

    // ===== 40xxx 客户端 / 入参校验 =====
    INVALID_PARAMETER(40000, "请求参数非法", 400, false, "检查入参"),
    MISSING_REQUIRED_PARAMETER(40001, "缺少必填参数", 400, false, "补齐必填参数"),
    TYPE_MISMATCH(40002, "参数类型不匹配", 400, false, "检查参数类型"),
    INPUT_REJECTED(40003, "输入内容不安全，已被拦截", 400, false, "提示用户换一种说法"),
    CLIENT_CANCELLED(40004, "用户已中断请求", 499, false, "无需处理"),
    INPUT_TOO_LONG(40005, "输入内容超长", 400, false,
            "缩短输入后重发（服务端不做截断：截断后的回答基于残缺输入，而调用方收不到任何信号）"),
    RESOURCE_NOT_FOUND(40400, "请求的资源不存在", 404, false, "检查请求路径"),

    // ===== 50xxx 系统 / 通用 =====
    SYSTEM_ERROR(50000, "系统内部错误", 500, true, "通用兜底并上报 traceId",
            "服务出了点问题，请稍后重试"),
    UNEXPECTED_ERROR(50001, "服务暂时不可用，请稍后重试", 500, true, "稍后重试并上报 traceId"),

    // ===== 60xxx 知识库 =====
    KNOWLEDGE_BASE_ERROR(60000, "知识库服务异常", 500, true, "稍后重试",
            "知识库暂时不可用，请稍后重试"),
    KNOWLEDGE_SEARCH_ERROR(60001, "知识库检索失败", 500, true, "稍后重试",
            "资料没查出来，请稍后重试"),
    KNOWLEDGE_INGEST_ERROR(60002, "知识库文档导入失败", 500, false, "检查文档格式或联系管理员",
            "文档没能导入，请换一种格式或稍后重试"),
    KNOWLEDGE_DEDUP_ERROR(60003, "知识库去重计算失败", 500, false, "联系管理员",
            "文档处理失败，请联系管理员"),
    KNOWLEDGE_STRATEGY_NOT_FOUND(60004, "未匹配到文档处理策略", 500, false, "检查文档格式",
            "暂不支持这种文档格式"),
    KNOWLEDGE_DOCUMENT_DUPLICATE(60005, "知识库已存在同名文档", 409, false, "改名后重传，或带 replace=true 覆盖更新"),
    KNOWLEDGE_UPLOAD_REJECTED(60006, "知识库文档上传被拒绝", 400, false, "检查文件类型是否在白名单内、是否超过大小上限"),

    // ===== 70xxx 会话记忆 / 检查点 =====
    CHAT_MEMORY_ERROR(70000, "会话记忆服务异常", 500, true, "稍后重试",
            "读不到这段会话的上下文，请稍后重试"),
    CHAT_MEMORY_READ_ERROR(70001, "读取会话记忆失败", 500, true, "新建会话",
            "读不到这段会话的上下文，请稍后重试；一直不行就换个新会话"),
    CHAT_MEMORY_WRITE_ERROR(70002, "写入会话记忆失败", 500, true, "稍后重试",
            "这一轮没能保存下来，请稍后重试"),
    CHAT_MEMORY_DELETE_ERROR(70003, "删除会话记忆失败", 500, false, "联系管理员",
            "会话清理失败，请联系管理员"),
    /* 对外文案里不提"图"：这里的"图"是编排状态图（langgraph4j 的 graph），不是图片。
       在带图的会话里报这个错，写成"图检查点"必然被读成"图片出了问题"。 */
    CHECKPOINT_ERROR(70004, "图检查点读写失败", 500, true, "稍后重试；持续失败则新建会话",
            "会话状态读取失败，请稍后重试；一直不行就换个新会话"),

    // ===== 80xxx 工具调用 =====
    TOOL_ERROR(80000, "工具调用异常", 500, true, "稍后重试",
            "这个能力暂时不可用，请稍后重试"),
    TOOL_NOT_FOUND(80001, "未找到指定工具", 404, false, "联系管理员（配置问题）",
            "这个能力当前不可用，请联系管理员"),
    TOOL_DUPLICATE(80002, "工具名称重复注册", 500, false, "联系管理员",
            "工具配置有冲突，请联系管理员"),
    TOOL_EXECUTION_FAILED(80003, "工具执行失败", 500, true, "稍后重试"),

    // ===== 90xxx 大模型 / 外部依赖 =====
    LLM_TIMEOUT(90000, "大模型接口响应超时", 504, true, "稍后重试"),
    LLM_UNAVAILABLE(90003, "AI 服务暂时不可用", 503, true, "稍后重试"),
    STORAGE_UNAVAILABLE(90004, "存储服务不可用", 503, true, "联系管理员",
            "服务暂时不可用，请稍后重试"),
    DEPENDENCY_NOT_CONFIGURED(90005, "服务依赖尚未配置", 503, false, "联系管理员在管控台完成配置",
            // 与 MODEL_NO_VISION 同型：具体去哪一页配是运维信息，进日志与 action；
            // 事件体只告诉终端用户"现在用不了、该找谁"。retryable=false，不说"稍后重试"。
            "服务暂未就绪，请联系管理员或稍后再试"),

    /* 上游"明确拒绝"的三类：欠费、鉴权、请求被拒。
       共同点是"重试也不会好"，所以 retryable 一律 false：此前它们全被塌进
       ORCHESTRATION_FAILED（retryable=true），前端会无意义地反复重发同一个必然失败的请求，
       而真正的原因（如 DeepSeek 的 Insufficient Balance）被通用文案盖掉。
       归哪一类由 UpstreamLlmFailure 按上游异常类型 + 原文语义定性。 */
    LLM_QUOTA_EXHAUSTED(90006, "上游账号余额或额度不足", 402, false,
            "为模型档案对应的上游账号充值 / 提额",
            // 余额是平台所有者的账，终端用户充不了；对外只说"暂时用不了、找谁"
            "AI 服务暂时不可用，请联系管理员"),
    LLM_AUTH_FAILED(90007, "上游凭证无效或未被授权", 401, false,
            "检查模型档案里的 API Key 是否失效、是否被授权访问该模型",
            "AI 服务配置有误，请联系管理员"),
    LLM_REQUEST_REJECTED(90008, "上游拒绝了本次请求", 400, false,
            "按上游原因调整入参（模型名 / 上下文长度 / 参数）",
            "本次请求未被 AI 服务接受，请调整后再试"),

    // ===== 91xxx 多模态端点（生图 / 重排 / 语音合成 / 语音识别） =====
    MODEL_ENDPOINT_UNSUPPORTED(91000, "该模型档案不支持此模态调用", 400, false,
            "该协议类型暂未适配此端点，或档案未声明对应端点族（请在「读取」结果里确认含该端点族）"),
    MODEL_NO_VISION(91001, "当前域没有可用的视觉模型，无法处理图片", 400, false,
            "到管控台「域空间」为该域（或其祖先域）绑定一个输入模态含 image 的模型；"
                    + "若该档案来自标准 OpenAI 规范的服务商，模态可能读不出来，需先在「模型」页对它点一次「探测」",
            // 对外文案不给运维指引：「管控台」「域」是内部概念，终端用户够不着、看到了也无从行动；
            // 只留一条他自己就能做的事（换文字提问）。绑模型该谁做，日志与 action 里都有。
            "当前环境暂不支持查看图片，请用文字描述你要问的内容");

    /** 面向调用方 / 前端的稳定状态码 */
    private final int code;
    /** 运维 / 日志文案：可以写内部术语，不直接展示给终端用户 */
    private final String message;
    /** 建议的 HTTP 响应状态码 */
    private final int httpStatus;
    /** 是否可重试：前端据此决定"退避重试"还是"直接提示" */
    private final boolean retryable;
    /** 建议前端采取的动作（人读，用于文档与排查） */
    private final String action;
    /** 对外文案：只说"发生了什么 + 该做什么"，供 SSE / HTTP 出口直接展示 */
    private final String userMessage;

    ErrorCode(int code, String message, int httpStatus, boolean retryable, String action) {
        this(code, message, httpStatus, retryable, action, message);
    }

    ErrorCode(int code, String message, int httpStatus, boolean retryable, String action,
              String userMessage) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.action = action;
        this.userMessage = userMessage == null || userMessage.isBlank() ? message : userMessage;
    }

    /** 按数字码查找，未定义时返回 null（调用方自行决定兜底） */
    public static ErrorCode of(int code) {
        for (ErrorCode ec : values()) {
            if (ec.code == code) {
                return ec;
            }
        }
        return null;
    }
}
