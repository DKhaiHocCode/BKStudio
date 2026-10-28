package com.bkstudio.order.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import com.bkstudio.order.state.OrderStateMachine.Details;
import com.bkstudio.queue.QueueFixtures;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import com.bkstudio.support.AbstractPostgresTest;
import com.bkstudio.support.MutableClock;
import com.bkstudio.support.TestClockConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** T-023: OrderStateMachine against real Postgres. */
@Import(TestClockConfig.class)
class OrderStateMachineIT extends AbstractPostgresTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String SECRET_EMAIL = "secret.buyer@example.com";
    private static final String SECRET_NAME = "Secret Buyer Name";
    private static final String SECRET_STREET = "1 Secret Street";

    @Autowired
    OrderStateMachine sm;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    PlatformTransactionManager tm;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2030-01-01T00:00:10Z"));
        wipe();
        jdbc.update("INSERT INTO terms_document (version, content_md) VALUES ('v1', 'x')");
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    private void wipe() {
        QueueFixtures.wipe(jdbc);
        jdbc.execute("TRUNCATE terms_document CASCADE");
        jdbc.execute("TRUNCATE email_outbox");
        jdbc.execute("TRUNCATE audit_log");
    }

    private long order(FulfillmentType type, OrderStatus status) {
        int n = SEQ.incrementAndGet();
        boolean shipped = status == OrderStatus.SHIPPED || status == OrderStatus.COMPLETED;
        return jdbc.queryForObject(
                "INSERT INTO customer_order (public_code, access_token_hash, idempotency_key, fulfillment_type,"
                        + " status, customer_name, customer_email, ship_line1, ship_city, ship_country,"
                        + " subtotal_cents, terms_version, terms_accepted_at, tracking_number, shipped_at)"
                        + " VALUES (?, decode(md5(?) || md5(?), 'hex'), ?, ?, ?, ?, ?, ?, 'Singapore', 'SG',"
                        + " 1000, 'v1', now(), ?, ?::timestamptz) RETURNING id",
                Long.class,
                "BK-T" + n,
                "a" + n,
                "b" + n,
                UUID.randomUUID(),
                type.name(),
                status.name(),
                SECRET_NAME,
                SECRET_EMAIL,
                SECRET_STREET,
                shipped ? "TRK1" : null,
                shipped ? "2030-01-01T00:00:00Z" : null);
    }

    private OrderStatus status(long id) {
        return OrderStatus.valueOf(
                jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?", String.class, id));
    }

    private int version(long id) {
        return jdbc.queryForObject("SELECT version FROM customer_order WHERE id = ?", Integer.class, id);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void assertNoTrace() {
        assertThat(count("audit_log")).isZero();
        assertThat(count("email_outbox")).isZero();
    }

    private ApiException failure(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected ApiException");
    }

    @Test
    void validTransition_updatesStatusAndIncrementsVersion() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.AWAITING_DEPOSIT);

        OrderStatus result = sm.transition(id, OrderAction.CONFIRM_DEPOSIT, 0, OrderActor.ADMIN);

        assertThat(result).isEqualTo(OrderStatus.QUEUED);
        assertThat(status(id)).isEqualTo(OrderStatus.QUEUED);
        assertThat(version(id)).isEqualTo(1);
    }

    @Test
    void invalidTransition_throws409_andWritesNothing() {
        long id = order(FulfillmentType.IN_STOCK, OrderStatus.AWAITING_PAYMENT);

        ApiException e =
                failure(() -> sm.transition(id, OrderAction.SHIP, 0, OrderActor.ADMIN, new Details("T1", null)));

        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_TRANSITION);
        assertThat(e.code().status().value()).isEqualTo(409);
        assertThat(status(id)).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(version(id)).isZero();
        assertNoTrace();
    }

    @Test
    void wrongFulfillmentType_isInvalidTransition() {
        long id = order(FulfillmentType.IN_STOCK, OrderStatus.PAID_IN_FULL);

        ApiException e = failure(() -> sm.transition(id, OrderAction.CANCEL_KEEP_DEPOSIT, 0, OrderActor.ADMIN));

        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_TRANSITION);
    }

    @Test
    void staleVersion_throwsVersionConflict_andWritesNothing() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.AWAITING_DEPOSIT);

        ApiException e = failure(() -> sm.transition(id, OrderAction.CONFIRM_DEPOSIT, 5, OrderActor.ADMIN));

        assertThat(e.code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        assertThat(status(id)).isEqualTo(OrderStatus.AWAITING_DEPOSIT);
        assertThat(version(id)).isZero();
        assertNoTrace();
    }

    @Test
    void staleVersion_winsOverInvalidTransition() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.AWAITING_DEPOSIT);
        sm.transition(id, OrderAction.CONFIRM_DEPOSIT, 0, OrderActor.ADMIN);

        // Loser still holds version 0 and asks for an action that is now invalid (QUEUED cannot CONFIRM_DEPOSIT).
        ApiException e = failure(() -> sm.transition(id, OrderAction.CONFIRM_DEPOSIT, 0, OrderActor.ADMIN));

        assertThat(e.code()).isEqualTo(ErrorCode.VERSION_CONFLICT);
        assertThat(version(id)).isEqualTo(1);
    }

    @Test
    void adminActor_recordsActorIdFromSecurityContext_othersNull() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.AWAITING_DEPOSIT);
        try {
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken("42", "n/a", List.of()));
            sm.transition(id, OrderAction.CONFIRM_DEPOSIT, 0, OrderActor.ADMIN);
            SecurityContextHolder.clearContext();
            sm.transition(id, OrderAction.START_PRODUCTION, 1, OrderActor.SYSTEM);
        } finally {
            SecurityContextHolder.clearContext();
        }

        List<Long> ids = jdbc.queryForList("SELECT actor_id FROM audit_log ORDER BY id", Long.class);
        assertThat(ids).containsExactly(42L, null);
    }

    @Test
    void controlCharacters_inTrackingOrReason_areBadRequest() {
        long ship = order(FulfillmentType.IN_STOCK, OrderStatus.PAID_IN_FULL);
        long cancel = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.QUEUED);

        ApiException a =
                failure(() -> sm.transition(ship, OrderAction.SHIP, 0, OrderActor.ADMIN, new Details("TRK\n1", null)));
        ApiException b = failure(() -> sm.transition(
                cancel, OrderAction.CANCEL_REFUND, 0, OrderActor.ADMIN, new Details(null, "bad\u0001reason")));

        assertThat(a.code()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(b.code()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertNoTrace();
    }

    @Test
    void unknownOrder_throwsNotFound() {
        ApiException e = failure(() -> sm.transition(999_999L, OrderAction.CANCEL, 0, OrderActor.ADMIN));

        assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void transition_writesAuditRow() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.AWAITING_DEPOSIT);

        sm.transition(id, OrderAction.CONFIRM_DEPOSIT, 0, OrderActor.SYSTEM);

        assertThat(count("audit_log")).isEqualTo(1);
        Map<String, Object> row =
                jdbc.queryForMap("SELECT actor, actor_id, action, entity_type, entity_id, before ->> 'status' AS b,"
                        + " after ->> 'status' AS a, after ->> 'action' AS act FROM audit_log");
        assertThat(row)
                .containsEntry("actor", "SYSTEM")
                .containsEntry("action", "ORDER_TRANSITION")
                .containsEntry("entity_type", "ORDER")
                .containsEntry("entity_id", id)
                .containsEntry("b", "AWAITING_DEPOSIT")
                .containsEntry("a", "QUEUED")
                .containsEntry("act", "CONFIRM_DEPOSIT");
        assertThat(row.get("actor_id")).isNull();
    }

    @Test
    void transition_writesOutboxRow_withMinimalPayload() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.AWAITING_DEPOSIT);

        sm.transition(id, OrderAction.CONFIRM_DEPOSIT, 0, OrderActor.ADMIN);

        Map<String, Object> row = jdbc.queryForMap("SELECT dedupe_key, template, to_email, status, payload::text AS p,"
                + " payload ->> 'publicCode' AS code, payload ->> 'status' AS st FROM email_outbox");
        assertThat(row)
                .containsEntry("dedupe_key", "order:" + id + ":CONFIRM_DEPOSIT:1")
                .containsEntry("template", "ORDER_DEPOSIT_CONFIRMED")
                .containsEntry("to_email", SECRET_EMAIL)
                .containsEntry("status", "PENDING")
                .containsEntry("st", "QUEUED");
        assertThat((String) row.get("code")).startsWith("BK-T");
        Integer keys =
                jdbc.queryForObject("SELECT count(*) FROM email_outbox, jsonb_object_keys(payload)", Integer.class);
        assertThat(keys).isEqualTo(2);
        assertThat((String) row.get("p")).doesNotContain("@", SECRET_NAME, SECRET_STREET, "TRK");
    }

    @Test
    void rolledBackTransaction_leavesNoTrace() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.AWAITING_DEPOSIT);

        new TransactionTemplate(tm).executeWithoutResult(tx -> {
            sm.transition(id, OrderAction.CONFIRM_DEPOSIT, 0, OrderActor.ADMIN);
            tx.setRollbackOnly();
        });

        assertThat(status(id)).isEqualTo(OrderStatus.AWAITING_DEPOSIT);
        assertThat(version(id)).isZero();
        assertNoTrace();
    }

    @Test
    void ship_withoutValidTracking_isBadRequest_andWritesNothing() {
        long id = order(FulfillmentType.IN_STOCK, OrderStatus.PAID_IN_FULL);

        for (Details d :
                List.of(new Details(null, null), new Details("   ", null), new Details("x".repeat(101), null))) {
            ApiException e = failure(() -> sm.transition(id, OrderAction.SHIP, 0, OrderActor.ADMIN, d));
            assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST);
        }
        ApiException e = failure(() -> sm.transition(id, OrderAction.SHIP, 0, OrderActor.ADMIN));
        assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST);

        assertThat(status(id)).isEqualTo(OrderStatus.PAID_IN_FULL);
        assertThat(version(id)).isZero();
        assertNoTrace();
    }

    @Test
    void shipThenComplete_setsTimestampsAndSatisfiesTrackingConstraint() {
        long id = order(FulfillmentType.IN_STOCK, OrderStatus.PAID_IN_FULL);

        sm.transition(id, OrderAction.SHIP, 0, OrderActor.ADMIN, new Details("  TRK-100  ", null));
        Map<String, Object> shipped = jdbc.queryForMap(
                "SELECT tracking_number, shipped_at, completed_at FROM customer_order WHERE id = ?", id);
        assertThat(shipped.get("tracking_number")).isEqualTo("TRK-100");
        assertThat(shipped.get("shipped_at")).isNotNull();
        assertThat(shipped.get("completed_at")).isNull();

        clock.advance(Duration.ofDays(3));
        sm.transition(id, OrderAction.COMPLETE, 1, OrderActor.ADMIN);

        Map<String, Object> done = jdbc.queryForMap(
                "SELECT tracking_number, shipped_at, completed_at FROM customer_order WHERE id = ?", id);
        assertThat(done.get("tracking_number")).isEqualTo("TRK-100");
        assertThat(done.get("completed_at")).isNotNull();
        assertThat(done.get("shipped_at")).isEqualTo(shipped.get("shipped_at"));
        assertThat(status(id)).isEqualTo(OrderStatus.COMPLETED);
        assertThat(version(id)).isEqualTo(2);
    }

    @Test
    void cancel_storesTrimmedReason() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.QUEUED);

        sm.transition(id, OrderAction.CANCEL_REFUND, 0, OrderActor.ADMIN, new Details(null, "  changed mind  "));

        assertThat(reason(id)).isEqualTo("changed mind");
        assertThat(status(id)).isEqualTo(OrderStatus.CANCELLED_REFUNDED);
    }

    @Test
    void cancel_withBlankReason_storesNull() {
        long id = order(FulfillmentType.IN_STOCK, OrderStatus.AWAITING_PAYMENT);

        sm.transition(id, OrderAction.CANCEL, 0, OrderActor.CUSTOMER, new Details(null, "   "));

        assertThat(reason(id)).isNull();
    }

    @Test
    void forfeit_storesReason() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.AWAITING_BALANCE);

        sm.transition(id, OrderAction.FORFEIT, 0, OrderActor.SYSTEM, new Details(null, "no balance in 30 days"));

        assertThat(reason(id)).isEqualTo("no balance in 30 days");
    }

    @Test
    void cancel_reasonOver500_isBadRequest_andWritesNothing() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.QUEUED);

        ApiException e = failure(() -> sm.transition(
                id, OrderAction.CANCEL_KEEP_DEPOSIT, 0, OrderActor.ADMIN, new Details(null, "x".repeat(501))));

        assertThat(e.code()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(status(id)).isEqualTo(OrderStatus.QUEUED);
        assertThat(version(id)).isZero();
        assertNoTrace();
    }

    @Test
    void cancel_reasonOf500_isAccepted() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.QUEUED);

        sm.transition(id, OrderAction.CANCEL_KEEP_DEPOSIT, 0, OrderActor.ADMIN, new Details(null, "x".repeat(500)));

        assertThat(status(id)).isEqualTo(OrderStatus.CANCELLED_DEPOSIT_KEPT);
    }

    @Test
    void invalidTransition_winsOverBadDetails() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.AWAITING_DEPOSIT);

        ApiException ship =
                failure(() -> sm.transition(id, OrderAction.SHIP, 0, OrderActor.ADMIN, new Details("  ", null)));
        ApiException cancel = failure(() ->
                sm.transition(id, OrderAction.CANCEL_REFUND, 0, OrderActor.ADMIN, new Details(null, "x".repeat(600))));

        assertThat(ship.code()).isEqualTo(ErrorCode.INVALID_TRANSITION);
        assertThat(cancel.code()).isEqualTo(ErrorCode.INVALID_TRANSITION);
    }

    @Test
    void concurrentTransitionsWithSameVersion_exactlyOneSucceeds() throws Exception {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.AWAITING_DEPOSIT);
        // Same version, two different valid actions: only one may win.
        OrderAction[] actions = {OrderAction.CONFIRM_DEPOSIT, OrderAction.CANCEL};
        ExecutorService pool = Executors.newFixedThreadPool(actions.length);
        CountDownLatch ready = new CountDownLatch(actions.length);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (OrderAction a : actions) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return sm.transition(id, a, 0, OrderActor.ADMIN);
                } catch (ApiException e) {
                    return e.code();
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(results.stream().filter(r -> r instanceof OrderStatus)).hasSize(1);
        assertThat(results.stream().filter(r -> r == ErrorCode.VERSION_CONFLICT))
                .hasSize(1);
        assertThat(version(id)).isEqualTo(1);
        assertThat(count("audit_log")).isEqualTo(1);
        assertThat(count("email_outbox")).isEqualTo(1);
    }

    @Test
    void fullMadeToOrderPath_endToEnd() {
        long id = order(FulfillmentType.MADE_TO_ORDER, OrderStatus.AWAITING_DEPOSIT);

        walk(
                id,
                new OrderAction[] {
                    OrderAction.CONFIRM_DEPOSIT,
                    OrderAction.START_PRODUCTION,
                    OrderAction.COMPLETE_PRODUCTION,
                    OrderAction.CONFIRM_BALANCE,
                    OrderAction.SHIP,
                    OrderAction.COMPLETE
                },
                new OrderStatus[] {
                    OrderStatus.QUEUED,
                    OrderStatus.IN_PRODUCTION,
                    OrderStatus.AWAITING_BALANCE,
                    OrderStatus.PAID_IN_FULL,
                    OrderStatus.SHIPPED,
                    OrderStatus.COMPLETED
                });
    }

    @Test
    void fullInStockPath_endToEnd() {
        long id = order(FulfillmentType.IN_STOCK, OrderStatus.AWAITING_PAYMENT);

        walk(
                id,
                new OrderAction[] {OrderAction.CONFIRM_PAYMENT, OrderAction.SHIP, OrderAction.COMPLETE},
                new OrderStatus[] {OrderStatus.PAID_IN_FULL, OrderStatus.SHIPPED, OrderStatus.COMPLETED});
    }

    private String reason(long id) {
        return jdbc.queryForObject("SELECT cancel_reason FROM customer_order WHERE id = ?", String.class, id);
    }

    private void walk(long id, OrderAction[] path, OrderStatus[] expected) {
        for (int i = 0; i < path.length; i++) {
            Details d = path[i] == OrderAction.SHIP ? new Details("TRK-9", null) : new Details(null, null);
            assertThat(sm.transition(id, path[i], i, OrderActor.ADMIN, d)).isEqualTo(expected[i]);
            assertThat(status(id)).isEqualTo(expected[i]);
            assertThat(version(id)).isEqualTo(i + 1);
        }
        assertThat(count("audit_log")).isEqualTo(path.length);
        assertThat(count("email_outbox")).isEqualTo(path.length);
        ApiException e = failure(() -> sm.transition(id, OrderAction.CANCEL, path.length, OrderActor.ADMIN));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_TRANSITION);
    }
}
