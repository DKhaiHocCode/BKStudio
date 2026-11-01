package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import com.bkstudio.order.state.OrderStatus;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T-034 admin dashboard over HTTP against real Postgres. Clock is 2030-01-01T00:00:10Z. */
class AdminDashboardIT extends AdminOrderTestBase {

    private static final List<String> KEYS = List.of(
            "AWAITING_LINK",
            "PAYMENT_TO_CONFIRM",
            "PAYMENT_EXPIRING",
            "READY_TO_PRODUCE",
            "IN_PRODUCTION",
            "AWAITING_BALANCE_LINK",
            "READY_TO_SHIP",
            "SHIPPED_TO_COMPLETE",
            "FORFEITED_TO_RESTOCK",
            "EMAIL_FAILED");

    // ---- helpers ----

    private String dash() throws Exception {
        return body(fetch("/api/admin/dashboard").andExpect(status().isOk()));
    }

    private static long count(String json, String key) {
        List<Integer> c = JsonPath.read(json, "$.groups[?(@.key=='" + key + "')].count");
        return c.get(0);
    }

    private static List<Long> ids(String json, String key) {
        List<Number> n = JsonPath.read(json, "$.groups[?(@.key=='" + key + "')].items[*].id");
        return n.stream().map(Number::longValue).toList();
    }

    private static List<Map<String, Object>> items(String json, String key) {
        List<List<Map<String, Object>>> l = JsonPath.read(json, "$.groups[?(@.key=='" + key + "')].items");
        return l.get(0);
    }

    private long pendingDue(long orderId, String type, Duration fromNow) {
        return jdbc.queryForObject(
                "INSERT INTO payment_request (order_id, type, amount_cents, channel, payment_url, due_at)"
                        + " VALUES (?, ?, 1000, 'ARTISTREE', 'https://artistree.io/p', ?) RETURNING id",
                Long.class,
                orderId,
                type,
                Timestamp.from(START.plus(fromNow)));
    }

    private long failedMail(String recipient) {
        return outboxRow("FAILED", recipient);
    }

    private long outboxRow(String status, String recipient) {
        return jdbc.queryForObject(
                "INSERT INTO email_outbox (dedupe_key, to_email, template, payload, status)"
                        + " VALUES (?, ?, 'ORDER_CONFIRMED', '{\"secretPayload\":\"zzz\"}'::jsonb, ?) RETURNING id",
                Long.class,
                "k:" + UUID.randomUUID(),
                recipient,
                status);
    }

    // ---- tests ----

    @Test
    void unauthenticated_isRejected_andAdminSessionIsOk() throws Exception {
        publicGet("/api/admin/dashboard").andExpect(status().isUnauthorized());
        fetch("/api/admin/dashboard").andExpect(status().isOk());
    }

    @Test
    void emptyDatabase_hasAllTenGroupsInOrder_withZeroCounts() throws Exception {
        String json = dash();
        List<String> keys = JsonPath.read(json, "$.groups[*].key");
        assertThat(keys).containsExactlyElementsOf(KEYS);
        for (String k : KEYS) {
            assertThat(count(json, k)).as(k).isZero();
            assertThat(items(json, k)).as(k).isEmpty();
        }
        assertThat(JsonPath.<String>read(json, "$.generatedAt")).isEqualTo(START.toString());
    }

