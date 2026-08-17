package io.github.zll.anydoc.exception;

/**
 * 422 malformed / missing_part / resource_limit：文档结构损坏、缺少必要部件，
 * 或触发 anydoc 内建安全上限（解压炸弹、嵌套深度、节点数）。
 *
 * <p>RAG 管道处理：进入死信 + 告警，不重试。
 */
public class CorruptedDocumentException extends DocumentConversionException {

    public CorruptedDocumentException(String message, String errorCode, String requestId) {
        super(message, 422, errorCode, requestId);
    }
}
