package com.zodiac.api.service;

public class AiServiceException extends RuntimeException {

    public enum Reason {
        MISCONFIGURED,
        TIMEOUT,
        INVALID_RESPONSE,
        UPSTREAM_ERROR
    }

    private final Reason reason;

    public AiServiceException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AiServiceException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public boolean isRetryable() {
        return reason == Reason.TIMEOUT
                || reason == Reason.INVALID_RESPONSE
                || reason == Reason.UPSTREAM_ERROR;
    }
}
