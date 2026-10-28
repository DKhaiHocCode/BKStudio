package com.bkstudio.notification.infra;

import com.bkstudio.notification.domain.EmailMessage;
import com.bkstudio.notification.domain.EmailSendException;
import com.bkstudio.notification.domain.EmailSender;
import com.bkstudio.notification.domain.NotificationProperties;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resend REST API (POST /emails, Bearer key, Idempotency-Key header). Errors keep only the HTTP status and Resend's
 * error name: the response message and the request are never stored or logged.
 */
@Component
@ConditionalOnExpression("!'${app.notification.resend-api-key:}'.isBlank()")
class ResendEmailSender implements EmailSender {

    static final String DEFAULT_BASE_URL = "https://api.resend.com";

    private record Response(int status, String body) {}

    private final RestClient client;
    private final NotificationProperties props;
    private final JsonMapper json;

    @Autowired
    ResendEmailSender(NotificationProperties props, JsonMapper json) {
        this(props, json, DEFAULT_BASE_URL);
    }

    ResendEmailSender(NotificationProperties props, JsonMapper json, String baseUrl) {
        this.props = props;
        this.json = json;
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client =
                RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public void send(EmailMessage m, String idempotencyKey) throws EmailSendException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", props.mailFrom());
        body.put("to", List.of(m.to()));
        body.put("subject", m.subject());
        body.put("html", m.html());
        body.put("text", m.text());
        Response res;
        try {
            res = client.post()
                    .uri("/emails")
                    .header("Authorization", "Bearer " + props.resendApiKey())
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body))
                    .exchange((rq, rs) -> new Response(
                            rs.getStatusCode().value(),
                            new String(rs.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException | UncheckedIOException e) {
            throw new EmailSendException("Resend network error", true);
        }
        if (res.status() >= 200 && res.status() < 300) {
            return;
        }
        String name = errorName(res.body());
        // 409 concurrent_idempotent_requests: our own earlier attempt is still in flight, retry later.
        // 401/403 are config errors (key/domain) an operator can fix; the order link must survive until then.
        boolean retry = res.status() == 429
                || res.status() == 401
                || res.status() == 403
                || res.status() >= 500
                || (res.status() == 409 && "concurrent_idempotent_requests".equals(name));
        throw new EmailSendException("Resend HTTP " + res.status() + " " + name, retry);
    }

    private String errorName(String body) {
        try {
            JsonNode n = json.readTree(body).get("name");
            return n == null || !n.isString() ? "unknown" : n.asString().replaceAll("[^A-Za-z0-9_.-]", "");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
