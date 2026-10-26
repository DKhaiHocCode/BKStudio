package com.bkstudio.admin.auth;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** RFC 6238 TOTP (HMAC-SHA1, 30 s step, 6 digits), accepting the previous, current and next step. */
@Component
public class TotpService {

    static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int WINDOW = 1;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TotpService(Clock clock) {
        this.clock = clock;
    }

    /** 20 random bytes (160 bits), the RFC 4226 recommended secret length. */
    public byte[] generateSecret() {
        byte[] secret = new byte[20];
        random.nextBytes(secret);
        return secret;
    }

    public String toBase32(byte[] secret) {
        return Base32.encode(secret);
    }

    public String otpauthUri(String issuer, String account, byte[] secret) {
        return "otpauth://totp/" + enc(issuer) + ":" + enc(account) + "?secret=" + Base32.encode(secret) + "&issuer="
                + enc(issuer) + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /**
     * Returns the matched time step, or empty if the code is wrong. The caller must pass the step to a
     * {@link TotpReplayGuard}: a valid code is single-use.
     */
    public OptionalLong verify(byte[] secret, String code) {
        if (code == null || code.length() != DIGITS || !code.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return OptionalLong.empty();
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long current = clock.instant().getEpochSecond() / STEP_SECONDS;
        long matched = -1;
        // No early exit: every step in the window is always compared, in constant time.
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            byte[] expected = generate(secret, step, DIGITS).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(given, expected)) {
                matched = step;
            }
        }
        return matched >= 0 ? OptionalLong.of(matched) : OptionalLong.empty();
    }

    /** HOTP (RFC 4226) for a time step. Package-private so tests can use the 8-digit RFC 6238 vectors. */
    static String generate(byte[] secret, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0x0F;
            int binary = ((h[offset] & 0x7F) << 24)
                    | ((h[offset + 1] & 0xFF) << 16)
                    | ((h[offset + 2] & 0xFF) << 8)
                    | (h[offset + 3] & 0xFF);
            long mod = 1;
            for (int i = 0; i < digits; i++) {
                mod *= 10;
            }
            return String.format(Locale.ROOT, "%0" + digits + "d", binary % mod);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
