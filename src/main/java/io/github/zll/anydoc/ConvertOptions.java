package io.github.zll.anydoc;

/**
 * 转换选项（不可变，fluent 复制风格）。
 *
 * <pre>{@code
 * ConvertOptions opts = ConvertOptions.defaults()
 *         .withRequestId(traceId)
 *         .withIncludeAssets(true)
 *         .withTimeoutSeconds(600);
 * ConversionResult result = client.convert(bytes, "report.docx", opts);
 * }</pre>
 *
 * @param requestId       链路追踪 ID；null 时 SDK 自动生成
 * @param includeAssets   是否要求服务端返回内嵌资产（图片）清单，默认 false
 * @param timeoutSeconds  本次转换超时（秒）；null = 服务端默认预算
 *                        （服务端钳制 [1, ANYDOC_REQUEST_TIMEOUT_MAX]）
 * @param useCache        v2.1 缓存旁路：false = 本次不读/不写服务端缓存；null = 跟随服务端开关
 * @param pages           v2.1 PDF 页选择（"0,2" / "1-3"，0 起；仅 PDF 生效）；null = 全文档
 * @param maxPages        v2.2 最大页数限制；null = 服务端默认（ANYDOC_MAX_PAGES，v2.5 起默认 500）；
 *                        <b>0 = 不限制页数</b>（v2.3）。所有格式均可设置；当前仅 PDF 具备
 *                        页结构而实际截断
 * @param headersFooters  v2.6 页眉页脚解析：null = 跟随服务端开关（ANYDOC_HEADERFOOTER_ENABLED，
 *                        默认开）；false = 本次跳过解析（不返回 headers_footers 字段、
 *                        PDF 不做重复行剥离，产物与开启时不同，服务端缓存键隔离）
 */
public record ConvertOptions(String requestId, boolean includeAssets, Integer timeoutSeconds,
                             Boolean useCache, String pages, Integer maxPages,
                             Boolean headersFooters) {

    public static ConvertOptions defaults() {
        return new ConvertOptions(null, false, null, null, null, null, null);
    }

    public ConvertOptions withRequestId(String requestId) {
        return new ConvertOptions(requestId, includeAssets, timeoutSeconds, useCache, pages,
                maxPages, headersFooters);
    }

    public ConvertOptions withIncludeAssets(boolean includeAssets) {
        return new ConvertOptions(requestId, includeAssets, timeoutSeconds, useCache, pages,
                maxPages, headersFooters);
    }

    /** 本次转换超时（秒）；服务端钳制 [1, ANYDOC_REQUEST_TIMEOUT_MAX]。 */
    public ConvertOptions withTimeoutSeconds(int timeoutSeconds) {
        return new ConvertOptions(requestId, includeAssets, timeoutSeconds, useCache, pages,
                maxPages, headersFooters);
    }

    /** v2.1：false = 本次不读/不写服务端缓存（一次性转换/测试场景）。 */
    public ConvertOptions withUseCache(boolean useCache) {
        return new ConvertOptions(requestId, includeAssets, timeoutSeconds, useCache, pages,
                maxPages, headersFooters);
    }

    /** v2.1：PDF 页选择（"0,2" / "1-3"，页码 0 起）；服务端转换前抽取子集。 */
    public ConvertOptions withPages(String pages) {
        return new ConvertOptions(requestId, includeAssets, timeoutSeconds, useCache, pages,
                maxPages, headersFooters);
    }

    /** v2.2：最大页数限制；0 = 不限制（v2.3）；缺省跟随服务端 ANYDOC_MAX_PAGES（默认 500）。 */
    public ConvertOptions withMaxPages(int maxPages) {
        return new ConvertOptions(requestId, includeAssets, timeoutSeconds, useCache, pages,
                maxPages, headersFooters);
    }

    /**
     * v2.6：页眉页脚解析开关。false = 本次跳过（不返回结构化页眉页脚、PDF 不剥离
     * 重复行）；缺省（null）跟随服务端 ANYDOC_HEADERFOOTER_ENABLED（默认开）。
     */
    public ConvertOptions withHeadersFooters(boolean headersFooters) {
        return new ConvertOptions(requestId, includeAssets, timeoutSeconds, useCache, pages,
                maxPages, headersFooters);
    }

    /** 构建查询串（include_assets / timeout / use_cache / pages / max_pages /
     *  headers_footers；缺省项不携带）。 */
    public String toQuery() {
        StringBuilder q = new StringBuilder();
        if (includeAssets) {
            q.append("include_assets=true");
        }
        appendIf(q, "timeout", timeoutSeconds);
        appendIf(q, "use_cache", useCache);
        appendIf(q, "pages", pages);
        appendIf(q, "max_pages", maxPages);
        appendIf(q, "headers_footers", headersFooters);
        return q.toString();
    }

    private static void appendIf(StringBuilder q, String name, Object value) {
        if (value == null) {
            return;
        }
        if (q.length() > 0) {
            q.append('&');
        }
        q.append(name).append('=').append(value);
    }

    /** 参与客户端单飞键的选项指纹（不同参数产物不同，不可共享结果）。 */
    public String variantFingerprint() {
        StringBuilder v = new StringBuilder();
        if (timeoutSeconds != null) {
            v.append("t=").append(timeoutSeconds);
        }
        if (useCache != null) {
            v.append(";c=").append(useCache);
        }
        if (pages != null && !pages.isBlank()) {
            v.append(";p=").append(pages.trim());
        }
        if (maxPages != null) {
            v.append(";mp=").append(maxPages);
        }
        if (headersFooters != null) {
            v.append(";hf=").append(headersFooters);
        }
        return v.toString();
    }
}
