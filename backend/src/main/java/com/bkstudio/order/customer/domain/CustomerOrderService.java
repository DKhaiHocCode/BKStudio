package com.bkstudio.order.customer.domain;

import com.bkstudio.notification.domain.OutboxWriter;
import com.bkstudio.order.admin.domain.OrderReservations;
import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import com.bkstudio.order.checkout.domain.CheckoutProperties;
import com.bkstudio.order.checkout.domain.CheckoutService;
import com.bkstudio.order.customer.api.CustomerOrderDtos.HistoryEntry;
import com.bkstudio.order.customer.api.CustomerOrderDtos.Item;
import com.bkstudio.order.customer.api.CustomerOrderDtos.OpenPayment;
import com.bkstudio.order.customer.api.CustomerOrderDtos.OrderView;
import com.bkstudio.order.state.OrderAction;
import com.bkstudio.order.state.OrderActor;
import com.bkstudio.order.state.OrderStateMachine;
import com.bkstudio.order.state.OrderStateMachine.Details;
import com.bkstudio.order.state.OrderStatus;
import com.bkstudio.payment.domain.PaymentRequestService;
import com.bkstudio.shared.audit.AuditLogWriter;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public order view, customer cancel and "email me my link" (WORKFLOWS §7, ADR-0012). The token is the only
 * credential: an unknown or malformed one is always the same 404. Nothing returned or logged contains personal data.
 */
@Service
@Transactional
public class CustomerOrderService {

    /** 32 random bytes, base64url without padding (see CheckoutService.newToken). */
    private static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final Logger log = LoggerFactory.getLogger(CustomerOrderService.class);

    private record Head(long id, int version, FulfillmentType type, OrderStatus status) {}

    private record OrderRow(
            String code,
            String type,
            String status,
            long subtotal,
            long shipping,
            String carrier,
            String tracking,
            Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OrderStateMachine machine;
    private final PaymentRequestService payments;
    private final OrderReservations reservations;
    private final OutboxWriter outbox;
    private final AuditLogWriter audit;
    private final CheckoutProperties props;
    private final CustomerOrderLimiters limiters;

    CustomerOrderService(
            JdbcTemplate jdbc,
            Clock clock,
            OrderStateMachine machine,
            PaymentRequestService payments,
            OrderReservations reservations,
            OutboxWriter outbox,
            AuditLogWriter audit,
            CheckoutProperties props,
            CustomerOrderLimiters limiters) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.machine = machine;
        this.payments = payments;
        this.reservations = reservations;
        this.outbox = outbox;
        this.audit = audit;
        this.props = props;
        this.limiters = limiters;
    }

    @Transactional(readOnly = true)
    public OrderView get(String token) {
        return build(idByToken(token));
    }

