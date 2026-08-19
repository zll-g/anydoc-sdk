package io.github.zll.anydoc;

/**
 * PDF 渲染选项（v1.8，不可变 fluent 风格）。
 *
 * <pre>{@code
 * RenderOptions opts = RenderOptions.defaults()
 *         .withPages("0,2").withScale(1.5).withFormat("jpeg");
 * PdfRenderResult r = client.renderPdf(bytes, opts);
 * }</pre>
 *
 * @param pages  页选择：null/空 = 全部（受服务端 ANYDOC_RENDER_MAX_PAGES 限制）；
 *               支持 "0,2" / "1-3" / 混合（页码 0 起）
 * @param scale  渲染倍率：null = 服务端默认（2.0）；服务端钳制 [0.5, 4.0]
 * @param format 输出格式：png（默认）/ jpeg
 */
public record RenderOptions(String pages, Double scale, String format) {

    public static RenderOptions defaults() {
        return new RenderOptions(null, null, null);
    }

    public RenderOptions withPages(String pages) {
        return new RenderOptions(pages, scale, format);
    }

    public RenderOptions withScale(double scale) {
        return new RenderOptions(pages, scale, format);
    }

    public RenderOptions withFormat(String format) {
        return new RenderOptions(pages, scale, format);
    }

    /** 拼接查询串（供客户端内部使用）。 */
    public String toQuery() {
        StringBuilder q = new StringBuilder();
        if (pages != null && !pages.isBlank()) {
            q.append("pages=").append(pages);
        }
        if (scale != null) {
            if (q.length() > 0) {
                q.append('&');
            }
            q.append("scale=").append(scale);
        }
        if (format != null && !format.isBlank()) {
            if (q.length() > 0) {
                q.append('&');
            }
            q.append("format=").append(format);
        }
        return q.toString();
    }
}
