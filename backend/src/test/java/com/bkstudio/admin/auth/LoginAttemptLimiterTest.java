package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    @Test
    void retryAfter_isRemainingWindowOfThatKey() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_ATTEMPTS; i++) {
            assertThat(limiter.tryAcquire("1.1.1.1")).isEmpty();
        }
        clock.advance(Duration.ofMinutes(4));
        assertThat(limiter.tryAcquire("1.1.1.1")).contains(Duration.ofMinutes(11));
    }

    @Test
    void whenFull_newKeyIsAccepted_andOldestEntryIsEvictedWithFreshBudget() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_ATTEMPTS; i++) {
            limiter.tryAcquire("oldest");
        }
        assertThat(limiter.tryAcquire("oldest")).isPresent();
        for (int i = 0; i < LoginAttemptLimiter.MAX_TRACKED - 1; i++) {
            clock.advance(Duration.ofMillis(1));
            assertThat(limiter.tryAcquire("k" + i)).isEmpty();
        }
        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("newcomer")).isEmpty(); // full, still accepted

        assertThat(limiter.tryAcquire("oldest")).isEmpty(); // evicted: fresh budget
        // a younger key kept its state: used 1 at creation, 4 more reach the budget, the 6th is refused
        for (int i = 1; i < LoginAttemptLimiter.MAX_ATTEMPTS; i++) {
            assertThat(limiter.tryAcquire("k5")).isEmpty();
        }
        assertThat(limiter.tryAcquire("k5")).isPresent();
    }
}