    @Test
    void awaitingLink_onlyOrdersWithoutPendingRequest() throws Exception {
        long depositNoReq = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 1);
        long paymentNoReq = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        long withReq = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        pendingDue(withReq, "FULL", Duration.ofDays(3));
        long expiredReq = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 1);
        long done = pendingDue(expiredReq, "DEPOSIT", Duration.ofDays(3));
        jdbc.update("UPDATE payment_request SET status = 'EXPIRED' WHERE id = ?", done);

        String json = dash();
        assertThat(count(json, "AWAITING_LINK")).isEqualTo(3);
        assertThat(ids(json, "AWAITING_LINK")).containsExactlyInAnyOrder(depositNoReq, paymentNoReq, expiredReq);
        assertThat(ids(json, "AWAITING_LINK")).doesNotContain(withReq);
        assertThat(count(json, "PAYMENT_TO_CONFIRM")).isEqualTo(1);
    }

    @Test
    void pendingRequests_splitBetweenToConfirmAndExpiringByDueDate() throws Exception {
        long soon = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        pendingDue(soon, "FULL", Duration.ofHours(1));
        long later = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        pendingDue(later, "FULL", Duration.ofDays(3));
        long overdue = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 1);
        pendingDue(overdue, "DEPOSIT", Duration.ofHours(-5));

        String json = dash();
        assertThat(count(json, "PAYMENT_TO_CONFIRM")).isEqualTo(3);
        // sorted by due date: overdue, soon, later
        assertThat(ids(json, "PAYMENT_TO_CONFIRM")).containsExactly(overdue, soon, later);
        assertThat(count(json, "PAYMENT_EXPIRING")).isEqualTo(2);
        assertThat(ids(json, "PAYMENT_EXPIRING")).containsExactly(overdue, soon);
        assertThat(count(json, "AWAITING_LINK")).isZero();
        assertThat(items(json, "PAYMENT_EXPIRING").get(1).get("dueAt"))
                .isEqualTo(START.plus(Duration.ofHours(1)).toString());
    }

    @Test
    void paymentExpiring_boundaryAt24Hours() throws Exception {
        long exact = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        pendingDue(exact, "FULL", Duration.ofHours(24));
        long justInside = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        pendingDue(justInside, "FULL", Duration.ofHours(24).minusSeconds(1));

        String json = dash();
        assertThat(ids(json, "PAYMENT_EXPIRING")).containsExactly(justInside);
        assertThat(ids(json, "PAYMENT_TO_CONFIRM")).containsExactlyInAnyOrder(exact, justInside);
    }

    @Test
    void emailFailed_capsItemsAt20_keepsExactCount() throws Exception {
        for (int i = 0; i < 25; i++) {
            failedMail("r" + i + "@example.com");
        }
        String json = dash();
        assertThat(count(json, "EMAIL_FAILED")).isEqualTo(25);
        assertThat(items(json, "EMAIL_FAILED")).hasSize(20);
    }

    @Test
    void statusGroups_holdExactlyTheirOrders() throws Exception {
        long queued = order("MADE_TO_ORDER", "QUEUED", 1);
        long inProd = order("MADE_TO_ORDER", "IN_PRODUCTION", 1);
        long paidMto = order("MADE_TO_ORDER", "PAID_IN_FULL", 1);
        long paidStock = order("IN_STOCK", "PAID_IN_FULL", 1);
        long shipped = order("IN_STOCK", "SHIPPED", 1);
        order("IN_STOCK", "COMPLETED", 1);
        order("MADE_TO_ORDER", "CANCELLED_DEPOSIT_KEPT", 1);

        String json = dash();
        assertThat(ids(json, "READY_TO_PRODUCE")).containsExactly(queued);
        assertThat(ids(json, "IN_PRODUCTION")).containsExactly(inProd);
        assertThat(ids(json, "READY_TO_SHIP")).containsExactlyInAnyOrder(paidMto, paidStock);
        assertThat(ids(json, "SHIPPED_TO_COMPLETE")).containsExactly(shipped);
        assertThat(count(json, "READY_TO_SHIP")).isEqualTo(2);
        assertThat(count(json, "AWAITING_LINK")).isZero();
        assertThat(count(json, "FORFEITED_TO_RESTOCK")).isZero();
    }

    @Test
    void awaitingBalance_withAndWithoutPendingRequest() throws Exception {
        long noReq = order("MADE_TO_ORDER", "AWAITING_BALANCE", 1);
        long withReq = order("MADE_TO_ORDER", "AWAITING_BALANCE", 1);
        pendingDue(withReq, "BALANCE", Duration.ofDays(3));

        String json = dash();
        assertThat(ids(json, "AWAITING_BALANCE_LINK")).containsExactly(noReq);
        assertThat(count(json, "AWAITING_BALANCE_LINK")).isEqualTo(1);
        assertThat(ids(json, "PAYMENT_TO_CONFIRM")).containsExactly(withReq);
        assertThat(count(json, "AWAITING_LINK")).isZero();
    }

    @Test
    void forfeitedToRestock_onlyMtoNotYetRestocked() throws Exception {
        long open = order("MADE_TO_ORDER", "FORFEITED", 1);
        long restocked = order("MADE_TO_ORDER", "FORFEITED", 1);
        jdbc.update("UPDATE customer_order SET restocked_at = now() WHERE id = ?", restocked);

        String json = dash();
        assertThat(ids(json, "FORFEITED_TO_RESTOCK")).containsExactly(open);
        assertThat(count(json, "FORFEITED_TO_RESTOCK")).isEqualTo(1);
    }

    @Test
    void emailFailed_countsOnlyFailedOutboxRows_oldestFirst() throws Exception {
        long first = failedMail("a@example.com");
        outboxRow("PENDING", "b@example.com");
        outboxRow("SENT", "c@example.com");
        long second = failedMail("d@example.com");
        jdbc.update("UPDATE email_outbox SET created_at = now() - interval '1 day' WHERE id = ?", first);

        String json = dash();
        assertThat(count(json, "EMAIL_FAILED")).isEqualTo(2);
        assertThat(ids(json, "EMAIL_FAILED")).containsExactly(first, second);
        assertThat(items(json, "EMAIL_FAILED").get(0).get("status")).isEqualTo("ORDER_CONFIRMED");
    }

    @Test
    void everyOrderWithAnAdminAction_appearsInAtLeastOneGroup() throws Exception {
        Map<Long, String> seeded = new java.util.LinkedHashMap<>();
        for (boolean withPending : new boolean[] {false, true}) {
            for (FulfillmentType type : FulfillmentType.values()) {
                for (OrderStatus st : OrderStatus.values()) {
                    boolean awaiting = st.name().startsWith("AWAITING_");
                    if (!validCombination(type, st) || (withPending && !awaiting)) {
                        continue;
                    }
                    long id = order(type.name(), st.name(), 1);
                    if (withPending) {
                        String reqType = st == OrderStatus.AWAITING_DEPOSIT
                                ? "DEPOSIT"
                                : st == OrderStatus.AWAITING_BALANCE ? "BALANCE" : "FULL";
                        pendingDue(id, reqType, Duration.ofDays(3));
                    }
                    seeded.put(id, type + " " + st + (withPending ? " +pending" : ""));
                }
            }
        }
        String json = dash();
        Set<Long> covered = new java.util.HashSet<>();
        for (String k : KEYS) {
            if (!k.equals("EMAIL_FAILED")) {
                covered.addAll(ids(json, k));
            }
        }
        int withActions = 0;
        for (var e : seeded.entrySet()) {
            List<String> actions = JsonPath.read(
                    body(fetch("/api/admin/orders/" + e.getKey()).andExpect(status().isOk())), "$.allowedActions");
            if (!actions.isEmpty()) {
                withActions++;
                assertThat(covered).as(e.getValue() + " has actions " + actions).contains(e.getKey());
            }
        }
        assertThat(withActions).isPositive();
    }

    @Test
    void group_capsItemsAt20_keepsExactCount_oldestFirst() throws Exception {
        Instant base = START.minus(Duration.ofDays(1));
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            long id = order("MADE_TO_ORDER", "QUEUED", 1);
            jdbc.update(
                    "UPDATE customer_order SET created_at = ? WHERE id = ?",
                    Timestamp.from(base.plus(Duration.ofMinutes(i))),
                    id);
            created.add(id);
        }
        String json = dash();
        assertThat(count(json, "READY_TO_PRODUCE")).isEqualTo(25);
        assertThat(ids(json, "READY_TO_PRODUCE")).containsExactlyElementsOf(created.subList(0, 20));
    }

    @Test
    void response_exposesNoCustomerOrOutboxData_andItemKeysAreWhitelisted() throws Exception {
        order("IN_STOCK", "AWAITING_PAYMENT", 1);
        long paid = order("IN_STOCK", "PAID_IN_FULL", 1);
        pendingDue(order("IN_STOCK", "AWAITING_PAYMENT", 1), "FULL", Duration.ofHours(1));
        failedMail("leak.recipient@example.com");

        String json = dash();
        assertThat(json)
                .doesNotContain(SECRET_EMAIL)
                .doesNotContain("Secret Road")
                .doesNotContain("Buyer")
                .doesNotContain("Singapore")
                .doesNotContain("leak.recipient")
                .doesNotContain("secretPayload")
                .doesNotContain("toEmail")
                .doesNotContain("payload");
        Set<String> allowed = Set.of("id", "publicCode", "status", "fulfillmentType", "dueAt", "createdAt");
        for (String k : KEYS) {
            for (Map<String, Object> item : items(json, k)) {
                assertThat(item.keySet()).as(k).isSubsetOf(allowed);
            }
        }
        assertThat(items(json, "READY_TO_SHIP").get(0).keySet()).contains("id", "publicCode", "status", "createdAt");
        assertThat(ids(json, "READY_TO_SHIP")).containsExactly(paid);
        Map<String, Object> mail = items(json, "EMAIL_FAILED").get(0);
        assertThat(mail.keySet()).contains("id", "status", "createdAt");
        assertThat(mail.get("publicCode")).isNull();
        assertThat(mail.get("fulfillmentType")).isNull();
        assertThat(mail.get("dueAt")).isNull();
    }

    private static boolean validCombination(FulfillmentType type, OrderStatus st) {
        List<OrderStatus> mtoOnly = List.of(
                OrderStatus.AWAITING_DEPOSIT,
                OrderStatus.QUEUED,
                OrderStatus.IN_PRODUCTION,
                OrderStatus.AWAITING_BALANCE,
                OrderStatus.CANCELLED_DEPOSIT_KEPT,
                OrderStatus.FORFEITED);
        return type == FulfillmentType.IN_STOCK ? !mtoOnly.contains(st) : st != OrderStatus.AWAITING_PAYMENT;
    }
}
