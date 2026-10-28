package com.bkstudio.order.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderTransitionsTest {

    /** WORKFLOWS 1.1 / 1.2 written out independently as "TYPE:STATUS:ACTION" to "NEW_STATUS". */
    private static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry("MADE_TO_ORDER:AWAITING_DEPOSIT:CONFIRM_DEPOSIT", "QUEUED"),
            Map.entry("MADE_TO_ORDER:AWAITING_DEPOSIT:CANCEL", "CANCELLED"),
            Map.entry("MADE_TO_ORDER:QUEUED:START_PRODUCTION", "IN_PRODUCTION"),
            Map.entry("MADE_TO_ORDER:QUEUED:CANCEL_KEEP_DEPOSIT", "CANCELLED_DEPOSIT_KEPT"),
            Map.entry("MADE_TO_ORDER:QUEUED:CANCEL_REFUND", "CANCELLED_REFUNDED"),
            Map.entry("MADE_TO_ORDER:IN_PRODUCTION:COMPLETE_PRODUCTION", "AWAITING_BALANCE"),
            Map.entry("MADE_TO_ORDER:IN_PRODUCTION:CANCEL_KEEP_DEPOSIT", "CANCELLED_DEPOSIT_KEPT"),
            Map.entry("MADE_TO_ORDER:IN_PRODUCTION:CANCEL_REFUND", "CANCELLED_REFUNDED"),
            Map.entry("MADE_TO_ORDER:AWAITING_BALANCE:CONFIRM_BALANCE", "PAID_IN_FULL"),
            Map.entry("MADE_TO_ORDER:AWAITING_BALANCE:FORFEIT", "FORFEITED"),
            Map.entry("MADE_TO_ORDER:PAID_IN_FULL:SHIP", "SHIPPED"),
            Map.entry("MADE_TO_ORDER:SHIPPED:COMPLETE", "COMPLETED"),
            Map.entry("IN_STOCK:AWAITING_PAYMENT:CONFIRM_PAYMENT", "PAID_IN_FULL"),
            Map.entry("IN_STOCK:AWAITING_PAYMENT:CANCEL", "CANCELLED"),
            Map.entry("IN_STOCK:PAID_IN_FULL:SHIP", "SHIPPED"),
            Map.entry("IN_STOCK:PAID_IN_FULL:CANCEL_REFUND", "CANCELLED_REFUNDED"),
            Map.entry("IN_STOCK:SHIPPED:COMPLETE", "COMPLETED"));

    @Test
    void everyCombination_matchesWorkflowTable_andNothingElseIsAllowed() {
        int checked = 0;
        for (FulfillmentType t : FulfillmentType.values()) {
            for (OrderStatus s : OrderStatus.values()) {
                for (OrderAction a : OrderAction.values()) {
                    String expected = EXPECTED.get(t + ":" + s + ":" + a);
                    assertThat(OrderTransitions.next(t, s, a))
                            .as("%s %s %s", t, s, a)
                            .isEqualTo(Optional.ofNullable(expected).map(OrderStatus::valueOf));
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(2 * 12 * 11);
        assertThat(OrderTransitions.all()).hasSize(EXPECTED.size());
    }

    @Test
    void terminalStates_haveNoActionForAnyType() {
        for (FulfillmentType t : FulfillmentType.values()) {
            for (OrderStatus s : OrderStatus.values()) {
                if (!s.isTerminal()) {
                    continue;
                }
                for (OrderAction a : OrderAction.values()) {
                    assertThat(OrderTransitions.next(t, s, a))
                            .as("%s %s %s", t, s, a)
                            .isEmpty();
                }
            }
        }
    }

    @Test
    void targetsNeverViolateDbTypeConstraints() {
        OrderTransitions.all().forEach((k, to) -> {
            if (k.type() == FulfillmentType.IN_STOCK) {
                assertThat(to)
                        .isNotIn(
                                OrderStatus.QUEUED,
                                OrderStatus.IN_PRODUCTION,
                                OrderStatus.AWAITING_BALANCE,
                                OrderStatus.CANCELLED_DEPOSIT_KEPT,
                                OrderStatus.FORFEITED);
            } else {
                assertThat(to).isNotEqualTo(OrderStatus.AWAITING_PAYMENT);
            }
        });
    }
}
