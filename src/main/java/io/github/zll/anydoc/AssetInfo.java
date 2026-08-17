package io.github.zll.anydoc;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 文档内嵌资产（图片等）元数据。
 *
 * <p>仅当转换请求携带 {@code include_assets=true} 时返回。
 * 图片资产附带 base64 解码后的字节（{@link #data()}）；
 * 非图片或超过服务端单资产限额时为 null 且 {@link #truncated()} 为 true。
 *
 * <p>{@link #placements()} 携带资产在文档流中的归属位置（标题链 + 块序号 + 前后语境），
 * 解决“图片解析后上下文丢失、无法定位归属章节”的问题。
 *
 * @param id          资产序号（服务端分配）
 * @param mediaType   MIME 类型（如 image/png）
 * @param originPart  来源部件路径（如 word/media/image1.png）
 * @param size        原始字节数
 * @param truncated   data 是否因超限/非图片而缺省
 * @param data        图片字节（可能为 null）
 * @param placements  文档流中的出现位置列表（同一资产可出现多次）
 * @param ocrText     图片内文字的本地 OCR 识别结果（v1.5，服务端开启内嵌图片 OCR
 *                    时携带；未开启时字段缺席 → null；识别失败为 null；无文字为空串）
 */
public record AssetInfo(
        @JsonProperty("id") int id,
        @JsonProperty("media_type") String mediaType,
        @JsonProperty("origin_part") String originPart,
        @JsonProperty("size") int size,
        @JsonProperty("truncated") boolean truncated,
        @JsonProperty("data_b64") byte[] data,
        @JsonProperty("placements") List<AssetPlacement> placements,
        @JsonProperty("ocr_text") String ocrText) {

    public boolean isImage() {
        return mediaType != null && mediaType.startsWith("image/");
    }

    /** 是否携带服务端本地 OCR 识别出的图片文字（非空文本）。 */
    public boolean hasOcrText() {
        return ocrText != null && !ocrText.isBlank();
    }

    /** 是否携带可用于 OCR/VLM 增强的图片字节。 */
    public boolean hasData() {
        return data != null && data.length > 0;
    }

    /** 主归属位置（首个出现位置；无位置信息时为 null）。 */
    public AssetPlacement primaryPlacement() {
        return placements == null || placements.isEmpty() ? null : placements.get(0);
    }

    /** 主归属标题链拼接（"A > B"），无位置信息时返回空串。 */
    public String primaryHeadingPath() {
        AssetPlacement p = primaryPlacement();
        return p == null ? "" : p.headingPathJoined();
    }
}
