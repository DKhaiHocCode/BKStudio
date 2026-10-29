package com.bkstudio.payment.domain;

import com.bkstudio.notification.domain.OutboxWriter;
import com.bkstudio.order.checkout.domain.CheckoutService;
import com.bkstudio.order.state.OrderAction;
import com.bkstudio.order.state.OrderActor;
import com.bkstudio.order.state.OrderStateMachine;
import com.bkstudio.payment.api.PaymentDtos.Confirmed;
import com.bkstudio.payment.api.PaymentDtos.CreatePaymentRequest;
import com.bkstudio.payment.api.PaymentDtos.PaymentChannel;
import com.bkstudio.payment.api.PaymentDtos.PaymentRequestView;
import com.bkstudio.payment.api.PaymentDtos.PaymentType;
import com.bkstudio.queue.domain.QueueService;
import com.bkstudio.shared.audit.AuditLogWriter;
import com.bkstudio.shared.settings.domain.SettingsService;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual payment requests (ADR-0008, WORKFLOWS §3). Lock order is always payment_request first, then
 * customer_order; create only locks the order. Every method is one transaction, so audit, outbox and the order
 * transition commit or roll back together.
 */
@Service
@Transactional
public class PaymentRequestService {

    static final Duration DUE_DEPOSIT_FULL = Duration.ofHours(48);
    static final Duration DUE_BALANCE = Duration.ofDays(30);

    private record OrderRow(
            String publicCode,
            String fulfillmentType,
            String status,
            int subtotalCents,
            int shippingCents,
            String email) {}

    private record RequestRow(
            long id,
            long orderId,
            PaymentType type,
            PaymentChannel channel,
            String status,
            String externalRef,
            Instant confirmedAt) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SettingsService settings;
    private final OutboxWriter outbox;
    private final AuditLogWriter audit;
    private final OrderStateMachine orders;
    private final QueueService queue;

