package io.github.zll.anydoc.exception;

/**
 * 503 overloaded：服务端并发饱和且排队超时（背压保护触发）。
 *
 * <p>携带服务端下发的 {@code Retry-After} 建议退避秒数。
 */
public class AnydocUnavailableException extends AnydocServiceException {

    private final int retryAfterSeconds;

    public AnydocUnavailableException(String message, int retryAfterSeconds, String requestId) {
        super(message, 503, "overloaded", requestId);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** 服务端建议的退避秒数（响应未携带时为 -1）。 */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
