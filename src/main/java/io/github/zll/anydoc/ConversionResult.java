package io.github.zll.anydoc;

import java.util.List;

/**
 * 转换结果（不可变）。
 *
 * @param markdown    GitHub Flavored Markdown 正文
 * @param format      服务端按字节内容嗅探出的格式（如 docx/xlsx/pdf/csv，可能与扩展名不同）
 * @param elapsedMs   服务端转换耗时（毫秒）
 * @param inputBytes  输入文档字节数
 * @param requestId   本次调用的链路追踪请求 ID
 * @param assets      内嵌资产（图片）清单；未请求 includeAssets 时为空列表
 * @param cacheHit    是否命中服务端转换缓存（重摄取/重复文档的直接收益信号）
 */
public record ConversionResult(
        String markdown,
        String format,
        double elapsedMs,
        int inputBytes,
        String requestId,
        List<AssetInfo> assets,
        boolean cacheHit,
        boolean ocrApplied) {

    /** 向后兼容构造（ocrApplied=false）。 */
    public ConversionResult(String markdown, String format, double elapsedMs, int inputBytes,
                            String requestId, List<AssetInfo> assets, boolean cacheHit) {
        this(markdown, format, elapsedMs, inputBytes, requestId, assets, cacheHit, false);
    }

    /** Markdown 是否为空（防御性判断：空结果建议走兜底流程）。 */
    public boolean isEmpty() {
        return markdown == null || markdown.isBlank();
    }

    /** 携带图片字节、可直接用于 OCR/VLM 增强的资产数。 */
    public long enrichableAssetCount() {
        return assets == null ? 0 : assets.stream().filter(AssetInfo::hasData).count();
    }
}