    PaymentRequestService(
            JdbcTemplate jdbc,
            Clock clock,
            SettingsService settings,
            OutboxWriter outbox,
            AuditLogWriter audit,
            OrderStateMachine orders,
            QueueService queue) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.outbox = outbox;
        this.audit = audit;
        this.orders = orders;
        this.queue = queue;
    }

    public PaymentRequestView create(long orderId, CreatePaymentRequest req) {
        List<OrderRow> found = jdbc.query(
                "SELECT public_code, fulfillment_type, status, subtotal_cents, shipping_cents, customer_email"
                        + " FROM customer_order WHERE id = ? FOR UPDATE",
                (rs, i) -> new OrderRow(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getString(6)),
                orderId);
        if (found.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Order not found.");
        }
        OrderRow o = found.get(0);
        PaymentType type = req.type();
        boolean stateOk = switch (type) {
            case DEPOSIT ->
                o.fulfillmentType().equals("MADE_TO_ORDER") && o.status().equals("AWAITING_DEPOSIT");
            case FULL -> o.fulfillmentType().equals("IN_STOCK") && o.status().equals("AWAITING_PAYMENT");
            case BALANCE ->
                o.fulfillmentType().equals("MADE_TO_ORDER") && o.status().equals("AWAITING_BALANCE");
        };
        if (!stateOk) {
            throw new ApiException(
                    ErrorCode.INVALID_TRANSITION,
                    "A " + type + " payment request is not allowed while the order is " + o.status() + ".");
        }

        int shipping = req.shippingCents() == null ? 0 : req.shippingCents();
        if (shipping < 0) {
            throw ApiException.invalidField("shippingCents", "shippingCents must not be negative.");
        }
        if (shipping > 0 && (type == PaymentType.DEPOSIT || req.channel() != PaymentChannel.ARTISTREE)) {
            throw ApiException.invalidField(
                    "shippingCents", "shippingCents is only allowed for ARTISTREE FULL or BALANCE requests.");
        }
        long deposit = CheckoutService.depositCents(o.subtotalCents());
        long expected = switch (type) {
            case DEPOSIT -> deposit;
            case FULL -> o.subtotalCents() + (long) shipping;
            case BALANCE -> o.subtotalCents() - deposit + shipping;
        };
        if (req.amountCents() == null || req.amountCents() != expected) {
            throw ApiException.invalidField("amountCents", "amountCents must be " + expected + " for this order.");
        }
        PaymentUrlValidator.validate(req.paymentUrl(), settings.get().paymentAllowedDomains());

        Instant now = clock.instant();
        Instant dueAt = now.plus(type == PaymentType.BALANCE ? DUE_BALANCE : DUE_DEPOSIT_FULL);
        long id;
        try {
            id = jdbc.queryForObject(
                    "INSERT INTO payment_request (order_id, type, amount_cents, channel, payment_url, due_at,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                    Long.class,
                    orderId,
                    type.name(),
                    req.amountCents(),
                    req.channel().name(),
                    req.paymentUrl(),
                    Timestamp.from(dueAt),
                    Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // Both unique indexes (one PENDING per order+type, unique external ref) map to the same 409.
            throw new ApiException(
                    ErrorCode.CONFLICT, "A pending " + type + " payment request already exists for this order.");
        }
        // Always bump version: a concurrent cancel cannot see this uncommitted PENDING request,
        // so its optimistic "WHERE version = ?" update must fail with VERSION_CONFLICT.
        jdbc.update(
                "UPDATE customer_order SET shipping_cents = ?, version = version + 1, updated_at = ?" + " WHERE id = ?",
                shipping,
                Timestamp.from(now),
                orderId);
        outbox.enqueue(
                "payment:" + id + ":link",
                o.email(),
                "ORDER_PAYMENT_LINK",
                Map.of(
                        "publicCode",
                        o.publicCode(),
                        "amountCents",
                        req.amountCents(),
                        "dueAt",
                        dueAt.toString(),
                        "paymentUrl",
                        req.paymentUrl()));
        audit.admin(
                "PAYMENT_REQUEST_CREATED",
                "PAYMENT_REQUEST",
                id,
                null,
                Map.of(
                        "orderId", orderId,
                        "type", type.name(),
                        "amountCents", req.amountCents(),
                        "channel", req.channel().name(),
                        // Store-owned link, not a secret or PII.
                        "paymentUrl", req.paymentUrl()));
        return new PaymentRequestView(
                id, orderId, type, req.amountCents(), req.channel(), req.paymentUrl(), "PENDING", dueAt);
    }

    public Confirmed confirm(long requestId, String externalRefRaw) {
        String ref = externalRefRaw == null ? "" : externalRefRaw.trim();
        if (ref.isEmpty() || ref.length() > 100 || ref.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField(
                    "externalRef", "externalRef must be 1 to 100 characters without control characters.");
        }
        RequestRow r = lockRequest(requestId);
        if (r.status().equals("PAID")) {
            if (!ref.equals(r.externalRef())) {
                throw new ApiException(
                        ErrorCode.CONFLICT, "This payment request was confirmed with another reference.");
            }
            // Idempotent repeat: no side effects.
            return new Confirmed(r.id(), r.orderId(), r.type(), "PAID", orderStatus(r.orderId()), r.confirmedAt());
        }
        if (!r.status().equals("PENDING")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Payment request is " + r.status() + ".");
        }
        Integer inUse = jdbc.queryForObject(
                "SELECT count(*) FROM payment_request WHERE channel = ? AND external_ref = ? AND id <> ?",
                Integer.class,
                r.channel().name(),
                ref,
                r.id());
        if (inUse != null && inUse > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "externalRef is already used by another payment request.");
        }

        // Order locked after the request; its current version lets the state machine's optimistic check pass.
        Integer version = jdbc.queryForObject(
                "SELECT version FROM customer_order WHERE id = ? FOR UPDATE", Integer.class, r.orderId());
        // Micros: what Postgres stores, so a replay returns the identical confirmedAt.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            jdbc.update(
                    "UPDATE payment_request SET status = 'PAID', confirmed_at = ?, external_ref = ?,"
                            + " version = version + 1 WHERE id = ? AND status = 'PENDING'",
                    Timestamp.from(now),
                    ref,
                    r.id());
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "externalRef is already used by another payment request.");
        }
        OrderAction action = switch (r.type()) {
            case DEPOSIT -> OrderAction.CONFIRM_DEPOSIT;
            case FULL -> OrderAction.CONFIRM_PAYMENT;
            case BALANCE -> OrderAction.CONFIRM_BALANCE;
        };
        String orderStatus = orders.transition(r.orderId(), action, version, OrderActor.ADMIN)
                .name();
        if (r.type() == PaymentType.DEPOSIT) {
            // Exactly once per item: the transition above only succeeds from AWAITING_DEPOSIT.
            jdbc.query(
                            "SELECT id, quantity FROM order_item WHERE order_id = ? ORDER BY id",
                            (rs, i) -> new long[] {rs.getLong(1), rs.getInt(2)},
                            r.orderId())
                    .forEach(item -> queue.allocate(item[0], (int) item[1]));
        }
        audit.admin(
                "PAYMENT_CONFIRMED",
                "PAYMENT_REQUEST",
                r.id(),
                Map.of("status", "PENDING"),
                Map.of(
                        "status",
                        "PAID",
                        "orderId",
                        r.orderId(),
                        "type",
                        r.type().name(),
                        "externalRef",
                        ref));
        return new Confirmed(r.id(), r.orderId(), r.type(), "PAID", orderStatus, now);
    }

    public PaymentRequestView cancel(long requestId, String reasonRaw) {
        String reason = reasonRaw == null ? "" : reasonRaw.trim();
        if (reason.isEmpty() || reason.length() > 500 || reason.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("reason", "reason must be 1 to 500 characters without control characters.");
        }
        RequestRow r = lockRequest(requestId);
        if (r.status().equals("PENDING")) {
            jdbc.update(
                    "UPDATE payment_request SET status = 'CANCELLED', version = version + 1"
                            + " WHERE id = ? AND status = 'PENDING'",
                    r.id());
            audit.admin(
                    "PAYMENT_REQUEST_CANCELLED",
                    "PAYMENT_REQUEST",
                    r.id(),
                    Map.of("status", "PENDING"),
                    Map.of(
                            "status",
                            "CANCELLED",
                            "orderId",
                            r.orderId(),
                            "type",
                            r.type().name(),
                            "reason",
                            reason));
        } else if (!r.status().equals("CANCELLED")) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "Payment request is " + r.status() + ".");
        }
        return view(r.id());
    }

    /**
     * Cancels every PENDING request of an order (shop cancels the order). Locks all the order's requests in id order
     * first, so callers must not hold the order row lock yet (lock order: payment_request, then customer_order).
     * Returns how many were cancelled; no email.
     */
    public int cancelPendingForOrder(long orderId) {
        return cancelPendingForOrder(orderId, "ADMIN");
    }

    /** Same, with the audit actor ({@code ADMIN}, {@code SYSTEM} or {@code CUSTOMER}). */
    public int cancelPendingForOrder(long orderId, String actor) {
        List<long[]> locked = jdbc.query(
                "SELECT id, status = 'PENDING' FROM payment_request WHERE order_id = ? ORDER BY id FOR UPDATE",
                (rs, i) -> new long[] {rs.getLong(1), rs.getBoolean(2) ? 1 : 0},
                orderId);
        List<Long> ids = locked.stream().filter(r -> r[1] == 1).map(r -> r[0]).toList();
        if (ids.isEmpty()) {
            return 0;
        }
        jdbc.update(
                "UPDATE payment_request SET status = 'CANCELLED', version = version + 1"
                        + " WHERE order_id = ? AND status = 'PENDING'",
                orderId);
        audit.by(
                actor,
                "PAYMENT_REQUESTS_CANCELLED_WITH_ORDER",
                "ORDER",
                orderId,
                Map.of("status", "PENDING"),
                Map.of("status", "CANCELLED", "requestIds", ids));
        return ids.size();
    }

    @Transactional(readOnly = true)
    public List<PaymentRequestView> listForOrder(long orderId) {
        return jdbc.query(
                "SELECT id, order_id, type, amount_cents, channel, payment_url, status, due_at FROM payment_request"
                        + " WHERE order_id = ? ORDER BY id",
                (rs, i) -> new PaymentRequestView(
                        rs.getLong(1),
                        rs.getLong(2),
                        PaymentType.valueOf(rs.getString(3)),
                        rs.getInt(4),
                        PaymentChannel.valueOf(rs.getString(5)),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getTimestamp(8).toInstant()),
                orderId);
    }

    private RequestRow lockRequest(long id) {
        List<RequestRow> rows = jdbc.query(
                "SELECT id, order_id, type, channel, status, external_ref, confirmed_at FROM payment_request"
                        + " WHERE id = ? FOR UPDATE",
                (rs, i) -> new RequestRow(
                        rs.getLong(1),
                        rs.getLong(2),
                        PaymentType.valueOf(rs.getString(3)),
                        PaymentChannel.valueOf(rs.getString(4)),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()),
                id);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Payment request not found.");
        }
        return rows.get(0);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM customer_order WHERE id = ?", String.class, orderId);
    }

    private PaymentRequestView view(long id) {
        return jdbc.queryForObject(
                "SELECT id, order_id, type, amount_cents, channel, payment_url, status, due_at FROM payment_request"
                        + " WHERE id = ?",
                (rs, i) -> new PaymentRequestView(
                        rs.getLong(1),
                        rs.getLong(2),
                        PaymentType.valueOf(rs.getString(3)),
                        rs.getInt(4),
                        PaymentChannel.valueOf(rs.getString(5)),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getTimestamp(8).toInstant()),
                id);
    }
}
