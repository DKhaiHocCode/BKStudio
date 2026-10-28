package com.bkstudio.shared.web;

import java.time.Duration;

/** Business error that maps to a Problem Details response with the given {@link ErrorCode}. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Duration retryAfter;
    private final String field;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, null);
    }

    /** {@code retryAfter} (nullable) becomes the {@code Retry-After} header, used with RATE_LIMITED. */
    public ApiException(ErrorCode code, String detail, Duration retryAfter) {
        this(code, detail, retryAfter, null);
    }

    private ApiException(ErrorCode code, String detail, Duration retryAfter, String field) {
        super(detail);
        this.code = code;
        this.retryAfter = retryAfter;
        this.field = field;
    }

    /** VALIDATION_FAILED for one request field; the handler renders it as {@code errors[].field}. */
    public static ApiException invalidField(String field, String detail) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail, null, field);
    }

    /** Request field the error refers to, or null. */
    public String field() {
        return field;
    }

    public ErrorCode code() {
        return code;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
