package io.github.zll.anydoc;

import java.util.List;

/**
 * anydoc-service 客户端（线程安全，全局单例复用）。
 *
 * <p>通过 {@link #builder()} 构建实例：
 * <pre>{@code
 * AnydocClient client = AnydocClient.builder()
 *         .baseUrl("http://anydoc-service:8080")
 *         .token(token)
 *         .build();
 * }</pre>
 */
public interface AnydocClient extends AutoCloseable {

    /**
     * 转换文档为 GitHub Flavored Markdown。
     *
     * @param content  文档二进制内容（非空）
     * @param filename 原始文件名（用于 CSV 等无签名格式的扩展名兜底识别，可为 null）
     * @return 转换结果
     * @throws io.github.zll.anydoc.exception.UnsupportedDocumentException 415：未知格式/无文本层扫描件（由上游 OCR 服务预处理）
     * @throws io.github.zll.anydoc.exception.EncryptedDocumentException   422：加密文档
     * @throws io.github.zll.anydoc.exception.CorruptedDocumentException   422：损坏/缺部件/触发安全上限
     * @throws io.github.zll.anydoc.exception.DocumentTooLargeException    413：超过服务端大小上限
     * @throws io.github.zll.anydoc.exception.UnauthorizedException        401：Token 缺失/错误
     * @throws io.github.zll.anydoc.exception.AnydocServiceException       瞬时故障（网络/5xx），已按重试策略重试
     */
    ConversionResult convert(byte[] content, String filename);

    /**
     * 转换文档，并显式指定链路追踪请求 ID（透传 X-Request-ID）。
     *
     * @param requestId 请求 ID；为 null/空白时自动生成
     */
    ConversionResult convert(byte[] content, String filename, String requestId);

    /**
     * 转换文档（完整选项）。
     *
     * <p>设置 {@code includeAssets=true} 时，返回结果携带内嵌图片资产清单
     * （{@link ConversionResult#assets()}），供图像 OCR/VLM 增强管道消费。
     */
    ConversionResult convert(byte[] content, String filename, ConvertOptions options);


    /**
     * PDF 页面位图渲染（v1.8）：把指定页渲染为图片字节返回
     * （服务端 pypdfium2；用途：预览/抽检/向 VLM 供图）。
     */
    PdfRenderResult renderPdf(byte[] content, RenderOptions options);

    /** PDF 页面位图渲染（默认选项：全部页、2x、PNG）。 */
    default PdfRenderResult renderPdf(byte[] content) {
        return renderPdf(content, RenderOptions.defaults());
    }

    /**
     * 提交异步转换任务（v1.7）：面向大文档等长任务。
     *
     * <p>服务端返回 202 + 任务 ID；命中转换缓存时任务直接完成
     * （{@link JobTicket#isDone()}）。任务结果经
     * {@link #jobStatus(String)} 轮询，或用 {@link #convertAsync} 一步到位。
     * 服务端异步队列满时抛出携带 Retry-After 的 {@code AnydocUnavailableException}。
     */
    JobTicket submitJob(byte[] content, String filename, ConvertOptions options);

    /**
     * 查询异步任务状态（v1.7）。
     *
     * @throws io.github.zll.anydoc.exception.AnydocException 任务不存在或已过期（404 job_not_found）
     */
    JobStatus jobStatus(String jobId);

    /**
     * 异步转换一步到位（v1.7）：提交 + 轮询直至终态，成功返回与同步
     * {@code convert()} 一致的结果；任务失败按标准异常契约抛出
     * （损坏/加密/不支持等为不可重试业务异常，超时/停机为可重试异常）。
     *
     * @param maxWait      最长等待时长（含排队与执行）
     * @param pollInterval 轮询间隔
     * @throws io.github.zll.anydoc.exception.AnydocTimeoutException 超过 maxWait 仍未终结
     */
    ConversionResult convertAsync(byte[] content, String filename, ConvertOptions options,
                                  java.time.Duration maxWait, java.time.Duration pollInterval);

    /**
     * 批量转换（历史文档迁移/批量重摄取场景）。
     *
     * <p>并发度 {@code parallelism} 上限内并行执行；<b>逐文档故障隔离</b>——
     * 结果按输入顺序返回，单个文档失败以 {@link ConversionOutcome#error()} 携带，不中断批量。
     *
     * @param parallelism 并发度（≥1；大批量建议 4~16，受服务端并发上限约束）
     */
    List<ConversionOutcome> convertAll(List<InputDocument> documents, int parallelism);

    /**
     * 探测服务健康状态（GET /healthz），用于应用侧健康检查与预热。
     *
     * @throws io.github.zll.anydoc.exception.AnydocException 服务不可达或响应异常
     */
    ServiceInfo health();

    /** 释放资源。JDK HttpClient 无需显式关闭，本方法为兼容性保留。 */
    @Override
    void close();

    /** 创建构建器。 */
    static AnydocClientBuilder builder() {
        return new AnydocClientBuilder();
    }
}
