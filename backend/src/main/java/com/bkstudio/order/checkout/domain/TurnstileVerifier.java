package com.bkstudio.order.checkout.domain;

import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cloudflare Turnstile siteverify (form POST: secret, response, remoteip). Fails closed: when
 * Cloudflare cannot give a verdict the caller gets 503, never a pass. The token, the secret and the client IP are
 * never logged; only the HTTP status and Cloudflare's error codes are.
 */
@Component
public class TurnstileVerifier {

    /** Cloudflare: a token is at most 2048 characters. */
    public static final int MAX_TOKEN_LENGTH = 2048;

    /** Verdicts about the visitor's token. Any other error code is our fault or Cloudflare's: 503, not "captcha". */
    private static final Set<String> TOKEN_ERRORS =
            Set.of("missing-input-response", "invalid-input-response", "timeout-or-duplicate");

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);

    /** Bulkhead: outbound calls in flight. A slow Cloudflare must not tie up every request thread. */
    static final int MAX_CONCURRENT = 20;

    /** siteverify answers are a few hundred bytes; anything bigger is not Cloudflare's answer. */
    static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private record Response(int status, byte[] body) {}

    private final TurnstileProperties props;
    private final JsonMapper json;
    private final RestClient client;
    private final Semaphore inFlight;

    @Autowired
    public TurnstileVerifier(TurnstileProperties props, JsonMapper json) {
        this(props, json, MAX_CONCURRENT);
    }

    public TurnstileVerifier(TurnstileProperties props, JsonMapper json, int maxConcurrent) {
        this.props = props;
        this.json = json;
        this.inFlight = new Semaphore(maxConcurrent);
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(props.readTimeout());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * True when Turnstile is off or Cloudflare accepted the token; false when the token is missing, oversized or
     * rejected.
     *
     * @throws ApiException SERVICE_UNAVAILABLE when Cloudflare is unreachable, slow, or answers something unusable,
     *     or when too many verifications are already in flight
     */
    public boolean verify(String token, String clientIp) {
        if (!props.enabled()) {
            return true;
        }
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return false;
        }
        String form = "secret=" + enc(props.secret()) + "&response=" + enc(token);
        if (clientIp != null && !clientIp.isBlank()) {
            form += "&remoteip=" + enc(clientIp);
        }
        if (!inFlight.tryAcquire()) {
            log.warn("Turnstile siteverify skipped: too many verifications already in flight");
            throw unavailable();
        }
        Response res;
        try {
            res = client.post()
                    .uri(props.verifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((rq, rs) -> new Response(
                            rs.getStatusCode().value(), rs.getBody().readNBytes(MAX_RESPONSE_BYTES + 1)));
        } catch (RestClientException | UncheckedIOException e) {
            // Class name only: the message could carry request details.
            log.warn("Turnstile siteverify unreachable: {}", e.getClass().getSimpleName());
            throw unavailable();
        } finally {
            inFlight.release();
        }
        JsonNode body;
        try {
            body = res.body().length > MAX_RESPONSE_BYTES
                    ? null
                    : json.readTree(new String(res.body(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            body = null;
        }
        JsonNode success = body == null ? null : body.get("success");
        if (res.status() >= 500 || success == null || !success.isBoolean()) {
            log.warn("Turnstile siteverify unusable answer: HTTP {}", res.status());
            throw unavailable();
        }
        if (success.asBoolean()) {
            return true;
        }
        List<String> codes = errorCodes(body);
        if (!TOKEN_ERRORS.containsAll(codes)) {
            // invalid-input-secret, bad-request, internal-error...: an operator must see it.
            log.error("Turnstile siteverify refused the request: {}", codes);
            throw unavailable();
        }
        return false;
    }

    private static List<String> errorCodes(JsonNode body) {
        List<String> out = new ArrayList<>();
        JsonNode codes = body.get("error-codes");
        if (codes != null && codes.isArray()) {
            for (JsonNode c : codes) {
                out.add(c.isString() ? c.asString().replaceAll("[^a-z-]", "") : "unknown");
            }
        }
        return out;
    }

    private static ApiException unavailable() {
        return new ApiException(
                ErrorCode.SERVICE_UNAVAILABLE, "Checkout is temporarily unavailable. Please try again shortly.");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
