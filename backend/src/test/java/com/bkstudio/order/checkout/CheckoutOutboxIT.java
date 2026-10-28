package com.bkstudio.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.notification.domain.OutboxSender;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** T-024: checkout queues the ORDER_RECEIVED email in the same transaction as the order. */
class CheckoutOutboxIT extends CheckoutTestBase {

    @Autowired
    OutboxSender outbox;

    @BeforeEach
    void clearOutbox() {
        jdbc.execute("TRUNCATE email_outbox");
    }

    @AfterEach
    void clearOutboxAfter() {
        jdbc.execute("TRUNCATE email_outbox");
    }

    private int outboxRows() {
        return count("SELECT count(*) FROM email_outbox");
    }

    @Test
    void checkout_success_queuesOneOrderReceivedEmailWithOrderUrl() {
        long v = stockVariant(1000, 5);
        Res r = place0(order("IN_STOCK", v, 1));

        assertThat(r.status()).isEqualTo(201);
        assertThat(outboxRows()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT dedupe_key, to_email, template, status, payload::text AS payload FROM email_outbox");
        long orderId = jdbc.queryForObject("SELECT id FROM customer_order", Long.class);
        assertThat(row.get("template")).isEqualTo("ORDER_RECEIVED");
        assertThat(row.get("to_email")).isEqualTo("jane.secret@example.com");
        assertThat(row.get("dedupe_key")).isEqualTo("order:" + orderId + ":received");
        assertThat(row.get("status")).isEqualTo("PENDING");
        Map<String, Object> payload = parse((String) row.get("payload"));
        assertThat(payload.keySet()).containsExactlyInAnyOrder("publicCode", "orderUrl");
        assertThat(payload.get("orderUrl")).isEqualTo(r.<String>json("$.orderUrl"));
        assertThat(payload.get("publicCode")).isEqualTo(r.<String>json("$.code"));
    }

    @Test
    void checkout_replayWithSameIdempotencyKey_addsNoOutboxRow() throws Exception {
        long v = stockVariant(1000, 5);
        UUID key = UUID.randomUUID();
        Map<String, Object> body = order("IN_STOCK", v, 1);

        assertThat(place(key, body, nextIp()).status()).isEqualTo(201);
        assertThat(place(key, body, nextIp()).status()).isEqualTo(200);

        assertThat(outboxRows()).isEqualTo(1);
    }

    @Test
    void checkout_outOfStock_leavesNoOutboxRow() {
        long v = stockVariant(1000, 1);

        Res r = place0(order("IN_STOCK", v, 2));

        assertThat(r.status()).isEqualTo(409);
        assertThat(orders()).isZero();
        assertThat(outboxRows()).isZero();
    }

    @Test
    void checkout_validationFailure_leavesNoOutboxRow() {
        long v = stockVariant(1000, 5);
        Map<String, Object> body = order("IN_STOCK", v, 1);
        body.put("acceptedTermsVersion", "not-the-published-version");

        Res r = place0(body);

        assertThat(r.status()).isEqualTo(409);
        assertThat(outboxRows()).isZero();
    }

    @Test
    void outboxRun_afterCheckout_removesOrderUrlFromStoredPayload() {
        long v = stockVariant(1000, 5);
        Res r = place0(order("IN_STOCK", v, 1));
        assertThat(r.status()).isEqualTo(201);

        // created_at is DB now(); align it with the fake clock so the 7-day retention scrub does not fire
        jdbc.update("UPDATE email_outbox SET created_at = ?", java.sql.Timestamp.from(clock.instant()));
        assertThat(outbox.runOnce()).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap("SELECT status, payload::text AS payload FROM email_outbox");
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(parse((String) row.get("payload")).keySet()).containsExactly("publicCode");
    }

    private Res place0(Object body) {
        try {
            return place(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        return om.readValue(json, Map.class);
    }
}
