package io.github.zll.anydoc.exception;

/**
 * 业务拒绝基类：服务端对文档内容做出的确定性判定（401/413/415/422）。
 *
 * <p>这类异常<b>不应重试</b>，应按 RAG 管道策略路由（OCR 兜底 / 隔离 / 死信）。
 */
public abstract class DocumentConversionException extends AnydocException {

    protected DocumentConversionException(String message, Integer httpStatus, String errorCode, String requestId) {
        super(message, httpStatus, errorCode, requestId);
    }
}
