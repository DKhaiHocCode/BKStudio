package com.bkstudio.queue;

import static com.bkstudio.queue.QueueFixtures.month;
import static com.bkstudio.queue.QueueFixtures.orderItems;
import static com.bkstudio.queue.QueueFixtures.setMonth;
import static com.bkstudio.queue.QueueFixtures.window;
import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.queue.domain.QueueService.Allocation;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class QueueConcurrencyIT extends QueueTestBase {

    /** Result of one thread: either its allocations or the error code it got. */
    private record Outcome(List<Allocation> won, ErrorCode error) {}

    /** Runs allocate(items[i], units) on items.length threads released together. */
    private List<Outcome> race(long[] items, int units) throws Exception {
        int n = items.length;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (long item : items) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome(queue.allocate(item, units), null);
                    } catch (ApiException e) {
                        return new Outcome(null, e.code());
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome> out = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The invariants that must hold after any race. */
    private void assertConsistent() {
        assertThat(count("SELECT count(*) FROM production_month WHERE allocated > capacity OR allocated < 0"))
                .isZero();
        assertThat(count("SELECT COALESCE(sum(allocated), 0) FROM production_month"))
                .isEqualTo(openEntries());
        assertThat(count("SELECT count(*) FROM production_month pm WHERE pm.allocated <> (SELECT count(*)"
                        + " FROM queue_entry q WHERE q.production_month = pm.month"
                        + " AND q.status IN ('QUEUED','IN_PRODUCTION'))"))
                .isZero();
    }

    @Test
    void concurrentAllocation_neverExceedsCapacity_exactlyTheFreeSlotsAreWon() throws Exception {
        window(jdbc, 0);
        for (int i = 0; i < 3; i++) {
            setMonth(jdbc, i, 1, 0); // 3 slots in total
        }

        List<Outcome> res = race(orderItems(jdbc, 8), 1);

        assertThat(res.stream().filter(o -> o.won() != null)).hasSize(3);
        assertThat(res.stream().filter(o -> o.error() == ErrorCode.QUEUE_FULL)).hasSize(5);
        assertThat(openEntries()).isEqualTo(3);
        assertConsistent();
    }

    @Test
    void concurrentAllocation_roomForAll_everyoneWinsAndNoMonthIsOverbooked() throws Exception {
        window(jdbc, 2);

        List<Outcome> res = race(orderItems(jdbc, 8), 1);

        assertThat(res).allMatch(o -> o.won() != null);
        assertThat(openEntries()).isEqualTo(8);
        assertConsistent();
    }

    @RepeatedTest(15)
    void twoThreadsRaceForLastSlotOfEarliestMonth_exactlyOneGetsIt() throws Exception {
        window(jdbc, 5);
        setMonth(jdbc, 0, 1, 0);

        List<Outcome> res = race(orderItems(jdbc, 2), 1);

        List<LocalDate> months =
                res.stream().map(o -> o.won().get(0).month()).sorted().toList();
        assertThat(months).containsExactly(month(0), month(1));
        assertConsistent();
    }

    @Test
    void concurrentMultiUnitAllocation_isAllOrNothingPerOrder() throws Exception {
        window(jdbc, 0);
        for (int i = 0; i < 6; i++) {
            setMonth(jdbc, i, 1, 0); // 6 slots, 4 orders x 2 units
        }
        long[] items = orderItems(jdbc, 4);

        List<Outcome> res = race(items, 2);

        long winners = res.stream().filter(o -> o.won() != null).count();
        assertThat(winners).isBetween(1L, 3L);
        assertThat(res.stream().filter(o -> o.won() == null)).allMatch(o -> o.error() == ErrorCode.QUEUE_FULL);
        for (int i = 0; i < items.length; i++) {
            int entries = count("SELECT count(*) FROM queue_entry WHERE order_item_id = ?", items[i]);
            assertThat(entries).isEqualTo(res.get(i).won() != null ? 2 : 0);
        }
        assertConsistent();
    }
}
