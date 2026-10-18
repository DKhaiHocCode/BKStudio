package com.bkstudio.shared.web;

/** Business error that maps to a Problem Details response with the given {@link ErrorCode}. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
