package io.github.zll.anydoc;

/**
 * 独立 OCR 调用结果（v1.6，{@code POST /v1/ocr}）。
 *
 * <p>对单张图片（png/jpeg/gif/bmp/tiff/webp）或 PDF 做本地文字识别
 * （服务端 PaddleOCR，数据不出域）；多行文本以换行拼接于 {@link #text()}。
 *
 * @param text        识别出的文本（多行以 \n 拼接；无文字时为空串）
 * @param kind        输入类型：image / pdf
 * @param inputBytes  输入字节数
 * @param elapsedMs   服务端识别耗时（毫秒，含模型推理）
 * @param requestId   链路追踪 ID
 */
public record OcrResult(String text, String kind, int inputBytes, double elapsedMs,
                        String requestId) {

    /** 是否识别出有效文本。 */
    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
