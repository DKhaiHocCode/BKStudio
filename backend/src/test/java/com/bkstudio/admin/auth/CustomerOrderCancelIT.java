package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.order.checkout.domain.CheckoutService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** T-028 POST /api/orders/{token}/cancel. */
class CustomerOrderCancelIT extends CustomerOrderTestBase {

    private int customerAudit(String action, long orderId) {
        return count(
                "SELECT count(*) FROM audit_log WHERE actor = 'CUSTOMER' AND action = ? AND entity_id = ?",
                action,
                orderId);
    }

    @Test
    void cancelInStockAwaitingPayment_restoresStockCancelsRequestsQueuesEmailAuditsCustomer() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 2);
        long req = pendingRequest(id, "FULL");
        String token = tokenFor(id);

        Res r = res(cancelOrder(token, null));

        assertThat(r.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(r.body(), "$.status")).isEqualTo("CANCELLED");
        assertThat(JsonPath.<Boolean>read(r.body(), "$.canCancel")).isFalse();
        assertThat(orderStatus(id)).isEqualTo("CANCELLED");
        assertThat(stock()).isEqualTo(7);
        assertThat(requestStatus(req)).isEqualTo("CANCELLED");
        assertThat(outbox("ORDER_CANCELLED", id)).isEqualTo(1);
        assertThat(customerAudit("ORDER_TRANSITION", id)).isEqualTo(1);
        assertThat(customerAudit("ORDER_CANCELLED_BY_CUSTOMER", id)).isEqualTo(1);
        assertThat(customerAudit("PAYMENT_REQUESTS_CANCELLED_WITH_ORDER", id)).isEqualTo(1);
    }

    @Test
    void cancelAwaitingDeposit_restoresQuota() throws Exception {
        long v = newVariant(0, 0);
        long id = orderOn("MADE_TO_ORDER", "AWAITING_DEPOSIT", v, 2);
        long req = pendingRequest(id, "DEPOSIT");

        Res r = res(cancelOrder(tokenFor(id), "{}"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(orderStatus(id)).isEqualTo("CANCELLED");
        assertThat(quota(v)).isEqualTo(2);
        assertThat(stock(v)).isZero();
        assertThat(requestStatus(req)).isEqualTo("CANCELLED");
        assertThat(outbox("ORDER_CANCELLED", id)).isEqualTo(1);
    }

    @Test
    void cancelQueuedWithoutAcknowledgement_400AndNothingChanged() throws Exception {
        long v = newVariant(0, 3);
        long id = queuedMto(v, 2);
        long balance = pendingRequest(id, "BALANCE");
        String token = tokenFor(id);
        String before = snapshot(id, v);

        for (String body : new String[] {null, "{}", "{\"acknowledgeDepositLost\":false}"}) {
            expectField(cancelOrder(token, body), "acknowledgeDepositLost");
            assertThat(snapshot(id, v)).isEqualTo(before);
            assertThat(requestStatus(balance)).isEqualTo("PENDING");
        }
        assertThat(orderStatus(id)).isEqualTo("QUEUED");
        assertThat(openEntries(id)).isEqualTo(2);
    }

    @Test
    void cancelQueuedWithAcknowledgement_keepsDepositReleasesSlotsKeepsQuota() throws Exception {
        long v = newVariant(0, 3);
        long id = queuedMto(v, 2);
        long balance = pendingRequest(id, "BALANCE");
        assertThat(allocatedTotal()).isEqualTo(2);

        Res r = res(cancelOrder(tokenFor(id), "{\"acknowledgeDepositLost\":true}"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(r.body(), "$.status")).isEqualTo("CANCELLED_DEPOSIT_KEPT");
        assertThat(orderStatus(id)).isEqualTo("CANCELLED_DEPOSIT_KEPT");
        assertThat(allocatedTotal()).isZero();
        assertThat(openEntries(id)).isZero();
        assertThat(count(
                        "SELECT count(*) FROM queue_entry q JOIN order_item i ON i.id = q.order_item_id"
                                + " WHERE i.order_id = ? AND q.status = 'CANCELLED'",
                        id))
                .isEqualTo(2);
        assertThat(quota(v)).isEqualTo(3);
        assertThat(stock(v)).isZero();
        assertThat(requestStatus(balance)).isEqualTo("CANCELLED");
        assertThat(outbox("ORDER_CANCELLED", id)).isEqualTo(1);
        assertThat(customerAudit("ORDER_CANCELLED_BY_CUSTOMER", id)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "MADE_TO_ORDER,IN_PRODUCTION",
        "MADE_TO_ORDER,AWAITING_BALANCE",
        "IN_STOCK,PAID_IN_FULL",
        "IN_STOCK,SHIPPED",
        "IN_STOCK,COMPLETED",
        "IN_STOCK,CANCELLED"
    })
    void cancelInOtherStatuses_409InvalidTransitionAndNothingChanged(String type, String status) throws Exception {
        long id = order(type, status, 1);
        pendingRequest(id, "BALANCE");
        String token = tokenFor(id);
        String before = snapshot(id, variant);

        expectProblem(cancelOrder(token, "{\"acknowledgeDepositLost\":true}"), 409, "INVALID_TRANSITION");

        assertThat(snapshot(id, variant)).isEqualTo(before);
    }

    @Test
    void cancelWithTokenOfAnotherOrder_onlyThatOrderChanges_unknownTokenIs404() throws Exception {
        long a = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        long b = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        String tokenA = tokenFor(a);
        tokenFor(b);

        assertThat(res(cancelOrder(tokenA, null)).status()).isEqualTo(200);
        assertThat(orderStatus(a)).isEqualTo("CANCELLED");
        assertThat(orderStatus(b)).isEqualTo("AWAITING_PAYMENT");

        expectProblem(cancelOrder(CheckoutService.newToken(), null), 404, "NOT_FOUND");
        expectProblem(cancelOrder("short", null), 404, "NOT_FOUND");
        assertThat(orderStatus(b)).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void cancelWithoutCsrf_403AndNothingChanged() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        String token = tokenFor(id);
        String before = snapshot(id, variant);

        assertThat(res(postWithoutCsrf("/api/orders/" + token + "/cancel", "{}"))
                        .status())
                .isEqualTo(403);

        assertThat(snapshot(id, variant)).isEqualTo(before);
    }

    @Test
    void secondCancelOfSameOrder_409AndStockRestoredOnce() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 2);
        String token = tokenFor(id);

        assertThat(res(cancelOrder(token, null)).status()).isEqualTo(200);
        expectProblem(cancelOrder(token, null), 409, "INVALID_TRANSITION");

        assertThat(stock()).isEqualTo(7);
        assertThat(outbox("ORDER_CANCELLED", id)).isEqualTo(1);
    }

    @Test
    void cancel_over60PerMinutePerIp_429RateLimited() throws Exception {
        String ip = nextIp();
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        String token = tokenFor(id);
        for (int i = 0; i < 60; i++) {
            cancelOrder(CheckoutService.newToken(), null, ip);
        }
        expectProblem(cancelOrder(token, null, ip), 429, "RATE_LIMITED");
        assertThat(orderStatus(id)).isEqualTo("AWAITING_PAYMENT");
    }
}
