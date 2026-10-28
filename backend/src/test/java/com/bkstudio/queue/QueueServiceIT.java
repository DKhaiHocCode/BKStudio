package com.bkstudio.queue;

import static com.bkstudio.queue.QueueFixtures.month;
import static com.bkstudio.queue.QueueFixtures.orderItems;
import static com.bkstudio.queue.QueueFixtures.setMonth;
import static com.bkstudio.queue.QueueFixtures.window;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bkstudio.queue.domain.QueueService.Allocation;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueueServiceIT extends QueueTestBase {

    private static void assertCode(Throwable t, ErrorCode code) {
        assertThat(t).isInstanceOf(ApiException.class);
        assertThat(((ApiException) t).code()).isEqualTo(code);
    }

    // ---- allocate ----

    @Test
    void allocate_missingMonthRows_areCreatedWithDefaultCapacityAndEarliestMonthWins() {
        long item = orderItems(jdbc, 1)[0];

        List<Allocation> a = queue.allocate(item, 1);

        assertThat(a).hasSize(1);
        assertThat(a.get(0).month()).isEqualTo(month(0));
        assertThat(count("SELECT count(*) FROM production_month")).isEqualTo(24);
        assertThat(count("SELECT capacity FROM production_month WHERE month = ?", java.sql.Date.valueOf(month(0))))
                .isEqualTo(5);
        assertThat(allocated(0)).isEqualTo(1);
    }

    @Test
    void allocate_twoUnits_createsTwoEntriesWithUnitIndex1And2() {
        window(jdbc, 5);
        long item = orderItems(jdbc, 1)[0];

        queue.allocate(item, 2);

        var rows = jdbc.queryForList(
                "SELECT unit_index, status FROM queue_entry WHERE order_item_id = ? ORDER BY unit_index", item);
        assertThat(rows).extracting(r -> r.get("unit_index")).containsExactly(1, 2);
        assertThat(rows).extracting(r -> r.get("status")).containsOnly("QUEUED");
        assertThat(allocated(0)).isEqualTo(2);
    }

    @Test
    void allocate_unitsSpillIntoNextMonthWhenEarliestIsFull() {
        window(jdbc, 0);
        setMonth(jdbc, 0, 1, 0);
        setMonth(jdbc, 1, 5, 0);
        long item = orderItems(jdbc, 1)[0];

        List<Allocation> a = queue.allocate(item, 2);

        assertThat(a).extracting(Allocation::month).containsExactly(month(0), month(1));
    }

    @Test
    void allocate_onlyOneFreeSlotInWholeWindow_twoUnitsThrowQueueFullAndPersistNothing() {
        window(jdbc, 0);
        setMonth(jdbc, 23, 1, 0);
        long item = orderItems(jdbc, 1)[0];

        assertThatThrownBy(() -> queue.allocate(item, 2)).satisfies(t -> assertCode(t, ErrorCode.QUEUE_FULL));

        assertThat(allocated(23)).isZero();
        assertThat(count("SELECT count(*) FROM queue_entry")).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6})
    void allocate_unitsOutOfRange_validationFailed(int units) {
        long item = orderItems(jdbc, 1)[0];
        assertThatThrownBy(() -> queue.allocate(item, units))
                .satisfies(t -> assertCode(t, ErrorCode.VALIDATION_FAILED));
    }

    // ---- release ----

    @Test
    void release_cancelsEntriesAndFreesSlots_secondReleaseIsNoOp() {
        window(jdbc, 5);
        long[] items = orderItems(jdbc, 2);
        queue.allocate(items[0], 2);
        queue.allocate(items[1], 1);
        assertThat(allocated(0)).isEqualTo(3);

        queue.release(items[0]);

        assertThat(allocated(0)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM queue_entry WHERE order_item_id = ? AND status = 'CANCELLED'", items[0]))
                .isEqualTo(2);
        assertThat(openEntries()).isEqualTo(1);

        queue.release(items[0]);

        assertThat(allocated(0)).isEqualTo(1);
        assertThat(openEntries()).isEqualTo(1);
    }

    @Test
    void release_neverDrivesAllocatedBelowZero() {
        window(jdbc, 5);
        long item = orderItems(jdbc, 1)[0];
        queue.allocate(item, 1);
        jdbc.update("UPDATE production_month SET allocated = 0"); // simulate drift

        queue.release(item);

        assertThat(allocated(0)).isZero();
    }

    @Test
    void release_itemWithoutEntries_isNoOp() {
        window(jdbc, 5);
        long item = orderItems(jdbc, 1)[0];

        queue.release(item);

        assertThat(allocated(0)).isZero();
    }

    // ---- estimate ----

    @Test
    void estimate_emptyDb_isCurrentMonthAndCreatesNoRows() {
        assertThat(queue.estimate(1)).contains("2030-01");
        assertThat(queue.estimate(5)).contains("2030-01");
        assertThat(count("SELECT count(*) FROM production_month")).isZero();
        assertThat(count("SELECT count(*) FROM queue_entry")).isZero();
    }

    @Test
    void estimate_skipsMonthsWithTooFewFreeSlots() {
        window(jdbc, 5);
        setMonth(jdbc, 0, 5, 5); // full
        setMonth(jdbc, 1, 5, 4); // 1 free
        setMonth(jdbc, 2, 5, 0);

        assertThat(queue.estimate(1)).contains("2030-02");
        assertThat(queue.estimate(2)).contains("2030-03");
    }

    @Test
    void estimate_windowFull_isEmpty() {
        window(jdbc, 2);
        jdbc.update("UPDATE production_month SET allocated = 2");

        assertThat(queue.estimate(1)).isEqualTo(Optional.empty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6})
    void estimate_qtyOutOfRange_validationFailed(int qty) {
        assertThatThrownBy(() -> queue.estimate(qty)).satisfies(t -> assertCode(t, ErrorCode.VALIDATION_FAILED));
    }
}
