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
 * @param floating    v2.1：docx 浮动图片（wp:anchor）标记——其 XML 锚点位置可能与
 *                    视觉位置不一致（常见整体偏移一个章节），headingPath 归属需谨慎采信；
 *                    非浮动/非 docx 时该字段缺席（null）
 */
public record AssetInfo(
        @JsonProperty("id") int id,
        @JsonProperty("media_type") String mediaType,
        @JsonProperty("origin_part") String originPart,
        @JsonProperty("size") int size,
        @JsonProperty("truncated") boolean truncated,
        @JsonProperty("data_b64") byte[] data,
        @JsonProperty("placements") List<AssetPlacement> placements,
        @JsonProperty("floating") Boolean floating) {

    /** 是否为浮动图片（wp:anchor，headingPath 归属可能偏移）。 */
    public boolean isFloating() {
        return Boolean.TRUE.equals(floating);
    }

    public boolean isImage() {
        return mediaType != null && mediaType.startsWith("image/");
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
