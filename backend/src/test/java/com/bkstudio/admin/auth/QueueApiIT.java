package com.bkstudio.admin.auth;

import static com.bkstudio.queue.QueueFixtures.month;
import static com.bkstudio.queue.QueueFixtures.orderItems;
import static com.bkstudio.queue.QueueFixtures.setMonth;
import static com.bkstudio.queue.QueueFixtures.window;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.queue.QueueFixtures;
import com.bkstudio.queue.domain.QueueService;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** T-021 HTTP surface: public estimate and the admin production-month / queue endpoints. Clock is 2030-01. */
class QueueApiIT extends AdminProductTestBase {

    private static final String MONTHS = "/api/admin/production-months";
    private static final String ADMIN_QUEUE = "/api/admin/queue";
    private static final String ESTIMATE = "/api/queue/estimate";

    @Autowired
    QueueService queue;

    @BeforeEach
    void cleanQueue() {
        QueueFixtures.wipe(jdbc);
    }

    @AfterEach
    void cleanQueueAfter() {
        QueueFixtures.wipe(jdbc);
    }

    private ResultActions putCapacity(String month, Object capacity) throws Exception {
        return put(MONTHS + "/" + month, j("capacity", capacity));
    }

    private int allocated(int offset) {
        return count("SELECT allocated FROM production_month WHERE month = ?", java.sql.Date.valueOf(month(offset)));
    }

    private int capacity(int offset) {
        return count("SELECT capacity FROM production_month WHERE month = ?", java.sql.Date.valueOf(month(offset)));
    }

    // ---- estimate (public) ----

