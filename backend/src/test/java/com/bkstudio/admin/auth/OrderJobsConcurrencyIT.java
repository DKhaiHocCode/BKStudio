package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T-027 races: the expiry and forfeit jobs against admin confirm/cancel of the same request. Each test repeats the
 * race ROUNDS times on fresh data and asserts the invariant (one coherent outcome, resources released exactly once,
 * no PAID request on a cancelled order), never timing.
 */
class OrderJobsConcurrencyIT extends OrderJobsTestBase {

    private static final int ROUNDS = 15;

    private void assertQueueConsistent() {
        assertThat(count("SELECT count(*) FROM production_month WHERE allocated < 0"))
                .isZero();
        assertThat(allocatedTotal()).isEqualTo(openEntries());
    }

    @Test
    @Timeout(300)
    void expireVsConfirmDeposit_eitherPaidWithSlotsOrCancelledWithQuotaBack_neverBoth() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long v = newVariant(0, 0);
            long order = orderOn("MADE_TO_ORDER", "AWAITING_DEPOSIT", v, 2);
            long req = overdue(order, "DEPOSIT");

            List<Object> r =
                    concurrently(List.<Callable<Object>>of(() -> jobs.expireUnpaid(), () -> res(confirm(req))));
            int expired = (int) r.get(0);
            Res confirmRes = (Res) r.get(1);

            String reqStatus = requestStatus(req);
            assertThat(reqStatus).as("round %d: %s", round, r).isIn("PAID", "EXPIRED");
            if (reqStatus.equals("PAID")) {
                assertThat(confirmRes.status()).isEqualTo(200);
                assertThat(expired).isZero();
                assertThat(orderStatus(order)).isEqualTo("QUEUED");
                assertThat(openEntries(order)).isEqualTo(2);
                assertThat(quota(v)).isZero();
                assertThat(outbox("ORDER_CANCELLED", order)).isZero();
            } else {
                assertThat(confirmRes.status()).isEqualTo(409);
                assertThat(expired).isEqualTo(1);
                assertThat(orderStatus(order)).isEqualTo("CANCELLED");
                assertThat(openEntries(order)).isZero();
                assertThat(quota(v)).isEqualTo(2);
                assertThat(outbox("ORDER_CANCELLED", order)).isEqualTo(1);
            }
            assertThat(count(
                            "SELECT count(*) FROM payment_request p JOIN customer_order o ON o.id = p.order_id"
                                    + " WHERE o.id = ? AND p.status = 'PAID' AND o.status LIKE 'CANCELLED%'",
                            order))
                    .isZero();
            assertQueueConsistent();
        }
    }

    @Test
    @Timeout(300)
    void expireVsCancelByShop_oneWinner_quotaRestoredExactlyOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long v = newVariant(0, 0);
            long order = orderOn("MADE_TO_ORDER", "AWAITING_DEPOSIT", v, 2);
            long req = overdue(order, "DEPOSIT");

            List<Object> r = concurrently(List.<Callable<Object>>of(
                    () -> jobs.expireUnpaid(),
                    () -> res(act(order, "cancel-by-shop", "version", 0, "reason", "race"))));
            int expired = (int) r.get(0);
            Res cancelRes = (Res) r.get(1);

            assertThat(orderStatus(order)).as(r.toString()).isEqualTo("CANCELLED");
            assertThat(quota(v)).as("round %d: restored once", round).isEqualTo(2);
            assertThat(outbox("ORDER_CANCELLED", order)).isEqualTo(1);
            if (cancelRes.status() == 200) {
                assertThat(expired).isZero();
                assertThat(requestStatus(req)).isEqualTo("CANCELLED");
                assertThat(auditCount("PAYMENT_REQUEST_EXPIRED", req)).isZero();
            } else {
                assertThat(cancelRes.status()).isEqualTo(409);
                assertThat(List.of("VERSION_CONFLICT", "INVALID_TRANSITION")).contains(cancelRes.code());
                assertThat(expired).isEqualTo(1);
                assertThat(requestStatus(req)).isEqualTo("EXPIRED");
                assertThat(auditCount("PAYMENT_REQUEST_EXPIRED", req)).isEqualTo(1);
            }
            assertQueueConsistent();
        }
    }

    @Test
    @Timeout(300)
    void forfeitVsConfirmBalance_eitherPaidInFullOrForfeited_neverBoth() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long v = newVariant(0, 3);
            long order = orderOn("MADE_TO_ORDER", "AWAITING_BALANCE", v, 1);
            long req = overdue(order, "BALANCE");

            List<Object> r =
                    concurrently(List.<Callable<Object>>of(() -> jobs.forfeitOverdue(), () -> res(confirm(req))));
            int forfeited = (int) r.get(0);
            Res confirmRes = (Res) r.get(1);

            String reqStatus = requestStatus(req);
            assertThat(reqStatus).as("round %d: %s", round, r).isIn("PAID", "EXPIRED");
            if (reqStatus.equals("PAID")) {
                assertThat(confirmRes.status()).isEqualTo(200);
                assertThat(forfeited).isZero();
                assertThat(orderStatus(order)).isEqualTo("PAID_IN_FULL");
                assertThat(outbox("ORDER_FORFEITED", order)).isZero();
            } else {
                assertThat(confirmRes.status()).isEqualTo(409);
                assertThat(forfeited).isEqualTo(1);
                assertThat(orderStatus(order)).isEqualTo("FORFEITED");
                assertThat(outbox("ORDER_FORFEITED", order)).isEqualTo(1);
            }
            assertThat(quota(v)).isEqualTo(3); // forfeit never touches stock or quota
            assertThat(stock(v)).isZero();
        }
    }

    @Test
    @Timeout(300)
    void twoExpiryRunsAtOnce_cancelEachOrderOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long v = newVariant(0, 0);
            long order = orderOn("MADE_TO_ORDER", "AWAITING_DEPOSIT", v, 2);
            overdue(order, "DEPOSIT");

            List<Integer> r =
                    concurrently(List.<Callable<Integer>>of(() -> jobs.expireUnpaid(), () -> jobs.expireUnpaid()));

            assertThat(r.get(0) + r.get(1)).as("round %d: %s", round, r).isEqualTo(1);
            assertThat(quota(v)).isEqualTo(2);
            assertThat(outbox("ORDER_CANCELLED", order)).isEqualTo(1);
        }
    }
}
