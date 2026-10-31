package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.order.jobs.OrderJobs;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** T-033 admin order detail: history, progress, restockedAt and the forfeited-to-stock action. */
class AdminOrderDetailIT extends ProgressTestBase {

    @Autowired
    OrderJobs jobs;

    private static final List<String> HISTORY_KEYS =
            List.of("at", "action", "actorType", "fromStatus", "toStatus", "transition");

    private String detail(long id) throws Exception {
        return body(fetch("/api/admin/orders/" + id).andExpect(status().isOk()));
    }

    @Test
    void history_listsRealTransitionsInOrder_withWhitelistedKeysOnly() throws Exception {
        long id = queuedMto(variant, 1);
        act(id, "start-production", "version", version(id)).andExpect(status().isOk());
        act(id, "mark-finished", "version", version(id)).andExpect(status().isOk());

        String json = detail(id);
        List<Map<String, Object>> history = JsonPath.read(json, "$.history");
        assertThat(history).hasSize(3);
        assertThat(history.stream().map(h -> h.get("toStatus")))
                .containsExactly("QUEUED", "IN_PRODUCTION", "AWAITING_BALANCE");
        assertThat(history.stream().map(h -> h.get("fromStatus")))
                .containsExactly("AWAITING_DEPOSIT", "QUEUED", "IN_PRODUCTION");
        assertThat(history.get(1).get("transition")).isEqualTo("START_PRODUCTION");
        assertThat(history.get(2).get("transition")).isEqualTo("COMPLETE_PRODUCTION");
        assertThat(history.get(0).get("transition")).isEqualTo("CONFIRM_DEPOSIT");
        for (Map<String, Object> h : history) {
            assertThat(h.keySet()).containsExactlyInAnyOrderElementsOf(HISTORY_KEYS);
            assertThat(h.get("action")).isEqualTo("ORDER_TRANSITION");
            assertThat(h.get("actorType")).isEqualTo("ADMIN");
        }
        assertThat(json).doesNotContain("refundRef").doesNotContain("requestId").doesNotContain("actorId");
    }

    @Test
    void cancelByShop_appearsOnceInHistory_andReasonStaysInCancelReason() throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 1);
        act(id, "cancel-by-shop", "version", version(id), "reason", "no stock", "refundRef", "RF-SECRET-1")
                .andExpect(status().isOk());

        String json = detail(id);
        List<Map<String, Object>> history = JsonPath.read(json, "$.history");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("action")).isEqualTo("ORDER_TRANSITION");
        assertThat(history.get(0).get("toStatus")).isEqualTo("CANCELLED_REFUNDED");
        assertThat(JsonPath.<String>read(json, "$.cancelReason")).isEqualTo("no stock");
        assertThat(json).doesNotContain("RF-SECRET-1").doesNotContain("ORDER_CANCELLED_BY_SHOP");
    }

    @Test
    void progress_listsApprovedStageWithImages_andInStockOrderHasNone() throws Exception {
        long id = inProduction();
        approve(id, "RAW_PRINT", "first print", photos(2)).andExpect(status().isOk());

        fetch("/api/admin/orders/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress.length()").value(1))
                .andExpect(jsonPath("$.progress[0].stage").value("RAW_PRINT"))
                .andExpect(jsonPath("$.progress[0].note").value("first print"))
                .andExpect(jsonPath("$.progress[0].approvedAt").isNotEmpty())
                .andExpect(jsonPath("$.progress[0].images.length()").value(2))
                .andExpect(jsonPath("$.progress[0].images[0].url").value(Matchers.endsWith("/1000.webp")));

        long inStock = order("IN_STOCK", "PAID_IN_FULL", 1);
        fetch("/api/admin/orders/" + inStock)
                .andExpect(jsonPath("$.progress.length()").value(0));
    }

    @Test
    void progress_listsApprovedStagesInProductionOrder() throws Exception {
        long id = inProduction();
        approve(id, "RAW_PRINT", "a", photos(1)).andExpect(status().isOk());
        approve(id, "PREP", "b", photos(1)).andExpect(status().isOk());

        assertThat(JsonPath.<List<String>>read(detail(id), "$.progress[*].stage"))
                .containsExactly("RAW_PRINT", "PREP");
    }

    @Test
    void history_actorTypeReflectsSystemAndCustomer() throws Exception {
        long expired = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        jdbc.update(
                "UPDATE payment_request SET due_at = ? WHERE id = ?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(60)),
                pendingRequest(expired, "FULL"));
        long cancelled = order("IN_STOCK", "AWAITING_PAYMENT", 1);

        assertThat(jobs.expireUnpaid()).isEqualTo(1);
        assertThat(res(cancelOrder(tokenFor(cancelled), null)).status()).isEqualTo(200);

        assertThat(JsonPath.<List<String>>read(detail(expired), "$.history[*].actorType"))
                .containsExactly("SYSTEM");
        assertThat(JsonPath.<List<String>>read(detail(cancelled), "$.history[*].actorType"))
                .containsExactly("CUSTOMER");
    }

    @Test
    void untouchedOrder_hasEmptyHistoryAndProgress() throws Exception {
        long id = order("IN_STOCK", "PAID_IN_FULL", 1);

        fetch("/api/admin/orders/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(0))
                .andExpect(jsonPath("$.progress.length()").value(0));
    }

    @Test
    void forfeitedToStock_actionOnlyForUnrestockedForfeitedMto_andGoneAfterCall() throws Exception {
        long mto = order("MADE_TO_ORDER", "FORFEITED", 1);
        fetch("/api/admin/orders/" + mto)
                .andExpect(jsonPath("$.allowedActions").value(Matchers.hasItem("forfeited-to-stock")))
                .andExpect(jsonPath("$.restockedAt").doesNotExist());

        act(mto, "forfeited-to-stock", "version", version(mto)).andExpect(status().isOk());

        String json = detail(mto);
        assertThat(JsonPath.<String>read(json, "$.restockedAt")).isNotBlank();
        assertThat(JsonPath.<List<String>>read(json, "$.allowedActions")).doesNotContain("forfeited-to-stock");
        List<Map<String, Object>> history = JsonPath.read(json, "$.history");
        assertThat(history.stream().map(h -> h.get("action"))).containsExactly("ORDER_FORFEITED_TO_STOCK");
        assertThat(history.get(0).get("fromStatus")).isEqualTo("FORFEITED");

        // other states never offer it
        for (String[] c : new String[][] {
            {"MADE_TO_ORDER", "QUEUED"}, {"MADE_TO_ORDER", "CANCELLED_DEPOSIT_KEPT"}, {"IN_STOCK", "PAID_IN_FULL"}
        }) {
            assertThat(JsonPath.<List<String>>read(detail(order(c[0], c[1], 1)), "$.allowedActions"))
                    .as("%s %s", c[0], c[1])
                    .doesNotContain("forfeited-to-stock");
        }
    }
}
