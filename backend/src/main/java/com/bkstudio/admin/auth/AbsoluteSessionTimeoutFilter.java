package com.bkstudio.admin.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session 12 h after the TOTP step (SECURITY.md T2), however active it is. Runs before the security
 * context is loaded, so the request simply continues as anonymous. Not a bean: it is created by the security chain.
 */
class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    static final String LOGIN_AT = "loginAt";
    static final Duration MAX_AGE = Duration.ofHours(12);

    private final Clock clock;

    AbsoluteSessionTimeoutFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null
                && session.getAttribute(LOGIN_AT) instanceof Instant at
                && !at.plus(MAX_AGE).isAfter(clock.instant())) {
            session.invalidate();
        }
        chain.doFilter(request, response);
    }
}
