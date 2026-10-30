package com.bkstudio.order.progress.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.order.progress.api.ProgressDtos.Photo;
import com.bkstudio.order.progress.api.ProgressDtos.ProgressView;
import com.bkstudio.order.progress.api.ProgressDtos.StageView;
import com.bkstudio.order.progress.domain.ProgressReader.Row;
import com.bkstudio.order.state.OrderStatus;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure derivation of the six customer stages from the order status and the stored stages. No database. */
class CustomerProgressServiceTest {

    private static final List<String> ORDER =
            List.of("AWAITING_APPROVAL", "DEPOSIT_RECEIVED", "RAW_PRINT", "PREP", "PAINT", "FINISHED");
    private static final Instant AT = Instant.parse("2026-11-01T00:00:00Z");

    /** D = DONE, C = CURRENT, P = PENDING, one letter per stage. */
    private static String states(ProgressView v) {
        StringBuilder sb = new StringBuilder();
        for (StageView s : v.stages()) {
            sb.append(s.state().charAt(0));
        }
        return sb.toString();
    }

    private static Map<String, Row> stored(String... stages) {
        Map<String, Row> m = new HashMap<>();
        for (String s : stages) {
            m.put(s, new Row("", AT, List.of()));
        }
        return m;
    }

    @Test
    void everyStatusWithoutStoredStages_hasExpectedStates() {
        Map<OrderStatus, String> expected = new EnumMap<>(OrderStatus.class);
        expected.put(OrderStatus.AWAITING_PAYMENT, "CPPPPP");
        expected.put(OrderStatus.AWAITING_DEPOSIT, "CPPPPP");
        expected.put(OrderStatus.QUEUED, "DCPPPP");
        expected.put(OrderStatus.IN_PRODUCTION, "DDCPPP");
        expected.put(OrderStatus.AWAITING_BALANCE, "DDDDDD");
        expected.put(OrderStatus.PAID_IN_FULL, "DDDDDD");
        expected.put(OrderStatus.SHIPPED, "DDDDDD");
        expected.put(OrderStatus.COMPLETED, "DDDDDD");
        expected.put(OrderStatus.CANCELLED, "PPPPPP");
        expected.put(OrderStatus.CANCELLED_DEPOSIT_KEPT, "DDPPPP");
        expected.put(OrderStatus.CANCELLED_REFUNDED, "DDPPPP");
        expected.put(OrderStatus.FORFEITED, "DDDDDD");

        assertThat(expected.keySet())
                .containsExactlyInAnyOrder(OrderStatus.values()); // a new status must be decided here
        expected.forEach((status, want) -> assertThat(states(CustomerProgressService.build(status, Map.of())))
                .as(status.name())
                .isEqualTo(want));
    }

    @Test
    void alwaysSixStagesInFixedOrder() {
        for (OrderStatus s : OrderStatus.values()) {
            ProgressView v = CustomerProgressService.build(s, Map.of());
            assertThat(v.stages()).extracting(StageView::stage).containsExactlyElementsOf(ORDER);
            assertThat(v.stages()).allSatisfy(st -> {
                assertThat(st.label()).isNotBlank();
                assertThat(st.explanation()).isNotBlank();
            });
        }
    }

    @Test
    void inProductionWithStoredStages_currentMovesForward() {
        assertThat(states(CustomerProgressService.build(OrderStatus.IN_PRODUCTION, stored("RAW_PRINT"))))
                .isEqualTo("DDDCPP");
        assertThat(states(CustomerProgressService.build(OrderStatus.IN_PRODUCTION, stored("RAW_PRINT", "PREP"))))
                .isEqualTo("DDDDCP");
        assertThat(states(
                        CustomerProgressService.build(OrderStatus.IN_PRODUCTION, stored("RAW_PRINT", "PREP", "PAINT"))))
                .isEqualTo("DDDDDP");
    }

    @Test
    void finishedAndAllStored_everythingDone() {
        assertThat(states(CustomerProgressService.build(
                        OrderStatus.AWAITING_BALANCE, stored("RAW_PRINT", "PREP", "PAINT"))))
                .isEqualTo("DDDDDD");
    }

    @Test
    void closedOrderWithStoredStage_keepsItDoneAndHasNoCurrent() {
        assertThat(states(CustomerProgressService.build(OrderStatus.CANCELLED_REFUNDED, stored("RAW_PRINT"))))
                .isEqualTo("DDDPPP");
    }

    @Test
    void storedStage_exposesApprovedAtNoteAndImages_derivedStagesExposeNone() {
        Map<String, Row> m = new HashMap<>();
        m.put("RAW_PRINT", new Row("looks good", AT, List.of(new Photo("https://x/1000.webp", 10, 20))));
        m.put("PREP", new Row("", AT, List.of()));
        ProgressView v = CustomerProgressService.build(OrderStatus.IN_PRODUCTION, m);

        StageView raw = v.stages().get(2);
        assertThat(raw.approvedAt()).isEqualTo(AT);
        assertThat(raw.note()).isEqualTo("looks good");
        assertThat(raw.images()).hasSize(1);
        StageView prep = v.stages().get(3);
        assertThat(prep.note()).isEmpty(); // stored stage: note is "" when none
        assertThat(prep.images()).isEmpty();
        for (int i : new int[] {0, 1, 5}) {
            StageView s = v.stages().get(i);
            assertThat(s.approvedAt()).isNull();
            assertThat(s.note()).isNull();
            assertThat(s.images()).isNull();
        }
        StageView pendingPaint = v.stages().get(4);
        assertThat(pendingPaint.approvedAt()).isNull();
        assertThat(pendingPaint.images()).isNull();
    }
}
