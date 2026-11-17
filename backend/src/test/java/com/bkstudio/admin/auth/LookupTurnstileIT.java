package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.order.checkout.domain.TurnstileVerifier;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T-064: POST /api/orders/lookup with Turnstile on, against a local fake of Cloudflare's siteverify (never the real
 * one). The captcha verdict comes before any DB read, so every captcha answer is the same for a matching pair and for
 * an unknown order.
 */
class LookupTurnstileIT extends CustomerOrderTestBase {

    private static final String SECRET = "0xITSECRETKEY123";
    private static final String TOKEN = "it.TOKEN-VALUE_987";
    private static final String OK = "{\"success\":true}";
    private static final String REJECTED = "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}";
    private static final String SLOW = "it.SLOW-TOKEN";
    private static final int IP_CAP = 10;

    private static final HttpServer CLOUDFLARE;
    private static final List<String> FORMS = new CopyOnWriteArrayList<>();
    private static volatile int status = 200;
    private static volatile String answer = OK;
    /** Requests carrying SLOW wait here (at most 4 s), so a test can keep verifications in flight. */
    private static volatile CountDownLatch hold = new CountDownLatch(0);

    static {
        try {
            CLOUDFLARE = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        CLOUDFLARE.createContext("/siteverify", ex -> {
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            FORMS.add(form);
            if (form.contains("response=" + SLOW)) {
                try {
                    hold.await(4, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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

    @Autowired
    TurnstileVerifier verifier;

    private long id;
    private String oldToken;
    private String hash;

    @BeforeEach
    void resetFakeAndOrder() {
        FORMS.clear();
        status = 200;
        answer = OK;
        id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        oldToken = tokenFor(id);
        hash = hashHex(id);
    }

    private Res lookup(String code, String email, String token, String ip) throws Exception {
        return res(mvc.perform(withCsrf(
                HttpMethod.POST,
                "/api/orders/lookup",
                j("code", code, "email", email, "turnstileToken", token),
                null,
                ip)));
    }

    private Res match(String token, String ip) throws Exception {
        return lookup(codeOf(id), SECRET_EMAIL, token, ip);
    }

    private Res unknown(String token, String ip) throws Exception {
        return lookup("BK-NOPE1234", "nobody@example.com", token, ip);
    }

    private void assertNothingHappened() throws Exception {
        assertThat(hashHex(id)).isEqualTo(hash);
        assertThat(count("SELECT count(*) FROM email_outbox")).isZero();
        assertThat(auditCount("ORDER_LINK_REISSUED", id)).isZero();
        assertThat(viewOrder(oldToken).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private static void assertCaptchaFailed(Res res) {
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("CAPTCHA_FAILED");
        assertThat(JsonPath.<String>read(res.body(), "$.detail"))
                .isEqualTo("Captcha verification failed. Please try again.");
        assertThat(res.body()).doesNotContainIgnoringCase("cloudflare").doesNotContain("invalid-input", TOKEN);
    }

    /** The error body without its per-request id. */
    private static String problem(Res res) {
        return res.body().replaceAll("\"requestId\":\"[0-9a-f]+\"", "");
    }

    @Test
    void validToken_202SameBodyForMatchAndUnknown_matchRotates_cloudflareGetsSecretTokenAndIpOnly() throws Exception {
        String ip = nextIp();

        Res miss = unknown(TOKEN, nextIp());
        assertNothingHappened();
        FORMS.clear();
        Res hit = match(TOKEN, ip);

        assertThat(hit.status()).isEqualTo(202);
        assertThat(miss.status()).isEqualTo(202);
        assertThat(hit.body()).isEqualTo(miss.body());
        assertThat(FORMS).hasSize(1);
        assertThat(FORMS.get(0).split("&"))
                .containsExactlyInAnyOrder("secret=" + SECRET, "response=" + TOKEN, "remoteip=" + ip);
        assertThat(lookupMails(id)).isEqualTo(1);
        assertThat(viewOrder(oldToken).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(viewOrder(linkToken(id)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void missingBlankOrOversizedToken_400CaptchaFailed_nothingHappens_cloudflareNotCalled() throws Exception {
        Res absent = res(lookupFrom(codeOf(id), SECRET_EMAIL, nextIp())); // field absent
        assertCaptchaFailed(absent);
        assertCaptchaFailed(match(null, nextIp()));
        assertCaptchaFailed(match("  ", nextIp()));
        assertCaptchaFailed(match("a".repeat(2049), nextIp()));
        // an unknown order gets the very same bytes: the answer does not depend on the order
        assertThat(problem(unknown(null, nextIp()))).isEqualTo(problem(absent));

        assertThat(FORMS).isEmpty();
        assertNothingHappened();
    }

    @Test
    void rejectedToken_400CaptchaFailed_sameBodyForMatchAndUnknown_noMailNoRotation() throws Exception {
        answer = REJECTED;

        Res hit = match(TOKEN, nextIp());
        Res miss = unknown(TOKEN, nextIp());

        assertCaptchaFailed(hit);
        assertThat(miss.status()).isEqualTo(400);
        assertThat(problem(miss)).isEqualTo(problem(hit));
        assertThat(FORMS).hasSize(2);
        assertNothingHappened();
    }

    @Test
    void cloudflareDown_503FailClosed_nothingHappens_doesNotCostAnAttempt() throws Exception {
        String ip = nextIp();
        status = 500;

        for (int i = 0; i < IP_CAP + 2; i++) { // more than the IP cap: an outage must not lock the customer out
            Res res = match(TOKEN, ip);
            assertThat(res.status()).isEqualTo(503);
            assertThat(res.code()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(res.body()).doesNotContainIgnoringCase("cloudflare").doesNotContain(TOKEN);
        }
        assertNothingHappened();

        status = 200;
        assertThat(match(TOKEN, ip).status()).isEqualTo(202);
        assertThat(lookupMails(id)).isEqualTo(1);
    }

    @Test
    void rejectedTokensCostAttempts_thenLimitedIpGets202WithoutCloudflareOrDb() throws Exception {
        String ip = nextIp();
        answer = REJECTED;
        for (int i = 0; i < IP_CAP; i++) {
            assertCaptchaFailed(match(TOKEN, ip));
        }
        answer = OK;
        FORMS.clear();

        Res limited = match(TOKEN, ip);
        Res limitedNoToken = match(null, ip);

        // ADR-0012: a limited IP gets the usual 202 whatever it sends, and nothing is done
        assertThat(limited.status()).isEqualTo(202);
        assertThat(limitedNoToken.status()).isEqualTo(202);
        assertThat(FORMS).isEmpty();
        assertNothingHappened();
    }

    @Test
    void acceptedLookupsCostOneAttemptEach() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < IP_CAP; i++) {
            assertThat(unknown(TOKEN, ip).status()).isEqualTo(202);
        }
        assertThat(FORMS).hasSize(IP_CAP);

        assertThat(match(TOKEN, ip).status()).isEqualTo(202);

        assertThat(FORMS).hasSize(IP_CAP); // over the cap: no siteverify call
        assertNothingHappened();
    }

    @Test
    void invalidBody_400ValidationFailed_cloudflareNotCalled() throws Exception {
        for (Res res : List.of(
                lookup("", SECRET_EMAIL, TOKEN, nextIp()),
                lookup(codeOf(id), null, TOKEN, nextIp()),
                lookup("X".repeat(21), SECRET_EMAIL, TOKEN, nextIp()))) {
            assertThat(res.status()).isEqualTo(400);
            assertThat(res.code()).isEqualTo("VALIDATION_FAILED");
        }

        assertThat(FORMS).isEmpty();
        assertNothingHappened();
    }

    private static void awaitForms(int n) throws InterruptedException {
        for (int i = 0; i < 300 && FORMS.size() < n; i++) {
            Thread.sleep(10);
        }
        assertThat(FORMS).hasSize(n);
    }

    @Test
    void lookupBulkheadFull_503WithoutCloudflareCall_checkoutKeepsItsPermits() throws Exception {
        int lookups = TurnstileVerifier.MAX_CONCURRENT_LOOKUP;
        int checkouts = 15; // the verifier's 20 permits minus the 5 lookup may hold
        List<String> ips = new ArrayList<>();
        for (int i = 0; i < lookups; i++) {
            ips.add(nextIp());
        }
        hold = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(lookups + checkouts)) {
            try {
                List<Future<Res>> held = new ArrayList<>();
                for (String ip : ips) {
                    held.add(pool.submit(() -> unknown(SLOW, ip)));
                }
                awaitForms(lookups);

                Res full = unknown(TOKEN, nextIp());

                assertThat(full.status()).isEqualTo(503);
                assertThat(full.code()).isEqualTo("SERVICE_UNAVAILABLE");
                assertThat(full.body()).doesNotContainIgnoringCase("cloudflare").doesNotContain(TOKEN);
                assertThat(FORMS).hasSize(lookups); // refused before any siteverify call

                // verify() is the checkout path: 15 of them reach Cloudflare while lookup holds all of its permits
                List<Future<Boolean>> orders = new ArrayList<>();
                for (int i = 0; i < checkouts; i++) {
                    orders.add(pool.submit(() -> verifier.verify(SLOW, "203.0.113.9")));
                }
                awaitForms(lookups + checkouts);

                hold.countDown();
                for (Future<Boolean> order : orders) {
                    assertThat(order.get(10, TimeUnit.SECONDS)).isTrue();
                }
                for (Future<Res> lookup : held) {
                    assertThat(lookup.get(10, TimeUnit.SECONDS).status()).isEqualTo(202);
                }
            } finally {
                hold.countDown();
            }
        }
        // a freed permit is usable again
        assertThat(unknown(TOKEN, nextIp()).status()).isEqualTo(202);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void tokenSecretIpAndEmailNeverLogged(CapturedOutput output) throws Exception {
        String ip = nextIp();
        assertThat(match(TOKEN, ip).status()).isEqualTo(202);
        answer = REJECTED;
        assertThat(match(TOKEN, ip).status()).isEqualTo(400);
        answer = "{\"success\":false,\"error-codes\":[\"invalid-input-secret\"]}";
        assertThat(match(TOKEN, ip).status()).isEqualTo(503);
        status = 500;
        assertThat(match(TOKEN, ip).status()).isEqualTo(503);

        assertThat(output.getAll())
                .contains("Turnstile siteverify") // the failures were logged, so the check below is not vacuous
                .doesNotContain(TOKEN, "it.TOKEN", SECRET, "ITSECRETKEY", ip, SECRET_EMAIL);
    }
}
