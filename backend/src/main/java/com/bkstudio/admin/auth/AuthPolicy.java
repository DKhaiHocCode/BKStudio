package com.bkstudio.admin.auth;

import java.time.Duration;

/** Login lockout policy (SECURITY.md T1), kept in one place. */
public final class AuthPolicy {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private AuthPolicy() {}
}
