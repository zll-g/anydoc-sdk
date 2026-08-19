package io.github.zll.anydoc;

import java.util.List;

/**
 * 转换结果（不可变）。
 *
 * @param markdown                 GitHub Flavored Markdown 正文（服务端开启页眉页脚剥离时，
 *                                 PDF 重复页眉/页脚行已剔除）
 * @param format                   服务端按字节内容嗅探出的格式（如 docx/xlsx/pdf/csv，可能与扩展名不同）
 * @param elapsedMs                服务端转换耗时（毫秒）
 * @param inputBytes               输入文档字节数
 * @param requestId                本次调用的链路追踪请求 ID
 * @param assets                   内嵌资产（图片）清单；未请求 includeAssets 时为空列表
 * @param cacheHit                 是否命中服务端转换缓存（重摄取/重复文档的直接收益信号）
 * @param headersFooters           v2.6 页眉/页脚结构化条目（docx 确定性提取 / pdf 统计检测）；
 *                                 服务端关闭解析或请求 headers_footers=false 时为空列表
 * @param headersFootersStripped   v2.6 PDF：从 Markdown 剥离的重复页眉/页脚行数；
 *                                 非 PDF 或未剥离时为 null
 */
public record ConversionResult(
        String markdown,
        String format,
        double elapsedMs,
        int inputBytes,
        String requestId,
        List<AssetInfo> assets,
        boolean cacheHit,
        List<HeaderFooterInfo> headersFooters,
        Integer headersFootersStripped) {

    /** Markdown 是否为空（防御性判断：空结果建议走兜底流程）。 */
    public boolean isEmpty() {
        return markdown == null || markdown.isBlank();
    }

    /** 携带图片字节、可直接用于 OCR/VLM 增强的资产数。 */
    public long enrichableAssetCount() {
        return assets == null ? 0 : assets.stream().filter(AssetInfo::hasData).count();
    }

    /** v2.6：是否解析出页眉/页脚条目。 */
    public boolean hasHeadersFooters() {
        return headersFooters != null && !headersFooters.isEmpty();
    }

    /**
     * v2.6：页眉/页脚纯文本摘要（去重、保持原序），便于写入文档级 metadata
     * （如密级、文档编号、版本号常见于页眉）。
     */
    public List<String> headerFooterTexts() {
        if (headersFooters == null) {
            return List.of();
        }
        return headersFooters.stream()
                .map(HeaderFooterInfo::text)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .toList();
    }
}
