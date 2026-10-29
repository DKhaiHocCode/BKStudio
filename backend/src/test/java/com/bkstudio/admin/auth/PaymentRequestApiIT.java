package com.bkstudio.admin.auth;

import static com.bkstudio.queue.QueueFixtures.setMonth;
import static com.bkstudio.queue.QueueFixtures.window;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.queue.QueueFixtures;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * T-025 admin payment requests over HTTP against real Postgres: create (amount rules, link allow-list, due dates,
 * outbox, audit), confirm (order transition, slot allocation, idempotency, rollback, concurrency) and cancel.
 * Clock is 2030-01-01T00:00:10Z (production month 2030-01). Lives in admin.auth to reuse the package-private
 * login helpers; must not run in parallel with other ITs (wipes order, queue and outbox tables).
 */
class PaymentRequestApiIT extends AdminProductTestBase {

    private static final String ARTISTREE_URL = "https://artistree.io/pay/42";
    private static final String SECRET_NAME = "Secret Buyer Name";
    private static final String SECRET_EMAIL = "secret.buyer@example.com";
    private static final String SECRET_STREET = "9 Secret Road";

    @BeforeEach
    void setUpPayments() {
        wipePayments();
        jdbc.update("INSERT INTO terms_document (version, content_md) VALUES ('v1', 'x')");
        jdbc.update(
                "INSERT INTO app_setting (key, value) VALUES ('payment_allowed_domains', 'artistree.io,shopee.sg')");
        window(jdbc, 10);
    }

    @AfterEach
    void tearDownPayments() {
        wipePayments();
    }

    private void wipePayments() {
        QueueFixtures.wipe(jdbc); // app_setting, production_month, customer_order (+ children), product
        jdbc.execute("TRUNCATE terms_document CASCADE");
        jdbc.execute("TRUNCATE email_outbox");
    }

    // ---- fixtures ----

