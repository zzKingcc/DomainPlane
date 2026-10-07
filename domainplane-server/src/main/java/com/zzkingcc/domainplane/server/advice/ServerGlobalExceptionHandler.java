package com.zzkingcc.domainplane.server.advice;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.api.support.TraceId;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.common.exception.ChatMemoryException;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.server.auth.AuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * DomainPlane 服务端全局异常处理器。
 *
 * @author zzkingcc
 */
@RestControllerAdvice
public class ServerGlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ServerGlobalExceptionHandler.class);

    /**
     * 仅用于在"文件超限"的文案里回显真实上限，避免在代码里再写一个会漂移的数字。
     * 取不到不影响功能，置空即可。
     */
    @Autowired(required = false)
    private MultipartProperties multipartProperties;

    /**
     * "这次失败来自 ES / Redis"的栈特征。
     */
    private static final String[] STORAGE_STACK_MARKERS = {
            "com.zzkingcc.domainplane.server.config.Swappable",
            "co.elastic.clients.",
            "org.elasticsearch.",
            "io.lettuce.",
            "redis.clients."
    };

    @ExceptionHandler(KnowledgeBaseException.class)
    public ResponseEntity<Map<String, Object>> handleKnowledgeBaseException(KnowledgeBaseException e) {
        log.error("[全局异常][KNOWLEDGE] code={}({}), detail={}",
                e.getCode(), e.getCodeName(), e.getMessage(), e);
        return build(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(ChatMemoryException.class)
    public ResponseEntity<Map<String, Object>> handleChatMemoryException(ChatMemoryException e) {
        log.error("[全局异常][CHAT_MEMORY] code={}({}), detail={}",
                e.getCode(), e.getCodeName(), e.getMessage(), e);
        return build(e.getErrorCode(), e.getMessage());
    }

    /**
     * 账号 / 凭证类异常：WARN 且不打堆栈
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuthException(AuthException e) {
        log.warn("[全局异常][AUTH] code={}({}), detail={}", e.getCode(), e.getCodeName(), e.getMessage());
        return build(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(NotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handleNotConfiguredException(NotConfiguredException e) {
        // 这里是 WARN 而不是 ERROR：依赖"尚未配置"是设计允许的初始状态（配置支持在管控台
        // 运行时补齐），不是故障。按 ERROR 记会让人误以为服务坏了，也会稀释真正需要人介入的日志。
        log.warn("[全局异常][NOT_CONFIGURED] code={}({}), detail={}",
                e.getCode(), e.getCodeName(), e.getMessage());
        return build(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<Map<String, Object>> handleBaseException(BaseException e) {
        ErrorCode code = e.getErrorCode();
        // 4xx 且不可重试的属于"调用方填错了"，与 NOT_CONFIGURED 同理按 WARN 记且不打堆栈：
        // 堆栈对"输入超长"这类问题零信息量，却会淹没真正需要人介入的 ERROR。
        boolean callerError = code.getHttpStatus() >= 400 && code.getHttpStatus() < 500 && !code.isRetryable();
        if (callerError) {
            log.warn("[全局异常][BUSINESS] code={}({}), detail={}",
                    e.getCode(), e.getCodeName(), e.getMessage());
        } else {
            log.error("[全局异常][BUSINESS] code={}({}), detail={}",
                    e.getCode(), e.getCodeName(), e.getMessage(), e);
        }
        return build(code, e.getMessage());
    }

    /**
     * 参数 / 配置填写类异常：WARN 且不打堆栈
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("[全局异常][PARAM] 参数非法: {}", e.getMessage());
        return build(ErrorCode.INVALID_PARAMETER, e.getMessage());
    }

    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPathVariable(MissingPathVariableException e) {
        log.error("[全局异常][PARAM] 路径参数缺失: {}", e.getVariableName(), e);
        return build(ErrorCode.MISSING_REQUIRED_PARAMETER, e.getVariableName() + " 不能为空");
    }

    /**
     * 请求体读不出来（缺 body、JSON 结构错、字段类型对不上）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("[全局异常][PARAM] 请求体不可读: {}", e.getMessage());
        return build(ErrorCode.INVALID_PARAMETER, "请求体格式不合法或为空");
    }

    /** 必填查询参数缺失：与"请求体不可读"同理，默认响应里没有 {@code code} */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("[全局异常][PARAM] 查询参数缺失: {}", e.getParameterName());
        return build(ErrorCode.MISSING_REQUIRED_PARAMETER, e.getParameterName() + " 不能为空");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String expect = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "未知";
        log.error("[全局异常][PARAM] 参数类型不匹配: name={}, expect={}", e.getName(), expect, e);
        return build(ErrorCode.TYPE_MISMATCH, "参数 " + e.getName() + " 期望类型：" + expect);
    }

    /**
     * 上传文件超过 {@code spring.servlet.multipart.max-file-size}。
     *
     * 这个异常在请求进入 Controller 之前由容器抛出，
     * 所以 {@code KnowledgeBaseService} 里那段基于 {@code RagProperties} 的大小校验
     * 根本没机会执行。它必须有自己的 handler：落到通用兜底会是 {@code UNEXPECTED_ERROR}
     * （HTTP 500、retryable=true），用户看到"服务暂时不可用，请稍后重试" ——
     * 重试永远不会成功，而且"文件太大"这个唯一有用的信息被彻底丢掉。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("[全局异常][PARAM] 上传文件超过容器上限: {}", e.getMessage());
        return build(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                "文件超过大小上限（上限 " + maxUploadLimit() + "）；请拆分后再上传");
    }

    /** 从容器配置里读回真实上限，避免文案里再写一个会漂移的数字 */
    private String maxUploadLimit() {
        DataSize limit = multipartProperties == null ? null : multipartProperties.getMaxFileSize();
        return limit == null ? "见服务端配置" : limit.toString();
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalStateException(IllegalStateException e) {
        log.error("[全局异常][STATE] 状态异常: {}", e.getMessage());
        return build(ErrorCode.SYSTEM_ERROR, e.getMessage());
    }

    @ExceptionHandler(SocketTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleSocketTimeoutException(SocketTimeoutException e) {
        if (isStorageFailure(e)) {
            return storageUnavailable(e, "存储读写超时");
        }
        log.error("[全局异常][LLM] 大模型接口读取超时(Socket): {}", e.getMessage(), e);
        return build(ErrorCode.LLM_TIMEOUT, "大模型接口响应超时，请稍后重试");
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleTimeoutException(TimeoutException e) {
        if (isStorageFailure(e)) {
            return storageUnavailable(e, "存储读写超时");
        }
        log.error("[全局异常][LLM] 大模型接口超时(Timeout): {}", e.getMessage(), e);
        return build(ErrorCode.LLM_TIMEOUT, "大模型接口响应超时，请稍后重试");
    }

    /**
     * IO 类失败：ES 客户端抛的就是 {@code IOException}（含连接被拒、读超时）。
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIOException(IOException e) {
        if (isStorageFailure(e)) {
            return storageUnavailable(e, "存储读写失败");
        }
        log.error("[全局异常][IO] 非存储类 IO 异常: {}", e.getMessage(), e);
        return build(ErrorCode.UNEXPECTED_ERROR, "服务暂时不可用，请稍后重试");
    }

    /**
     * 请求路径没有任何处理者：WARN 且不打堆栈
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFoundException(NoResourceFoundException e) {
        String resourcePath = e.getResourcePath();
        if (!"favicon.ico".equals(resourcePath)) {
            log.warn("[全局异常][RESOURCE] 资源未找到: {}", resourcePath);
        }
        return build(ErrorCode.RESOURCE_NOT_FOUND, "请求的资源不存在：" + resourcePath);
    }

    @ExceptionHandler(CancellationException.class)
    public ResponseEntity<Map<String, Object>> handleCancellationException(CancellationException e) {
        log.warn("[全局异常][CANCEL] 用户主动中断请求");
        // 499 是 Nginx 私有码（客户端关闭连接），非 HTTP 标准；
        // 若网关不认，可改为 408 或直接复用 40004 码 + 标准状态
        ErrorCode code = ErrorCode.CLIENT_CANCELLED;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.getCode());
        body.put("codeName", code.name());
        body.put("error", code.getMessage());
        body.put("detail", e.getMessage());
        body.put("retryable", false);
        body.put("traceId", TraceId.currentOrNew());
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(code.getHttpStatus()).body(body);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        // Redis 类失败抛的是 DataAccessException（RuntimeException 的子类），
        // 这里一并判一次，避免为了一个分支去引 spring-tx 的编译期依赖
        if (isStorageFailure(e)) {
            return storageUnavailable(e, "存储访问失败");
        }
        log.error("[全局异常][UNEXPECTED] 未预期运行时异常：{}", e.getMessage(), e);
        return build(ErrorCode.UNEXPECTED_ERROR, "服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        if (isStorageFailure(e)) {
            return storageUnavailable(e, "存储访问失败");
        }
        log.error("[全局异常][UNEXPECTED] 未预期异常：{}", e.getMessage(), e);
        return build(ErrorCode.UNEXPECTED_ERROR, "服务暂时不可用，请稍后重试");
    }

    /**
     * 存储运行时不可用 —— "配了但连不上"的那一档（90004，ERROR + 堆栈）
     */
    private ResponseEntity<Map<String, Object>> storageUnavailable(Throwable e, String summary) {
        log.error("[全局异常][STORAGE] {}(code=90004): {}", summary, e.getMessage(), e);
        return build(ErrorCode.STORAGE_UNAVAILABLE, summary + "：" + rootMessage(e));
    }

    /**
     * 判断一次失败是否来自 ES / Redis。（按需扩展匹配项）
     */
    private static boolean isStorageFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            for (StackTraceElement frame : current.getStackTrace()) {
                String className = frame.getClassName();
                for (String marker : STORAGE_STACK_MARKERS) {
                    if (className.startsWith(marker)) {
                        return true;
                    }
                }
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /** 取最内层原因的可读信息（顶层 message 常常是"xxx failed"这类无信息量的包装） */
    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private ResponseEntity<Map<String, Object>> build(ErrorCode errorCode, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", errorCode.getCode());
        body.put("codeName", errorCode.name());
        body.put("error", errorCode.getMessage());
        body.put("detail", detail);
        // 前端据此决定"退避重试"还是"直接提示"，不必自己维护一份可重试码清单
        body.put("retryable", errorCode.isRetryable());
        body.put("action", errorCode.getAction());
        // 完整堆栈只进日志；给调用方一个 traceId 用于上报与串联
        body.put("traceId", TraceId.currentOrNew());
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }
}