    @Test
    void estimate_anonymous_returnsMonthWithPublicCache() throws Exception {
        publicGet(ESTIMATE)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("public")))
                .andExpect(header().string("Cache-Control", containsString("max-age=60")))
                .andExpect(jsonPath("$.startMonth").value("2030-01"));
        assertThat(count("SELECT count(*) FROM production_month")).isZero();
    }

    @Test
    void estimate_qtyTwo_skipsMonthWithOneFreeSlot() throws Exception {
        window(jdbc, 5);
        setMonth(jdbc, 0, 5, 4);

        publicGet(ESTIMATE + "?qty=2").andExpect(jsonPath("$.startMonth").value("2030-02"));
    }

    @Test
    void estimate_windowFull_startMonthIsNull() throws Exception {
        window(jdbc, 0);

        publicGet(ESTIMATE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startMonth").value((Object) null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "6", "-1", "abc"})
    void estimate_badQty_400(String qty) throws Exception {
        publicGet(ESTIMATE + "?qty=" + qty).andExpect(status().isBadRequest());
    }

    // ---- access ----

    @Test
    void adminQueueEndpoints_withoutSession_401() throws Exception {
        expectProblem(publicGet(MONTHS), 401, "UNAUTHENTICATED");
        expectProblem(publicGet(ADMIN_QUEUE + "?month=2030-01"), 401, "UNAUTHENTICATED");
        expectProblem(
                mvc.perform(withCsrf(
                        org.springframework.http.HttpMethod.PUT,
                        MONTHS + "/2030-01",
                        "{\"capacity\":3}",
                        null,
                        nextIp())),
                401,
                "UNAUTHENTICATED");
    }

    @Test
    void putCapacity_withoutCsrf_403() throws Exception {
        mvc.perform(MockMvcRequestBuilders.put(MONTHS + "/2030-01")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capacity\":3}"))
                .andExpect(status().isForbidden());
        assertThat(count("SELECT count(*) FROM production_month")).isZero();
    }

    // ---- GET production-months ----

    @Test
    void months_defaultRange_is12MonthsFromCurrentWithVirtualRows() throws Exception {
        setMonth(jdbc, 1, 7, 2);

        fetch(MONTHS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[0].month").value("2030-01"))
                .andExpect(jsonPath("$[0].capacity").value(5))
                .andExpect(jsonPath("$[0].allocated").value(0))
                .andExpect(jsonPath("$[1].capacity").value(7))
                .andExpect(jsonPath("$[1].allocated").value(2))
                .andExpect(jsonPath("$[11].month").value("2030-12"));
        assertThat(count("SELECT count(*) FROM production_month")).isEqualTo(1);
    }

    @Test
    void months_exactly24Months_ok_25_400() throws Exception {
        fetch(MONTHS + "?from=2030-01&to=2031-12")
                .andExpect(jsonPath("$.length()").value(24));
        expectProblem(fetch(MONTHS + "?from=2030-01&to=2032-01"), 400, "VALIDATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "from=2030-1",
                "from=2030-13",
                "from=2030-00",
                "from=202001",
                "to=garbage",
                "from=2030-06&to=2030-05"
            })
    void months_badInput_400(String query) throws Exception {
        expectProblem(fetch(MONTHS + "?" + query), 400, "VALIDATION_FAILED");
    }

    // ---- PUT capacity ----

    @Test
    void putCapacity_raise_ok_oneAuditRow() throws Exception {
        putCapacity("2030-03", 9)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2030-03"))
                .andExpect(jsonPath("$.capacity").value(9))
                .andExpect(jsonPath("$.allocated").value(0));

        assertThat(capacity(2)).isEqualTo(9);
        var rows = audits("PRODUCTION_MONTH", 203003);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).action()).isEqualTo("PRODUCTION_CAPACITY_UPDATED");
        JsonNodes.assertAfter(om, rows.get(0).after(), "2030-03", 5, 9);
    }

    @Test
    void putCapacity_belowAllocated_409_nothingChanged() throws Exception {
        setMonth(jdbc, 0, 5, 3);

        expectProblem(putCapacity("2030-01", 2), 409, "CAPACITY_BELOW_ALLOCATED");

        assertThat(capacity(0)).isEqualTo(5);
        assertThat(audits("PRODUCTION_MONTH", 203001)).isEmpty();
    }

    @Test
    void putCapacity_equalToAllocated_ok() throws Exception {
        setMonth(jdbc, 0, 5, 3);

        putCapacity("2030-01", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(3));

        assertThat(capacity(0)).isEqualTo(3);
        assertThat(audits("PRODUCTION_MONTH", 203001)).hasSize(1);
    }

    @Test
    void putCapacity_zeroWhenNothingAllocated_ok() throws Exception {
        putCapacity("2030-02", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(0));
    }

    @Test
    void putCapacity_identicalValue_noAuditRow() throws Exception {
        setMonth(jdbc, 0, 6, 1);

        putCapacity("2030-01", 6)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(6));
        putCapacity("2030-04", 5).andExpect(status().isOk()); // equals the default of a virtual row

        assertThat(auditRows()).isZero();
    }

    @Test
    void putCapacity_pastMonth_400() throws Exception {
        expectProblem(putCapacity("2029-12", 5), 400, "VALIDATION_FAILED");
        assertThat(count("SELECT count(*) FROM production_month")).isZero();
    }

    @Test
    void putCapacity_currentMonth_ok() throws Exception {
        putCapacity("2030-01", 8).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(ints = {1001, -1})
    void putCapacity_outOfRange_400(int capacity) throws Exception {
        expectProblem(putCapacity("2030-02", capacity), 400, "VALIDATION_FAILED");
    }

    @Test
    void putCapacity_max1000_ok_missingOrNullCapacity_400() throws Exception {
        putCapacity("2030-02", 1000).andExpect(status().isOk());
        expectProblem(put("/api/admin/production-months/2030-02", "{}"), 400, "VALIDATION_FAILED");
        expectProblem(putCapacity("2030-02", null), 400, "VALIDATION_FAILED");
    }

    @Test
    void putCapacity_badMonthFormat_400() throws Exception {
        expectProblem(putCapacity("2030-13", 5), 400, "VALIDATION_FAILED");
        expectProblem(putCapacity("2030-1", 5), 400, "VALIDATION_FAILED");
    }

    // ---- PUT lower racing with allocate ----

    @RepeatedTest(10)
    void putLowerRacingAllocate_neverLeavesAllocatedAboveCapacity() throws Exception {
        window(jdbc, 0);
        setMonth(jdbc, 0, 2, 1); // one free slot; PUT 2 -> 1 and allocate both want it
        jdbc.update("DELETE FROM queue_entry");
        long item = orderItems(jdbc, 1)[0];

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Boolean> alloc = pool.submit(() -> {
                go.await();
                try {
                    queue.allocate(item, 1);
                    return true;
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo(ErrorCode.QUEUE_FULL);
                    return false;
                }
            });
            Future<Boolean> lower = pool.submit(() -> {
                go.await();
                var res = putCapacity("2030-01", 1).andReturn().getResponse();
                if (res.getStatus() == 409) {
                    return false;
                }
                assertThat(res.getStatus()).isEqualTo(200);
                return true;
            });
            go.countDown();
            boolean allocated = alloc.get(30, TimeUnit.SECONDS);
            boolean lowered = lower.get(30, TimeUnit.SECONDS);

            // allocated=1 here is the preset value (no entry row), so: lower first -> cap 1, allocate refused;
            // allocate first -> allocated 2, lowering to 1 refused. Never both.
            assertThat(allocated ^ lowered).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM production_month WHERE allocated > capacity"))
                .isZero();
    }

    // ---- GET admin queue ----

    @Test
    void adminQueue_entriesOrderedByQueuedAtThenId() throws Exception {
        window(jdbc, 5);
        long[] items = orderItems(jdbc, 3);
        Instant t0 = clock.instant();
        clock.set(t0.plus(Duration.ofHours(2)));
        queue.allocate(items[0], 1); // lowest id, latest queued_at
        clock.set(t0);
        queue.allocate(items[1], 2); // two entries, same queued_at -> by id
        queue.allocate(items[2], 1);
        QueueFixtures.setMonth(jdbc, 1, 5, 0); // untouched, must not leak into the list

        fetch(ADMIN_QUEUE + "?month=2030-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2030-01"))
                .andExpect(jsonPath("$.entries.length()").value(4))
                .andExpect(jsonPath("$.entries[0].orderItemId").value(items[1]))
                .andExpect(jsonPath("$.entries[0].unitIndex").value(1))
                .andExpect(jsonPath("$.entries[1].orderItemId").value(items[1]))
                .andExpect(jsonPath("$.entries[1].unitIndex").value(2))
                .andExpect(jsonPath("$.entries[2].orderItemId").value(items[2]))
                .andExpect(jsonPath("$.entries[3].orderItemId").value(items[0]))
                .andExpect(jsonPath("$.entries[3].status").value("QUEUED"));
    }

    @Test
    void adminQueue_emptyMonth_emptyList_badOrMissingMonth_400() throws Exception {
        fetch(ADMIN_QUEUE + "?month=2030-05")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty());
        expectProblem(fetch(ADMIN_QUEUE + "?month=2030-5"), 400, "VALIDATION_FAILED");
        fetch(ADMIN_QUEUE).andExpect(status().isBadRequest());
    }

    /** Tiny helper to read the audit "after" JSON. */
    private static final class JsonNodes {
        static void assertAfter(tools.jackson.databind.ObjectMapper om, String json, String month, int oldC, int newC) {
            var n = om.readTree(json);
            assertThat(n.get("month").asString()).isEqualTo(month);
            assertThat(n.get("oldCapacity").asInt()).isEqualTo(oldC);
            assertThat(n.get("newCapacity").asInt()).isEqualTo(newC);
        }
    }
}
