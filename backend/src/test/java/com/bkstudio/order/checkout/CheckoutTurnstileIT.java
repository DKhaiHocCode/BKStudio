package com.bkstudio.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** T-060: checkout with Turnstile on, against a local fake of Cloudflare's siteverify (never the real one). */
@TestPropertySource(properties = "app.checkout.rate-limit-max=2")
class CheckoutTurnstileIT extends CheckoutTestBase {

    private static final String SECRET = "0xITSECRETKEY123";
    private static final String TOKEN = "it.TOKEN-VALUE_987";
    private static final String OK = "{\"success\":true}";
    private static final String REJECTED = "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}";

    private static final HttpServer CLOUDFLARE;
    private static final List<String> FORMS = new CopyOnWriteArrayList<>();
    private static volatile int status = 200;
    private static volatile String answer = OK;

    static {
        try {
            CLOUDFLARE = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        CLOUDFLARE.createContext("/siteverify", ex -> {
            FORMS.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        CLOUDFLARE.setExecutor(r -> Thread.ofPlatform().daemon().start(r)); // must not keep the test JVM alive
        CLOUDFLARE.start();
    }

    @DynamicPropertySource
    static void turnstile(DynamicPropertyRegistry registry) {
        registry.add("app.turnstile.secret", () -> SECRET);
        registry.add(
                "app.turnstile.verify-url",
                () -> "http://127.0.0.1:" + CLOUDFLARE.getAddress().getPort() + "/siteverify");
    }

    @BeforeEach
    void resetFake() {
        FORMS.clear();
        status = 200;
        answer = OK;
    }

    private Map<String, Object> order(long variant, String token) {
        Map<String, Object> body = order("IN_STOCK", variant, 1);
        body.put("turnstileToken", token);
        return body;
    }

    private static void assertCaptchaFailed(Res res) {
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.<String>json("$.code")).isEqualTo("CAPTCHA_FAILED");
        assertThat(res.<String>json("$.detail")).isEqualTo("Captcha verification failed. Please try again.");
        assertThat(res.body()).doesNotContainIgnoringCase("cloudflare").doesNotContain("invalid-input", TOKEN);
    }

    @Test
    void validToken_201_cloudflareGetsSecretTokenAndIpOnly() throws Exception {
        long v = stockVariant(1_000, 5);
        String ip = nextIp();
        UUID key = UUID.randomUUID();

        Res res = place(key, order(v, TOKEN), ip);

        assertThat(res.status()).isEqualTo(201);
        assertThat(FORMS).hasSize(1);
        assertThat(FORMS.get(0).split("&"))
                .containsExactlyInAnyOrder("secret=" + SECRET, "response=" + TOKEN, "remoteip=" + ip);
    }

    @Test
    void missingBlankOrOversizedToken_400CaptchaFailed_noOrder_cloudflareNotCalled() throws Exception {
        long v = stockVariant(1_000, 5);

        assertCaptchaFailed(place(order("IN_STOCK", v, 1))); // field absent
        assertCaptchaFailed(place(order(v, null)));
        assertCaptchaFailed(place(order(v, "  ")));
        assertCaptchaFailed(place(order(v, "a".repeat(2049))));

        assertThat(FORMS).isEmpty();
        assertThat(orders()).isZero();
        assertThat(stock(v)).isEqualTo(5);
    }

    @Test
    void rejectedToken_400CaptchaFailed_noOrder_noCloudflareDetails() throws Exception {
        long v = stockVariant(1_000, 5);
        answer = REJECTED;

        assertCaptchaFailed(place(order(v, TOKEN)));

        assertThat(FORMS).hasSize(1);
        assertThat(orders()).isZero();
        assertThat(stock(v)).isEqualTo(5);
    }

    @Test
    void cloudflareDown_503FailClosed_noOrder_doesNotCostAnAttempt() throws Exception {
        long v = stockVariant(1_000, 5);
        String ip = nextIp();
        status = 500;

        for (int i = 0; i < 3; i++) { // more than rate-limit-max: an outage must not lock the customer out
            Res res = place(UUID.randomUUID(), order(v, TOKEN), ip);
            assertThat(res.status()).isEqualTo(503);
            assertThat(res.<String>json("$.code")).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(res.body()).doesNotContainIgnoringCase("cloudflare").doesNotContain(TOKEN);
        }
        assertThat(orders()).isZero();
        assertThat(stock(v)).isEqualTo(5);

        status = 200;
        assertThat(place(UUID.randomUUID(), order(v, TOKEN), ip).status()).isEqualTo(201);
    }

    @Test
    void replayOfExistingKey_isVerifiedAgain_200CostsNoAttempt_withoutToken400() throws Exception {
        long v = stockVariant(1_000, 5);
        String ip = nextIp();
        UUID key = UUID.randomUUID();
        assertThat(place(key, order(v, TOKEN), ip).status()).isEqualTo(201);

        Res replay = place(key, order(v, TOKEN), ip);

        assertThat(replay.status()).isEqualTo(200);
        assertThat(FORMS).hasSize(2).noneMatch(f -> f.contains("idempotency_key"));
        // rate-limit-max=2: the order took one attempt; more replays still pass, so replays took none
        assertThat(place(key, order(v, TOKEN), ip).status()).isEqualTo(200);
        assertThat(place(key, order(v, TOKEN), ip).status()).isEqualTo(200);
        assertCaptchaFailed(place(key, order(v, null), ip));
        assertThat(orders()).isEqualTo(1);
    }

    @Test
    void blockedIp_429BeforeCloudflareIsCalled() throws Exception {
        long v = stockVariant(1_000, 5);
        String ip = nextIp();
        assertThat(place(UUID.randomUUID(), order(v, TOKEN), ip).status()).isEqualTo(201);
        assertThat(place(UUID.randomUUID(), order(v, TOKEN), ip).status()).isEqualTo(201);
        FORMS.clear();

        Res limited = place(UUID.randomUUID(), order(v, TOKEN), ip);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.<String>json("$.code")).isEqualTo("RATE_LIMITED");
        assertThat(FORMS).isEmpty();
    }

    @Test
    void rejectedTokensCostAttempts_soOneIpCannotCallCloudflareWithoutLimit() throws Exception {
        long v = stockVariant(1_000, 5);
        String ip = nextIp();
        answer = REJECTED;

        assertCaptchaFailed(place(UUID.randomUUID(), order(v, TOKEN), ip));
        assertCaptchaFailed(place(UUID.randomUUID(), order(v, TOKEN), ip));
        Res third = place(UUID.randomUUID(), order(v, TOKEN), ip);

        assertThat(third.status()).isEqualTo(429);
        assertThat(FORMS).hasSize(2);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void tokenAndSecretNeverLogged(CapturedOutput output) throws Exception {
        long v = stockVariant(1_000, 5);
        assertThat(place(order(v, TOKEN)).status()).isEqualTo(201);
        answer = REJECTED;
        assertThat(place(order(v, TOKEN)).status()).isEqualTo(400);
        answer = "{\"success\":false,\"error-codes\":[\"invalid-input-secret\"]}";
        assertThat(place(order(v, TOKEN)).status()).isEqualTo(503);
        status = 500;
        assertThat(place(order(v, TOKEN)).status()).isEqualTo(503);

        assertThat(output.getAll())
                .contains("Turnstile siteverify") // the failures were logged, so the check below is not vacuous
                .doesNotContain(TOKEN, "it.TOKEN", SECRET, "ITSECRETKEY");
    }
}
