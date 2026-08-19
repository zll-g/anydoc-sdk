package io.github.zll.anydoc.exception;

/**
 * 转换超时：客户端请求超时（requestTimeout）或服务端返回 504 timeout（单文档转换超时）。
 *
 * <p>重试语义（v1.8）：
 * <ul>
 *   <li><b>客户端超时</b>（httpStatus 为 null）：服务端可能仍在处理，重试可经
 *       服务端在途合并（single-flight）共享同一转换，允许重试；</li>
 *   <li><b>服务端 504</b>：对方已耗尽其完整超时预算，立即重试只会重复消耗
 *       重型转换（多页扫描 OCR）——<b>不重试</b>。调用方应改用异步任务 API
 *       （{@code submitJob/convertAsync}）或调大服务端超时预算。</li>
 * </ul>
 */
public class AnydocTimeoutException extends AnydocServiceException {

    public AnydocTimeoutException(String message, Throwable cause, String requestId) {
        super(message, cause, null, "client_timeout", requestId);
    }

    public AnydocTimeoutException(String message, String requestId) {
        super(message, 504, "timeout", requestId);
    }

    @Override
    public boolean isRetryable() {
        return httpStatus() == null;   // 仅客户端超时可重试（见类注释）
    }
}
