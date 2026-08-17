package io.github.zll.anydoc.exception;

/**
 * 瞬时故障基类：网络异常、连接/读取超时、5xx。
 *
 * <p>SDK 已按 {@link io.github.zll.anydoc.RetryPolicy} 自动重试，抛出时说明重试耗尽。
 * RAG 管道处理：转入兜底队列缓冲，等待服务恢复后重放。
 */
public class AnydocServiceException extends AnydocException {

    public AnydocServiceException(String message, Throwable cause, Integer httpStatus, String errorCode, String requestId) {
        super(message, cause, httpStatus, errorCode, requestId);
    }

    public AnydocServiceException(String message, Integer httpStatus, String errorCode, String requestId) {
        super(message, httpStatus, errorCode, requestId);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
