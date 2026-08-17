package io.github.zll.anydoc.exception;

/**
 * SDK 全部异常的基类。
 *
 * <p>携带服务端契约信息：HTTP 状态码、错误码（见服务端 README 错误契约表）、链路请求 ID。
 */
public class AnydocException extends RuntimeException {

    private final Integer httpStatus;
    private final String errorCode;
    private final String requestId;

    public AnydocException(String message, Throwable cause, Integer httpStatus, String errorCode, String requestId) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.requestId = requestId;
    }

    public AnydocException(String message, Integer httpStatus, String errorCode, String requestId) {
        this(message, null, httpStatus, errorCode, requestId);
    }

    /** HTTP 状态码（客户端本地故障如网络异常时为 null）。 */
    public Integer httpStatus() {
        return httpStatus;
    }

    /** 服务端错误码（如 too_large / unsupported / encrypted / malformed / overloaded）。 */
    public String errorCode() {
        return errorCode;
    }

    /** 链路追踪请求 ID，可用于与服务端日志（结构化 JSON）对账。 */
    public String requestId() {
        return requestId;
    }

    /**
     * 是否瞬时故障（可重试）：网络异常、超时、5xx、503/504。
     * 业务拒绝（401/413/415/422）恒为 false。
     */
    public boolean isRetryable() {
        return false;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "{httpStatus=" + httpStatus
                + ", errorCode=" + errorCode
                + ", requestId=" + requestId
                + ", message=" + getMessage() + "}";
    }
}
