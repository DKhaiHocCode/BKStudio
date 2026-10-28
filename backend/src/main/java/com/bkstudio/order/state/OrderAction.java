package com.bkstudio.order.state;

/** What happened to an order; the state machine maps (type, status, action) to the next status. */
public enum OrderAction {
    CONFIRM_DEPOSIT("ORDER_DEPOSIT_CONFIRMED"),
    CONFIRM_PAYMENT("ORDER_PAID"),
    START_PRODUCTION("ORDER_PRODUCTION_STARTED"),
    COMPLETE_PRODUCTION("ORDER_BALANCE_REQUESTED"),
    CONFIRM_BALANCE("ORDER_PAID"),
    SHIP("ORDER_SHIPPED"),
    COMPLETE("ORDER_COMPLETED"),
    CANCEL("ORDER_CANCELLED"),
    CANCEL_KEEP_DEPOSIT("ORDER_CANCELLED"),
    CANCEL_REFUND("ORDER_CANCELLED"),
    FORFEIT("ORDER_FORFEITED");

    private final String emailTemplate;

    OrderAction(String emailTemplate) {
        this.emailTemplate = emailTemplate;
    }

    /** email_outbox template queued for the customer on this action. */
    public String emailTemplate() {
        return emailTemplate;
    }
}
