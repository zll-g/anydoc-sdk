package io.github.zll.anydoc.exception;

/**
 * 415 unsupported：未知格式，或<b>无文本层的扫描 PDF</b>（本服务 v2.0 起不含 OCR，
 * 扫描件由上游专门服务处理）。RAG 管道建议：死信 + 人工复核，不重试。
 */
public class UnsupportedDocumentException extends DocumentConversionException {

    /** 服务端错误码：扫描 PDF 需本地 OCR，但转换实例未安装 OCR 依赖（部署问题，可修复）。 */
    public static final String CODE_OCR_UNAVAILABLE = "ocr_unavailable";

    public UnsupportedDocumentException(String message, String errorCode, String requestId) {
        super(message, 415, errorCode, requestId);
    }

}
