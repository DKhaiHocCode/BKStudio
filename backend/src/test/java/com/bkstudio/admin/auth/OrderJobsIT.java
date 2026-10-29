package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * T-027: payment expiry, balance reminders, forfeit and the forfeited-to-stock endpoint against real Postgres. The
 * scheduler is off in tests; jobs are called directly. Every job must be idempotent: a second run changes nothing.
 * Clock is 2030-01-01T00:00:10Z and only moves via {@code clock}.
 */
class OrderJobsIT extends OrderJobsTestBase {

    private static final String ACTION = "forfeited-to-stock";

    // ---- expiry ----

    @Test
    void expireUnpaid_madeToOrderDeposit_cancelsOrderAndRestoresQuota() {
        long order = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 2);
        long req = overdue(order, "DEPOSIT");

        assertThat(jobs.expireUnpaid()).isEqualTo(1);

        assertThat(requestStatus(req)).isEqualTo("EXPIRED");
        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
        assertThat(quota(variant)).isEqualTo(5); // 3 + 2
        assertThat(stock()).isEqualTo(5);
        assertThat(outbox("ORDER_CANCELLED", order)).isEqualTo(1);
        assertThat(auditCount("PAYMENT_REQUEST_EXPIRED", req)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM customer_order WHERE id = ?", String.class, order))
                .isNotBlank();
    }

    @Test
    void expireUnpaid_inStockFullPayment_restoresStock() {
        long order = order("IN_STOCK", "AWAITING_PAYMENT", 2);
        long req = overdue(order, "FULL");

        assertThat(jobs.expireUnpaid()).isEqualTo(1);

        assertThat(requestStatus(req)).isEqualTo("EXPIRED");
        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
        assertThat(stock()).isEqualTo(7);
        assertThat(quota(variant)).isEqualTo(3);
        assertThat(outbox("ORDER_CANCELLED", order)).isEqualTo(1);
    }

    @Test
    void expireUnpaid_notYetDue_changesNothing() {
        long order = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 1);
        long req = notYetDue(order, "DEPOSIT");
        int outboxBefore = outboxTotal();
        int auditBefore = auditTotal();

        assertThat(jobs.expireUnpaid()).isZero();

