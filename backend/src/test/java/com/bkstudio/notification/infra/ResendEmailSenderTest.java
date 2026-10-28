package com.bkstudio.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bkstudio.notification.domain.EmailMessage;
import com.bkstudio.notification.domain.EmailSendException;
import com.bkstudio.notification.domain.NotificationProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ResendEmailSenderTest {

    private static final String KEY = "re_SECRET_API_KEY_123";
    private static final String TO = "buyer.secret@example.com";
    private static final EmailMessage MSG = new EmailMessage(TO, "Subj SECRETSUBJECT", "<p>HTMLBODY</p>", "TEXTBODY");

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private volatile int status = 200;
    private volatile String responseBody = "{\"id\":\"abc\"}";
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> idem = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            method.set(ex.getRequestMethod());
            path.set(ex.getRequestURI().getPath());
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            idem.set(ex.getRequestHeaders().getFirst("Idempotency-Key"));
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
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

    private ResendEmailSender sender(String baseUrl) {
        return new ResendEmailSender(
                new NotificationProperties(
                        "BKStudio <orders@bk.example>", KEY, new NotificationProperties.Outbox(false)),
                json,
                baseUrl);
    }

    private ResendEmailSender sender() {
        return sender("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void assertTransient(Throwable t, boolean expected) {
        assertThat(t)
                .isInstanceOfSatisfying(
                        EmailSendException.class,
                        e -> assertThat(e.transientFailure()).isEqualTo(expected));
    }

    @Test
    void send_ok_postsEmailsWithBearerIdempotencyKeyAndJsonBody() throws Exception {
        sender().send(MSG, "order:1:received");

        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/emails");
        assertThat(auth.get()).isEqualTo("Bearer " + KEY);
        assertThat(idem.get()).isEqualTo("order:1:received");
        assertThat(contentType.get()).startsWith("application/json");
        JsonNode b = json.readTree(body.get());
        assertThat(b.get("from").asString()).isEqualTo("BKStudio <orders@bk.example>");
        assertThat(b.get("to").isArray()).isTrue();
        assertThat(b.get("to")).hasSize(1);
        assertThat(b.get("to").get(0).asString()).isEqualTo(TO);
        assertThat(b.get("subject").asString()).isEqualTo(MSG.subject());
        assertThat(b.get("html").asString()).isEqualTo(MSG.html());
        assertThat(b.get("text").asString()).isEqualTo(MSG.text());
    }

    @Test
    void send_okStatus_doesNotThrow() {
        assertThatCode(() -> sender().send(MSG, "k")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 502, 503})
    void send_retryableStatus_isTransient(int code) {
        status = code;
        responseBody = "{\"name\":\"rate_limit_exceeded\",\"message\":\"oops " + TO + "\"}";
        assertThatThrownBy(() -> sender().send(MSG, "k")).satisfies(t -> assertTransient(t, true));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 422})
    void send_clientError_isPermanent(int code) {
        status = code;
        responseBody = "{\"name\":\"validation_error\",\"message\":\"bad " + TO + "\"}";
        assertThatThrownBy(() -> sender().send(MSG, "k")).satisfies(t -> assertTransient(t, false));
    }

    @Test
    void send_409ConcurrentIdempotentRequests_isTransient() {
        status = 409;
        responseBody = "{\"name\":\"concurrent_idempotent_requests\",\"message\":\"m\"}";
        assertThatThrownBy(() -> sender().send(MSG, "k")).satisfies(t -> assertTransient(t, true));
    }

    @Test
    void send_409InvalidIdempotentRequest_isPermanent() {
        status = 409;
        responseBody = "{\"name\":\"invalid_idempotent_request\",\"message\":\"m\"}";
        assertThatThrownBy(() -> sender().send(MSG, "k")).satisfies(t -> assertTransient(t, false));
    }

    @Test
    void send_409WithUnparseableBody_isPermanent() {
        status = 409;
        responseBody = "not json";
        assertThatThrownBy(() -> sender().send(MSG, "k")).satisfies(t -> assertTransient(t, false));
    }

    @Test
    void send_networkError_isTransient() {
        int port = server.getAddress().getPort();
        server.stop(0); // port is now closed
        assertThatThrownBy(() -> sender("http://127.0.0.1:" + port).send(MSG, "k"))
                .satisfies(t -> assertTransient(t, true));
    }

    @Test
    void send_httpFailure_messageHoldsNoRecipientBodyOrApiKey() {
        status = 422;
        responseBody = "{\"name\":\"validation_error\",\"message\":\"bad " + TO + " " + KEY + "\"}";
        assertThatThrownBy(() -> sender().send(MSG, "k")).isInstanceOfSatisfying(EmailSendException.class, e -> {
            assertThat(e.getMessage())
                    .doesNotContain(TO)
                    .doesNotContain("buyer.secret")
                    .doesNotContain(KEY)
                    .doesNotContain("SECRETSUBJECT")
                    .doesNotContain("HTMLBODY")
                    .doesNotContain("TEXTBODY");
            assertThat(e.getMessage()).contains("422").contains("validation_error");
            assertThat(e.getCause()).isNull();
        });
    }

    @Test
    void send_networkFailure_messageHoldsNoRecipientOrApiKey() {
        int port = server.getAddress().getPort();
        server.stop(0);
        assertThatThrownBy(() -> sender("http://127.0.0.1:" + port).send(MSG, "k"))
                .isInstanceOfSatisfying(EmailSendException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain(TO).doesNotContain(KEY);
                    assertThat(e.getCause()).isNull();
                });
    }
}
