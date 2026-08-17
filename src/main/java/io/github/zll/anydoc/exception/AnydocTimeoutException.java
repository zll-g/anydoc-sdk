package io.github.zll.anydoc.exception;

/**
 * 转换超时：客户端请求超时（requestTimeout）或服务端返回 504 timeout（单文档转换超时）。
 */
public class AnydocTimeoutException extends AnydocServiceException {

    public AnydocTimeoutException(String message, Throwable cause, String requestId) {
        super(message, cause, null, "client_timeout", requestId);
    }

    public AnydocTimeoutException(String message, String requestId) {
        super(message, 504, "timeout", requestId);
    }
}
