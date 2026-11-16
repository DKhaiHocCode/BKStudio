package com.bkstudio.order.checkout.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Cloudflare Turnstile. {@code secret} (env TURNSTILE_SECRET_KEY, never logged) blank = verification is off
 * (dev/test); prod fails startup without it. {@code verifyUrl} is only overridden by tests. {@code allowTestSecret}
 * (env TURNSTILE_ALLOW_TEST_SECRET, default false) lets the prod profile start with a Cloudflare dummy secret: only
 * for local rehearsals / E2E, never on the real site (the always-pass secret accepts a public dummy token).
 */
@Validated
@ConfigurationProperties("app.turnstile")
public record TurnstileProperties(
        String secret,
        @NotBlank String verifyUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        boolean allowTestSecret) {

    public static final String OFFICIAL_VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    public boolean enabled() {
        return secret != null && !secret.isBlank();
    }
}
