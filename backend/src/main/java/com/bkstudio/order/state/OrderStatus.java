package com.bkstudio.order.state;

/** Mirrors the chk_order_status CHECK constraint. */
public enum OrderStatus {
    AWAITING_PAYMENT,
    AWAITING_DEPOSIT,
    QUEUED,
    IN_PRODUCTION,
    AWAITING_BALANCE,
    PAID_IN_FULL,
    SHIPPED,
    COMPLETED,
    CANCELLED,
    CANCELLED_DEPOSIT_KEPT,
    CANCELLED_REFUNDED,
    FORFEITED;

    public boolean isTerminal() {
        return this == COMPLETED
                || this == CANCELLED
                || this == CANCELLED_DEPOSIT_KEPT
                || this == CANCELLED_REFUNDED
                || this == FORFEITED;
    }
}
