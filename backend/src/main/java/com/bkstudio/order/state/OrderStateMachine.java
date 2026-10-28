package com.bkstudio.order.state;

import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import com.bkstudio.shared.audit.AuditLogWriter;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only place an order's status changes (WORKFLOWS §1.3). Status update, audit row and customer email are written
 * in one transaction. Callers pass the version they read; a stale one yields VERSION_CONFLICT. Callers must enforce
 * which actor may perform which action; the state machine does not.
 */
@Service
@Transactional
public class OrderStateMachine {

    private record Row(int version, String publicCode, FulfillmentType type, OrderStatus status, String email) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper json;
    private final AuditLogWriter audit;

    OrderStateMachine(JdbcTemplate jdbc, Clock clock, JsonMapper json, AuditLogWriter audit) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
        this.audit = audit;
    }

    /** Optional extra data; all fields nullable. Never written to the audit log or the outbox. */
    public record Details(String trackingNumber, String cancelReason) {}

    public OrderStatus transition(long orderId, OrderAction action, int expectedVersion, OrderActor actor) {
        return transition(orderId, action, expectedVersion, actor, new Details(null, null));
    }

    /** Returns the new status. */
    public OrderStatus transition(
            long orderId, OrderAction action, int expectedVersion, OrderActor actor, Details details) {
        List<Row> rows = jdbc.query(
                "SELECT version, public_code, fulfillment_type, status, customer_email FROM customer_order WHERE id = ?",
                (rs, i) -> new Row(
                        rs.getInt(1),
                        rs.getString(2),
                        FulfillmentType.valueOf(rs.getString(3)),
                        OrderStatus.valueOf(rs.getString(4)),
                        rs.getString(5)),
                orderId);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Order not found.");
        }
        Row o = rows.get(0);
        // Checked before the table lookup: a stale caller must see VERSION_CONFLICT, not a misleading
        // INVALID_TRANSITION.
        if (o.version() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "Order was modified by someone else.");
        }
        OrderStatus next = OrderTransitions.next(o.type(), o.status(), action)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_TRANSITION, "Action " + action + " is not allowed from " + o.status() + "."));

        String tracking = null;
        if (action == OrderAction.SHIP) {
            tracking = details == null || details.trackingNumber() == null
                    ? ""
                    : details.trackingNumber().trim();
            if (tracking.isEmpty() || tracking.length() > 100 || hasControlChar(tracking)) {
                throw new ApiException(
                        ErrorCode.BAD_REQUEST,
                        "trackingNumber is required (max 100 characters, no control characters).");
            }
        }
        String reason = null;
        if (next.name().startsWith("CANCELLED") || next == OrderStatus.FORFEITED) {
            String r = details == null || details.cancelReason() == null
                    ? ""
                    : details.cancelReason().trim();
            if (r.length() > 500 || hasControlChar(r)) {
                throw new ApiException(
                        ErrorCode.BAD_REQUEST, "cancelReason is invalid (max 500 characters, no control characters).");
            }
            reason = r.isEmpty() ? null : r;
        }
        Timestamp now = Timestamp.from(clock.instant());
        int updated = jdbc.update(
                "UPDATE customer_order SET status = ?, version = version + 1, updated_at = ?,"
                        + " tracking_number = COALESCE(?, tracking_number),"
                        + " shipped_at = COALESCE(?::timestamptz, shipped_at),"
                        + " completed_at = COALESCE(?::timestamptz, completed_at),"
                        + " cancel_reason = COALESCE(?, cancel_reason)"
                        + " WHERE id = ? AND version = ?",
                next.name(),
                now,
                tracking,
                action == OrderAction.SHIP ? now : null,
                action == OrderAction.COMPLETE ? now : null,
                reason,
                orderId,
                expectedVersion);
        if (updated == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "Order was modified by someone else.");
        }

        audit.by(
                actor.name(),
                "ORDER_TRANSITION",
                "ORDER",
                orderId,
                Map.of("status", o.status().name()),
                Map.of("status", next.name(), "action", action.name()));

        String payload = json.writeValueAsString(Map.of("publicCode", o.publicCode(), "status", next.name()));
        jdbc.update(
                // dedupe_key contains the new version, so a conflict means this exact transition is already queued.
                "INSERT INTO email_outbox (dedupe_key, to_email, template, payload) VALUES (?, ?, ?, ?::jsonb)"
                        + " ON CONFLICT (dedupe_key) DO NOTHING",
                "order:" + orderId + ":" + action + ":" + (expectedVersion + 1),
                o.email(),
                action.emailTemplate(),
                payload);
        return next;
    }

    private static boolean hasControlChar(String s) {
        return s.chars().anyMatch(Character::isISOControl);
    }
}
