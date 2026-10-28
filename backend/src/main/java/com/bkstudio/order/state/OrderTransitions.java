package com.bkstudio.order.state;

import static com.bkstudio.order.state.OrderAction.*;
import static com.bkstudio.order.state.OrderStatus.*;

import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** The valid-transition table of WORKFLOWS.md §1.1 and §1.2. Anything absent is invalid. */
public final class OrderTransitions {

    public record Key(FulfillmentType type, OrderStatus status, OrderAction action) {}

    private static final Map<Key, OrderStatus> TABLE = build();

    private OrderTransitions() {}

    public static Optional<OrderStatus> next(FulfillmentType type, OrderStatus status, OrderAction action) {
        return Optional.ofNullable(TABLE.get(new Key(type, status, action)));
    }

    /** Immutable view of every valid transition, for tests. */
    public static Map<Key, OrderStatus> all() {
        return TABLE;
    }

    private static Map<Key, OrderStatus> build() {
        Map<Key, OrderStatus> t = new HashMap<>();
        FulfillmentType m = FulfillmentType.MADE_TO_ORDER;
        FulfillmentType s = FulfillmentType.IN_STOCK;
        t.put(new Key(m, AWAITING_DEPOSIT, CONFIRM_DEPOSIT), QUEUED);
        t.put(new Key(m, AWAITING_DEPOSIT, CANCEL), CANCELLED);
        t.put(new Key(m, QUEUED, START_PRODUCTION), IN_PRODUCTION);
        t.put(new Key(m, QUEUED, CANCEL_KEEP_DEPOSIT), CANCELLED_DEPOSIT_KEPT);
        t.put(new Key(m, QUEUED, CANCEL_REFUND), CANCELLED_REFUNDED);
        t.put(new Key(m, IN_PRODUCTION, COMPLETE_PRODUCTION), AWAITING_BALANCE);
        t.put(new Key(m, IN_PRODUCTION, CANCEL_KEEP_DEPOSIT), CANCELLED_DEPOSIT_KEPT);
        t.put(new Key(m, IN_PRODUCTION, CANCEL_REFUND), CANCELLED_REFUNDED);
        t.put(new Key(m, AWAITING_BALANCE, CONFIRM_BALANCE), PAID_IN_FULL);
        t.put(new Key(m, AWAITING_BALANCE, FORFEIT), FORFEITED);
        t.put(new Key(m, PAID_IN_FULL, SHIP), SHIPPED);
        t.put(new Key(m, SHIPPED, COMPLETE), COMPLETED);
        t.put(new Key(s, AWAITING_PAYMENT, CONFIRM_PAYMENT), PAID_IN_FULL);
        t.put(new Key(s, AWAITING_PAYMENT, CANCEL), CANCELLED);
        t.put(new Key(s, PAID_IN_FULL, SHIP), SHIPPED);
        t.put(new Key(s, PAID_IN_FULL, CANCEL_REFUND), CANCELLED_REFUNDED);
        t.put(new Key(s, SHIPPED, COMPLETE), COMPLETED);
        return Map.copyOf(t);
    }
}
