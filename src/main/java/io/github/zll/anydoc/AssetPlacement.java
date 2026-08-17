package io.github.zll.anydoc;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 资产在文档流中的出现位置（解决图片解析后的上下文丢失问题）。
 *
 * <p>同一资产可能在文档中出现多次（复用图），故每个资产对应多个 placement。
 *
 * @param headingPath  自顶层到所在节的标题链（归属章节，如 ["2026 方案", "二、转换层选型"]）
 * @param blockIndex   图所在块在文档流中的序号（定位锚点）
 * @param contextBefore 图前最近的非空文本（语境锚定）
 * @param contextAfter  图后最近的非空文本（常为图注，如"图1：系统架构"）
 */
public record AssetPlacement(
        @JsonProperty("heading_path") List<String> headingPath,
        @JsonProperty("block_index") int blockIndex,
        @JsonProperty("context_before") String contextBefore,
        @JsonProperty("context_after") String contextAfter) {

    /** 标题链拼接（"A > B > C"），用于检索块的 headingPath 面包屑。 */
    public String headingPathJoined() {
        if (headingPath == null || headingPath.isEmpty()) {
            return "";
        }
        return String.join(" > ", headingPath);
    }

    /** 语境摘要：优先图后文本（常见图注），否则图前文本。 */
    public String anchorText() {
        if (contextAfter != null && !contextAfter.isBlank()) {
            return contextAfter;
        }
        return contextBefore == null ? "" : contextBefore;
    }
}
