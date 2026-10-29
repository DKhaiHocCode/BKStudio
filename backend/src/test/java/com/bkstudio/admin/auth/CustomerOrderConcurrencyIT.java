package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T-028 races: customer cancel against admin actions on the same order, and two lookups at once. Each test repeats
 * the race ROUNDS times on fresh data and asserts the invariant, never timing.
 */
class CustomerOrderConcurrencyIT extends CustomerOrderTestBase {

    private static final int ROUNDS = 15;

    private void assertQueueConsistent() {
        assertThat(count("SELECT count(*) FROM production_month WHERE allocated < 0"))
                .isZero();
        assertThat(allocatedTotal()).isEqualTo(openEntries());
    }

    @Test
    @Timeout(300)
    void customerCancelVsConfirmDeposit_eitherCancelledWithQuotaBackOrQueued_neverPaidOnCancelled() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long v = newVariant(0, 0);
            long order = orderOn("MADE_TO_ORDER", "AWAITING_DEPOSIT", v, 2);
            long req = pendingRequest(order, "DEPOSIT");
            String token = tokenFor(order);

            List<Res> r =
                    concurrently(List.<Callable<Res>>of(() -> res(cancelOrder(token, null)), () -> res(confirm(req))));
            Res cancel = r.get(0);
            Res confirm = r.get(1);

            String reqStatus = requestStatus(req);
            assertThat(reqStatus).as("round %d: %s", round, r).isIn("PAID", "CANCELLED");
            if (reqStatus.equals("PAID")) {
                // deposit confirmed first: the order is QUEUED, so a cancel without acknowledgement is refused
                assertThat(confirm.status()).isEqualTo(200);
                assertThat(cancel.status()).isEqualTo(400);
                assertThat(orderStatus(order)).isEqualTo("QUEUED");
                assertThat(openEntries(order)).isEqualTo(2);
                assertThat(quota(v)).isZero();
                assertThat(outbox("ORDER_CANCELLED", order)).isZero();
            } else {
                assertThat(cancel.status()).isEqualTo(200);
                assertThat(confirm.status()).isEqualTo(409);
                assertThat(orderStatus(order)).isEqualTo("CANCELLED");
                assertThat(openEntries(order)).isZero();
                assertThat(quota(v)).isEqualTo(2);
                assertThat(outbox("ORDER_CANCELLED", order)).isEqualTo(1);
            }
            assertThat(count(
                            "SELECT count(*) FROM payment_request p JOIN customer_order o ON o.id = p.order_id"
                                    + " WHERE o.id = ? AND p.status = 'PAID' AND o.status = 'CANCELLED'",
                            order))
                    .isZero();
            assertQueueConsistent();
        }
    }

    @Test
    @Timeout(300)
    void customerCancelVsCancelByShop_exactlyOneWins_stockRestoredOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long v = newVariant(5, 0);
            long order = orderOn("IN_STOCK", "AWAITING_PAYMENT", v, 2);
            long req = pendingRequest(order, "FULL");
            String token = tokenFor(order);

            List<Res> r = concurrently(List.<Callable<Res>>of(
                    () -> res(cancelOrder(token, null)),
                    () -> res(act(order, "cancel-by-shop", "version", 0, "reason", "race"))));
            Res customer = r.get(0);
            Res shop = r.get(1);

            assertThat(List.of(customer.status(), shop.status()))
                    .as("round %d: %s", round, r)
                    .containsExactlyInAnyOrder(200, 409);
            Res loser = customer.status() == 200 ? shop : customer;
            assertThat(List.of("VERSION_CONFLICT", "INVALID_TRANSITION")).contains(loser.code());
            assertThat(orderStatus(order)).isEqualTo("CANCELLED");
            assertThat(stock(v)).as("restored once").isEqualTo(7);
            assertThat(requestStatus(req)).isEqualTo("CANCELLED");
            assertThat(outbox("ORDER_CANCELLED", order)).isEqualTo(1);
        }
    }

    @Test
    @Timeout(300)
    void customerCancelVsAdminCreatePaymentRequest_neverCancelledOrderWithPendingRequest() throws Exception {
        jdbc.update("INSERT INTO app_setting (key, value) VALUES ('payment_allowed_domains', 'artistree.io')"
                + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value");
        for (int round = 0; round < ROUNDS; round++) {
            long v = newVariant(0, 0);
            long order = orderOn("MADE_TO_ORDER", "AWAITING_DEPOSIT", v, 2);
            String token = tokenFor(order);

            List<Res> r = concurrently(List.<Callable<Res>>of(
                    () -> res(post(
                            "/api/admin/orders/" + order + "/payment-requests",
                            j(
                                    "type",
                                    "DEPOSIT",
                                    "amountCents",
                                    1000,
                                    "channel",
                                    "ARTISTREE",
                                    "paymentUrl",
                                    "https://artistree.io/p"))),
                    () -> res(cancelOrder(token, null))));
            Res create = r.get(0);
            Res cancel = r.get(1);

            int pending =
                    count("SELECT count(*) FROM payment_request WHERE order_id = ? AND status = 'PENDING'", order);
            assertThat(orderStatus(order).startsWith("CANCELLED") && pending > 0)
                    .as("round %d: cancelled order with PENDING request: %s", round, r)
                    .isFalse();
            if (create.status() == 201) {
                assertThat(cancel.status()).as(r.toString()).isEqualTo(409);
                assertThat(orderStatus(order)).isEqualTo("AWAITING_DEPOSIT");
                assertThat(pending).isEqualTo(1);
            } else {
                assertThat(cancel.status()).as(r.toString()).isEqualTo(200);
                assertThat(create.status()).isEqualTo(409);
                assertThat(create.code()).isEqualTo("INVALID_TRANSITION");
                assertThat(orderStatus(order)).startsWith("CANCELLED");
                assertThat(pending).isZero();
            }
            assertThat(count("SELECT count(*) FROM email_outbox e WHERE e.template = 'ORDER_PAYMENT_LINK'"
                            + " AND NOT EXISTS (SELECT 1 FROM payment_request p"
                            + " WHERE e.dedupe_key = 'payment:' || p.id || ':link' AND p.status = 'PENDING')"))
                    .as("round %d: orphan payment link email", round)
                    .isZero();
        }
    }

    @Test
    @Timeout(300)
    void twoLookupsAtOnce_exactlyOneValidToken_matchingTheSingleEmailedLink() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long order = order("IN_STOCK", "AWAITING_PAYMENT", 1);
            String oldToken = tokenFor(order);

            List<Res> r = concurrently(List.<Callable<Res>>of(
                    () -> res(lookup(codeOf(order), SECRET_EMAIL)), () -> res(lookup(codeOf(order), SECRET_EMAIL))));

            assertThat(r.get(0).status()).isEqualTo(202);
            assertThat(r.get(1).status()).isEqualTo(202);
            assertThat(r.get(0).body()).isEqualTo(r.get(1).body());
            assertThat(lookupMails(order)).as("round %d", round).isEqualTo(1);
            assertThat(version(order)).isEqualTo(1);
            assertThat(viewOrder(oldToken).andReturn().getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(viewOrder(linkToken(order)).andReturn().getResponse().getStatus())
                    .isEqualTo(200);
        }
    }
}
