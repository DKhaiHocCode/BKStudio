package com.bkstudio.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.bkstudio.order.checkout.domain.CheckoutProperties;
import com.bkstudio.order.checkout.domain.TurnstileProperties;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class CheckoutConfigValidatorTest {

    private static final String REAL_SECRET = "0x4AAAAAAAnotARealSecretButShapedLikeOne";

    private static void validate(String baseUrl, String turnstileSecret) throws Exception {
        validate(baseUrl, turnstileSecret, false);
    }

    private static void validate(String baseUrl, String turnstileSecret, boolean allowTestSecret) throws Exception {
        validate(baseUrl, turnstileSecret, allowTestSecret, TurnstileProperties.OFFICIAL_VERIFY_URL);
    }

    private static void validate(String baseUrl, String turnstileSecret, boolean allowTestSecret, String verifyUrl)
            throws Exception {
        Class<?> c = Class.forName("com.bkstudio.order.checkout.domain.CheckoutConfigValidator");
        Constructor<?> ctor = c.getDeclaredConstructor(CheckoutProperties.class, TurnstileProperties.class);
        ctor.setAccessible(true);
        Object v = ctor.newInstance(
                new CheckoutProperties(baseUrl, 5, Duration.ofHours(1), 3, Duration.ofHours(1)),
                new TurnstileProperties(
                        turnstileSecret, verifyUrl, Duration.ofSeconds(3), Duration.ofSeconds(5), allowTestSecret));
        Method init = c.getDeclaredMethod("init");
        init.setAccessible(true);
        try {
            init.invoke(v);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "http://bkstudio.xxx", "ftp://x", "bkstudio.xxx"})
    void init_blankOrNonHttps_failsStartup(String url) {
        assertThatIllegalStateException().isThrownBy(() -> validate(url, REAL_SECRET));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                " ",
                "1x0000000000000000000000000000000AA", // Cloudflare dummy: always passes
                "2x0000000000000000000000000000000AA",
                "3x0000000000000000000000000000000AA"
            })
    void init_blankOrDummyTurnstileSecret_failsStartup(String secret) {
        assertThatIllegalStateException()
                .isThrownBy(() -> validate("https://bkstudio.xxx", secret))
                .withMessageContaining("TURNSTILE_SECRET_KEY");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1x0000000000000000000000000000000AA",
                "2x0000000000000000000000000000000AA",
                "3x0000000000000000000000000000000AA"
            })
    void init_dummyTurnstileSecret_allowTestSecret_ok(String secret) {
        assertThatCode(() -> validate("https://bkstudio.xxx", secret, true)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void init_blankTurnstileSecret_failsStartupEvenWithAllowTestSecret(String secret) {
        assertThatIllegalStateException()
                .isThrownBy(() -> validate("https://bkstudio.xxx", secret, true))
                .withMessageContaining("TURNSTILE_SECRET_KEY");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://evil.example/turnstile/v0/siteverify",
                "http://challenges.cloudflare.com/turnstile/v0/siteverify",
                "https://challenges.cloudflare.com/turnstile/v0/siteverify/",
                "https://challenges.cloudflare.com.evil.example/turnstile/v0/siteverify",
                "http://127.0.0.1:8089/siteverify"
            })
    void init_verifyUrlNotCloudflares_failsStartup_unlessAllowTestSecret(String url) {
        assertThatIllegalStateException()
                .isThrownBy(() -> validate("https://bkstudio.xxx", REAL_SECRET, false, url))
                .withMessageContaining("verify-url");
        assertThatCode(() -> validate("https://bkstudio.xxx", REAL_SECRET, true, url))
                .doesNotThrowAnyException();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void init_dummySecretAllowed_warnsAtStartup_realSecretDoesNot(CapturedOutput output) throws Exception {
        validate("https://bkstudio.xxx", REAL_SECRET, true);
        assertThat(output.getAll()).doesNotContain("TEST secret");

        validate("https://bkstudio.xxx", "1x0000000000000000000000000000000AA", true);
        assertThat(output.getAll()).contains("WARN", "TEST secret").doesNotContain("1x0000");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void init_httpsAndRealSecret_ok(boolean allowTestSecret) {
        assertThatCode(() -> validate("https://bkstudio.xxx", REAL_SECRET, allowTestSecret))
                .doesNotThrowAnyException();
    }
}
