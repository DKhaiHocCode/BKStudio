package com.bkstudio.admin.auth;

import static com.bkstudio.queue.QueueFixtures.setMonth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import com.bkstudio.order.state.OrderAction;
import com.bkstudio.order.state.OrderStatus;
import com.bkstudio.order.state.OrderTransitions;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * T-026 admin order API over HTTP against real Postgres: list, detail (allowedActions), the five actions, cancel
 * side effects and note. Concurrency lives in {@link AdminOrderConcurrencyIT}. Clock is 2030-01-01T00:00:10Z.
 */
class AdminOrderApiIT extends AdminOrderTestBase {

    private static final List<String> ACTIONS =
            List.of("start-production", "mark-finished", "ship", "complete", "cancel-by-shop");

    // ---- action fixtures ----

    /** An order in the one state where {@code action} is valid. */
    private long validOrder(String action) {
        return switch (action) {
            case "start-production" -> order("MADE_TO_ORDER", "QUEUED", 1);
            case "mark-finished" -> order("MADE_TO_ORDER", "IN_PRODUCTION", 1);
            case "ship" -> order("IN_STOCK", "PAID_IN_FULL", 1);
            case "complete" -> order("IN_STOCK", "SHIPPED", 1);
            default -> order("IN_STOCK", "AWAITING_PAYMENT", 1);
        };
    }

    private static Object[] extras(String action) {
        return switch (action) {
            case "ship" -> new Object[] {"carrier", "DHL", "trackingNumber", "T1"};
            case "cancel-by-shop" -> new Object[] {"reason", "no stock"};
            default -> new Object[0];
        };
    }

    /** Valid body for the action with the given version; a null version leaves the key out. */
    private ResultActions run(long id, String action, Integer version) throws Exception {
        List<Object> kv = new ArrayList<>();
        if (version != null) {
            kv.add("version");
            kv.add(version);
        }
        kv.addAll(Arrays.asList(extras(action)));
        return act(id, action, kv.toArray());
    }

    // ================= list and detail =================

