package com.bkstudio.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 401 / 403 raised inside the security filter chain, in the same Problem Details shape as the global handler. */
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JacksonJsonHttpMessageConverter json = new JacksonJsonHttpMessageConverter();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        write(response, ErrorCode.UNAUTHENTICATED, "Authentication is required.");
    }

    /** Also receives CSRF failures (missing or wrong token). */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response, ErrorCode.FORBIDDEN, "Access denied.");
    }

    private void write(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        response.setStatus(code.status().value());
        json.write(
                GlobalExceptionHandler.problem(code, detail),
                MediaType.APPLICATION_PROBLEM_JSON,
                new ServletServerHttpResponse(response));
    }
}
