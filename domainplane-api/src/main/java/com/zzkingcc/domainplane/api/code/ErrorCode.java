package com.zzkingcc.domainplane.api.code;

import lombok.Getter;

/**
 * DomainPlane 统一状态码
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
            "检查或删除 config/accounts.json 后重启服务"),
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
    SYSTEM_ERROR(50000, "系统内部错误", 500, true, "通用兜底并上报 traceId"),
    UNEXPECTED_ERROR(50001, "服务暂时不可用，请稍后重试", 500, true, "稍后重试并上报 traceId"),

    // ===== 60xxx 知识库 =====
    KNOWLEDGE_BASE_ERROR(60000, "知识库服务异常", 500, true, "稍后重试"),
    KNOWLEDGE_SEARCH_ERROR(60001, "知识库检索失败", 500, true, "稍后重试"),
    KNOWLEDGE_INGEST_ERROR(60002, "知识库文档导入失败", 500, false, "检查文档格式或联系管理员"),
    KNOWLEDGE_DEDUP_ERROR(60003, "知识库去重计算失败", 500, false, "联系管理员"),
    KNOWLEDGE_STRATEGY_NOT_FOUND(60004, "未匹配到文档处理策略", 500, false, "检查文档格式"),
    KNOWLEDGE_DOCUMENT_DUPLICATE(60005, "知识库已存在同名文档", 409, false, "改名后重传，或带 replace=true 覆盖更新"),
    KNOWLEDGE_UPLOAD_REJECTED(60006, "知识库文档上传被拒绝", 400, false, "检查文件类型是否在白名单内、是否超过大小上限"),

    // ===== 70xxx 会话记忆 / 检查点 =====
    CHAT_MEMORY_ERROR(70000, "会话记忆服务异常", 500, true, "稍后重试"),
    CHAT_MEMORY_READ_ERROR(70001, "读取会话记忆失败", 500, true, "新建会话"),
    CHAT_MEMORY_WRITE_ERROR(70002, "写入会话记忆失败", 500, true, "稍后重试"),
    CHAT_MEMORY_DELETE_ERROR(70003, "删除会话记忆失败", 500, false, "联系管理员"),
    CHECKPOINT_ERROR(70004, "图检查点读写失败", 500, true, "新建会话"),

    // ===== 80xxx 工具调用 =====
    TOOL_ERROR(80000, "工具调用异常", 500, true, "稍后重试"),
    TOOL_NOT_FOUND(80001, "未找到指定工具", 404, false, "联系管理员（配置问题）"),
    TOOL_DUPLICATE(80002, "工具名称重复注册", 500, false, "联系管理员"),
    TOOL_EXECUTION_FAILED(80003, "工具执行失败", 500, true, "稍后重试"),

    // ===== 90xxx 大模型 / 外部依赖 =====
    LLM_TIMEOUT(90000, "大模型接口响应超时", 504, true, "稍后重试"),
    EXTERNAL_SERVICE_TIMEOUT(90001, "外部服务调用超时", 504, true, "稍后重试"),
    SERVER_UNREACHABLE(90002, "无法连接 DomainPlane 服务端", 503, true, "确认服务端已启动且地址正确"),
    LLM_UNAVAILABLE(90003, "AI 服务暂时不可用", 503, true, "稍后重试"),
    STORAGE_UNAVAILABLE(90004, "存储服务不可用", 503, true, "联系管理员"),
    DEPENDENCY_NOT_CONFIGURED(90005, "服务依赖尚未配置", 503, false, "联系管理员在管控台完成配置");

    /** 面向调用方 / 前端的稳定状态码 */
    private final int code;
    /** 默认可读文案，可直接展示给终端用户 */
    private final String message;
    /** 建议的 HTTP 响应状态码 */
    private final int httpStatus;
    /** 是否可重试：前端据此决定"退避重试"还是"直接提示" */
    private final boolean retryable;
    /** 建议前端采取的动作（人读，用于文档与排查） */
    private final String action;

    ErrorCode(int code, String message, int httpStatus, boolean retryable, String action) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.action = action;
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