    @Test
    void list_hasNoContactData_andDetailHasAllowedActions() throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 1);
        String code = jdbc.queryForObject("SELECT public_code FROM customer_order WHERE id = ?", String.class, id);

        ResultActions list = fetch("/api/admin/orders?status=PAID_IN_FULL&q=" + code.toLowerCase())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
        assertThat(body(list)).doesNotContain(SECRET_EMAIL).doesNotContain("Secret Road");

        fetch("/api/admin/orders/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.email").value(SECRET_EMAIL))
                .andExpect(jsonPath("$.items[0].quantity").value(1))
                .andExpect(jsonPath("$.allowedActions[0]").value("ship"));
        expectProblem(fetch("/api/admin/orders/" + UNKNOWN_ID), 404, "NOT_FOUND");
    }

    @Test
    void allowedActions_matchTransitionTable_forEveryTypeAndStatus() throws Exception {
        int checked = 0;
        for (FulfillmentType type : FulfillmentType.values()) {
            for (OrderStatus st : OrderStatus.values()) {
                if (!validCombination(type, st)) {
                    continue;
                }
                long id = order(type.name(), st.name(), 1);
                List<String> actual =
                        read(fetch("/api/admin/orders/" + id).andExpect(status().isOk()), "$.allowedActions");
                assertThat(actual).as("%s %s", type, st).containsExactlyInAnyOrderElementsOf(expectedActions(type, st));
                if (st.isTerminal()) {
                    assertThat(actual).as("terminal %s %s", type, st).isEmpty();
                }
                checked++;
            }
        }
        assertThat(checked).isEqualTo(6 + 11); // 6 IN_STOCK + 11 MADE_TO_ORDER statuses (chk_order_*_status)
    }

    @Test
    void allowedActions_literalSpotChecks() throws Exception {
        assertThat(allowed(order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 1)))
                .containsExactlyInAnyOrder("cancel-by-shop", "create-payment-request");
        assertThat(allowed(order("MADE_TO_ORDER", "QUEUED", 1)))
                .containsExactlyInAnyOrder("start-production", "cancel-by-shop");
        assertThat(allowed(order("MADE_TO_ORDER", "IN_PRODUCTION", 1)))
                .containsExactlyInAnyOrder("mark-finished", "cancel-by-shop");
        assertThat(allowed(order("MADE_TO_ORDER", "AWAITING_BALANCE", 1))).containsExactly("create-payment-request");
        assertThat(allowed(order("MADE_TO_ORDER", "PAID_IN_FULL", 1))).containsExactly("ship");
        assertThat(allowed(order("IN_STOCK", "PAID_IN_FULL", 1))).containsExactlyInAnyOrder("ship", "cancel-by-shop");
        assertThat(allowed(order("IN_STOCK", "COMPLETED", 1))).isEmpty();
        assertThat(allowed(order("IN_STOCK", "CANCELLED_REFUNDED", 1))).isEmpty();
    }

    private List<String> allowed(long id) throws Exception {
        return read(fetch("/api/admin/orders/" + id), "$.allowedActions");
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

    private static List<String> expectedActions(FulfillmentType type, OrderStatus st) {
        List<String> out = new ArrayList<>();
        addIf(out, "start-production", type, st, OrderAction.START_PRODUCTION);
        addIf(out, "mark-finished", type, st, OrderAction.COMPLETE_PRODUCTION);
        addIf(out, "ship", type, st, OrderAction.SHIP);
        addIf(out, "complete", type, st, OrderAction.COMPLETE);
        if (OrderTransitions.next(type, st, OrderAction.CANCEL).isPresent()
                || OrderTransitions.next(type, st, OrderAction.CANCEL_REFUND).isPresent()) {
            out.add("cancel-by-shop");
        }
        if (Stream.of(OrderAction.CONFIRM_DEPOSIT, OrderAction.CONFIRM_PAYMENT, OrderAction.CONFIRM_BALANCE)
                .anyMatch(a -> OrderTransitions.next(type, st, a).isPresent())) {
            out.add("create-payment-request");
        }
        return out;
    }

    private static void addIf(List<String> out, String name, FulfillmentType t, OrderStatus s, OrderAction a) {
        if (OrderTransitions.next(t, s, a).isPresent()) {
            out.add(name);
        }
    }

    @Test
    void detail_showsRequestsAndTimestamps_neverTokens() throws Exception {
        long id = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 2);
        long req = pendingRequest(id, "DEPOSIT");
        ResultActions r = fetch("/api/admin/orders/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentRequests.length()").value(1))
                .andExpect(jsonPath("$.paymentRequests[0].id").value(req))
                .andExpect(jsonPath("$.paymentRequests[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.version").value(0));
        assertThat(body(r)).doesNotContainIgnoringCase("token").doesNotContainIgnoringCase("hash");
    }

    @Test
    void list_statusFilter_returnsOnlyThatStatus() throws Exception {
        order("IN_STOCK", "AWAITING_PAYMENT", 1);
        long paid = order("IN_STOCK", "PAID_IN_FULL", 1);
        fetch("/api/admin/orders?status=PAID_IN_FULL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(paid))
                .andExpect(jsonPath("$.items[0].status").value("PAID_IN_FULL"));
        fetch("/api/admin/orders").andExpect(jsonPath("$.total").value(2));
        expectProblem(fetch("/api/admin/orders?status=NOPE"), 400, "VALIDATION_FAILED");
    }

    @Test
    void list_q_matchesPublicCodeExactly_ignoringCase() throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 1);
        order("IN_STOCK", "PAID_IN_FULL", 1);
        String code = jdbc.queryForObject("SELECT public_code FROM customer_order WHERE id = ?", String.class, id);
        for (String q : List.of(code, code.toLowerCase(), "  " + code + " ")) {
            fetch("/api/admin/orders?q=" + q)
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(id));
        }
        fetch("/api/admin/orders?q=" + code.substring(0, code.length() - 1))
                .andExpect(jsonPath("$.total").value(0));
        fetch("/api/admin/orders?q=BK").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void list_paging_pagesOf20_newestFirst_andBeyondRangeIsEmpty() throws Exception {
        long first = 0;
        long last = 0;
        for (int i = 0; i < 21; i++) {
            last = order("IN_STOCK", "AWAITING_PAYMENT", 1);
            first = i == 0 ? last : first;
        }
        fetch("/api/admin/orders?page=0")
                .andExpect(jsonPath("$.total").value(21))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].id").value(last));
        fetch("/api/admin/orders?page=1")
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first));
        fetch("/api/admin/orders?page=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(21));
        expectProblem(fetch("/api/admin/orders?page=-1"), 400, "VALIDATION_FAILED");
        expectProblem(fetch("/api/admin/orders?page=10001"), 400, "VALIDATION_FAILED");
    }

    @Test
    void list_hasNoCustomerFields() throws Exception {
        order("IN_STOCK", "PAID_IN_FULL", 1);
        String json = body(fetch("/api/admin/orders").andExpect(status().isOk()));
        assertThat(json)
                .doesNotContain(SECRET_EMAIL)
                .doesNotContain("Secret Road")
                .doesNotContain("Buyer")
                .doesNotContain("Singapore")
                .doesNotContain("customer");
    }

    // ================= every action: common cases =================

    @ParameterizedTest
    @CsvSource({
        "start-production,IN_PRODUCTION",
        "mark-finished,AWAITING_BALANCE",
        "ship,SHIPPED",
        "complete,COMPLETED",
        "cancel-by-shop,CANCELLED"
    })
    void action_happyPath_returnsNewStatusAndVersion(String action, String expected) throws Exception {
        long id = validOrder(action);
        run(id, action, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(orderStatus(id)).isEqualTo(expected);
        assertThat(version(id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"start-production", "mark-finished", "ship", "complete", "cancel-by-shop"})
    void action_staleVersion_409VersionConflict_changesNothing(String action) throws Exception {
        long id = validOrder(action);
        String before = orderStatus(id);
        int stockBefore = stock();
        expectProblem(run(id, action, 7), 409, "VERSION_CONFLICT");
        assertThat(orderStatus(id)).isEqualTo(before);
        assertThat(version(id)).isZero();
        assertThat(stock()).isEqualTo(stockBefore);
        assertThat(count("SELECT count(*) FROM email_outbox")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"start-production", "mark-finished", "ship", "complete", "cancel-by-shop"})
    void action_missingVersion_400ValidationFailed(String action) throws Exception {
        long id = validOrder(action);
        expectField(run(id, action, null), "version");
        expectProblem(act(id, action), 400, "VALIDATION_FAILED");
        assertThat(version(id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"start-production", "mark-finished", "ship", "complete", "cancel-by-shop"})
    void action_wrongStatus_409InvalidTransition_changesNothing(String action) throws Exception {
        long id = order("IN_STOCK", "COMPLETED", 1);
        int stockBefore = stock();
        expectProblem(run(id, action, 0), 409, "INVALID_TRANSITION");
        assertThat(orderStatus(id)).isEqualTo("COMPLETED");
        assertThat(version(id)).isZero();
        assertThat(stock()).isEqualTo(stockBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"start-production", "mark-finished", "ship", "complete", "cancel-by-shop"})
    void action_unknownOrder_404(String action) throws Exception {
        expectProblem(run(UNKNOWN_ID, action, 0), 404, "NOT_FOUND");
    }

    @Test
    void endpoints_unauthenticated_401() throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 1);
        for (String action : ACTIONS) {
            mvc.perform(withCsrf(
                            HttpMethod.POST, "/api/admin/orders/" + id + "/" + action, j("version", 0), null, nextIp()))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(withCsrf(
                        HttpMethod.PATCH, "/api/admin/orders/" + id + "/note", j("adminNote", "x"), null, nextIp()))
                .andExpect(status().isUnauthorized());
        mvc.perform(getFrom("/api/admin/orders", null, nextIp())).andExpect(status().isUnauthorized());
        mvc.perform(getFrom("/api/admin/orders/" + id, null, nextIp())).andExpect(status().isUnauthorized());
        assertThat(orderStatus(id)).isEqualTo("PAID_IN_FULL");
        assertThat(version(id)).isZero();
    }

    @Test
    void endpoints_missingCsrf_403() throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 1);
        for (String action : ACTIONS) {
            mvc.perform(MockMvcRequestBuilders.post("/api/admin/orders/" + id + "/" + action)
                            .session(session)
                            .with(r -> {
                                r.setRemoteAddr(nextIp());
                                return r;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(j("version", 0, "carrier", "DHL", "trackingNumber", "T1", "reason", "x")))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(MockMvcRequestBuilders.patch("/api/admin/orders/" + id + "/note")
                        .session(session)
                        .with(r -> {
                            r.setRemoteAddr(nextIp());
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(j("adminNote", "x")))
                .andExpect(status().isForbidden());
        assertThat(orderStatus(id)).isEqualTo("PAID_IN_FULL");
        assertThat(version(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT admin_note FROM customer_order WHERE id = ?", String.class, id))
                .isNull();
    }

    // ================= start-production, mark-finished, complete =================

    @Test
    void startProduction_movesQueuedToInProduction_andAudits() throws Exception {
        long id = order("MADE_TO_ORDER", "QUEUED", 1);
        act(id, "start-production", "version", 0).andExpect(jsonPath("$.status").value("IN_PRODUCTION"));
        assertThat(outbox("ORDER_PRODUCTION_STARTED", id)).isEqualTo(1);
        assertThat(auditCount("ORDER_TRANSITION", id)).isEqualTo(1);
    }

    @Test
    void markFinished_movesToAwaitingBalance_andCreatesNoPaymentRequest() throws Exception {
        long id = order("MADE_TO_ORDER", "IN_PRODUCTION", 1);
        act(id, "mark-finished", "version", 0, "photoImageIds", List.of(1, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_BALANCE"));
        assertThat(count("SELECT count(*) FROM payment_request WHERE order_id = ?", id))
                .isZero();
        assertThat(outbox("ORDER_BALANCE_REQUESTED", id)).isEqualTo(1);
    }

    @Test
    void complete_setsCompletedAt() throws Exception {
        long id = order("IN_STOCK", "SHIPPED", 1);
        assertThat(jdbc.queryForObject("SELECT completed_at FROM customer_order WHERE id = ?", Timestamp.class, id))
                .isNull();
        clock.advance(Duration.ofMinutes(10));
        act(id, "complete", "version", 0).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(jdbc.queryForObject("SELECT completed_at FROM customer_order WHERE id = ?", Timestamp.class, id)
                        .toInstant())
                .isEqualTo(START.plus(Duration.ofMinutes(10)));
        assertThat(outbox("ORDER_COMPLETED", id)).isEqualTo(1);
    }

    // ================= ship =================

    @Test
    void ship_persistsCarrierAndTracking_andRequiresVersion() throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 1);
        expectProblem(act(id, "ship", "carrier", "DHL", "trackingNumber", "T1"), 400, "VALIDATION_FAILED");
        expectProblem(
                act(id, "ship", "version", version(id), "carrier", "", "trackingNumber", "T1"),
                400,
                "VALIDATION_FAILED");
        act(id, "ship", "version", 0, "carrier", " DHL ", "trackingNumber", "T1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(jdbc.queryForMap("SELECT carrier, tracking_number FROM customer_order WHERE id = ?", id))
                .containsEntry("carrier", "DHL")
                .containsEntry("tracking_number", "T1");
        expectProblem(act(id, "ship", "version", 0, "carrier", "DHL", "trackingNumber", "T1"), 409, "VERSION_CONFLICT");
        act(id, "complete", "version", 1).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void ship_setsShippedAtFromClock_andQueuesShippedEmail() throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 1);
        clock.advance(Duration.ofMinutes(5));
        act(id, "ship", "version", 0, "carrier", "SingPost", "trackingNumber", "RR123SG")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT shipped_at FROM customer_order WHERE id = ?", Timestamp.class, id)
                        .toInstant())
                .isEqualTo(START.plus(Duration.ofMinutes(5)));
        assertThat(jdbc.queryForMap(
                        "SELECT to_email, template, status FROM email_outbox WHERE dedupe_key = ?",
                        "order:" + id + ":SHIP:1"))
                .containsEntry("to_email", SECRET_EMAIL)
                .containsEntry("template", "ORDER_SHIPPED")
                .containsEntry("status", "PENDING");
        assertThat(auditCount("ORDER_TRANSITION", id)).isEqualTo(1);
    }

    @Test
    void ship_acceptsMaximumLengths() throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 1);
        act(id, "ship", "version", 0, "carrier", "c".repeat(50), "trackingNumber", "t".repeat(100))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT length(carrier) FROM customer_order WHERE id = ?", Integer.class, id))
                .isEqualTo(50);
    }

    static Stream<Arguments> badShipFields() {
        return Stream.of(
                Arguments.of(null, "T1", "carrier"),
                Arguments.of("", "T1", "carrier"),
                Arguments.of("   ", "T1", "carrier"),
                Arguments.of("c".repeat(51), "T1", "carrier"),
                Arguments.of("DH\u0007L", "T1", "carrier"),
                Arguments.of("DH\tL", "T1", "carrier"),
                Arguments.of("DHL", null, "trackingNumber"),
                Arguments.of("DHL", "", "trackingNumber"),
                Arguments.of("DHL", "   ", "trackingNumber"),
                Arguments.of("DHL", "t".repeat(101), "trackingNumber"),
                Arguments.of("DHL", "T\u00001", "trackingNumber"),
                Arguments.of("DHL", "T\n1", "trackingNumber"));
    }

    @ParameterizedTest
    @MethodSource("badShipFields")
    void ship_invalidCarrierOrTracking_400WithFieldName_changesNothing(String carrier, String tracking, String field)
            throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 1);
        expectField(act(id, "ship", "version", 0, "carrier", carrier, "trackingNumber", tracking), field);
        assertThat(orderStatus(id)).isEqualTo("PAID_IN_FULL");
        assertThat(version(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT carrier FROM customer_order WHERE id = ?", String.class, id))
                .isNull();
    }

    @Test
    void ship_madeToOrderPaidInFull_works() throws Exception {
        long id = order("MADE_TO_ORDER", "PAID_IN_FULL", 1);
        act(id, "ship", "version", 0, "carrier", "DHL", "trackingNumber", "T9")
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    // ================= cancel-by-shop: statuses and validation =================

    @Test
    void cancelInStock_cancelsPendingRequest_restoresStock() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 2);
        jdbc.update("UPDATE product_variant SET stock_qty = 3 WHERE id = ?", variant);
        long req = pendingRequest(id, "FULL");
        act(id, "cancel-by-shop", "version", 0, "reason", "out of stock", "refundRef", "ignored")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stock()).isEqualTo(5);
        assertThat(requestStatus(req)).isEqualTo("CANCELLED");
        assertThat(auditAfter("ORDER_CANCELLED_BY_SHOP", id))
                .doesNotContain("ignored")
                .doesNotContain("out of stock")
                .contains("\"reasonLength\": 12");
    }

    @Test
    void cancelQueuedMto_needsRefundRef_doesNotRestoreQuota() throws Exception {
        long id = order("MADE_TO_ORDER", "QUEUED", 2);
        jdbc.update("UPDATE product_variant SET made_to_order_quota = 1 WHERE id = ?", variant);
        expectField(act(id, "cancel-by-shop", "version", 0, "reason", "sorry"), "refundRef");
        assertThat(orderStatus(id)).isEqualTo("QUEUED");
        assertThat(quota(variant)).isEqualTo(1);

        act(id, "cancel-by-shop", "version", 0, "reason", "sorry", "refundRef", "RF-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED_REFUNDED"));
        assertThat(quota(variant)).isEqualTo(1); // refund cancel: quota is adjusted by hand
        assertThat(auditAfter("ORDER_CANCELLED_BY_SHOP", id)).contains("RF-1");
    }

    @Test
    void cancelFromShipped_isInvalidTransition_andChangesNothing() throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 1);
        act(id, "ship", "version", 0, "carrier", "DHL", "trackingNumber", "T1").andExpect(status().isOk());
        int stockBefore = stock();
        expectProblem(act(id, "cancel-by-shop", "version", 1, "reason", "x"), 409, "INVALID_TRANSITION");
        assertThat(stock()).isEqualTo(stockBefore);
        assertThat(orderStatus(id)).isEqualTo("SHIPPED");
    }

    @ParameterizedTest
    @CsvSource({"IN_STOCK,AWAITING_PAYMENT", "MADE_TO_ORDER,AWAITING_DEPOSIT"})
    void cancelBeforePayment_goesToCancelled_refundRefNotNeeded_reasonSaved(String type, String st) throws Exception {
        long id = order(type, st, 1);
        act(id, "cancel-by-shop", "version", 0, "reason", "  changed my mind  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM customer_order WHERE id = ?", String.class, id))
                .isEqualTo("changed my mind");
        assertThat(outbox("ORDER_CANCELLED", id)).isEqualTo(1);
        assertThat(auditCount("ORDER_CANCELLED_BY_SHOP", id)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"MADE_TO_ORDER,QUEUED", "MADE_TO_ORDER,IN_PRODUCTION", "IN_STOCK,PAID_IN_FULL"})
    void cancelAfterPayment_requiresRefundRef_thenRefunds_refundRefOnlyInAudit(String type, String st)
            throws Exception {
        long id = order(type, st, 1);
        expectField(act(id, "cancel-by-shop", "version", 0, "reason", "r"), "refundRef");
        expectField(act(id, "cancel-by-shop", "version", 0, "reason", "r", "refundRef", "  "), "refundRef");
        expectField(act(id, "cancel-by-shop", "version", 0, "reason", "r", "refundRef", "x".repeat(101)), "refundRef");
        expectField(act(id, "cancel-by-shop", "version", 0, "reason", "r", "refundRef", "a\u0007b"), "refundRef");
        assertThat(orderStatus(id)).isEqualTo(st);
        assertThat(version(id)).isZero();

        String secret = "RF-SECRET-9";
        ResultActions ok = act(id, "cancel-by-shop", "version", 0, "reason", "r", "refundRef", secret)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED_REFUNDED"));
        assertThat(body(ok)).doesNotContain(secret);
        assertThat(auditAfter("ORDER_CANCELLED_BY_SHOP", id)).contains(secret);
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'ORDER_TRANSITION'"
                                + " AND after::text LIKE ?",
                        id,
                        "%" + secret + "%"))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM email_outbox WHERE payload::text LIKE ? OR to_email LIKE ?",
                        "%" + secret + "%",
                        "%" + secret + "%"))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM customer_order WHERE cancel_reason LIKE ?",
                        Integer.class,
                        "%" + secret + "%"))
                .isZero();
        assertThat(body(fetch("/api/admin/orders/" + id))).doesNotContain(secret);
        assertThat(outbox("ORDER_CANCELLED", id)).isEqualTo(1);
    }

    @Test
    void cancelMadeToOrderPaidInFull_409InvalidTransition_withOrWithoutRefundRef() throws Exception {
        long id = order("MADE_TO_ORDER", "PAID_IN_FULL", 1);
        expectProblem(act(id, "cancel-by-shop", "version", 0, "reason", "r"), 409, "INVALID_TRANSITION");
        expectProblem(
                act(id, "cancel-by-shop", "version", 0, "reason", "r", "refundRef", "RF"), 409, "INVALID_TRANSITION");
        assertThat(orderStatus(id)).isEqualTo("PAID_IN_FULL");
        assertThat(version(id)).isZero();
        assertThat(quota(variant)).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({
        "MADE_TO_ORDER,AWAITING_BALANCE",
        "IN_STOCK,SHIPPED",
        "IN_STOCK,COMPLETED",
        "IN_STOCK,CANCELLED",
        "IN_STOCK,CANCELLED_REFUNDED",
        "MADE_TO_ORDER,CANCELLED_DEPOSIT_KEPT",
        "MADE_TO_ORDER,FORFEITED"
    })
    void cancelFromOtherStatuses_409InvalidTransition(String type, String st) throws Exception {
        long id = order(type, st, 1);
        expectProblem(
                act(id, "cancel-by-shop", "version", 0, "reason", "r", "refundRef", "RF"), 409, "INVALID_TRANSITION");
        assertThat(orderStatus(id)).isEqualTo(st);
        assertThat(stock()).isEqualTo(5);
        assertThat(quota(variant)).isEqualTo(3);
    }

    static Stream<Arguments> badReasons() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(""),
                Arguments.of("   "),
                Arguments.of("x".repeat(501)),
                Arguments.of("bad\u0007reason"),
                Arguments.of("two\nlines"));
    }

    @ParameterizedTest
    @MethodSource("badReasons")
    void cancel_invalidReason_400_changesNothing(String reason) throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        long req = pendingRequest(id, "FULL");
        expectField(act(id, "cancel-by-shop", "version", 0, "reason", reason), "reason");
        assertThat(orderStatus(id)).isEqualTo("AWAITING_PAYMENT");
        assertThat(requestStatus(req)).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(5);
    }

    @Test
    void cancel_reasonOf500Characters_isAccepted() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        act(id, "cancel-by-shop", "version", 0, "reason", "x".repeat(500)).andExpect(status().isOk());
    }

    // ================= cancel-by-shop: side effects =================

    @Test
    void cancelInStock_restoresStock_andBumpsVariantVersion() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 2);
        jdbc.update("UPDATE product_variant SET stock_qty = 1 WHERE id = ?", variant);
        int versionBefore = variantVersion(variant);
        act(id, "cancel-by-shop", "version", 0, "reason", "r").andExpect(status().isOk());
        assertThat(stock()).isEqualTo(3);
        assertThat(variantVersion(variant)).isEqualTo(versionBefore + 1);
        assertThat(quota(variant)).isEqualTo(3); // in-stock orders never touch quota
    }

    @Test
    void refundCancelInStockPaidInFull_leavesStockAndVariantUnchanged() throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 2);
        jdbc.update("UPDATE product_variant SET stock_qty = 1 WHERE id = ?", variant);
        int versionBefore = variantVersion(variant);
        act(id, "cancel-by-shop", "version", 0, "reason", "r", "refundRef", "RF")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED_REFUNDED"));
        assertThat(stock()).isEqualTo(1);
        assertThat(variantVersion(variant)).isEqualTo(versionBefore);
        assertThat(quota(variant)).isEqualTo(3);
    }

    @Test
    void cancelInStock_itemsSharingOneVariant_restoreTheSum() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 2);
        addItem(id, variant, 3);
        jdbc.update("UPDATE product_variant SET stock_qty = 0 WHERE id = ?", variant);
        act(id, "cancel-by-shop", "version", 0, "reason", "r").andExpect(status().isOk());
        assertThat(stock()).isEqualTo(5);
    }

    @Test
    void cancelInStock_itemsOnDifferentVariants_restoreEachOwn() throws Exception {
        long other = newVariant(1, null);
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 2);
        addItem(id, other, 4);
        act(id, "cancel-by-shop", "version", 0, "reason", "r").andExpect(status().isOk());
        assertThat(stock(variant)).isEqualTo(7);
        assertThat(stock(other)).isEqualTo(5);
    }

    @Test
    void cancelMto_quotaNull_staysNull_andVariantUntouched() throws Exception {
        long unlimited = newVariant(0, null);
        long id = orderOn("MADE_TO_ORDER", "QUEUED", unlimited, 2);
        int versionBefore = variantVersion(unlimited);
        act(id, "cancel-by-shop", "version", 0, "reason", "r", "refundRef", "RF")
                .andExpect(status().isOk());
        assertThat(quota(unlimited)).isNull();
        assertThat(variantVersion(unlimited)).isEqualTo(versionBefore);
        assertThat(stock(unlimited)).isZero(); // made-to-order never touches stock
    }

    @Test
    void cancelMto_itemsSharingOneVariant_restoreQuotaSum() throws Exception {
        jdbc.update("UPDATE product_variant SET made_to_order_quota = 0 WHERE id = ?", variant);
        long id = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 2);
        addItem(id, variant, 3);
        act(id, "cancel-by-shop", "version", 0, "reason", "r").andExpect(status().isOk());
        assertThat(quota(variant)).isEqualTo(5);
        assertThat(stock()).isEqualTo(5);
    }

    @Test
    void cancelMtoBeforeDeposit_noSlotsYet_releaseIsNoOp() throws Exception {
        long id = order("MADE_TO_ORDER", "AWAITING_DEPOSIT", 2);
        act(id, "cancel-by-shop", "version", 0, "reason", "r").andExpect(status().isOk());
        assertThat(allocatedTotal()).isZero();
        assertThat(count("SELECT count(*) FROM queue_entry")).isZero();
        assertThat(quota(variant)).isEqualTo(5);
    }

    @Test
    void cancelQueuedMto_releasesSlotsAcrossMonths_forMultiQuantityItems() throws Exception {
        setMonth(jdbc, 0, 1, 0);
        setMonth(jdbc, 1, 1, 0);
        setMonth(jdbc, 2, 10, 0);
        long id = queuedMto(variant, 3, 2); // item 1 fills months 0, 1, 2; item 2 takes two more in month 2
        assertThat(allocations()).containsExactly(1, 1, 3);
        assertThat(openEntries(id)).isEqualTo(5);

        act(id, "cancel-by-shop", "version", 1, "reason", "r", "refundRef", "RF-7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED_REFUNDED"))
                .andExpect(jsonPath("$.version").value(2));

        assertThat(allocations()).containsExactly(0, 0, 0);
        assertThat(allocatedTotal()).isZero();
        assertThat(openEntries()).isZero();
        assertThat(count("SELECT count(*) FROM queue_entry WHERE status = 'CANCELLED'"))
                .isEqualTo(5);
        assertThat(quota(variant)).isEqualTo(3); // refund cancel: slots released, quota left for manual adjustment
    }

    private List<Integer> allocations() {
        return jdbc.queryForList("SELECT allocated FROM production_month ORDER BY month LIMIT 3", Integer.class);
    }

    @Test
    void cancelInProductionMto_releasesSlots() throws Exception {
        long id = queuedMto(variant, 2);
        act(id, "start-production", "version", 1).andExpect(status().isOk());
        assertThat(allocatedTotal()).isEqualTo(2);
        act(id, "cancel-by-shop", "version", 2, "reason", "r", "refundRef", "RF")
                .andExpect(status().isOk());
        assertThat(allocatedTotal()).isZero();
        assertThat(openEntries()).isZero();
    }

    @Test
    void cancel_cancelsPendingRequests_butPaidOnesStayPaid() throws Exception {
        long id = queuedMto(variant, 1);
        long paid = jdbc.queryForObject("SELECT id FROM payment_request WHERE order_id = ?", Long.class, id);
        long pending = pendingRequest(id, "BALANCE");
        act(id, "cancel-by-shop", "version", 1, "reason", "r", "refundRef", "RF")
                .andExpect(status().isOk());
        assertThat(requestStatus(paid)).isEqualTo("PAID");
        assertThat(requestStatus(pending)).isEqualTo("CANCELLED");
        assertThat(auditCount("PAYMENT_REQUESTS_CANCELLED_WITH_ORDER", id)).isEqualTo(1);
        assertThat(allocatedTotal()).isZero();
    }

    @Test
    void cancel_failureAfterStateChange_rollsEverythingBack() throws Exception {
        long id = queuedMto(variant, 2);
        long pending = pendingRequest(id, "BALANCE");
        int allocatedBefore = allocatedTotal();
        // Fail the very last write of the transaction (the cancel audit row), after status, request and stock changes.
        jdbc.execute("CREATE OR REPLACE FUNCTION fail_cancel_audit() RETURNS trigger AS $$ BEGIN"
                + " IF NEW.action = 'ORDER_CANCELLED_BY_SHOP' THEN RAISE EXCEPTION 'forced failure'; END IF;"
                + " RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER trg_fail_cancel_audit BEFORE INSERT ON audit_log"
                + " FOR EACH ROW EXECUTE FUNCTION fail_cancel_audit()");
        try {
            act(id, "cancel-by-shop", "version", 1, "reason", "r", "refundRef", "RF")
                    .andExpect(status().is5xxServerError());
        } finally {
            jdbc.execute("DROP TRIGGER trg_fail_cancel_audit ON audit_log");
            jdbc.execute("DROP FUNCTION fail_cancel_audit()");
        }
        assertThat(orderStatus(id)).isEqualTo("QUEUED");
        assertThat(version(id)).isEqualTo(1);
        assertThat(requestStatus(pending)).isEqualTo("PENDING");
        assertThat(allocatedTotal()).isEqualTo(allocatedBefore);
        assertThat(openEntries(id)).isEqualTo(2);
        assertThat(quota(variant)).isEqualTo(3);
        assertThat(outbox("ORDER_CANCELLED", id)).isZero();
    }

    // ================= note =================

    private ResultActions note(long id, Object value) throws Exception {
        return call(HttpMethod.PATCH, "/api/admin/orders/" + id + "/note", j("adminNote", value));
    }

    private String savedNote(long id) {
        return jdbc.queryForObject("SELECT admin_note FROM customer_order WHERE id = ?", String.class, id);
    }

    @Test
    void note_isSavedAndAudited() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        int v = version(id);
        note(id, "call buyer")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminNote").value("call buyer"));
        assertThat(version(id)).isEqualTo(v);
        assertThat(auditCount("ORDER_NOTE_UPDATED", id)).isEqualTo(1);
        assertThat(auditAfter("ORDER_NOTE_UPDATED", id))
                .doesNotContain("call buyer")
                .contains("\"noteLength\": 10");
        expectProblem(note(UNKNOWN_ID, "x"), 404, "NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void note_blankClearsTheNote(String blank) throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        note(id, "first").andExpect(status().isOk());
        note(id, blank)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminNote").doesNotExist());
        assertThat(savedNote(id)).isNull();
        assertThat(auditCount("ORDER_NOTE_UPDATED", id)).isEqualTo(2);
    }

    @Test
    void note_nullClearsTheNote_andVersionStays() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        note(id, "first").andExpect(status().isOk());
        note(id, null).andExpect(status().isOk());
        assertThat(savedNote(id)).isNull();
        assertThat(version(id)).isZero();
    }

    @Test
    void note_2000CharactersOk_2001Rejected() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        note(id, "n".repeat(2000)).andExpect(status().isOk());
        expectField(note(id, "n".repeat(2001)), "adminNote");
        assertThat(savedNote(id)).hasSize(2000);
    }

    @Test
    void note_controlCharacters_400_butNewlinesAllowed() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        expectField(note(id, "bad\u0007note"), "adminNote");
        expectField(note(id, "tab\there"), "adminNote");
        assertThat(savedNote(id)).isNull();
        note(id, "line1\nline2").andExpect(status().isOk());
        assertThat(savedNote(id)).isEqualTo("line1\nline2");
    }

    @Test
    void note_doesNotChangeStatusOrVersion_andAuditsOnlyTheLength() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        note(id, "remember").andExpect(status().isOk());
        assertThat(orderStatus(id)).isEqualTo("AWAITING_PAYMENT");
        assertThat(version(id)).isZero();
        assertThat(auditAfter("ORDER_NOTE_UPDATED", id))
                .contains("\"noteLength\": 8")
                .doesNotContain("remember");
    }
}
