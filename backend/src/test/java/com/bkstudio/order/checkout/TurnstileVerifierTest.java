package com.bkstudio.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bkstudio.order.checkout.domain.TurnstileProperties;
import com.bkstudio.order.checkout.domain.TurnstileVerifier;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class TurnstileVerifierTest {

    private static final String SECRET = "0xSECRETKEY+/=123";
    private static final String TOKEN = "tok.EN-VALUE_987";
    private static final String IP = "203.0.113.77";

    private HttpServer server;
    private volatile int status = 200;
    private volatile String responseBody = "{\"success\":true}";
    private volatile long delayMs;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/siteverify", ex -> {
            calls.incrementAndGet();
            method.set(ex.getRequestMethod());
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TurnstileVerifier verifier(String secret) {
        return verifier(secret, 20);
    }

    private TurnstileVerifier verifier(String secret, int maxConcurrent) {
        return new TurnstileVerifier(
                new TurnstileProperties(
                        secret,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/siteverify",
                        Duration.ofSeconds(2),
                        Duration.ofMillis(500),
                        false),
                JsonMapper.builder().build(),
                maxConcurrent);
    }

    private void assertUnavailable(TurnstileVerifier v) {
        assertThatThrownBy(() -> v.verify(TOKEN, IP))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void verify_success_postsUrlEncodedFormWithSecretTokenAndIpOnly() {
        assertThat(verifier(SECRET).verify(TOKEN, IP)).isTrue();

        assertThat(method.get()).isEqualTo("POST");
        assertThat(contentType.get()).startsWith("application/x-www-form-urlencoded");
        assertThat(body.get().split("&"))
                .containsExactlyInAnyOrder("secret=0xSECRETKEY%2B%2F%3D123", "response=" + TOKEN, "remoteip=" + IP);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}",
                "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}",
                "{\"success\":false,\"error-codes\":[]}",
                "{\"success\":false}"
            })
    void verify_tokenRejected_false(String answer) {
        responseBody = answer;

        assertThat(verifier(SECRET).verify(TOKEN, IP)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void verify_missingToken_falseWithoutCallingCloudflare(String token) {
        assertThat(verifier(SECRET).verify(token, IP)).isFalse();
        assertThat(calls).hasValue(0);
    }

    @Test
    void verify_tokenLength_2048Sent_2049RefusedLocally() {
        TurnstileVerifier v = verifier(SECRET);

        assertThat(v.verify("a".repeat(2048), IP)).isTrue();
        assertThat(v.verify("a".repeat(2049), IP)).isFalse();
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void verify_blankSecret_disabled_trueWithoutCallingCloudflare(String secret) {
        assertThat(verifier(secret).verify(null, IP)).isTrue();
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void verify_cloudflare5xx_unavailable_evenIfBodySaysSuccess(int code) {
        status = code;

        assertUnavailable(verifier(SECRET));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{}", "{\"success\":\"true\"}", "[]", "null"})
    void verify_unusableBody_unavailable(String answer) {
        responseBody = answer;

        assertUnavailable(verifier(SECRET));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-input-secret", "missing-input-secret", "bad-request", "internal-error"})
    void verify_errorNotAboutTheToken_unavailableAndLoggedForOperator(String code, CapturedOutput output) {
        responseBody = "{\"success\":false,\"error-codes\":[\"" + code + "\"]}";

        assertUnavailable(verifier(SECRET));
        assertThat(output.getAll()).contains(code);
    }

    @Test
    void verify_answerOver16KiB_unavailable_atTheLimitStillRead() {
        String head = "{\"success\":true,\"pad\":\"";
        TurnstileVerifier v = verifier(SECRET);

        responseBody = head + "a".repeat(16 * 1024 - head.length() - 2) + "\"}";
        assertThat(v.verify(TOKEN, IP)).isTrue();

        responseBody = head + "a".repeat(16 * 1024 - head.length() - 1) + "\"}";
        assertUnavailable(v);
    }

    @Test
    void verify_allPermitsInFlight_unavailableWithoutCallingCloudflare_permitReleasedAfterwards() throws Exception {
        TurnstileVerifier v = verifier(SECRET, 1);
        delayMs = 250; // under the 500 ms read timeout
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> v.verify(TOKEN, IP));
        while (calls.get() == 0) {
            Thread.sleep(5);
        }

        assertUnavailable(v);

        assertThat(calls).hasValue(1);
        assertThat(first.get()).isTrue();
        delayMs = 0;
        assertThat(v.verify(TOKEN, IP)).isTrue();
        // a failed call gives its permit back too
        status = 500;
        assertUnavailable(v);
        status = 200;
        assertThat(v.verify(TOKEN, IP)).isTrue();
    }

    @Test
    void verify_slowerThanReadTimeout_unavailable() {
        delayMs = 1_500;

        assertUnavailable(verifier(SECRET));
    }

    @Test
    void verify_connectionRefused_unavailable() {
        TurnstileVerifier v = verifier(SECRET);
        server.stop(0);

        assertUnavailable(v);
    }

    @Test
    void verify_neverLogsTokenSecretOrIp(CapturedOutput output) {
        TurnstileVerifier v = verifier(SECRET);
        v.verify(TOKEN, IP);
        responseBody = "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}";
        v.verify(TOKEN, IP);
        responseBody = "{\"success\":false,\"error-codes\":[\"invalid-input-secret\",\"" + TOKEN + SECRET + "\"]}";
        assertUnavailable(v);
        status = 500;
        assertUnavailable(v);
        server.stop(0);
        assertUnavailable(v);

        assertThat(output.getAll())
                .contains("Turnstile") // something was logged, so the check below is not vacuous
                .doesNotContain(TOKEN, "tok.EN", SECRET, "SECRETKEY", IP);
    }
}