    /**
     * Lock order: payment_request rows first, then customer_order (as AdminOrderService.cancelByShop). The version is
     * read here under the row lock, so a concurrent admin action either finished before (we see its status and answer
     * 409) or waits for us (it then gets VERSION_CONFLICT).
     */
    public OrderView cancel(String token, boolean acknowledgeDepositLost) {
        long id = idByToken(token);
        int cancelledRequests = payments.cancelPendingForOrder(id, OrderActor.CUSTOMER.name());
        Head o = jdbc.queryForObject(
                "SELECT id, version, fulfillment_type, status FROM customer_order WHERE id = ? FOR UPDATE",
                (rs, i) -> new Head(
                        rs.getLong(1),
                        rs.getInt(2),
                        FulfillmentType.valueOf(rs.getString(3)),
                        OrderStatus.valueOf(rs.getString(4))),
                id);
        // An admin PaymentRequestService.create holds this order lock while it inserts, so once we hold it every
        // committed create is visible. cancelPendingForOrder above ran before the lock and may have missed it; the
        // version guard cannot catch it (create bumped the version before we re-read it). Our own requests are
        // already CANCELLED in this tx. Do not re-run cancelPendingForOrder here: it locks request-then-order,
        // like confirm, and the reverse order would deadlock.
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM payment_request WHERE order_id = ? AND status = 'PENDING'",
                        Long.class,
                        id)
                > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "The order was just updated. Please reload and try again.");
        }
        OrderAction action = customerAction(o.status())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_TRANSITION,
                        "This order can no longer be cancelled online. Please contact the shop."));
        boolean keepsDeposit = action == OrderAction.CANCEL_KEEP_DEPOSIT;
        if (keepsDeposit && !acknowledgeDepositLost) {
            throw ApiException.invalidField(
                    "acknowledgeDepositLost", "Cancelling this order forfeits your deposit. Confirm to continue.");
        }
        OrderStatus next = machine.transition(
                o.id(), action, o.version(), OrderActor.CUSTOMER, new Details(null, "Cancelled by customer"));
        // Deposit kept: the slot is freed but the quota is not given back (same rule as a forfeit).
        reservations.release(o.id(), o.type(), !keepsDeposit);
        audit.by(
                OrderActor.CUSTOMER.name(),
                "ORDER_CANCELLED_BY_CUSTOMER",
                "ORDER",
                o.id(),
                Map.of("status", o.status().name()),
                Map.of("status", next.name(), "cancelledPaymentRequests", cancelledRequests));
        return build(o.id());
    }

    /**
     * Returns normally whether or not the pair matches; only a match with free rate limits rotates the token and
     * queues the email. The new token is generated and hashed in every case (same CPU work), but a match also writes
     * UPDATE + outbox + audit, so a small timing difference remains. Accepted: the attacker needs both code and
     * email, is capped at 10/h/IP, and each hit is noisy for the victim (a new email).
     */
    public void lookup(String code, String email) {
        String token = CheckoutService.newToken();
        byte[] hash = CheckoutService.hashToken(token);
        String publicCode = code.trim().toUpperCase(Locale.ROOT);
        List<Map.Entry<Long, String>> rows = jdbc.query(
                "SELECT id, customer_email FROM customer_order WHERE public_code = ?",
                (rs, i) -> Map.entry(rs.getLong(1), rs.getString(2)),
                publicCode);
        if (rows.isEmpty() || !rows.get(0).getValue().trim().equalsIgnoreCase(email.trim())) {
            return;
        }
        long id = rows.get(0).getKey();
        if (!limiters.allowRotation(id)) {
            return;
        }
        // One statement: the old link dies and the version moves on (a stale admin tab gets VERSION_CONFLICT).
        Integer version = jdbc.queryForObject(
                "UPDATE customer_order SET access_token_hash = ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ? RETURNING version",
                Integer.class,
                hash,
                Timestamp.from(clock.instant()),
                id);
        // The key holds the new version: each rotation has exactly one email, and that email holds its own token.
        outbox.enqueue(
                "order:" + id + ":lookup:" + version,
                rows.get(0).getValue().trim(),
                "ORDER_LOOKUP_LINK",
                Map.of("publicCode", publicCode, "orderUrl", props.orderUrl(token)));
        audit.by(OrderActor.CUSTOMER.name(), "ORDER_LINK_REISSUED", "ORDER", id, null, Map.of("version", version));
        log.info("Order link reissued orderId={}", id);
    }

    // ---- helpers ----

    /** Which cancel the customer may do from this status; empty = contact the shop. */
    static Optional<OrderAction> customerAction(OrderStatus status) {
        return switch (status) {
            case AWAITING_PAYMENT, AWAITING_DEPOSIT -> Optional.of(OrderAction.CANCEL);
            case QUEUED -> Optional.of(OrderAction.CANCEL_KEEP_DEPOSIT);
            default -> Optional.empty();
        };
    }

    private long idByToken(String token) {
        if (token == null || !TOKEN_SHAPE.matcher(token).matches()) {
            throw notFound();
        }
        // (Object) cast: pass the byte[] as one argument, not as the varargs array.
        List<Long> ids = jdbc.query(
                "SELECT id FROM customer_order WHERE access_token_hash = ?", (rs, i) -> rs.getLong(1), (Object)
                        CheckoutService.hashToken(token));
        if (ids.isEmpty()) {
            throw notFound();
        }
        return ids.get(0);
    }

    private OrderView build(long id) {
        OrderRow r = jdbc.queryForObject(
                "SELECT public_code, fulfillment_type, status, subtotal_cents, shipping_cents, carrier, tracking_number,"
                        + " created_at FROM customer_order WHERE id = ?",
                (rs, i) -> new OrderRow(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getLong(4),
                        rs.getLong(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getTimestamp(8).toInstant()),
                id);
        OrderStatus status = OrderStatus.valueOf(r.status());
        boolean mto = FulfillmentType.MADE_TO_ORDER.name().equals(r.type());
        List<Item> items = jdbc.query(
                "SELECT product_name, scale, quantity, unit_price_cents FROM order_item WHERE order_id = ? ORDER BY id",
                (rs, i) -> new Item(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4)),
                id);
        List<OpenPayment> open = jdbc.query(
                "SELECT type, amount_cents, payment_url, due_at FROM payment_request"
                        + " WHERE order_id = ? AND status = 'PENDING' ORDER BY id",
                (rs, i) -> new OpenPayment(
                        rs.getString(1),
                        rs.getLong(2),
                        rs.getString(3),
                        rs.getTimestamp(4).toInstant()),
                id);
        String month = null;
        if (mto) {
            Date first = jdbc.queryForObject(
                    "SELECT min(q.production_month) FROM queue_entry q JOIN order_item i ON i.id = q.order_item_id"
                            + " WHERE i.order_id = ? AND q.status IN ('QUEUED','IN_PRODUCTION')",
                    Date.class,
                    id);
            month = first == null ? null : YearMonth.from(first.toLocalDate()).toString();
        }
        List<HistoryEntry> history = jdbc.query(
                "SELECT after ->> 'status', occurred_at FROM audit_log WHERE entity_type = 'ORDER' AND entity_id = ?"
                        + " AND action = 'ORDER_TRANSITION' ORDER BY occurred_at, id",
                (rs, i) -> new HistoryEntry(rs.getString(1), rs.getTimestamp(2).toInstant()),
                id);
        boolean shipped = status == OrderStatus.SHIPPED || status == OrderStatus.COMPLETED;
        Optional<OrderAction> cancel = customerAction(status);
        return new OrderView(
                r.code(),
                r.type(),
                r.status(),
                r.subtotal(),
                mto ? CheckoutService.depositCents((int) r.subtotal()) : 0,
                r.shipping(),
                items,
                open,
                month,
                shipped ? r.carrier() : null,
                shipped ? r.tracking() : null,
                history,
                r.createdAt(),
                cancel.isPresent(),
                cancel.orElse(null) == OrderAction.CANCEL_KEEP_DEPOSIT);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Order not found.");
    }
}