        assertThat(requestStatus(req)).isEqualTo("PENDING");
        assertThat(orderStatus(order)).isEqualTo("AWAITING_DEPOSIT");
        assertThat(quota(variant)).isEqualTo(3);
        assertThat(outboxTotal()).isEqualTo(outboxBefore);
        assertThat(auditTotal()).isEqualTo(auditBefore);
    }

    @Test
    void expireUnpaid_orderWithoutPaymentRequest_isNeverExpired() {
        long mto = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 1);
        long inStock = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        clock.advance(Duration.ofDays(365));

        assertThat(jobs.expireUnpaid()).isZero();

        assertThat(orderStatus(mto)).isEqualTo("AWAITING_DEPOSIT");
        assertThat(orderStatus(inStock)).isEqualTo("AWAITING_PAYMENT");
        assertThat(quota(variant)).isEqualTo(3);
        assertThat(stock()).isEqualTo(5);
    }

    @Test
    void expireUnpaid_overdueBalanceRequest_isNotTouched() {
        long order = order("MADE_TO_ORDER", "AWAITING_BALANCE", 1);
        long req = overdue(order, "BALANCE");

        assertThat(jobs.expireUnpaid()).isZero();

        assertThat(requestStatus(req)).isEqualTo("PENDING");
        assertThat(orderStatus(order)).isEqualTo("AWAITING_BALANCE");
    }

    @Test
    void expireUnpaid_cancelledRequest_isNotTouched() {
        long order = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 1);
        long req = overdue(order, "DEPOSIT");
        jdbc.update("UPDATE payment_request SET status = 'CANCELLED' WHERE id = ?", req);

        assertThat(jobs.expireUnpaid()).isZero();

        assertThat(requestStatus(req)).isEqualTo("CANCELLED");
        assertThat(orderStatus(order)).isEqualTo("AWAITING_DEPOSIT");
    }

    @Test
    void expireUnpaid_secondRun_changesNothingAndSendsNoDuplicateEmail() {
        long a = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 1);
        long b = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        overdue(a, "DEPOSIT");
        overdue(b, "FULL");

        assertThat(jobs.expireUnpaid()).isEqualTo(2);
        int outboxAfterFirst = outboxTotal();
        int auditAfterFirst = auditTotal();
        int quotaAfterFirst = quota(variant);
        int stockAfterFirst = stock();

        assertThat(jobs.expireUnpaid()).isZero();

        assertThat(outboxTotal()).isEqualTo(outboxAfterFirst);
        assertThat(auditTotal()).isEqualTo(auditAfterFirst);
        assertThat(quota(variant)).isEqualTo(quotaAfterFirst);
        assertThat(stock()).isEqualTo(stockAfterFirst);
        assertThat(outbox("ORDER_CANCELLED", a)).isEqualTo(1);
        assertThat(outbox("ORDER_CANCELLED", b)).isEqualTo(1);
    }

    @Test
    void expireUnpaid_oneFailingOrder_doesNotStopTheRest() {
        long badVariant = newVariant(5, 3);
        long goodVariant = newVariant(5, 3);
        long bad = orderOn("IN_STOCK", "AWAITING_PAYMENT", badVariant, 1);
        long good = orderOn("IN_STOCK", "AWAITING_PAYMENT", goodVariant, 1);
        // Earlier due_at, so the failing order is processed first.
        long badReq = dueRequest(bad, "FULL", clock.instant().minusSeconds(600));
        long goodReq = dueRequest(good, "FULL", clock.instant().minusSeconds(60));
        // Any variant update on the bad variant raises, which rolls back that order's transaction only.
        jdbc.execute("CREATE OR REPLACE FUNCTION t027_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                + " RAISE EXCEPTION 't027 forced failure'; END $$");
        jdbc.execute("CREATE TRIGGER t027_fail_trg BEFORE UPDATE ON product_variant FOR EACH ROW" + " WHEN (OLD.id = "
                + badVariant + ") EXECUTE FUNCTION t027_fail()");
        try {
            assertThat(jobs.expireUnpaid()).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER t027_fail_trg ON product_variant");
            jdbc.execute("DROP FUNCTION t027_fail()");
        }

        assertThat(orderStatus(good)).isEqualTo("CANCELLED");
        assertThat(requestStatus(goodReq)).isEqualTo("EXPIRED");
        assertThat(stock(goodVariant)).isEqualTo(6);
        assertThat(orderStatus(bad)).isEqualTo("AWAITING_PAYMENT");
        assertThat(requestStatus(badReq)).isEqualTo("PENDING");
        assertThat(stock(badVariant)).isEqualTo(5);
        assertThat(outbox("ORDER_CANCELLED", bad)).isZero();
        assertThat(auditCount("PAYMENT_REQUEST_EXPIRED", badReq)).isZero();
    }

    // ---- reminders ----

    private long balanceOrder(Duration untilDue) {
        long order = order("MADE_TO_ORDER", "AWAITING_BALANCE", 1);
        dueRequest(order, "BALANCE", clock.instant().plus(untilDue));
        return order;
    }

    @Test
    void remindBalance_eightDaysBeforeDue_sendsNothing() {
        long order = balanceOrder(Duration.ofDays(8));

        assertThat(jobs.remindBalance()).isZero();

        assertThat(reminders(order, "7d")).isZero();
        assertThat(reminders(order, "1d")).isZero();
    }

    @Test
    void remindBalance_sixDaysBeforeDue_sendsSevenDayReminderOnce() {
        long order = balanceOrder(Duration.ofDays(6));

        assertThat(jobs.remindBalance()).isEqualTo(1);
        assertThat(jobs.remindBalance()).isZero();

        assertThat(reminders(order, "7d")).isEqualTo(1);
        assertThat(reminders(order, "1d")).isZero();
        assertThat(outbox("ORDER_REMINDER", order)).isEqualTo(1);
    }

    @Test
    void remindBalance_twelveHoursBeforeDue_sendsOnlyOneDayReminder() {
        long order = balanceOrder(Duration.ofHours(12));

        assertThat(jobs.remindBalance()).isEqualTo(1);
        assertThat(jobs.remindBalance()).isZero();

        assertThat(reminders(order, "1d")).isEqualTo(1);
        assertThat(reminders(order, "7d")).isZero();
    }

    @Test
    void remindBalance_timePasses_sendsSevenDayThenOneDay() {
        long order = balanceOrder(Duration.ofDays(6));
        assertThat(jobs.remindBalance()).isEqualTo(1);

        clock.advance(Duration.ofDays(5).plusHours(12)); // 12 hours left
        assertThat(jobs.remindBalance()).isEqualTo(1);
        assertThat(jobs.remindBalance()).isZero();

        assertThat(reminders(order, "7d")).isEqualTo(1);
        assertThat(reminders(order, "1d")).isEqualTo(1);
    }

    @Test
    void remindBalance_replacementBalanceRequest_getsItsOwnReminder() {
        long order = order("MADE_TO_ORDER", "AWAITING_BALANCE", 1);
        long first = dueRequest(order, "BALANCE", clock.instant().plus(Duration.ofDays(6)));
        assertThat(jobs.remindBalance()).isEqualTo(1);
        assertThat(reminders(order, first, "7d")).isEqualTo(1);

        jdbc.update("UPDATE payment_request SET status = 'CANCELLED' WHERE id = ?", first);
        long second = dueRequest(order, "BALANCE", clock.instant().plus(Duration.ofDays(5)));

        assertThat(jobs.remindBalance()).isEqualTo(1);
        assertThat(jobs.remindBalance()).isZero();
        assertThat(reminders(order, second, "7d")).isEqualTo(1);
        assertThat(reminders(order, first, "7d")).isEqualTo(1);
    }

    @Test
    void remindBalance_afterDueDate_sendsNothing() {
        long order = balanceOrder(Duration.ofHours(-1));

        assertThat(jobs.remindBalance()).isZero();

        assertThat(outbox("ORDER_REMINDER", order)).isZero();
    }

    @Test
    void remindBalance_paidOrCancelledRequest_sendsNothing() {
        long paid = balanceOrder(Duration.ofDays(3));
        long cancelled = balanceOrder(Duration.ofDays(3));
        jdbc.update(
                "UPDATE payment_request SET status = 'PAID', confirmed_at = now(), external_ref = 'X-1'"
                        + " WHERE order_id = ?",
                paid);
        jdbc.update("UPDATE payment_request SET status = 'CANCELLED' WHERE order_id = ?", cancelled);

        assertThat(jobs.remindBalance()).isZero();

        assertThat(outbox("ORDER_REMINDER", paid)).isZero();
        assertThat(outbox("ORDER_REMINDER", cancelled)).isZero();
    }

    @Test
    void remindBalance_orderNotAwaitingBalance_sendsNothing() {
        long order = order("MADE_TO_ORDER", "IN_PRODUCTION", 1);
        dueRequest(order, "BALANCE", clock.instant().plus(Duration.ofDays(3)));

        assertThat(jobs.remindBalance()).isZero();
        assertThat(outbox("ORDER_REMINDER", order)).isZero();
    }

    @Test
    void remindBalance_payload_hasNoEmailAndNoToken() {
        long order = balanceOrder(Duration.ofDays(3));
        assertThat(jobs.remindBalance()).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT to_email, payload::text AS payload FROM email_outbox WHERE dedupe_key LIKE ?",
                "order:" + order + ":pr:%:balance-reminder-7d");
        String payload = (String) row.get("payload");

        assertThat(row.get("to_email")).isEqualTo(SECRET_EMAIL); // the address lives in its own column only
        assertThat(payload).doesNotContain(SECRET_EMAIL).doesNotContain(SECRET_STREET);
        assertThat(payload.toLowerCase()).doesNotContain("token").doesNotContain("@");
        assertThat(JsonPath.<String>read(payload, "$.paymentUrl")).startsWith("https://");
    }

    // ---- forfeit ----

    @Test
    void forfeitOverdue_overdueBalance_forfeitsOrderAndKeepsStockAndQuota() {
        long order = order("MADE_TO_ORDER", "AWAITING_BALANCE", 2);
        long req = overdue(order, "BALANCE");

        assertThat(jobs.forfeitOverdue()).isEqualTo(1);

        assertThat(requestStatus(req)).isEqualTo("EXPIRED");
        assertThat(orderStatus(order)).isEqualTo("FORFEITED");
        assertThat(outbox("ORDER_FORFEITED", order)).isEqualTo(1);
        assertThat(auditCount("PAYMENT_REQUEST_EXPIRED", req)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM customer_order WHERE id = ?", String.class, order))
                .isNotBlank();
        assertThat(stock()).isEqualTo(5);
        assertThat(quota(variant)).isEqualTo(3);
    }

    @Test
    void forfeitOverdue_awaitingBalanceWithoutBalanceRequest_isNeverForfeited() {
        long order = order("MADE_TO_ORDER", "AWAITING_BALANCE", 1);
        clock.advance(Duration.ofDays(365));

        assertThat(jobs.forfeitOverdue()).isZero();

        assertThat(orderStatus(order)).isEqualTo("AWAITING_BALANCE");
    }

    @Test
    void forfeitOverdue_notYetDue_changesNothing() {
        long order = order("MADE_TO_ORDER", "AWAITING_BALANCE", 1);
        long req = notYetDue(order, "BALANCE");

        assertThat(jobs.forfeitOverdue()).isZero();

        assertThat(requestStatus(req)).isEqualTo("PENDING");
        assertThat(orderStatus(order)).isEqualTo("AWAITING_BALANCE");
    }

    @Test
    void forfeitOverdue_secondRun_changesNothingAndSendsNoDuplicateEmail() {
        long order = order("MADE_TO_ORDER", "AWAITING_BALANCE", 1);
        overdue(order, "BALANCE");

        assertThat(jobs.forfeitOverdue()).isEqualTo(1);
        int outboxAfterFirst = outboxTotal();
        int auditAfterFirst = auditTotal();
        int versionAfterFirst = version(order);

        assertThat(jobs.forfeitOverdue()).isZero();

        assertThat(outboxTotal()).isEqualTo(outboxAfterFirst);
        assertThat(auditTotal()).isEqualTo(auditAfterFirst);
        assertThat(version(order)).isEqualTo(versionAfterFirst);
        assertThat(outbox("ORDER_FORFEITED", order)).isEqualTo(1);
    }

    @Test
    void forfeitOverdue_overdueDepositRequest_isNotTouched() {
        long order = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 1);
        long req = overdue(order, "DEPOSIT");

        assertThat(jobs.forfeitOverdue()).isZero();

        assertThat(requestStatus(req)).isEqualTo("PENDING");
        assertThat(orderStatus(order)).isEqualTo("AWAITING_DEPOSIT");
    }

    // ---- forfeited-to-stock endpoint ----

    @Test
    void forfeitedToStock_firstCall_addsStockOnceAndAudits() throws Exception {
        long order = order("MADE_TO_ORDER", "FORFEITED", 2);
        int variantVersionBefore = variantVersion(variant);

        act(order, ACTION, "version", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order))
                .andExpect(jsonPath("$.status").value("FORFEITED"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(stock()).isEqualTo(7);
        assertThat(variantVersion(variant)).isEqualTo(variantVersionBefore + 1);
        assertThat(version(order)).isEqualTo(1);
        assertThat(orderStatus(order)).isEqualTo("FORFEITED");
        assertThat(count("SELECT count(*) FROM customer_order WHERE id = ? AND restocked_at IS NOT NULL", order))
                .isEqualTo(1);
        assertThat(auditCount("ORDER_FORFEITED_TO_STOCK", order)).isEqualTo(1);
        Map<String, Object> after = JsonPath.read(auditAfter("ORDER_FORFEITED_TO_STOCK", order), "$");
        assertThat(after).containsOnlyKeys("itemCount");
        assertThat(after.get("itemCount")).isEqualTo(1);
    }

    @Test
    void forfeitedToStock_secondCall_conflictAndStockUnchanged() throws Exception {
        long order = order("MADE_TO_ORDER", "FORFEITED", 2);
        act(order, ACTION, "version", 0).andExpect(status().isOk());

        expectProblem(act(order, ACTION, "version", 1), 409, "CONFLICT");

        assertThat(stock()).isEqualTo(7);
        assertThat(version(order)).isEqualTo(1);
        assertThat(auditCount("ORDER_FORFEITED_TO_STOCK", order)).isEqualTo(1);
    }

    @Test
    void forfeitedToStock_orderNotForfeited_invalidTransition() throws Exception {
        long order = order("MADE_TO_ORDER", "AWAITING_BALANCE", 1);

        expectProblem(act(order, ACTION, "version", 0), 409, "INVALID_TRANSITION");

        assertThat(stock()).isEqualTo(5);
        assertThat(orderStatus(order)).isEqualTo("AWAITING_BALANCE");
    }

    @Test
    void forfeitedToStock_inStockOrder_invalidTransition() throws Exception {
        long order = order("IN_STOCK", "PAID_IN_FULL", 1);

        expectProblem(act(order, ACTION, "version", 0), 409, "INVALID_TRANSITION");

        assertThat(stock()).isEqualTo(5);
    }

    @Test
    void forfeitedToStock_staleVersion_versionConflict() throws Exception {
        long order = order("MADE_TO_ORDER", "FORFEITED", 1);

        expectProblem(act(order, ACTION, "version", 7), 409, "VERSION_CONFLICT");

        assertThat(stock()).isEqualTo(5);
        assertThat(version(order)).isZero();
    }

    @Test
    void forfeitedToStock_missingVersion_400() throws Exception {
        long order = order("MADE_TO_ORDER", "FORFEITED", 1);

        expectProblem(act(order, ACTION), 400, "VALIDATION_FAILED");

        assertThat(stock()).isEqualTo(5);
    }

    @Test
    void forfeitedToStock_unknownOrder_404() throws Exception {
        expectProblem(act(UNKNOWN_ID, ACTION, "version", 0), 404, "NOT_FOUND");
    }

    @Test
    void forfeitedToStock_unauthenticated_401() throws Exception {
        long order = order("MADE_TO_ORDER", "FORFEITED", 1);

        mvc.perform(withCsrf(
                        HttpMethod.POST, "/api/admin/orders/" + order + "/" + ACTION, j("version", 0), null, nextIp()))
                .andExpect(status().isUnauthorized());

        assertThat(stock()).isEqualTo(5);
    }

    @Test
    void forfeitedToStock_missingCsrf_403() throws Exception {
        long order = order("MADE_TO_ORDER", "FORFEITED", 1);

        mvc.perform(MockMvcRequestBuilders.post("/api/admin/orders/" + order + "/" + ACTION)
                        .session(session)
                        .with(r -> {
                            r.setRemoteAddr(nextIp());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(j("version", 0)))
                .andExpect(status().isForbidden());

        assertThat(stock()).isEqualTo(5);
        assertThat(version(order)).isZero();
    }

    @Test
    void forfeitedToStock_concurrentDoubleCall_exactlyOneWinsAndStockAddedOnce() throws Exception {
        long order = order("MADE_TO_ORDER", "FORFEITED", 2);

        List<Res> r = concurrently(List.<Callable<Res>>of(
                () -> res(act(order, ACTION, "version", 0)), () -> res(act(order, ACTION, "version", 0))));

        assertThat(r.stream().filter(x -> x.status() == 200).count())
                .as(r.toString())
                .isEqualTo(1);
        Res loser = r.stream().filter(x -> x.status() != 200).findFirst().orElseThrow();
        assertThat(loser.status()).isEqualTo(409);
        assertThat(List.of("CONFLICT", "VERSION_CONFLICT")).contains(loser.code());
        assertThat(stock()).isEqualTo(7);
        assertThat(version(order)).isEqualTo(1);
        assertThat(auditCount("ORDER_FORFEITED_TO_STOCK", order)).isEqualTo(1);
    }
}
