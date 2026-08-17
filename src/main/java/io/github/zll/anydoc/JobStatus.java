package io.github.zll.anydoc;

/**
 * 异步任务状态快照（v1.7，{@code GET /v1/jobs/{id}}）。
 *
 * <p>状态机：queued → running → succeeded / failed。
 * succeeded 时 {@link #result()} 与同步 {@code convert()} 结果一致；
 * failed 时携带 {@link #errorCode()}/{@link #errorReason()}/{@link #retryable()}。
 *
 * @param jobId       任务 ID
 * @param status      queued / running / succeeded / failed
 * @param format      内容嗅探出的格式
 * @param createdAt   创建时间（服务端 ISO8601）
 * @param startedAt   开始执行时间（未开始时 null）
 * @param finishedAt  终态时间（未终结时 null）
 * @param result      成功时的转换结果（其他状态为 null）
 * @param errorCode   失败错误码（如 timeout / missing_part / server_shutting_down）
 * @param errorReason 失败原因文本
 * @param retryable   失败是否建议重试（业务性错误如损坏/加密为 false）
 */
public record JobStatus(String jobId, String status, String format,
                        String createdAt, String startedAt, String finishedAt,
                        ConversionResult result,
                        String errorCode, String errorReason, boolean retryable) {

    public boolean isTerminal() {
        return "succeeded".equals(status) || "failed".equals(status);
    }

    public boolean isSucceeded() {
        return "succeeded".equals(status);
    }

    public boolean isFailed() {
        return "failed".equals(status);
    }
}
