package com.bkstudio.order.admin.domain;

import com.bkstudio.notification.domain.OutboxFailures;
import com.bkstudio.order.admin.api.AdminOrderDtos.Dashboard;
import com.bkstudio.order.admin.api.AdminOrderDtos.DashboardGroup;
import com.bkstudio.order.admin.api.AdminOrderDtos.DashboardItem;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin to-do groups (API_SPEC dashboard). Every order with an allowed admin action lands in at least one group.
 * SQL is constant; only {@code now}/{@code now+24h} are bound. Counts are exact, items capped at {@link #LIMIT}.
 */
@Service
@Transactional(readOnly = true)
public class AdminDashboardService {

    static final int LIMIT = 20;

    private static final String NO_PENDING =
            " AND NOT EXISTS (SELECT 1 FROM payment_request p WHERE p.order_id = o.id AND p.status = 'PENDING')";
    private static final String PENDING_JOIN = " FROM customer_order o JOIN payment_request p ON p.order_id = o.id"
            + " WHERE p.status = 'PENDING' AND o.status IN ('AWAITING_DEPOSIT','AWAITING_PAYMENT','AWAITING_BALANCE')";
    private static final String PLAIN_COLS =
            "SELECT o.id, o.public_code, o.status, o.fulfillment_type, NULL::timestamptz, o.created_at";
    private static final String DUE_COLS =
            "SELECT o.id, o.public_code, o.status, o.fulfillment_type, p.due_at, o.created_at";
    private static final String BY_CREATED = " ORDER BY o.created_at, o.id LIMIT " + LIMIT;
    private static final String BY_DUE = " ORDER BY p.due_at, o.id LIMIT " + LIMIT;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OutboxFailures outbox;

    AdminDashboardService(JdbcTemplate jdbc, Clock clock, OutboxFailures outbox) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.outbox = outbox;
    }

    public Dashboard get() {
        Instant now = clock.instant();
        Timestamp soon = Timestamp.from(now.plus(Duration.ofHours(24)));
        List<DashboardGroup> g = new ArrayList<>();
        g.add(group(
                "AWAITING_LINK",
                " FROM customer_order o WHERE o.status IN ('AWAITING_DEPOSIT','AWAITING_PAYMENT')" + NO_PENDING,
                PLAIN_COLS,
                BY_CREATED));
        g.add(group("PAYMENT_TO_CONFIRM", PENDING_JOIN, DUE_COLS, BY_DUE));
        // Includes already-overdue requests the expiry job has not swept yet.
        g.add(group("PAYMENT_EXPIRING", PENDING_JOIN + " AND p.due_at < ?", DUE_COLS, BY_DUE, soon));
        g.add(status("READY_TO_PRODUCE", "QUEUED"));
        g.add(status("IN_PRODUCTION", "IN_PRODUCTION"));
        g.add(group(
                "AWAITING_BALANCE_LINK",
                " FROM customer_order o WHERE o.status = 'AWAITING_BALANCE'" + NO_PENDING,
                PLAIN_COLS,
                BY_CREATED));
        g.add(status("READY_TO_SHIP", "PAID_IN_FULL"));
        g.add(status("SHIPPED_TO_COMPLETE", "SHIPPED"));
        g.add(group(
                "FORFEITED_TO_RESTOCK",
                " FROM customer_order o WHERE o.status = 'FORFEITED' AND o.fulfillment_type = 'MADE_TO_ORDER'"
                        + " AND o.restocked_at IS NULL",
                PLAIN_COLS,
                BY_CREATED));
        List<DashboardItem> failed = outbox.oldest(LIMIT).stream()
                .map(f -> new DashboardItem(f.id(), null, f.template(), null, null, f.createdAt()))
                .toList();
        g.add(new DashboardGroup("EMAIL_FAILED", outbox.count(), failed));
        return new Dashboard(now, g);
    }

    private DashboardGroup status(String key, String status) {
        return group(key, " FROM customer_order o WHERE o.status = ?", PLAIN_COLS, BY_CREATED, status);
    }

    private DashboardGroup group(String key, String from, String cols, String order, Object... args) {
        Long count = jdbc.queryForObject("SELECT count(*)" + from, Long.class, args);
        List<DashboardItem> items = jdbc.query(
                cols + from + order,
                (rs, i) -> new DashboardItem(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        instant(rs.getTimestamp(5)),
                        instant(rs.getTimestamp(6))),
                args);
        return new DashboardGroup(key, count == null ? 0 : count, items);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
