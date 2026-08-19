package io.github.zll.anydoc;

import java.util.List;

/**
 * PDF 页面位图渲染结果（v1.8，{@code POST /v1/pdf/render}，基于 pypdfium2）。
 *
 * <p>用途：扫描件预览、可视化抽检、向 VLM 供图等。
 *
 * @param totalPages  PDF 总页数
 * @param rendered    本次实际渲染页数
 * @param scale       实际渲染倍率（服务端钳制后）
 * @param pages       渲染出的页面位图列表
 * @param inputBytes  输入字节数
 * @param elapsedMs   服务端渲染耗时（毫秒）
 * @param requestId   链路追踪 ID
 */
public record PdfRenderResult(int totalPages, int rendered, double scale,
                              List<RenderedPage> pages, int inputBytes, double elapsedMs,
                              String requestId) {

    /** 单页位图。 */
    public record RenderedPage(int page, int width, int height, String mediaType, byte[] data) {
        public boolean isPng() {
            return "image/png".equals(mediaType);
        }
    }
}
