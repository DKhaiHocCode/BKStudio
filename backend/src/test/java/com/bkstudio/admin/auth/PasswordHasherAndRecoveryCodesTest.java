package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class PasswordHasherAndRecoveryCodesTest {

    @Test
    void passwordMatchAndMismatch() {
        PasswordHasher h = new PasswordHasher();
        String hash = h.hash("correct horse battery");
        assertThat(hash).startsWith("$argon2id$");
        assertThat(h.matches("correct horse battery", hash)).isTrue();
        assertThat(h.matches("wrong", hash)).isFalse();
        assertThat(h.hash("correct horse battery")).isNotEqualTo(hash); // random salt
        assertThat(h.matchesDummy("anything")).isFalse();
    }

    @Test
    void whenAllArgon2PermitsAreBusy_hashFailsFastWithRateLimited_andRecoversAfterRelease() throws Exception {
        PasswordHasher h = new PasswordHasher(1, Duration.ofMillis(50));
        h.permits.acquire();

        assertThatThrownBy(() -> h.hash("correct horse battery")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
            assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        });
        assertThatThrownBy(() -> h.matchesDummy("x")).isInstanceOf(ApiException.class);

        h.permits.release();
        assertThat(h.hash("correct horse battery")).startsWith("$argon2id$");
        assertThat(h.permits.availablePermits()).isEqualTo(1);
    }

    @Test
    void ipv6AddressesOfOneSlash64_shareOneLimiterKey() {
        assertThat(LoginAttemptLimiter.key("2001:db8:1:2:aaaa::1"))
                .isEqualTo(LoginAttemptLimiter.key("2001:db8:1:2::ff"));
        assertThat(LoginAttemptLimiter.key("2001:db8:1:3::1")).isNotEqualTo(LoginAttemptLimiter.key("2001:db8:1:2::1"));
        assertThat(LoginAttemptLimiter.key("203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void recoveryCodesFormat() {
        List<String> codes = RecoveryCodes.generate();
        assertThat(codes).hasSize(10);
        assertThat(new HashSet<>(codes)).hasSize(10);
        assertThat(codes).allMatch(c -> c.matches("([A-Z2-7]{4}-){3}[A-Z2-7]{4}"));
    }

    @Test
    void hashNormalisesDashesSpacesAndCase() {
        String h = RecoveryCodes.hash("ABCD-EFGH-2345-6723");
        assertThat(h).hasSize(64).matches("[0-9a-f]+");
        assertThat(RecoveryCodes.hash("abcdefgh23456723")).isEqualTo(h);
        assertThat(RecoveryCodes.hash(" abcd efgh-2345-6723 ")).isEqualTo(h);
        assertThat(RecoveryCodes.hash("ABCD-EFGH-2345-6724")).isNotEqualTo(h);
    }
}
