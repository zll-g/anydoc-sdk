package io.github.zll.anydoc;

/**
 * 批量转换的输入文档。
 *
 * @param content       文档字节
 * @param filename      文件名（用于 CSV 等无签名格式的扩展名兜底识别）
 * @param requestId     链路追踪 ID（null 时自动生成）
 * @param includeAssets 是否提取内嵌资产（图片）
 */
public record InputDocument(byte[] content, String filename, String requestId, boolean includeAssets) {

    /** 便捷构造（自动生成 requestId，不提取资产）。 */
    public static InputDocument of(byte[] content, String filename) {
        return new InputDocument(content, filename, null, false);
    }

    ConvertOptions toOptions() {
        return ConvertOptions.defaults()
                .withRequestId(requestId)
                .withIncludeAssets(includeAssets);
    }
}
