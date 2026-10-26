package com.bkstudio.shared.web;

import java.time.Duration;

/** Business error that maps to a Problem Details response with the given {@link ErrorCode}. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Duration retryAfter;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, null);
    }

    /** {@code retryAfter} (nullable) becomes the {@code Retry-After} header, used with RATE_LIMITED. */
    public ApiException(ErrorCode code, String detail, Duration retryAfter) {
        super(detail);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public ErrorCode code() {
        return code;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
