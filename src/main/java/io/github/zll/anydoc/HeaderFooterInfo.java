package io.github.zll.anydoc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 页眉/页脚结构化条目（v2.6）。
 *
 * <p>服务端对文档页眉页脚的解析结果，两种来源：
 * <ul>
 *   <li><b>docx（确定性）</b>：直接解析 OOXML header/footer 部件，
 *       {@link #scope()} 区分 default/first/even 三种页面范围，
 *       {@link #sections()} 给出引用该部件的节序号，页码域以 {PAGE}/{NUMPAGES}
 *       占位符出现在 {@link #text()} 中；</li>
 *   <li><b>pdf（统计启发式）</b>：对抽样页面做字符坐标聚行 + 跨页频率聚类，
 *       {@link #pagesSeen()}/{@link #pageFrequency()} 给出证据强度，
 *       纯页码型条目 {@link #pageNumber()} 为 true。服务端同时会把命中的重复行
 *       从 Markdown 中剥离（剥离行数见 {@code ConversionResult.headersFootersStripped()}）。</li>
 * </ul>
 *
 * <p>典型用途：RAG 摄取时把密级/文档编号等元信息写入文档级 metadata，
 * 同时确认正文 Markdown 已剔除重复页眉页脚噪声。
 *
 * @param kind          header / footer
 * @param scope         适用范围：docx 为 default/first/even；pdf 恒为 all
 * @param location      位置：docx 按段落对齐 left/center/right/justify；pdf 为 top/bottom
 * @param text          文本内容（页码域为 {PAGE}/{NUMPAGES} 占位符；pdf 取最高频原文）
 * @param sections      docx：引用该条目的节序号列表；pdf 为 null
 * @param pagesSeen     pdf：抽样中出现的页数；docx 为 null
 * @param pageFrequency pdf：出现频率（0-1）；docx 为 null
 * @param pageNumber    pdf：是否纯页码型（归一化后仅剩页码符号）
 * @param source        解析来源：docx / pdf
 * @param image         v2.7：页眉/页脚内图片（logo/印章等）引用；纯文本条目为 null。
 *                      {@link HeaderFooterImage#originPart()} 可用于从原始文档
 *                      归档/下载该图片部件
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeaderFooterInfo(
        @JsonProperty("kind") String kind,
        @JsonProperty("scope") String scope,
        @JsonProperty("location") String location,
        @JsonProperty("text") String text,
        @JsonProperty("sections") List<Integer> sections,
        @JsonProperty("pages_seen") Integer pagesSeen,
        @JsonProperty("page_frequency") Double pageFrequency,
        @JsonProperty("page_number") Boolean pageNumber,
        @JsonProperty("source") String source,
        @JsonProperty("image") HeaderFooterImage image) {

    /**
     * 页眉/页脚内嵌图片引用（v2.7；v2.8 增加字节与大小）。
     *
     * @param originPart 图片部件路径（如 word/media/image1.png）
     * @param mediaType  MIME 类型（image/png 等；无法识别时 application/octet-stream）
     * @param alt        无障碍描述（wp:docPr descr/title/name），常含语义（如"公司徽标"）
     * @param size       v2.8 原始字节数（媒体部件缺失时为 0）
     * @param data       v2.8 图片字节（base64 解码后）；超过服务端单资产限额
     *                   （ANYDOC_MAX_ASSET_BYTES）时省略为 null 且 {@link #truncated()} 为 true
     * @param truncated  v2.8 data 是否因超限/缺失而缺省
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HeaderFooterImage(
            @JsonProperty("origin_part") String originPart,
            @JsonProperty("media_type") String mediaType,
            @JsonProperty("alt") String alt,
            @JsonProperty("size") Integer size,
            @JsonProperty("data_b64") byte[] data,
            @JsonProperty("truncated") Boolean truncated) {

        /** 是否携带图片字节（可直接落盘/转存对象存储）。 */
        public boolean hasData() {
            return data != null && data.length > 0;
        }

        /** 字节是否因超限/缺失而缺省。 */
        public boolean isTruncated() {
            return Boolean.TRUE.equals(truncated);
        }
    }

    /** 是否页眉条目。 */
    public boolean isHeader() {
        return "header".equals(kind);
    }

    /** 是否页脚条目。 */
    public boolean isFooter() {
        return "footer".equals(kind);
    }

    /** 是否纯页码型条目（仅 pdf 检测结果携带）。 */
    public boolean isPageNumber() {
        return Boolean.TRUE.equals(pageNumber);
    }
}
