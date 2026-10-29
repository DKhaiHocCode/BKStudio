package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T-026 concurrency: cancel-by-shop against payment confirmation, against itself and against ship. Each test repeats
 * the race ROUNDS times on fresh data, asserts the invariants (exactly one winner, no 500/deadlock, stock, quota and
 * slots restored exactly once, allocated never negative and equal to the open queue entries), not timing.
 */
class AdminOrderConcurrencyIT extends AdminOrderTestBase {

    private static final int ROUNDS = 15;

    private Callable<Res> cancel(long order, int version) {
        return () ->
                res(act(order, "cancel-by-shop", "version", version, "reason", "race", "refundRef", "RF-" + order));
    }

    private static boolean conflict(Res r) {
        return r.status() == 409
                && List.of("VERSION_CONFLICT", "INVALID_TRANSITION").contains(r.code());
    }

    private static long wins(Collection<Res> results) {
        return results.stream().filter(r -> r.status() == 200).count();
    }

    /** Global queue invariants that must hold after every race. */
    private void assertQueueConsistent() {
        assertThat(count("SELECT count(*) FROM production_month WHERE allocated < 0"))
                .isZero();
        assertThat(allocatedTotal()).isEqualTo(openEntries());
    }

    @Test
    @Timeout(300)
    void cancelVsConfirmPendingDeposit_noDeadlock_exactlyOneWinner_stateConsistent() throws Exception {
        int cancelWon = 0;
        int confirmWon = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long v = newVariant(0, 0);
            long order = orderOn("MADE_TO_ORDER", "AWAITING_DEPOSIT", v, 2);
            long req = pendingRequest(order, "DEPOSIT");

            List<Res> r = concurrently(List.<Callable<Res>>of(cancel(order, 0), () -> res(confirm(req))));
            Res cancelRes = r.get(0);
            Res confirmRes = r.get(1);

            assertThat(wins(r)).as("round %d: exactly one winner %s", round, r).isEqualTo(1);
            if (cancelRes.status() == 200) {
                cancelWon++;
                assertThat(confirmRes.status()).isEqualTo(409);
                assertThat(orderStatus(order)).isEqualTo("CANCELLED");
                assertThat(requestStatus(req)).isEqualTo("CANCELLED");
                assertThat(openEntries(order)).isZero();
                assertThat(count(
                                "SELECT count(*) FROM queue_entry q JOIN order_item i ON i.id = q.order_item_id"
                                        + " WHERE i.order_id = ?",
                                order))
                        .isZero();
                assertThat(quota(v)).isEqualTo(2);
            } else {
                confirmWon++;
                assertThat(conflict(cancelRes))
                        .as("round %d cancel: %s", round, cancelRes)
                        .isTrue();
                assertThat(orderStatus(order)).isEqualTo("QUEUED");
                assertThat(requestStatus(req)).isEqualTo("PAID");
                assertThat(openEntries(order)).isEqualTo(2);
                assertThat(quota(v)).isZero();
            }
            assertThat(auditCount("ORDER_CANCELLED_BY_SHOP", order)).isEqualTo(cancelRes.status() == 200 ? 1 : 0);
            assertQueueConsistent();
        }
        System.out.println("cancel-vs-confirm: cancel won " + cancelWon + ", confirm won " + confirmWon);
    }

    @Test
    @Timeout(300)
    void cancelVsConfirmPendingFull_inStock_exactlyOneWinner_stockRestoredOnlyIfCancelled() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long v = newVariant(0, null);
            long order = orderOn("IN_STOCK", "AWAITING_PAYMENT", v, 2);
            long req = pendingRequest(order, "FULL");

            List<Res> r = concurrently(List.<Callable<Res>>of(cancel(order, 0), () -> res(confirm(req))));

            assertThat(wins(r)).as("round %d: %s", round, r).isEqualTo(1);
            if (r.get(0).status() == 200) {
                assertThat(orderStatus(order)).isEqualTo("CANCELLED");
                assertThat(requestStatus(req)).isEqualTo("CANCELLED");
                assertThat(stock(v)).isEqualTo(2);
            } else {
                assertThat(conflict(r.get(0))).isTrue();
                assertThat(orderStatus(order)).isEqualTo("PAID_IN_FULL");
                assertThat(requestStatus(req)).isEqualTo("PAID");
                assertThat(stock(v)).isZero();
            }
        }
    }

    @Test
    @Timeout(300)
    void concurrentCancelByShop_madeToOrder_oneWins_slotsAndQuotaRestoredOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long v = newVariant(0, 0);
            long order = queuedMto(v, 2, 1);
            assertThat(openEntries(order)).isEqualTo(3);

            List<Res> r = concurrently(List.of(cancel(order, 1), cancel(order, 1), cancel(order, 1)));

            assertThat(wins(r)).as("round %d: %s", round, r).isEqualTo(1);
            r.stream()
                    .filter(x -> x.status() != 200)
                    .forEach(x -> assertThat(conflict(x)).as(x.toString()).isTrue());
            assertThat(orderStatus(order)).isEqualTo("CANCELLED_REFUNDED");
            assertThat(quota(v)).isZero(); // refund cancel: quota is adjusted by hand
            assertThat(openEntries(order)).isZero();
            assertThat(auditCount("ORDER_CANCELLED_BY_SHOP", order)).isEqualTo(1);
            assertThat(outbox("ORDER_CANCELLED", order)).isEqualTo(1);
            assertQueueConsistent();
            assertThat(allocatedTotal()).isZero();
        }
    }

    @Test
    @Timeout(300)
    void concurrentCancelByShop_inStock_oneWins_stockNotRestored() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long v = newVariant(0, null);
            long order = orderOn("IN_STOCK", "PAID_IN_FULL", v, 3);
            int versionBefore = variantVersion(v);

            List<Res> r = concurrently(List.of(cancel(order, 0), cancel(order, 0)));

            assertThat(wins(r)).as("round %d: %s", round, r).isEqualTo(1);
            r.stream()
                    .filter(x -> x.status() != 200)
                    .forEach(x -> assertThat(conflict(x)).as(x.toString()).isTrue());
            assertThat(orderStatus(order)).isEqualTo("CANCELLED_REFUNDED");
            assertThat(stock(v)).isZero();
            assertThat(variantVersion(v)).isEqualTo(versionBefore);
            assertThat(auditCount("ORDER_CANCELLED_BY_SHOP", order)).isEqualTo(1);
        }
    }

    @Test
    @Timeout(300)
    void concurrentShipAndCancel_exactlyOneWins_stockNotRestored() throws Exception {
        int shipWon = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long v = newVariant(0, null);
            long order = orderOn("IN_STOCK", "PAID_IN_FULL", v, 2);

            List<Callable<Res>> calls = new ArrayList<>();
            calls.add(() -> res(act(order, "ship", "version", 0, "carrier", "DHL", "trackingNumber", "T" + order)));
            calls.add(cancel(order, 0));
            List<Res> r = concurrently(calls);

            assertThat(wins(r)).as("round %d: %s", round, r).isEqualTo(1);
            if (r.get(0).status() == 200) {
                shipWon++;
                assertThat(conflict(r.get(1))).isTrue();
                assertThat(orderStatus(order)).isEqualTo("SHIPPED");
                assertThat(stock(v)).isZero();
                assertThat(jdbc.queryForObject(
                                "SELECT shipped_at FROM customer_order WHERE id = ?", Object.class, order))
                        .isNotNull();
                assertThat(auditCount("ORDER_CANCELLED_BY_SHOP", order)).isZero();
            } else {
                assertThat(conflict(r.get(0))).isTrue();
                assertThat(orderStatus(order)).isEqualTo("CANCELLED_REFUNDED");
                assertThat(stock(v)).isZero(); // refund cancel: stock is adjusted by hand
                assertThat(jdbc.queryForObject(
                                "SELECT shipped_at FROM customer_order WHERE id = ?", Object.class, order))
                        .isNull();
                assertThat(outbox("ORDER_SHIPPED", order)).isZero();
            }
        }
        System.out.println("ship-vs-cancel: ship won " + shipWon + " of " + ROUNDS);
    }
}
