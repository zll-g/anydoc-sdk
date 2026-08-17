package io.github.zll.anydoc;

/**
 * 异步任务提交回执（v1.7，{@code POST /v1/jobs} 返回 202）。
 *
 * @param jobId    任务 ID（用于 {@link AnydocClient#jobStatus(String)} 轮询）
 * @param status   提交时状态：queued（入队）/ succeeded（命中转换缓存，直接完成）
 * @param cacheHit 是否因命中服务端转换缓存而直接完成
 */
public record JobTicket(String jobId, String status, boolean cacheHit) {

    /** 提交即完成（命中缓存），可直接查询结果。 */
    public boolean isDone() {
        return "succeeded".equals(status);
    }
}
