package com.bkstudio.order.checkout.domain;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fails startup in prod when APP_BASE_URL is missing or not https (order links are emailed to customers), or when
 * the Turnstile secret is missing or a Cloudflare dummy test secret (those accept the public dummy token; allowed only
 * with app.turnstile.allow-test-secret=true, for local prod-profile rehearsals), or when the verify URL is not
 * Cloudflare's (same flag lifts it).
 */
@Component
@Profile("prod")
class CheckoutConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(CheckoutConfigValidator.class);

    private final CheckoutProperties props;
    private final TurnstileProperties turnstile;

    CheckoutConfigValidator(CheckoutProperties props, TurnstileProperties turnstile) {
        this.props = props;
        this.turnstile = turnstile;
    }

    @PostConstruct
    void init() {
        if (props.siteBaseUrl() == null || !props.siteBaseUrl().startsWith("https://")) {
            throw new IllegalStateException("app.checkout.site-base-url (APP_BASE_URL) must start with https://");
        }
        if (!turnstile.enabled()) {
            throw new IllegalStateException("app.turnstile.secret (TURNSTILE_SECRET_KEY) must be set");
        }
        boolean testSecret = turnstile.secret().trim().matches("[123]x0+AA");
        if (testSecret && !turnstile.allowTestSecret()) {
            throw new IllegalStateException("app.turnstile.secret (TURNSTILE_SECRET_KEY) is a Cloudflare test secret;"
                    + " set TURNSTILE_ALLOW_TEST_SECRET=true only for local rehearsals");
        }
        // The secret and every token are posted to this URL: only a rehearsal may point it elsewhere.
        if (!TurnstileProperties.OFFICIAL_VERIFY_URL.equals(turnstile.verifyUrl()) && !turnstile.allowTestSecret()) {
            throw new IllegalStateException(
                    "app.turnstile.verify-url must be " + TurnstileProperties.OFFICIAL_VERIFY_URL);
        }
        if (testSecret) {
            log.warn("Turnstile runs with a Cloudflare TEST secret (TURNSTILE_ALLOW_TEST_SECRET=true):"
                    + " the captcha stops nobody. Never use this on the real site.");
        }
    }
}
