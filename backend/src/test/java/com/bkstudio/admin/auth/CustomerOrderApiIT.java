package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.order.checkout.domain.CheckoutService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T-028 GET /api/orders/{token}: shape per status, 404 uniformity, no leaks, headers, rate limit. */
class CustomerOrderApiIT extends CustomerOrderTestBase {

    private Res get(String token) throws Exception {
        return res(viewOrder(token));
    }

    private static <T> T at(Res r, String path) {
        return JsonPath.read(r.body(), path);
    }

    @Test
    void inStockAwaitingPayment_showsPendingFullRequestAndCancelWithoutWarning() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 2);
        pendingRequest(id, "FULL");
        Res r = get(tokenFor(id));

        assertThat(r.status()).isEqualTo(200);
        assertThat(at(r, "$.publicCode").equals(codeOf(id))).isTrue();
        assertThat(at(r, "$.status").equals("AWAITING_PAYMENT")).isTrue();
        assertThat(at(r, "$.fulfillmentType").equals("IN_STOCK")).isTrue();
        assertThat(at(r, "$.depositCents").equals(0)).isTrue();
        assertThat(at(r, "$.subtotalCents").equals(2000)).isTrue();
        assertThat(at(r, "$.items[0].productName").equals("Order test")).isTrue();
        assertThat(at(r, "$.items[0].quantity").equals(2)).isTrue();
        assertThat(at(r, "$.paymentRequests[0].type").equals("FULL")).isTrue();
        assertThat(at(r, "$.paymentRequests[0].paymentUrl").equals("https://artistree.io/p"))
                .isTrue();
        assertThat(JsonPath.<Object>read(r.body(), "$.estimatedProductionMonth"))
                .isNull();
        assertThat(at(r, "$.canCancel").equals(true)).isTrue();
        assertThat(at(r, "$.cancelWarning").equals(false)).isTrue();
    }

    @Test
    void madeToOrderAwaitingDeposit_showsPendingDepositRequestAndDepositAmount() throws Exception {
        long id = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 1);
        pendingRequest(id, "DEPOSIT");
        Res r = get(tokenFor(id));

        assertThat(r.status()).isEqualTo(200);
        assertThat(at(r, "$.paymentRequests[0].type").equals("DEPOSIT")).isTrue();
        assertThat(((Number) at(r, "$.depositCents")).longValue()).isEqualTo(CheckoutService.depositCents(2000));
        assertThat(at(r, "$.canCancel").equals(true)).isTrue();
        assertThat(at(r, "$.cancelWarning").equals(false)).isTrue();
    }

    @Test
    void queued_showsEstimatedMonthHistoryAndDepositForfeitWarning() throws Exception {
        long id = queuedMto(newVariant(0, 3), 1);
        Res r = get(tokenFor(id));

        assertThat(r.status()).isEqualTo(200);
        String month = jdbc.queryForObject(
                "SELECT to_char(min(q.production_month), 'YYYY-MM') FROM queue_entry q"
                        + " JOIN order_item i ON i.id = q.order_item_id WHERE i.order_id = ?",
                String.class,
                id);
        assertThat(month).matches("\\d{4}-\\d{2}");
        assertThat(at(r, "$.estimatedProductionMonth").equals(month)).isTrue();
        assertThat(at(r, "$.status").equals("QUEUED")).isTrue();
        assertThat(JsonPath.<List<String>>read(r.body(), "$.history[*].status")).contains("QUEUED");
        assertThat(at(r, "$.canCancel").equals(true)).isTrue();
        assertThat(at(r, "$.cancelWarning").equals(true)).isTrue();
        assertThat(JsonPath.<List<Object>>read(r.body(), "$.paymentRequests")).isEmpty();
    }

    @Test
    void shippedAndCompleted_showCarrierAndTracking_otherStatusesHideThem() throws Exception {
        for (String status : List.of("SHIPPED", "COMPLETED")) {
            long id = order("IN_STOCK", status, 1);
            jdbc.update("UPDATE customer_order SET carrier = 'DHL' WHERE id = ?", id);
            Res r = get(tokenFor(id));
            assertThat(at(r, "$.carrier").equals("DHL")).as(status).isTrue();
            assertThat(at(r, "$.trackingNumber").equals("T0")).as(status).isTrue();
            assertThat(at(r, "$.canCancel").equals(false)).as(status).isTrue();
        }
        long paid = order("IN_STOCK", "PAID_IN_FULL", 1);
        jdbc.update("UPDATE customer_order SET carrier = 'DHL', tracking_number = 'T9' WHERE id = ?", paid);
        Res r = get(tokenFor(paid));
        assertThat(JsonPath.<Object>read(r.body(), "$.carrier")).isNull();
        assertThat(JsonPath.<Object>read(r.body(), "$.trackingNumber")).isNull();
    }

    @Test
    void cancelFlags_perStatus() throws Exception {
        record Case(String type, String status, boolean canCancel, boolean warning) {}
        for (Case c : List.of(
                new Case("IN_STOCK", "AWAITING_PAYMENT", true, false),
                new Case("MADE_TO_ORDER", "AWAITING_DEPOSIT", true, false),
                new Case("MADE_TO_ORDER", "QUEUED", true, true),
                new Case("MADE_TO_ORDER", "IN_PRODUCTION", false, false),
                new Case("MADE_TO_ORDER", "AWAITING_BALANCE", false, false),
                new Case("IN_STOCK", "PAID_IN_FULL", false, false),
                new Case("IN_STOCK", "CANCELLED", false, false))) {
            Res r = get(tokenFor(order(c.type(), c.status(), 1)));
            assertThat(at(r, "$.canCancel").equals(c.canCancel()))
                    .as(c.toString())
                    .isTrue();
            assertThat(at(r, "$.cancelWarning").equals(c.warning()))
                    .as(c.toString())
                    .isTrue();
        }
    }

    @Test
    void paymentRequests_onlyPendingAreListed() throws Exception {
        long id = order("MADE_TO_ORDER", "AWAITING_BALANCE", 1);
        for (String type : List.of("DEPOSIT", "FULL")) {
            jdbc.update(
                    "INSERT INTO payment_request (order_id, type, amount_cents, channel, payment_url, due_at, status,"
                            + " external_ref, confirmed_at) VALUES (?, ?, 500, 'ARTISTREE', 'https://x.io/p', now(),"
                            + " 'PAID', ?, now())",
                    id,
                    type,
                    MARKER + "REF-" + type);
        }
        jdbc.update(
                "INSERT INTO payment_request (order_id, type, amount_cents, channel, payment_url, due_at, status)"
                        + " VALUES (?, 'BALANCE', 500, 'ARTISTREE', 'https://x.io/expired', now(), 'EXPIRED')",
                id);
        jdbc.update(
                "INSERT INTO payment_request (order_id, type, amount_cents, channel, payment_url, due_at, status)"
                        + " VALUES (?, 'BALANCE', 500, 'ARTISTREE', 'https://x.io/cancelled', now(), 'CANCELLED')",
                id);
        pendingRequest(id, "BALANCE");

        Res r = get(tokenFor(id));
        assertThat(JsonPath.<List<String>>read(r.body(), "$.paymentRequests[*].paymentUrl"))
                .containsExactly("https://artistree.io/p");
        assertThat(r.body()).doesNotContain(MARKER);
    }

    @Test
    void unknownAndMalformedTokens_getIdenticalNotFound() throws Exception {
        List<String> tokens = List.of(
                CheckoutService.newToken(), // valid shape, not stored
                "short",
                "*".repeat(43),
                CheckoutService.newToken() + "x");
        Res first = get(tokens.get(0));
        assertThat(first.status()).isEqualTo(404);
        assertThat(first.body()).doesNotContain(tokens.get(0));
        for (String t : tokens) {
            Res r = get(t);
            assertThat(r.status()).as(t).isEqualTo(404);
            for (String f : List.of("type", "title", "status", "detail", "code")) {
                assertThat(at(r, "$." + f).equals(at(first, "$." + f)))
                        .as(t + " " + f)
                        .isTrue();
            }
            assertThat(at(r, "$.code").equals("NOT_FOUND")).isTrue();
        }
    }

    @Test
    void response_neverContainsPersonalDataInternalIdsOrSecrets() throws Exception {
        long id = queuedMto(newVariant(0, 3), 1);
        jdbc.update(
                "UPDATE customer_order SET admin_note = ?, cancel_reason = ?, customer_name = ?, phone = ?,"
                        + " ship_line2 = ?, carrier = ?, ship_postal_code = ? WHERE id = ?",
                MARKER + "NOTE",
                MARKER + "REASON",
                MARKER + "NAME",
                MARKER + "PHONE",
                MARKER + "LINE2",
                MARKER + "CARRIER",
                MARKER + "POSTAL",
                id);
        jdbc.update(
                "UPDATE payment_request SET external_ref = external_ref || ? WHERE order_id = ?",
                MARKER + "REFUND",
                id);
        String token = tokenFor(id);
        Res r = get(token);

        assertThat(r.status()).isEqualTo(200);
        List<String> forbidden = new ArrayList<>(List.of(
                MARKER,
                SECRET_EMAIL,
                SECRET_STREET,
                "Singapore",
                "externalRef",
                "refund",
                "\"id\"",
                "orderId",
                "itemId",
                "variantId",
                "productId",
                "version",
                "hash",
                "adminNote",
                "cancelReason",
                token));
        forbidden.addAll(secretsOf(id));
        for (String s : forbidden) {
            assertThat(r.body()).as("body must not contain %s", s).doesNotContain(s);
        }
    }

    @Test
    void response_hasNoStoreAndNoReferrerHeaders() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        var ok = viewOrder(tokenFor(id)).andReturn().getResponse();
        assertThat(ok.getHeader("Cache-Control")).contains("no-store");
        assertThat(ok.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void get_over60PerMinutePerIp_429WithRetryAfter_otherIpAndLaterWindowFine() throws Exception {
        String ip = nextIp();
        String unknown = CheckoutService.newToken();
        for (int i = 0; i < 60; i++) {
            assertThat(viewOrder(unknown, ip).andReturn().getResponse().getStatus())
                    .isEqualTo(404);
        }
        var limited = expectProblem(viewOrder(unknown, ip), 429, "RATE_LIMITED")
                .andReturn()
                .getResponse();
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        assertThat(limited.getContentAsString()).doesNotContain(unknown);
        assertThat(viewOrder(unknown, nextIp()).andReturn().getResponse().getStatus())
                .isEqualTo(404);

        clock.advance(Duration.ofSeconds(61));
        assertThat(viewOrder(unknown, ip).andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
