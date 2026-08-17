package io.github.zll.anydoc.exception;

/**
 * 401 unauthorized：Token 缺失或错误。属于配置错误，重试无意义，应立即告警修复配置。
 */
public class UnauthorizedException extends DocumentConversionException {

    public UnauthorizedException(String message, String requestId) {
        super(message, 401, "unauthorized", requestId);
    }
}
