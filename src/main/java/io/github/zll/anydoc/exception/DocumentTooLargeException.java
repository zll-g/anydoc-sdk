package io.github.zll.anydoc.exception;

/**
 * 413 too_large：超过服务端 ANYDOC_MAX_BYTES（默认 50MB）。
 *
 * <p>RAG 管道处理：拒绝上传并提示用户。
 */
public class DocumentTooLargeException extends DocumentConversionException {

    public DocumentTooLargeException(String message, String requestId) {
        super(message, 413, "too_large", requestId);
    }
}
