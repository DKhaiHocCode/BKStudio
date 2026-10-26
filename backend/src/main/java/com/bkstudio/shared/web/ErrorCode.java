package com.bkstudio.shared.web;

import org.springframework.http.HttpStatus;

/** Error codes returned in the {@code code} field of Problem Details (API_SPEC §1). */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    OUT_OF_STOCK(HttpStatus.CONFLICT),
    INVALID_TRANSITION(HttpStatus.CONFLICT),
    VERSION_CONFLICT(HttpStatus.CONFLICT),
    TERMS_OUTDATED(HttpStatus.CONFLICT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    /** Duplicate slug / scale, image limit reached. */
    CONFLICT(HttpStatus.CONFLICT),
    /** Upload over the multipart limit. */
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    /** Fallback for client errors without a dedicated code (405, 415, ...). */
    BAD_REQUEST(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Lower-case, dash-separated form used in the Problem Details {@code type} URI. */
    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }

    /** Best-effort code for exceptions raised by Spring MVC itself, which only know an HTTP status. */
    static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 400 -> VALIDATION_FAILED;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 429 -> RATE_LIMITED;
            default -> status >= 500 ? INTERNAL_ERROR : BAD_REQUEST;
        };
    }
}
