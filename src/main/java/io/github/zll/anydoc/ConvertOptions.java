package io.github.zll.anydoc;

/**
 * 转换选项（不可变，fluent 复制风格）。
 *
 * <pre>{@code
 * ConvertOptions opts = ConvertOptions.defaults()
 *         .withRequestId(traceId)
 *         .withIncludeAssets(true)
 *         .withOcrAssets(true);          // v1.6：动态开启内嵌图片 OCR
 * ConversionResult result = client.convert(bytes, "report.docx", opts);
 * }</pre>
 *
 * @param requestId     链路追踪 ID；null 时 SDK 自动生成
 * @param includeAssets 是否要求服务端返回内嵌资产（图片）清单，默认 false
 * @param ocrAssets     内嵌图片 OCR 动态开关（v1.6）：null = 跟随服务端全局配置
 *                      ANYDOC_OCR_ASSETS_ENABLED；true/false = 请求级显式覆盖
 */
public record ConvertOptions(String requestId, boolean includeAssets, Boolean ocrAssets) {

    public static ConvertOptions defaults() {
        return new ConvertOptions(null, false, null);
    }

    public ConvertOptions withRequestId(String requestId) {
        return new ConvertOptions(requestId, includeAssets, ocrAssets);
    }

    public ConvertOptions withIncludeAssets(boolean includeAssets) {
        return new ConvertOptions(requestId, includeAssets, ocrAssets);
    }

    /**
     * 动态开启/关闭服务端内嵌图片 OCR（v1.6）。
     *
     * <p>true：本次转换对文档内嵌图片做本地 OCR，资产携带 {@code ocrText}
     * （服务端引擎不可用时返回 415 ocr_unavailable）；false：显式关闭；
     * 不调用则跟随服务端全局配置。
     */
    public ConvertOptions withOcrAssets(boolean ocrAssets) {
        return new ConvertOptions(requestId, includeAssets, ocrAssets);
    }
}
