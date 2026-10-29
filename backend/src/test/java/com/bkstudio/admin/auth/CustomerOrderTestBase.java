package com.bkstudio.admin.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.bkstudio.order.checkout.domain.CheckoutService;
import com.jayway.jsonpath.JsonPath;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * T-028 fixtures: orders get a known access token (the real one is only returned by checkout), plus helpers for the
 * anonymous public requests (CSRF cookie + header, explicit client IP).
 */
abstract class CustomerOrderTestBase extends AdminOrderTestBase {

    static final String MARKER = "ZZ-MARKER-";

    /** Gives the order a fresh known token (version untouched) and returns it. */
    String tokenFor(long orderId) {
        String token = CheckoutService.newToken();
        jdbc.update(
                "UPDATE customer_order SET access_token_hash = ? WHERE id = ?",
                CheckoutService.hashToken(token),
                orderId);
        return token;
    }

    String codeOf(long orderId) {
        return jdbc.queryForObject("SELECT public_code FROM customer_order WHERE id = ?", String.class, orderId);
    }

    String hashHex(long orderId) {
        return jdbc.queryForObject(
                "SELECT encode(access_token_hash, 'hex') FROM customer_order WHERE id = ?", String.class, orderId);
    }

    // ---- requests ----

    ResultActions viewOrder(String token) throws Exception {
        return viewOrder(token, nextIp());
    }

    ResultActions viewOrder(String token, String ip) throws Exception {
        return mvc.perform(getFrom("/api/orders/" + token, null, ip));
    }

    ResultActions cancelOrder(String token, String body) throws Exception {
        return cancelOrder(token, body, nextIp());
    }

    ResultActions cancelOrder(String token, String body, String ip) throws Exception {
        return mvc.perform(withCsrf(HttpMethod.POST, "/api/orders/" + token + "/cancel", body, null, ip));
    }

    ResultActions lookup(String code, String email) throws Exception {
        return lookupFrom(code, email, nextIp());
    }

    ResultActions lookupFrom(String code, String email, String ip) throws Exception {
        return mvc.perform(withCsrf(HttpMethod.POST, "/api/orders/lookup", j("code", code, "email", email), null, ip));
    }

    /** POST without the XSRF cookie and header. */
    ResultActions postWithoutCsrf(String path, String body) throws Exception {
        return mvc.perform(request(HttpMethod.POST, path)
                .with(r -> {
                    r.setRemoteAddr(nextIp());
                    return r;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ---- reads ----

    /** Token inside the newest ORDER_LOOKUP_LINK email of the order. */
    String linkToken(long orderId) {
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM email_outbox WHERE template = 'ORDER_LOOKUP_LINK' AND dedupe_key LIKE ?"
                        + " ORDER BY id DESC LIMIT 1",
                String.class,
                "order:" + orderId + ":lookup:%");
        String url = JsonPath.read(payload, "$.orderUrl");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    int lookupMails(long orderId) {
        return outbox("ORDER_LOOKUP_LINK", orderId);
    }

    /** Everything a cancel may touch, as one comparable string. */
    String snapshot(long orderId, long variantId) {
        return orderStatus(orderId)
                + "|v" + version(orderId)
                + "|stock" + stock(variantId)
                + "|quota" + quota(variantId)
                + "|alloc" + allocatedTotal()
                + "|open" + openEntries()
                + "|pending"
                + count("SELECT count(*) FROM payment_request WHERE order_id = ? AND status = 'PENDING'", orderId)
                + "|outbox" + count("SELECT count(*) FROM email_outbox")
                + "|audit"
                + count("SELECT count(*) FROM audit_log WHERE entity_type = 'ORDER' AND entity_id = ?", orderId);
    }

    /** Raw-body strings that must never appear in a public response. */
    List<String> secretsOf(long orderId) {
        byte[] hash =
                jdbc.queryForObject("SELECT access_token_hash FROM customer_order WHERE id = ?", byte[].class, orderId);
        return List.of(
                HexFormat.of().formatHex(hash),
                HexFormat.of().withUpperCase().formatHex(hash),
                Base64.getEncoder().encodeToString(hash),
                Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
    }
}
