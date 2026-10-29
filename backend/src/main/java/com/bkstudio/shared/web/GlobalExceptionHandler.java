package com.bkstudio.shared.web;

import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Turns every exception into an RFC 9457 Problem Details body with {@code code} and {@code requestId}. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String TYPE_BASE = "https://bkstudio.xxx/errors/";

    /** Fixed on purpose: Spring would otherwise echo the request path, and /api/orders/{token} carries a secret. */
    private static final URI NO_INSTANCE = URI.create("about:blank");

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApi(ApiException ex) {
        ProblemDetail body = problem(ex.code(), ex.getMessage());
        if (ex.field() != null) {
            body.setProperty("errors", List.of(new FieldIssue(ex.field(), ex.getMessage())));
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.code().status());
        if (ex.retryAfter() != null) {
            response.header(
                    HttpHeaders.RETRY_AFTER,
                    String.valueOf(Math.max(1, ex.retryAfter().toSeconds())));
        }
        return response.body(body);
    }

    /** DB errors: message can carry row values (PostgreSQL "Failing row contains ..."), so log class + SQLState only. */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> handleDataAccess(DataAccessException ex) {
        String sqlState = null;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                sqlState = sql.getSQLState();
                break;
            }
        }
        log.error("Data access failure {} sqlState={}", ex.getClass().getName(), sqlState);
        ProblemDetail body = problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** Anything unexpected: full stack trace goes to the log, the client only gets the requestId. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail body = problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldIssue> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldIssue(e.getField(), e.getDefaultMessage()))
                .toList();
        ex.getBody().setProperty("errors", errors);
        return super.handleMethodArgumentNotValid(ex, headers, status, request);
    }

    /** Every Spring MVC exception (404, 405, malformed JSON, ...) passes through here; add code and requestId. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        // The base class builds the ProblemDetail itself when body is null, so enrich what it returns.
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            ErrorCode code = ErrorCode.fromStatus(statusCode.value());
            problem.setType(URI.create(TYPE_BASE + code.slug()));
            problem.setInstance(NO_INSTANCE);
            problem.setProperty("code", code.name());
            problem.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        }
        return response;
    }

    static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(TYPE_BASE + code.slug()));
        problem.setInstance(NO_INSTANCE);
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        problem.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        return problem;
    }

    /** One entry of the {@code errors} array for VALIDATION_FAILED. */
    record FieldIssue(String field, String message) {}
}
