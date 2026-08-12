package cn.cctstudio.qqbotauth.qq;

public final class QQApiException extends RuntimeException {
    private final int httpStatus;
    private final long errorCode;
    private final String traceId;

    public QQApiException(int httpStatus, long errorCode, String message, String traceId) {
        super(message == null || message.isBlank() ? "QQ API request failed" : message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.traceId = traceId == null ? "" : traceId;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public long errorCode() {
        return errorCode;
    }

    public String traceId() {
        return traceId;
    }
}