    /** Order with one item per entry of {@code quantities}; unit price is arbitrary, subtotal is the given one. */
    private long order(String type, String status, int subtotal, int... quantities) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long product = jdbc.queryForObject(
                "INSERT INTO product (slug, name) VALUES (?, 'Pay test') RETURNING id", Long.class, "pt-" + tag);
        long variant = jdbc.queryForObject(
                "INSERT INTO product_variant (product_id, scale, price_cents, made_to_order_enabled)"
                        + " VALUES (?, '1/7', 1000, true) RETURNING id",
                Long.class,
                product);
        long order = jdbc.queryForObject(
                "INSERT INTO customer_order (public_code, access_token_hash, idempotency_key, fulfillment_type,"
                        + " status, customer_name, customer_email, ship_line1, ship_city, ship_country,"
                        + " subtotal_cents, terms_version, terms_accepted_at)"
                        + " VALUES (?, decode(md5(random()::text) || md5(random()::text), 'hex'), gen_random_uuid(),"
                        + " ?, ?, ?, ?, ?, 'Singapore', 'SG', ?, 'v1', now()) RETURNING id",
                Long.class,
                "BK-" + tag,
                type,
                status,
                SECRET_NAME,
                SECRET_EMAIL,
                SECRET_STREET,
                subtotal);
        for (int qty : quantities.length == 0 ? new int[] {1} : quantities) {
            jdbc.update(
                    "INSERT INTO order_item (order_id, variant_id, product_name, scale, unit_price_cents, quantity)"
                            + " VALUES (?, ?, 'Pay test', '1/7', 1000, ?)",
                    order,
                    variant,
                    qty);
        }
        return order;
    }

    private long mtoDeposit(int subtotal, int... quantities) {
        return order("MADE_TO_ORDER", "AWAITING_DEPOSIT", subtotal, quantities);
    }

    private long inStock(int subtotal) {
        return order("IN_STOCK", "AWAITING_PAYMENT", subtotal);
    }

    private long mtoBalance(int subtotal) {
        return order("MADE_TO_ORDER", "AWAITING_BALANCE", subtotal, 1);
    }

    private ResultActions create(long orderId, String type, int amount, Integer shipping, String channel, String url)
            throws Exception {
        return post(
                "/api/admin/orders/" + orderId + "/payment-requests",
                j(
                        "type",
                        type,
                        "amountCents",
                        amount,
                        "shippingCents",
                        shipping,
                        "channel",
                        channel,
                        "paymentUrl",
                        url));
    }

    private ResultActions create(long orderId, String type, int amount) throws Exception {
        return create(orderId, type, amount, null, "ARTISTREE", ARTISTREE_URL);
    }

    private long createOk(long orderId, String type, int amount) throws Exception {
        return readId(create(orderId, type, amount).andExpect(status().isCreated()), "$.id");
    }

    private ResultActions confirm(long requestId, String ref) throws Exception {
        return post("/api/admin/payment-requests/" + requestId + "/confirm", j("externalRef", ref));
    }

    private ResultActions cancel(long requestId, String reason) throws Exception {
        return post("/api/admin/payment-requests/" + requestId + "/cancel", j("reason", reason));
    }

    private static ResultActions expectField(ResultActions r, int httpStatus, String field) throws Exception {
        return expectProblem(r, httpStatus, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[?(@.field=='" + field + "')]").isNotEmpty());
    }

    // ---- reads ----

    private String orderStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?", String.class, id);
    }

    private int orderVersion(long id) {
        return count("SELECT version FROM customer_order WHERE id = ?", id);
    }

    private int shippingCents(long id) {
        return count("SELECT shipping_cents FROM customer_order WHERE id = ?", id);
    }

    private Map<String, Object> request(long id) {
        return jdbc.queryForMap("SELECT * FROM payment_request WHERE id = ?", id);
    }

    private String requestStatus(long id) {
        return (String) request(id).get("status");
    }

    private int requestRows(long orderId) {
        return count("SELECT count(*) FROM payment_request WHERE order_id = ?", orderId);
    }

    private Instant dueAt(long requestId) {
        return jdbc.queryForObject(
                        "SELECT due_at FROM payment_request WHERE id = ?", java.sql.Timestamp.class, requestId)
                .toInstant();
    }

    private int queueEntries() {
        return count("SELECT count(*) FROM queue_entry");
    }

    private int outboxRows() {
        return count("SELECT count(*) FROM email_outbox");
    }

    private int auditCount(String action, String entityType, long entityId) {
        return count(
                "SELECT count(*) FROM audit_log WHERE action = ? AND entity_type = ? AND entity_id = ?",
                action,
                entityType,
                entityId);
    }

    /** Everything a failed/no-op call must leave untouched, as one comparable value. */
    private record Snapshot(int audit, int outbox, int queue, int version, String status, int requests) {}

    private Snapshot snapshot(long orderId) {
        return new Snapshot(
                auditRows(),
                outboxRows(),
                queueEntries(),
                orderVersion(orderId),
                orderStatus(orderId),
                requestRows(orderId));
    }

    // ================= create =================

    @Test
    void create_deposit_returns201_dueIn48h_withOutboxAndAudit() throws Exception {
        long orderId = mtoDeposit(10001, 1);

        ResultActions r = create(orderId, "DEPOSIT", 5001);

        r.andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amountCents").value(5001))
                .andExpect(jsonPath("$.channel").value("ARTISTREE"))
                .andExpect(jsonPath("$.paymentUrl").value(ARTISTREE_URL))
                .andExpect(jsonPath("$.status").value("PENDING"));
        long id = readId(r, "$.id");
        Instant expectedDue = START.plus(Duration.ofHours(48));
        assertThat(readInstant(r, "$.dueAt")).isEqualTo(expectedDue);
        assertThat(dueAt(id)).isEqualTo(expectedDue);
        assertThat(requestStatus(id)).isEqualTo("PENDING");
        assertThat(orderVersion(orderId)).isEqualTo(1);
        assertThat(orderStatus(orderId)).isEqualTo("AWAITING_DEPOSIT");

        Map<String, Object> mail =
                jdbc.queryForMap("SELECT dedupe_key, to_email, template, payload::text AS payload FROM email_outbox");
        assertThat(mail.get("dedupe_key")).isEqualTo("payment:" + id + ":link");
        assertThat(mail.get("template")).isEqualTo("ORDER_PAYMENT_LINK");
        assertThat(mail.get("to_email")).isEqualTo(SECRET_EMAIL);
        String payload = (String) mail.get("payload");
        Map<String, Object> parsed =
                om.readValue(payload, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(parsed.keySet()).containsExactlyInAnyOrder("publicCode", "amountCents", "dueAt", "paymentUrl");
        assertThat(parsed.get("amountCents")).isEqualTo(5001);
        assertThat(parsed.get("paymentUrl")).isEqualTo(ARTISTREE_URL);
        assertThat(Instant.parse((String) parsed.get("dueAt"))).isEqualTo(expectedDue);
        assertThat(payload).doesNotContain("Secret").doesNotContain("example.com");

        var audits = audits("PAYMENT_REQUEST", id);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).action()).isEqualTo("PAYMENT_REQUEST_CREATED");
        assertThat(audits.get(0).actor()).isEqualTo("ADMIN");
        assertThat(audits.get(0).actorId()).isEqualTo(admin.id());
        assertThat(audits.get(0).after()).contains(ARTISTREE_URL).doesNotContain(SECRET_EMAIL);
    }

    @ParameterizedTest
    @ValueSource(ints = {5000, 5002, 10001, 1})
    void create_deposit_wrongAmount_400_noRowOrMail(int wrongAmount) throws Exception {
        long orderId = mtoDeposit(10001);
        Snapshot before = snapshot(orderId);

        expectField(create(orderId, "DEPOSIT", wrongAmount), 400, "amountCents");

        assertThat(snapshot(orderId)).isEqualTo(before);
    }

    @Test
    void create_deposit_evenSubtotal_isExactHalf() throws Exception {
        long orderId = mtoDeposit(10000);

        create(orderId, "DEPOSIT", 5000).andExpect(status().isCreated());
    }

    @Test
    void create_full_inStock_artistreeShipping_updatesOrderShippingAndVersion() throws Exception {
        long orderId = inStock(7000);

        ResultActions r = create(orderId, "FULL", 8500, 1500, "ARTISTREE", ARTISTREE_URL);

        r.andExpect(status().isCreated()).andExpect(jsonPath("$.amountCents").value(8500));
        assertThat(readInstant(r, "$.dueAt")).isEqualTo(START.plus(Duration.ofHours(48)));
        assertThat(shippingCents(orderId)).isEqualTo(1500);
        assertThat(orderVersion(orderId)).isEqualTo(1);
        assertThat(orderStatus(orderId)).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void create_full_noShipping_amountIsSubtotal_orderVersionBumped() throws Exception {
        long orderId = inStock(7000);

        create(orderId, "FULL", 7000, null, "SHOPEE_PROXY", "https://shopee.sg/order/9")
                .andExpect(status().isCreated());

        assertThat(shippingCents(orderId)).isZero();
        assertThat(orderVersion(orderId)).isEqualTo(1);
    }

    @Test
    void create_full_amountIncludesShippingButShippingOmitted_400() throws Exception {
        long orderId = inStock(7000);

        expectField(create(orderId, "FULL", 8500), 400, "amountCents");
        expectField(create(orderId, "FULL", 7000, 1500, "ARTISTREE", ARTISTREE_URL), 400, "amountCents");

        assertThat(requestRows(orderId)).isZero();
    }

    @Test
    void create_full_shippingOnShopeeProxy_400_andOrderUntouched() throws Exception {
        long orderId = inStock(7000);
        Snapshot before = snapshot(orderId);

        expectField(
                create(orderId, "FULL", 8500, 1500, "SHOPEE_PROXY", "https://shopee.sg/order/9"), 400, "shippingCents");

        assertThat(snapshot(orderId)).isEqualTo(before);
        assertThat(shippingCents(orderId)).isZero();
    }

    @Test
    void create_full_shippingOnOtherChannel_400() throws Exception {
        long orderId = inStock(7000);

        expectField(create(orderId, "FULL", 8500, 1500, "OTHER", ARTISTREE_URL), 400, "shippingCents");
    }

    @Test
    void create_deposit_withShipping_400() throws Exception {
        long orderId = mtoDeposit(10001);

        expectField(create(orderId, "DEPOSIT", 6501, 1500, "ARTISTREE", ARTISTREE_URL), 400, "shippingCents");
        expectField(create(orderId, "DEPOSIT", 5001, 1500, "ARTISTREE", ARTISTREE_URL), 400, "shippingCents");

        assertThat(requestRows(orderId)).isZero();
    }

    @Test
    void create_negativeShipping_400() throws Exception {
        long orderId = inStock(7000);

        expectField(create(orderId, "FULL", 6000, -1000, "ARTISTREE", ARTISTREE_URL), 400, "shippingCents");
    }

    @Test
    void create_balance_dueIn30Days_amountIsRemainderPlusShipping() throws Exception {
        long orderId = mtoBalance(10001); // deposit 5001, remainder 5000

        ResultActions r = create(orderId, "BALANCE", 5700, 700, "ARTISTREE", ARTISTREE_URL);

        r.andExpect(status().isCreated()).andExpect(jsonPath("$.amountCents").value(5700));
        assertThat(readInstant(r, "$.dueAt")).isEqualTo(START.plus(Duration.ofDays(30)));
        assertThat(dueAt(readId(r, "$.id"))).isEqualTo(START.plus(Duration.ofDays(30)));
        assertThat(shippingCents(orderId)).isEqualTo(700);
        assertThat(orderVersion(orderId)).isEqualTo(1);
    }

    @Test
    void create_balance_withoutShipping_amountIsSubtotalMinusDeposit() throws Exception {
        long orderId = mtoBalance(10001);

        expectField(create(orderId, "BALANCE", 5001), 400, "amountCents"); // would be wrong (rounded-up half)
        create(orderId, "BALANCE", 5000).andExpect(status().isCreated());
    }

    @Test
    void create_dueDate_followsTheClock() throws Exception {
        long orderId = inStock(7000);
        clock.advance(Duration.ofMinutes(5)); // stays inside the admin session timeout

        ResultActions r = create(orderId, "FULL", 7000);

        assertThat(readInstant(r, "$.dueAt"))
                .isEqualTo(START.plus(Duration.ofMinutes(5)).plus(Duration.ofHours(48)));
    }

    @Test
    void create_typeNotMatchingOrderStateOrFulfillment_409_noRow() throws Exception {
        long stock = inStock(7000);
        long mto = mtoDeposit(10001);
        long queued = order("MADE_TO_ORDER", "QUEUED", 10001, 1);
        long cancelled = order("IN_STOCK", "CANCELLED", 7000);

        expectProblem(create(stock, "DEPOSIT", 3500), 409, "INVALID_TRANSITION");
        expectProblem(create(stock, "BALANCE", 3500), 409, "INVALID_TRANSITION");
        expectProblem(create(mto, "FULL", 10001), 409, "INVALID_TRANSITION");
        expectProblem(create(mto, "BALANCE", 5000), 409, "INVALID_TRANSITION");
        expectProblem(create(queued, "DEPOSIT", 5001), 409, "INVALID_TRANSITION");
        expectProblem(create(cancelled, "FULL", 7000), 409, "INVALID_TRANSITION");

        assertThat(count("SELECT count(*) FROM payment_request")).isZero();
        assertThat(outboxRows()).isZero();
    }

    @Test
    void create_unknownOrder_404() throws Exception {
        expectProblem(create(UNKNOWN_ID, "DEPOSIT", 5001), 404, "NOT_FOUND");
    }

    @Test
    void create_secondPendingOfSameType_409_noExtraRowOrMail() throws Exception {
        long orderId = mtoDeposit(10001);
        createOk(orderId, "DEPOSIT", 5001);
        Snapshot before = snapshot(orderId);

        expectProblem(create(orderId, "DEPOSIT", 5001), 409, "CONFLICT");

        assertThat(snapshot(orderId)).isEqualTo(before);
        assertThat(outboxRows()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://evil.com/pay",
                "http://artistree.io/pay",
                "https://artistree.io@evil.com/pay",
                "https://evil.com@artistree.io/pay",
                "https://artistree.io:8443/pay",
                "https://artistree.io.evil.com/pay",
                "javascript:alert(1)",
                "artistree.io/pay"
            })
    void create_badPaymentUrl_400_noRowOrMail(String url) throws Exception {
        long orderId = mtoDeposit(10001);
        Snapshot before = snapshot(orderId);

        expectField(create(orderId, "DEPOSIT", 5001, null, "ARTISTREE", url), 400, "paymentUrl");

        assertThat(snapshot(orderId)).isEqualTo(before);
    }

    @Test
    void create_emptyAllowList_rejectsEveryLink() throws Exception {
        long orderId = mtoDeposit(10001);
        jdbc.update("UPDATE app_setting SET value = '' WHERE key = 'payment_allowed_domains'");

        expectField(create(orderId, "DEPOSIT", 5001), 400, "paymentUrl");
    }

    @Test
    void create_missingFields_400() throws Exception {
        long orderId = mtoDeposit(10001);
        String path = "/api/admin/orders/" + orderId + "/payment-requests";

        expectProblem(post(path, "{}"), 400, "VALIDATION_FAILED");
        expectProblem(
                post(path, j("type", "REFUND", "amountCents", 1, "channel", "ARTISTREE", "paymentUrl", ARTISTREE_URL)),
                400,
                "VALIDATION_FAILED");
        assertThat(requestRows(orderId)).isZero();
    }

    @Test
    void endpoints_unauthenticated_401() throws Exception {
        long orderId = mtoDeposit(10001);
        for (String path : List.of(
                "/api/admin/orders/" + orderId + "/payment-requests",
                "/api/admin/payment-requests/1/confirm",
                "/api/admin/payment-requests/1/cancel")) {
            mvc.perform(withCsrf(org.springframework.http.HttpMethod.POST, path, "{}", null, nextIp()))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(requestRows(orderId)).isZero();
    }

    @Test
    void endpoints_missingCsrf_403() throws Exception {
        long orderId = mtoDeposit(10001);
        String body = j("type", "DEPOSIT", "amountCents", 5001, "channel", "ARTISTREE", "paymentUrl", ARTISTREE_URL);
        for (String path : List.of(
                "/api/admin/orders/" + orderId + "/payment-requests",
                "/api/admin/payment-requests/1/confirm",
                "/api/admin/payment-requests/1/cancel")) {
            mvc.perform(MockMvcRequestBuilders.post(path)
                            .session(session)
                            .with(r -> {
                                r.setRemoteAddr(nextIp());
                                return r;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }
        assertThat(requestRows(orderId)).isZero();
    }

    // ================= confirm =================

    @Test
    void confirmDeposit_marksPaid_queuesOrder_allocatesSlotPerUnit_writesAuditAndMail() throws Exception {
        long orderId = mtoDeposit(10001, 2, 1, 3);
        long id = createOk(orderId, "DEPOSIT", 5001);
        clock.advance(Duration.ofHours(1));

        confirm(id, "INV-1001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.orderStatus").value("QUEUED"));

        Map<String, Object> row = request(id);
        assertThat(row.get("status")).isEqualTo("PAID");
        assertThat(row.get("external_ref")).isEqualTo("INV-1001");
        assertThat(((Timestamp) row.get("confirmed_at")).toInstant()).isEqualTo(START.plus(Duration.ofHours(1)));
        assertThat(orderStatus(orderId)).isEqualTo("QUEUED");
        assertThat(orderVersion(orderId)).isEqualTo(2);

        assertThat(queueEntries()).isEqualTo(6);
        assertThat(jdbc.queryForList(
                        "SELECT count(*) FROM queue_entry q JOIN order_item i ON i.id = q.order_item_id"
                                + " WHERE i.order_id = ? GROUP BY i.id ORDER BY i.id",
                        Integer.class,
                        orderId))
                .containsExactly(2, 1, 3);
        assertThat(count("SELECT sum(allocated) FROM production_month")).isEqualTo(6);

        assertThat(auditCount("PAYMENT_CONFIRMED", "PAYMENT_REQUEST", id)).isEqualTo(1);
        assertThat(auditCount("ORDER_TRANSITION", "ORDER", orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT template FROM email_outbox WHERE dedupe_key = ?",
                        String.class,
                        "order:" + orderId + ":CONFIRM_DEPOSIT:1"))
                .isEqualTo("ORDER_DEPOSIT_CONFIRMED");
        assertThat(outboxRows()).isEqualTo(2); // payment link + deposit confirmed
    }

    @Test
    void confirmFull_inStock_ordersBecomesPaidInFull_noSlots() throws Exception {
        long orderId = inStock(7000);
        long id = createOk(orderId, "FULL", 7000);

        confirm(id, "INV-F1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FULL"))
                .andExpect(jsonPath("$.orderStatus").value("PAID_IN_FULL"));

        assertThat(orderStatus(orderId)).isEqualTo("PAID_IN_FULL");
        assertThat(requestStatus(id)).isEqualTo("PAID");
        assertThat(queueEntries()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT template FROM email_outbox WHERE dedupe_key = ?",
                        String.class,
                        "order:" + orderId + ":CONFIRM_PAYMENT:1"))
                .isEqualTo("ORDER_PAID");
    }

    @Test
    void confirmBalance_ordersBecomesPaidInFull_noNewSlots() throws Exception {
        long orderId = mtoBalance(10001);
        long id = createOk(orderId, "BALANCE", 5000);

        confirm(id, "INV-B1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("PAID_IN_FULL"));

        assertThat(orderStatus(orderId)).isEqualTo("PAID_IN_FULL");
        assertThat(queueEntries()).isZero();
        assertThat(auditCount("PAYMENT_CONFIRMED", "PAYMENT_REQUEST", id)).isEqualTo(1);
    }

    @Test
    void confirm_twiceWithSameRef_isIdempotent_noNewRowsOrVersion() throws Exception {
        long orderId = mtoDeposit(10001, 2, 1);
        long id = createOk(orderId, "DEPOSIT", 5001);
        var first = confirm(id, "INV-2").andExpect(status().isOk());
        Snapshot before = snapshot(orderId);
        Object confirmedAt = request(id).get("confirmed_at");
        clock.advance(Duration.ofMinutes(5));

        var replay = confirm(id, "INV-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.orderStatus").value("QUEUED"));
        assertThat(readInstant(replay, "$.confirmedAt")).isEqualTo(readInstant(first, "$.confirmedAt"));

        assertThat(snapshot(orderId)).isEqualTo(before);
        assertThat(request(id).get("confirmed_at")).isEqualTo(confirmedAt);
        assertThat(auditCount("PAYMENT_CONFIRMED", "PAYMENT_REQUEST", id)).isEqualTo(1);
        assertThat(queueEntries()).isEqualTo(3);
    }

    @Test
    void confirm_sameRequestDifferentRef_409_nothingChanges() throws Exception {
        long orderId = inStock(7000);
        long id = createOk(orderId, "FULL", 7000);
        confirm(id, "INV-3").andExpect(status().isOk());
        Snapshot before = snapshot(orderId);

        expectProblem(confirm(id, "INV-OTHER"), 409, "CONFLICT");

        assertThat(snapshot(orderId)).isEqualTo(before);
        assertThat(request(id).get("external_ref")).isEqualTo("INV-3");
    }

    @Test
    void confirm_refAlreadyUsedOnAnotherRequestSameChannel_409_andRolledBack() throws Exception {
        long first = createOk(inStock(7000), "FULL", 7000);
        long secondOrder = inStock(8000);
        long second = createOk(secondOrder, "FULL", 8000);
        confirm(first, "INV-DUP").andExpect(status().isOk());
        Snapshot before = snapshot(secondOrder);

        expectProblem(confirm(second, "INV-DUP"), 409, "CONFLICT");

        assertThat(snapshot(secondOrder)).isEqualTo(before);
        assertThat(requestStatus(second)).isEqualTo("PENDING");
        assertThat(request(second).get("external_ref")).isNull();
        assertThat(orderStatus(secondOrder)).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void confirm_sameRefOnDifferentChannel_isAllowed() throws Exception {
        long first = createOk(inStock(7000), "FULL", 7000);
        long second = readId(
                create(inStock(8000), "FULL", 8000, null, "SHOPEE_PROXY", "https://shopee.sg/o/1")
                        .andExpect(status().isCreated()),
                "$.id");
        confirm(first, "INV-SHARED").andExpect(status().isOk());

        confirm(second, "INV-SHARED").andExpect(status().isOk());
    }

    @Test
    void confirm_cancelledRequest_409() throws Exception {
        long orderId = inStock(7000);
        long id = createOk(orderId, "FULL", 7000);
        cancel(id, "customer asked").andExpect(status().isOk());
        Snapshot before = snapshot(orderId);

        expectProblem(confirm(id, "INV-4"), 409, "INVALID_TRANSITION");

        assertThat(snapshot(orderId)).isEqualTo(before);
        assertThat(requestStatus(id)).isEqualTo("CANCELLED");
    }

    @Test
    void confirm_expiredRequest_409() throws Exception {
        long orderId = inStock(7000);
        long id = createOk(orderId, "FULL", 7000);
        jdbc.update("UPDATE payment_request SET status = 'EXPIRED' WHERE id = ?", id);
        Snapshot before = snapshot(orderId);

        expectProblem(confirm(id, "INV-5"), 409, "INVALID_TRANSITION");

        assertThat(snapshot(orderId)).isEqualTo(before);
        assertThat(requestStatus(id)).isEqualTo("EXPIRED");
    }

    @Test
    void confirm_whenOrderAlreadyCancelled_409_andRequestStaysPending() throws Exception {
        long orderId = mtoDeposit(10001, 1);
        long id = createOk(orderId, "DEPOSIT", 5001);
        jdbc.update("UPDATE customer_order SET status = 'CANCELLED' WHERE id = ?", orderId);
        Snapshot before = snapshot(orderId);

        expectProblem(confirm(id, "INV-6"), 409, "INVALID_TRANSITION");

        assertThat(snapshot(orderId)).isEqualTo(before);
        assertThat(requestStatus(id)).isEqualTo("PENDING");
        assertThat(request(id).get("external_ref")).isNull();
    }

    @Test
    void confirmDeposit_queueFull_409_andEverythingRolledBack() throws Exception {
        // Only 2 free slots in the whole window but the order needs 2 + 2: the first item is allocated, the second
        // fails.
        window(jdbc, 0);
        setMonth(jdbc, 0, 2, 0);
        long orderId = mtoDeposit(10001, 2, 2);
        long id = createOk(orderId, "DEPOSIT", 5001);
        Snapshot before = snapshot(orderId);
        int auditBefore = auditCount("PAYMENT_CONFIRMED", "PAYMENT_REQUEST", id);

        expectProblem(confirm(id, "INV-Q"), 409, "QUEUE_FULL");

        assertThat(snapshot(orderId)).isEqualTo(before);
        assertThat(auditCount("PAYMENT_CONFIRMED", "PAYMENT_REQUEST", id)).isEqualTo(auditBefore);
        assertThat(auditCount("ORDER_TRANSITION", "ORDER", orderId)).isZero();
        assertThat(requestStatus(id)).isEqualTo("PENDING");
        assertThat(request(id).get("external_ref")).isNull();
        assertThat(request(id).get("confirmed_at")).isNull();
        assertThat(orderStatus(orderId)).isEqualTo("AWAITING_DEPOSIT");
        assertThat(orderVersion(orderId)).isEqualTo(1);
        assertThat(queueEntries()).isZero();
        assertThat(count("SELECT coalesce(sum(allocated), 0) FROM production_month"))
                .isZero();
        assertThat(count("SELECT count(*) FROM email_outbox WHERE dedupe_key LIKE 'order:%'"))
                .isZero();

        // Once capacity exists the very same request can be confirmed.
        setMonth(jdbc, 1, 5, 0);
        confirm(id, "INV-Q").andExpect(status().isOk());
        assertThat(queueEntries()).isEqualTo(4);
    }

    @Test
    void confirmDeposit_allMonthsFull_409_noSideEffects() throws Exception {
        for (int i = 0; i < 24; i++) {
            setMonth(jdbc, i, 1, 1);
        }
        long orderId = mtoDeposit(10001, 1);
        long id = createOk(orderId, "DEPOSIT", 5001);
        Snapshot before = snapshot(orderId);

        expectProblem(confirm(id, "INV-QF"), 409, "QUEUE_FULL");

        assertThat(snapshot(orderId)).isEqualTo(before);
        assertThat(requestStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void confirm_concurrentSameRef_singleEffect_bothSucceed() throws Exception {
        long orderId = mtoDeposit(10001, 2, 1);
        long id = createOk(orderId, "DEPOSIT", 5001);
        int auditBefore = auditRows();
        int outboxBefore = outboxRows();

        List<Integer> statuses = concurrently(
                4, () -> confirm(id, "INV-C").andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        assertThat(queueEntries()).isEqualTo(3);
        assertThat(count("SELECT sum(allocated) FROM production_month")).isEqualTo(3);
        assertThat(auditCount("PAYMENT_CONFIRMED", "PAYMENT_REQUEST", id)).isEqualTo(1);
        assertThat(auditCount("ORDER_TRANSITION", "ORDER", orderId)).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(auditBefore + 2);
        assertThat(outboxRows()).isEqualTo(outboxBefore + 1);
        assertThat(orderVersion(orderId)).isEqualTo(2);
        assertThat(orderStatus(orderId)).isEqualTo("QUEUED");
        assertThat(requestStatus(id)).isEqualTo("PAID");
    }

    @Test
    void confirm_concurrentDifferentRefs_exactlyOneWins_otherGets409() throws Exception {
        long orderId = mtoDeposit(10001, 1, 1);
        long id = createOk(orderId, "DEPOSIT", 5001);
        List<String> refs = List.of("INV-X1", "INV-X2");
        List<Callable<Integer>> calls = new ArrayList<>();
        for (String ref : refs) {
            calls.add(() -> confirm(id, ref).andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = concurrently(calls);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(queueEntries()).isEqualTo(2);
        assertThat(auditCount("PAYMENT_CONFIRMED", "PAYMENT_REQUEST", id)).isEqualTo(1);
        assertThat(orderVersion(orderId)).isEqualTo(2);
        assertThat(refs).contains((String) request(id).get("external_ref"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ab\u0007c", "line\nbreak"})
    void confirm_blankOrControlCharRef_400_noEffect(String ref) throws Exception {
        long orderId = inStock(7000);
        long id = createOk(orderId, "FULL", 7000);
        Snapshot before = snapshot(orderId);

        expectField(confirm(id, ref), 400, "externalRef");

        assertThat(snapshot(orderId)).isEqualTo(before);
        assertThat(requestStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void confirm_oversizeOrMissingRef_400() throws Exception {
        long orderId = inStock(7000);
        long id = createOk(orderId, "FULL", 7000);

        expectField(confirm(id, "x".repeat(101)), 400, "externalRef");
        expectField(confirm(id, "x".repeat(201)), 400, "externalRef");
        expectField(post("/api/admin/payment-requests/" + id + "/confirm", "{}"), 400, "externalRef");

        assertThat(requestStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void confirm_refOfExactlyMaxLength_accepted_andTrimmed() throws Exception {
        long orderId = inStock(7000);
        long id = createOk(orderId, "FULL", 7000);

        confirm(id, "  " + "x".repeat(100) + "  ").andExpect(status().isOk());

        assertThat(request(id).get("external_ref")).isEqualTo("x".repeat(100));
    }

    @Test
    void confirm_unknownRequest_404() throws Exception {
        expectProblem(confirm(UNKNOWN_ID, "INV"), 404, "NOT_FOUND");
    }

    // ================= cancel =================

    @Test
    void cancel_pending_becomesCancelled_orderStatusUnchanged_audited() throws Exception {
        long orderId = mtoDeposit(10001);
        long id = createOk(orderId, "DEPOSIT", 5001);

        cancel(id, "wrong amount")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(requestStatus(id)).isEqualTo("CANCELLED");
        assertThat(orderStatus(orderId)).isEqualTo("AWAITING_DEPOSIT");
        assertThat(orderVersion(orderId)).isEqualTo(1);
        assertThat(queueEntries()).isZero();
        var cancels = audits("PAYMENT_REQUEST", id).stream()
                .filter(a -> a.action().equals("PAYMENT_REQUEST_CANCELLED"))
                .toList();
        assertThat(cancels).hasSize(1);
        assertThat(cancels.get(0).after()).contains("wrong amount");
    }

    @Test
    void cancel_twice_isIdempotent_noExtraAudit() throws Exception {
        long id = createOk(mtoDeposit(10001), "DEPOSIT", 5001);
        cancel(id, "first").andExpect(status().isOk());
        int auditBefore = auditRows();

        cancel(id, "second")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(auditRows()).isEqualTo(auditBefore);
        assertThat(auditCount("PAYMENT_REQUEST_CANCELLED", "PAYMENT_REQUEST", id))
                .isEqualTo(1);
    }

    @Test
    void cancel_paidRequest_409_staysPaid() throws Exception {
        long orderId = inStock(7000);
        long id = createOk(orderId, "FULL", 7000);
        confirm(id, "INV-P").andExpect(status().isOk());
        Snapshot before = snapshot(orderId);

        expectProblem(cancel(id, "too late"), 409, "INVALID_TRANSITION");

        assertThat(snapshot(orderId)).isEqualTo(before);
        assertThat(requestStatus(id)).isEqualTo("PAID");
    }

    @Test
    void cancel_thenCreateSameTypeAgain_succeeds_withNewMail() throws Exception {
        long orderId = mtoDeposit(10001);
        long first = createOk(orderId, "DEPOSIT", 5001);
        cancel(first, "new link needed").andExpect(status().isOk());

        long second = createOk(orderId, "DEPOSIT", 5001);

        assertThat(second).isNotEqualTo(first);
        assertThat(requestStatus(second)).isEqualTo("PENDING");
        assertThat(count(
                        "SELECT count(*) FROM email_outbox WHERE dedupe_key IN (?, ?)",
                        "payment:" + first + ":link",
                        "payment:" + second + ":link"))
                .isEqualTo(2);
    }

    @Test
    void confirm_afterCancel_409_andCancelledRequestNeverQueuesOrder() throws Exception {
        long orderId = mtoDeposit(10001, 1);
        long id = createOk(orderId, "DEPOSIT", 5001);
        cancel(id, "mistake").andExpect(status().isOk());

        expectProblem(confirm(id, "INV-AC"), 409, "INVALID_TRANSITION");

        assertThat(orderStatus(orderId)).isEqualTo("AWAITING_DEPOSIT");
        assertThat(queueEntries()).isZero();
    }

    @Test
    void cancel_blankMissingOrOversizeReason_400() throws Exception {
        long id = createOk(mtoDeposit(10001), "DEPOSIT", 5001);

        expectField(cancel(id, ""), 400, "reason");
        expectField(cancel(id, "   "), 400, "reason");
        expectField(cancel(id, "r\u0007x"), 400, "reason");
        expectField(cancel(id, "x".repeat(501)), 400, "reason");
        expectField(post("/api/admin/payment-requests/" + id + "/cancel", "{}"), 400, "reason");

        assertThat(requestStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void cancel_unknownRequest_404() throws Exception {
        expectProblem(cancel(UNKNOWN_ID, "x"), 404, "NOT_FOUND");
    }

    // ---- concurrency helper ----

    private <T> List<T> concurrently(int threads, Callable<T> call) throws Exception {
        List<Callable<T>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(call);
        }
        return concurrently(calls);
    }

    /** Runs the calls on separate threads released together by a latch; results in submission order. */
    private <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
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
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
