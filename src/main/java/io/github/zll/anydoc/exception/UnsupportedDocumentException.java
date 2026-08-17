package io.github.zll.anydoc.exception;

/**
 * 415 unsupported：未知格式或内容无法解析；以及 415 {@code ocr_unavailable}。
 *
 * <p>自 v1.4 起，扫描 PDF（无文本层）由 anydoc-service 内置本地 OCR 兜底
 * （数据不出域），正常情况下不再产生 415。仅当转换实例<b>未安装 OCR 依赖</b>时，
 * 服务端返回独立错误码 {@link #CODE_OCR_UNAVAILABLE}（{@link #isOcrUnavailable()}）。
 *
 * <p>RAG 管道处理建议：
 * <ul>
 *   <li>{@code ocr_unavailable}：属部署缺陷而非文档问题 —— 文档入兜底队列缓冲，
 *       同时告警推动以 {@code WITH_OCR=true} 重建镜像，修复后重放即可成功；</li>
 *   <li>其余 unsupported：文档本身无法处理 —— 死信 + 人工复核，不重试。</li>
 * </ul>
 */
public class UnsupportedDocumentException extends DocumentConversionException {

    /** 服务端错误码：扫描 PDF 需本地 OCR，但转换实例未安装 OCR 依赖（部署问题，可修复）。 */
    public static final String CODE_OCR_UNAVAILABLE = "ocr_unavailable";

    public UnsupportedDocumentException(String message, String errorCode, String requestId) {
        super(message, 415, errorCode, requestId);
    }

    /** 是否为“OCR 依赖缺失”型 415（部署问题，修复镜像后重放可成功）。 */
    public boolean isOcrUnavailable() {
        return CODE_OCR_UNAVAILABLE.equals(errorCode());
    }
}
