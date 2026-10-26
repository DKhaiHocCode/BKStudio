package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpServiceTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private static TotpService at(long epochSecond) {
        return new TotpService(Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    // RFC 6238 Appendix B, SHA-1 vectors (8 digits).
    @ParameterizedTest
    @CsvSource({
        "59,94287082",
        "1111111109,07081804",
        "1111111111,14050471",
        "1234567890,89005924",
        "2000000000,69279037",
        "20000000000,65353130"
    })
    void matchesRfc6238Vectors(long time, String expected8) {
        assertThat(TotpService.generate(RFC_SECRET, time / 30, 8)).isEqualTo(expected8);
        // 6-digit code is the last 6 digits of the 8-digit one (same 31-bit value, mod 10^6).
        String code6 = TotpService.generate(RFC_SECRET, time / 30, 6);
        assertThat(code6).isEqualTo(expected8.substring(2));
        assertThat(at(time).verify(RFC_SECRET, code6)).hasValue(time / 30);
    }

    @Test
    void acceptsPlusMinusOneStepAndRejectsTwo() {
        long now = 1111111109L;
        long step = now / 30;
        TotpService svc = at(now);
        assertThat(svc.verify(RFC_SECRET, TotpService.generate(RFC_SECRET, step - 1, 6)))
                .hasValue(step - 1);
        assertThat(svc.verify(RFC_SECRET, TotpService.generate(RFC_SECRET, step + 1, 6)))
                .hasValue(step + 1);
        assertThat(svc.verify(RFC_SECRET, TotpService.generate(RFC_SECRET, step - 2, 6)))
                .isEmpty();
        assertThat(svc.verify(RFC_SECRET, TotpService.generate(RFC_SECRET, step + 2, 6)))
                .isEmpty();
    }

    @Test
    void rejectsMalformedCodes() {
        TotpService svc = at(59);
        for (String bad : new String[] {null, "", "12345", "1234567", "abcdef", "28708 "}) {
            assertThat(svc.verify(RFC_SECRET, bad)).isEmpty();
        }
    }

    @Test
    void replayGuardRejectsReuseAndOlderSteps() {
        TotpReplayGuard guard = new TotpReplayGuard();
        assertThat(guard.accept(1, 100)).isTrue();
        assertThat(guard.accept(1, 100)).isFalse();
        assertThat(guard.accept(1, 99)).isFalse();
        assertThat(guard.accept(1, 101)).isTrue();
        assertThat(guard.accept(2, 100)).isTrue(); // other admin is independent
    }

    @Test
    void otpauthUriAndSecretFormat() {
        TotpService svc = at(0);
        byte[] secret = svc.generateSecret();
        assertThat(secret).hasSize(20);
        String uri = svc.otpauthUri("BKStudio", "admin", RFC_SECRET);
        assertThat(uri)
                .startsWith("otpauth://totp/BKStudio:admin?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
                .contains("issuer=BKStudio", "digits=6", "period=30");
    }
}
