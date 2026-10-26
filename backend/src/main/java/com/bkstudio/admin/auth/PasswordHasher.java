package com.bkstudio.admin.auth;

import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id password hashing. At most 4 run at once (19 MiB each): a flood of logins gets a fast 429 instead of
 * exhausting memory or request threads.
 */
@Component
public class PasswordHasher {

    // OWASP minimum profile: m=19 MiB, t=2, p=1 (salt 16 B, hash 32 B). Argon2PasswordEncoder is Argon2id.
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    private final String dummyHash;
    final Semaphore permits; // package-private for the unit test
    private final Duration maxWait;

    @Autowired
    public PasswordHasher() {
        this(4, Duration.ofSeconds(2));
    }

    PasswordHasher(int maxConcurrent, Duration maxWait) {
        this.permits = new Semaphore(maxConcurrent);
        this.maxWait = maxWait;
        byte[] junk = new byte[32];
        new SecureRandom().nextBytes(junk);
        this.dummyHash = encoder.encode(HexFormat.of().formatHex(junk));
    }

    public String hash(String rawPassword) {
        return guarded(() -> encoder.encode(rawPassword));
    }

    public boolean matches(String rawPassword, String hash) {
        return guarded(() -> encoder.matches(rawPassword, hash));
    }

    /** Burns the same time as a real check; call when the email is unknown. Always returns false. */
    public boolean matchesDummy(String rawPassword) {
        guarded(() -> encoder.matches(rawPassword, dummyHash));
        return false;
    }

    private <T> T guarded(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Server busy. Try again shortly.", Duration.ofSeconds(1));
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
