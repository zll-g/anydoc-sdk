package io.github.zll.anydoc.exception;

/**
 * 422 encrypted：文档加密或受密码保护。
 *
 * <p>RAG 管道处理：隔离文档并通知所有者提供未加密版本。
 */
public class EncryptedDocumentException extends DocumentConversionException {

    public EncryptedDocumentException(String message, String requestId) {
        super(message, 422, "encrypted", requestId);
    }
}
