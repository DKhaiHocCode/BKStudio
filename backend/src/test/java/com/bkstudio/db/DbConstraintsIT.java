package com.bkstudio.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bkstudio.support.AbstractPostgresTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The 17 scenarios of docs/DATABASE.md section 5, against real PostgreSQL 16 (V1__init.sql). */
class DbConstraintsIT extends AbstractPostgresTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final Instant T0 = Instant.parse("2027-01-01T00:00:00Z");
    private static final LocalDate NOV = LocalDate.of(2027, 11, 1);
    private static final LocalDate DEC = LocalDate.of(2027, 12, 1);
    private static final int RACE_ROUNDS = 20;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE queue_entry, order_item, payment_request, customer_order, production_month, "
                + "product_variant, product_image, product, terms_document, admin_user RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO terms_document(version, content_md) VALUES ('v1', 'terms')");
    }

    // ---------------------------------------------------------------- scenarios

    @Test
    @DisplayName("#1 lastUnit_concurrent_onlyOneBuyerSucceeds")
    void lastUnit_concurrent_onlyOneBuyerSucceeds() throws Exception {
        long variant = variant(productId("ACTIVE"), 1, null);
        for (int round = 0; round < RACE_ROUNDS; round++) {
            jdbc.update("UPDATE product_variant SET stock_qty = 1 WHERE id = ?", variant);

            List<Integer> results = race(
                    2,
                    () -> inTx(() -> jdbc.update(
                            "UPDATE product_variant SET stock_qty = stock_qty - 1 WHERE id = ? AND stock_qty >= 1",
                            variant)));

            assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
            assertThat(stock(variant)).isZero();
        }
    }

    @Test
    @DisplayName("#2 stockDecrementBelowZero_isBlockedByCheck")
    void stockDecrementBelowZero_isBlockedByCheck() {
        long variant = variant(productId("ACTIVE"), 0, null);

        assertViolation(
                CHECK_VIOLATION,
                "chk_product_variant_stock_nonneg",
                () -> jdbc.update("UPDATE product_variant SET stock_qty = stock_qty - 1 WHERE id = ?", variant));
        assertThat(stock(variant)).isZero();
    }

    @Test
    @DisplayName("#3 concurrentDepositConfirmations_getDifferentMonths_noOverbook")
    void concurrentDepositConfirmations_getDifferentMonths_noOverbook() throws Exception {
        long variant = variant(productId("ACTIVE"), 0, null);
        for (int round = 0; round < 5; round++) {
            jdbc.execute("TRUNCATE queue_entry, order_item, production_month, customer_order CASCADE");
            month(NOV, 1);
            month(DEC, 2);
            long itemA = orderItem(order("MADE_TO_ORDER", "QUEUED", UUID.randomUUID()), variant);
            long itemB = orderItem(order("MADE_TO_ORDER", "QUEUED", UUID.randomUUID()), variant);
            AtomicInteger next = new AtomicInteger();
            long[] items = {itemA, itemB};

            List<LocalDate> months = race(2, () -> allocateSlot(items[next.getAndIncrement()]));

            assertThat(Set.copyOf(months)).containsExactlyInAnyOrder(NOV, DEC);
            assertThat(allocated(NOV)).isEqualTo(1);
            assertThat(allocated(DEC)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM queue_entry", Integer.class))
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("#4 allocatedAboveCapacity_isBlockedByCheck")
    void allocatedAboveCapacity_isBlockedByCheck() {
        month(NOV, 2);

        assertViolation(
                CHECK_VIOLATION,
                "chk_production_month_no_overbook",
                () -> jdbc.update("UPDATE production_month SET allocated = 3 WHERE month = ?", Date.valueOf(NOV)));
        assertThat(allocated(NOV)).isZero();
    }

    @Test
    @DisplayName("#5 checkoutRetry_sameIdempotencyKey_createsNoSecondOrder")
    void checkoutRetry_sameIdempotencyKey_createsNoSecondOrder() {
        UUID key = UUID.randomUUID();
        order("IN_STOCK", "AWAITING_PAYMENT", key);

        // the retry as the app will write it: no-op, no row returned
        List<Long> retried = jdbc.queryForList(
                orderInsertSql("ON CONFLICT (idempotency_key) DO NOTHING"),
                Long.class,
                orderArgs("IN_STOCK", "AWAITING_PAYMENT", key));
        assertThat(retried).isEmpty();
        // the retry without ON CONFLICT: blocked by the unique constraint
        assertViolation(
                UNIQUE_VIOLATION,
                "uq_customer_order_idempotency_key",
                () -> jdbc.queryForList(
                        orderInsertSql(""), Long.class, orderArgs("IN_STOCK", "AWAITING_PAYMENT", key)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#6 madeToOrderOrder_awaitingPayment_isBlocked")
    void madeToOrderOrder_awaitingPayment_isBlocked() {
        assertViolation(
                CHECK_VIOLATION,
                "chk_order_mto_status",
                () -> order("MADE_TO_ORDER", "AWAITING_PAYMENT", UUID.randomUUID()));
    }

    @Test
    @DisplayName("#7 shippedWithoutTrackingNumber_isBlocked")
    void shippedWithoutTrackingNumber_isBlocked() {
        long id = order("IN_STOCK", "PAID_IN_FULL", UUID.randomUUID());

        assertViolation(
                CHECK_VIOLATION,
                "chk_order_shipped_needs_tracking",
                () -> jdbc.update("UPDATE customer_order SET status = 'SHIPPED' WHERE id = ?", id));
        assertViolation(
                CHECK_VIOLATION,
                "chk_order_shipped_needs_tracking",
                () -> order("IN_STOCK", "SHIPPED", UUID.randomUUID()));
    }

    @Test
    @DisplayName("#8 secondPendingDepositForSameOrder_isBlocked")
    void secondPendingDepositForSameOrder_isBlocked() {
        long orderId = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", UUID.randomUUID());
        payment(orderId, "DEPOSIT", "PENDING", "ARTISTREE", null);

        assertViolation(
                UNIQUE_VIOLATION,
                "uq_payment_request_open_per_type",
                () -> payment(orderId, "DEPOSIT", "PENDING", "ARTISTREE", null));
        // a different type is still allowed
        payment(orderId, "BALANCE", "PENDING", "ARTISTREE", null);
    }

    @Test
    @DisplayName("#9 confirmPaymentTwice_secondUpdateAffectsZeroRows")
    void confirmPaymentTwice_secondUpdateAffectsZeroRows() {
        long orderId = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", UUID.randomUUID());
        long paymentId = payment(orderId, "DEPOSIT", "PENDING", "ARTISTREE", null);
        String confirm = "UPDATE payment_request SET status = 'PAID', external_ref = 'INV-1', confirmed_at = now() "
                + "WHERE id = ? AND status = 'PENDING'";

        assertThat(jdbc.update(confirm, paymentId)).isEqualTo(1);
        assertThat(jdbc.update(confirm, paymentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM payment_request WHERE id = ?", String.class, paymentId))
                .isEqualTo("PAID");
    }

    @Test
    @DisplayName("#10 reusedExternalInvoiceOnAnotherOrder_isBlocked")
    void reusedExternalInvoiceOnAnotherOrder_isBlocked() {
        long order1 = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", UUID.randomUUID());
        long order2 = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", UUID.randomUUID());
        payment(order1, "DEPOSIT", "PAID", "ARTISTREE", "INV-42");

        assertViolation(
                UNIQUE_VIOLATION,
                "uq_payment_request_channel_external_ref",
                () -> payment(order2, "DEPOSIT", "PAID", "ARTISTREE", "INV-42"));
    }

    @Test
    @DisplayName("#11 paidWithoutExternalRef_isBlocked")
    void paidWithoutExternalRef_isBlocked() {
        long orderId = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", UUID.randomUUID());

        assertViolation(
                CHECK_VIOLATION,
                "chk_payment_request_paid_needs_ref",
                () -> payment(orderId, "DEPOSIT", "PAID", "ARTISTREE", null));
    }

    @Test
    @DisplayName("#12 javascriptPaymentUrl_isBlocked")
    void javascriptPaymentUrl_isBlocked() {
        long orderId = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", UUID.randomUUID());

        assertViolation(
                CHECK_VIOLATION,
                "chk_payment_request_url_https",
                () -> jdbc.update(
                        "INSERT INTO payment_request(order_id, type, amount_cents, channel, payment_url, due_at) "
                                + "VALUES (?, 'DEPOSIT', 5000, 'ARTISTREE', 'javascript:alert(1)', ?)",
                        orderId,
                        ts(T0)));
    }

    @Test
    @DisplayName("#13 inStockOrder_queuedStatus_isBlocked")
    void inStockOrder_queuedStatus_isBlocked() {
        assertViolation(
                CHECK_VIOLATION, "chk_order_in_stock_status", () -> order("IN_STOCK", "QUEUED", UUID.randomUUID()));
    }

    @Test
    @DisplayName("#14 validFlow_paidInFull_thenShippedWithTracking_works")
    void validFlow_paidInFull_thenShippedWithTracking_works() {
        long orderId = order("IN_STOCK", "AWAITING_PAYMENT", UUID.randomUUID());
        payment(orderId, "FULL", "PENDING", "ARTISTREE", null);

        jdbc.update(
                "UPDATE payment_request SET status = 'PAID', external_ref = 'INV-7', confirmed_at = now() "
                        + "WHERE order_id = ? AND status = 'PENDING'",
                orderId);
        jdbc.update("UPDATE customer_order SET status = 'PAID_IN_FULL' WHERE id = ?", orderId);
        jdbc.update(
                "UPDATE customer_order SET status = 'SHIPPED', carrier = 'DHL', tracking_number = 'TRK123', shipped_at = now() "
                        + "WHERE id = ?",
                orderId);

        assertThat(jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?", String.class, orderId))
                .isEqualTo("SHIPPED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_request WHERE order_id = ?", String.class, orderId))
                .isEqualTo("PAID");
    }

    @Test
    // Only proves this test's own SQL filter; T-010 must re-test against the real repository/controller.
    @DisplayName("#15 catalogQuery_hidesUnlistedProducts")
    void catalogQuery_hidesUnlistedProducts() {
        productId("ACTIVE");
        long unlisted = productId("UNLISTED");
        productId("DRAFT");

        // catalog query as T-010 will write it
        List<Long> catalog =
                jdbc.queryForList("SELECT id FROM product WHERE status = 'ACTIVE' ORDER BY id", Long.class);

        assertThat(catalog).hasSize(1).doesNotContain(unlisted);
    }

    @Test
    @DisplayName("#16 commissionQuotaOfOne_concurrent_onlyOneBuyerSucceeds")
    void commissionQuotaOfOne_concurrent_onlyOneBuyerSucceeds() throws Exception {
        long variant = variant(productId("UNLISTED"), 0, 1);
        for (int round = 0; round < RACE_ROUNDS; round++) {
            jdbc.update("UPDATE product_variant SET made_to_order_quota = 1 WHERE id = ?", variant);

            List<Integer> results = race(
                    2,
                    () -> inTx(() -> jdbc.update(
                            "UPDATE product_variant SET made_to_order_quota = made_to_order_quota - 1 "
                                    + "WHERE id = ? AND made_to_order_quota >= 1",
                            variant)));

            assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT made_to_order_quota FROM product_variant WHERE id = ?", Integer.class, variant))
                    .isZero();
        }
    }

    @Test
    @DisplayName("#17 commissionQuotaBelowZero_isBlockedByCheck")
    void commissionQuotaBelowZero_isBlockedByCheck() {
        long variant = variant(productId("UNLISTED"), 0, 0);

        assertViolation(
                CHECK_VIOLATION,
                "chk_product_variant_mto_quota_nonneg",
                () -> jdbc.update(
                        "UPDATE product_variant SET made_to_order_quota = made_to_order_quota - 1 WHERE id = ?",
                        variant));
    }

    // ---------------------------------------------------------------- extra hardening, not in DATABASE.md §5

    @Test
    @DisplayName("extra-a shortAccessTokenHash_isBlocked")
    void shortAccessTokenHash_isBlocked() {
        Object[] args = orderArgs("IN_STOCK", "AWAITING_PAYMENT", UUID.randomUUID());
        args[1] = new byte[] {1, 2, 3, 4, 5};

        assertViolation(
                CHECK_VIOLATION,
                "chk_order_access_token_hash_length",
                () -> jdbc.queryForList(orderInsertSql(""), Long.class, args));
    }

    @Test
    @DisplayName("extra-b adminTotpEnabledWithoutSecret_isBlocked")
    void adminTotpEnabledWithoutSecret_isBlocked() {
        assertViolation(
                CHECK_VIOLATION,
                "chk_admin_user_totp_secret_when_enabled",
                () -> jdbc.update("INSERT INTO admin_user(email, password_hash, totp_enabled, totp_secret_encrypted) "
                        + "VALUES ('admin@x.com', 'hash', true, NULL)"));
    }

    @Test
    @DisplayName("extra-c adminEmailsDifferingOnlyByCase_collide")
    void adminEmailsDifferingOnlyByCase_collide() {
        jdbc.update("INSERT INTO admin_user(email, password_hash) VALUES ('A@x.com', 'hash')");

        assertViolation(
                UNIQUE_VIOLATION,
                "uq_admin_user_email_lower",
                () -> jdbc.update("INSERT INTO admin_user(email, password_hash) VALUES ('a@X.com', 'hash')"));
    }

    @Test
    @DisplayName("extra-d restockedAt_onNonForfeitedOrder_isBlocked")
    void restockedAt_onNonForfeitedOrder_isBlocked() {
        long id = order("MADE_TO_ORDER", "CANCELLED_DEPOSIT_KEPT", UUID.randomUUID());

        assertViolation(
                CHECK_VIOLATION,
                "chk_order_restocked_only_forfeited",
                () -> jdbc.update("UPDATE customer_order SET restocked_at = now() WHERE id = ?", id));
    }

    @Test
    @DisplayName("extra-e restockForfeitedOrder_twice_secondUpdateAffectsZeroRows")
    void restockForfeitedOrder_twice_secondUpdateAffectsZeroRows() {
        long id = order("MADE_TO_ORDER", "FORFEITED", UUID.randomUUID());
        String restock = "UPDATE customer_order SET restocked_at = ? "
                + "WHERE id = ? AND restocked_at IS NULL AND status = 'FORFEITED'";

        assertThat(jdbc.update(restock, ts(T0), id)).isEqualTo(1);
        assertThat(jdbc.update(restock, ts(T0), id)).isZero();
        // leaving FORFEITED with restocked_at set is blocked too
        assertViolation(
                CHECK_VIOLATION,
                "chk_order_restocked_only_forfeited",
                () -> jdbc.update("UPDATE customer_order SET status = 'CANCELLED_DEPOSIT_KEPT' WHERE id = ?", id));
    }

    // ---------------------------------------------------------------- helpers

    /** Slot allocation as in DATABASE.md section 4: lock the first month with room, skipping locked ones. */
    private LocalDate allocateSlot(long orderItemId) {
        return inTx(() -> {
            LocalDate month = jdbc.queryForObject(
                    "SELECT month FROM production_month WHERE allocated < capacity ORDER BY month LIMIT 1 FOR UPDATE SKIP LOCKED",
                    LocalDate.class);
            sleep(100); // widen the overlap so the second transaction really meets the first one's lock
            jdbc.update("UPDATE production_month SET allocated = allocated + 1 WHERE month = ?", Date.valueOf(month));
            jdbc.update(
                    "INSERT INTO queue_entry(order_item_id, unit_index, production_month) VALUES (?, 1, ?)",
                    orderItemId,
                    Date.valueOf(month));
            return month;
        });
    }

    /** Runs the task on {@code threads} threads released together; each task uses its own connection. */
    private <T> List<T> race(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private <T> T inTx(Callable<T> body) {
        return new TransactionTemplate(txManager).execute(status -> {
            try {
                return body.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Asserts the DB rejected the statement with the given SQLState and the given named constraint. */
    private static void assertViolation(String sqlState, String constraint, Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    PSQLException pg = (PSQLException) NestedExceptionUtils.getRootCause(e);
                    assertThat(pg.getSQLState()).isEqualTo(sqlState);
                    assertThat(pg.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
                });
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private long productId(String status) {
        return jdbc.queryForObject(
                "INSERT INTO product(slug, name, status) VALUES (?, 'Figure', ?) RETURNING id",
                Long.class,
                "figure-" + seq.incrementAndGet(),
                status);
    }

    private long variant(long productId, int stock, Integer mtoQuota) {
        return jdbc.queryForObject(
                "INSERT INTO product_variant(product_id, scale, price_cents, stock_qty, "
                        + "made_to_order_enabled, made_to_order_quota) VALUES (?, '1/7', 10000, ?, true, ?) RETURNING id",
                Long.class,
                productId,
                stock,
                mtoQuota);
    }

    private void month(LocalDate month, int capacity) {
        jdbc.update("INSERT INTO production_month(month, capacity) VALUES (?, ?)", Date.valueOf(month), capacity);
    }

    private int allocated(LocalDate month) {
        return jdbc.queryForObject(
                "SELECT allocated FROM production_month WHERE month = ?", Integer.class, Date.valueOf(month));
    }

    private int stock(long variantId) {
        return jdbc.queryForObject("SELECT stock_qty FROM product_variant WHERE id = ?", Integer.class, variantId);
    }

    private static String orderInsertSql(String suffix) {
        return "INSERT INTO customer_order(public_code, access_token_hash, idempotency_key, fulfillment_type, status, "
                + "customer_name, customer_email, ship_line1, ship_city, ship_country, subtotal_cents, terms_version, "
                + "terms_accepted_at) VALUES (?, ?, ?, ?, ?, 'Test Buyer', 'buyer@example.com', '1 Test St', 'Singapore', "
                + "'SG', 10000, 'v1', now()) " + suffix + " RETURNING id";
    }

    private Object[] orderArgs(String type, String status, UUID key) {
        int n = seq.incrementAndGet();
        return new Object[] {"BK-" + n, sha256("token-" + n), key, type, status};
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private long order(String type, String status, UUID key) {
        return jdbc.queryForObject(orderInsertSql(""), Long.class, orderArgs(type, status, key));
    }

    private long orderItem(long orderId, long variantId) {
        return jdbc.queryForObject(
                "INSERT INTO order_item(order_id, variant_id, product_name, scale, unit_price_cents, quantity) "
                        + "VALUES (?, ?, 'Figure', '1/7', 10000, 1) RETURNING id",
                Long.class,
                orderId,
                variantId);
    }

    private long payment(long orderId, String type, String status, String channel, String externalRef) {
        boolean paid = "PAID".equals(status);
        // PAID rows only get confirmed_at when a ref is given, so the CHECK can be probed with ref = null
        return jdbc.queryForObject(
                "INSERT INTO payment_request(order_id, type, amount_cents, channel, payment_url, status, "
                        + "due_at, external_ref, confirmed_at) VALUES (?, ?, 5000, ?, 'https://pay.example.com/x', ?, ?, ?, ?) RETURNING id",
                Long.class,
                orderId,
                type,
                channel,
                status,
                ts(T0),
                externalRef,
                paid && externalRef != null ? ts(T0) : null);
    }
}
