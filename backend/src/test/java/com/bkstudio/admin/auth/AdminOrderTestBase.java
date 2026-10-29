package com.bkstudio.admin.auth;

import static com.bkstudio.queue.QueueFixtures.window;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.queue.QueueFixtures;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Shared fixtures for the T-026 admin order ITs: a clean order/queue/outbox state per test, one default variant
 * (stock 5, quota 3) and helpers to build orders, payment requests and queued made-to-order orders. Must not run in
 * parallel with other ITs (wipes order, queue and outbox tables).
 */
abstract class AdminOrderTestBase extends AdminProductTestBase {

    static final String SECRET_EMAIL = "secret.buyer@example.com";
    static final String SECRET_STREET = "9 Secret Road";

    /** Default variant: stock 5, made-to-order quota 3. */
    long variant;

    record Res(int status, String body) {
        String code() {
            return JsonPath.read(body, "$.code");
        }
    }

    @BeforeEach
    void setUpOrders() {
        wipe();
        jdbc.update("INSERT INTO terms_document (version, content_md) VALUES ('v1', 'x')");
        window(jdbc, 10);
        variant = newVariant(5, 3);
    }

    @AfterEach
    void tearDownOrders() {
        wipe();
    }

    private void wipe() {
        QueueFixtures.wipe(jdbc);
        jdbc.execute("TRUNCATE terms_document CASCADE");
        jdbc.execute("TRUNCATE email_outbox");
    }

    // ---- fixtures ----

    long newVariant(Integer stock, Integer quota) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long product = jdbc.queryForObject(
                "INSERT INTO product (slug, name) VALUES (?, 'Order test') RETURNING id", Long.class, "ot-" + tag);
        return jdbc.queryForObject(
                "INSERT INTO product_variant (product_id, scale, price_cents, stock_qty, made_to_order_enabled,"
                        + " made_to_order_quota) VALUES (?, '1/7', 1000, ?, true, ?) RETURNING id",
                Long.class,
                product,
                stock,
                quota);
    }

    long order(String type, String status, int qty) {
        return orderOn(type, status, variant, qty);
    }

    long orderOn(String type, String status, long variantId, int qty) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long id = jdbc.queryForObject(
                "INSERT INTO customer_order (public_code, access_token_hash, idempotency_key, fulfillment_type,"
                        + " status, customer_name, customer_email, ship_line1, ship_city, ship_country,"
                        + " subtotal_cents, terms_version, terms_accepted_at)"
                        + " VALUES (?, decode(md5(random()::text) || md5(random()::text), 'hex'), gen_random_uuid(),"
                        + " ?, ?, 'Buyer', ?, ?, 'Singapore', 'SG', 2000, 'v1', now()) RETURNING id",
                Long.class,
                "BK-" + tag.toUpperCase(),
                type,
                status,
                SECRET_EMAIL,
                SECRET_STREET);
        if (status.equals("SHIPPED") || status.equals("COMPLETED")) {
            jdbc.update("UPDATE customer_order SET tracking_number = 'T0', shipped_at = now() WHERE id = ?", id);
        }
        addItem(id, variantId, qty);
        return id;
    }

    long addItem(long orderId, long variantId, int qty) {
        return jdbc.queryForObject(
                "INSERT INTO order_item (order_id, variant_id, product_name, scale, unit_price_cents, quantity)"
                        + " VALUES (?, ?, 'Order test', '1/7', 1000, ?) RETURNING id",
                Long.class,
                orderId,
                variantId,
                qty);
    }

    long pendingRequest(long orderId, String type) {
        return jdbc.queryForObject(
                "INSERT INTO payment_request (order_id, type, amount_cents, channel, payment_url, due_at)"
                        + " VALUES (?, ?, 1000, 'ARTISTREE', 'https://artistree.io/p', now() + interval '2 days')"
                        + " RETURNING id",
                Long.class,
                orderId,
                type);
    }

    ResultActions confirm(long requestId) throws Exception {
        return post(
                "/api/admin/payment-requests/" + requestId + "/confirm",
                j("externalRef", "INV-" + UUID.randomUUID().toString().substring(0, 8)));
    }

    /** MADE_TO_ORDER order (one item per quantity, same variant) with a confirmed DEPOSIT: QUEUED, version 1. */
    long queuedMto(long variantId, int... quantities) throws Exception {
        long id = orderOn("MADE_TO_ORDER", "AWAITING_DEPOSIT", variantId, quantities[0]);
        for (int i = 1; i < quantities.length; i++) {
            addItem(id, variantId, quantities[i]);
        }
        confirm(pendingRequest(id, "DEPOSIT")).andExpect(status().isOk());
        assertThat(orderStatus(id)).isEqualTo("QUEUED");
        return id;
    }

    // ---- requests ----

    ResultActions act(long id, String action, Object... kv) throws Exception {
        return post("/api/admin/orders/" + id + "/" + action, j(kv));
    }

    static Res res(ResultActions r) {
        var response = r.andReturn().getResponse();
        try {
            return new Res(response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static ResultActions expectField(ResultActions r, String field) throws Exception {
        return expectProblem(r, 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[?(@.field=='" + field + "')]").isNotEmpty());
    }

    // ---- reads ----

    int version(long id) {
        return count("SELECT version FROM customer_order WHERE id = ?", id);
    }

    String orderStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?", String.class, id);
    }

    String requestStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM payment_request WHERE id = ?", String.class, id);
    }

    int stock() {
        return stock(variant);
    }

    int stock(long variantId) {
        return count("SELECT stock_qty FROM product_variant WHERE id = ?", variantId);
    }

    Integer quota(long variantId) {
        return jdbc.queryForObject(
                "SELECT made_to_order_quota FROM product_variant WHERE id = ?", Integer.class, variantId);
    }

    int variantVersion(long variantId) {
        return count("SELECT version FROM product_variant WHERE id = ?", variantId);
    }

    int allocatedTotal() {
        return count("SELECT coalesce(sum(allocated), 0) FROM production_month");
    }

    int openEntries() {
        return count("SELECT count(*) FROM queue_entry WHERE status IN ('QUEUED','IN_PRODUCTION')");
    }

    int openEntries(long orderId) {
        return count(
                "SELECT count(*) FROM queue_entry q JOIN order_item i ON i.id = q.order_item_id"
                        + " WHERE i.order_id = ? AND q.status IN ('QUEUED','IN_PRODUCTION')",
                orderId);
    }

    int outbox(String template, long orderId) {
        return count(
                "SELECT count(*) FROM email_outbox WHERE template = ? AND dedupe_key LIKE ?",
                template,
                "order:" + orderId + ":%");
    }

    int auditCount(String action, long entityId) {
        return count("SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?", action, entityId);
    }

    String auditAfter(String action, long entityId) {
        return jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE action = ? AND entity_id = ?", String.class, action, entityId);
    }

    // ---- concurrency ----

    /** Runs the calls on separate threads released together by a latch; results in submission order. */
    <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> c : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return c.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS)); // a deadlock shows up as a timeout here
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
