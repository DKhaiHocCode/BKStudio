package com.bkstudio.order.jobs;

import com.bkstudio.notification.domain.OutboxWriter;
import com.bkstudio.order.admin.domain.OrderReservations;
import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import com.bkstudio.order.state.OrderAction;
import com.bkstudio.order.state.OrderActor;
import com.bkstudio.order.state.OrderStateMachine;
import com.bkstudio.order.state.OrderStateMachine.Details;
import com.bkstudio.order.state.OrderStatus;
import com.bkstudio.shared.audit.AuditLogWriter;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment expiry, balance reminders, forfeit, auto-complete and anonymisation (WORKFLOWS §5, §10). Each order runs in its own transaction, so one
 * failure never blocks the rest, and every job is safe to re-run. Lock order: payment_request first, then
 * customer_order (same as PaymentRequestService.confirm). Logs carry ids and exception class names only.
 *
 * <p>shortcut: one batch of 50 per run, no paging; a permanently failing order at the head of the queue is retried
 * every run, so watch for repeated log lines if more than ~50 rows ever get stuck.
 */
@Component
public class OrderJobs {

    static final int BATCH = 50;

    private static final Logger log = LoggerFactory.getLogger(OrderJobs.class);

    private record Reminder(
            long requestId,
            long orderId,
            String publicCode,
            String email,
            int amountCents,
            Instant dueAt,
            String url) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OrderStateMachine machine;
    private final OrderReservations reservations;
    private final OutboxWriter outbox;
    private final AuditLogWriter audit;
    private final boolean enabled;
    private final Duration completeAfter;
    private final Duration retention;

    /** Terminal statuses as an SQL IN list (enum names only, never user input). */
    private static final String TERMINAL = Arrays.stream(OrderStatus.values())
            .filter(OrderStatus::isTerminal)
            .map(s -> "'" + s.name() + "'")
            .collect(Collectors.joining(","));

    /**
     * Every customer_order column the anonymise job overwrites, with its SQL value (constants only; the one "?" is
     * the random hash). Placeholders where NOT NULL / CHECK must hold: name, email format, line1, city, and tracking
     * of shipped orders. ship_country and carrier stay: statistics, not personal on their own.
     */
    static final Map<String, String> CLEARED = Map.ofEntries(
            Map.entry("customer_name", "'anonymised'"),
            Map.entry("customer_email", "'" + OutboxWriter.ANONYMISED_EMAIL + "'"),
            Map.entry("phone", "NULL"),
            Map.entry("ship_line1", "'anonymised'"),
            Map.entry("ship_line2", "NULL"),
            Map.entry("ship_city", "'anonymised'"),
            Map.entry("ship_region", "NULL"),
            Map.entry("ship_postal_code", "NULL"),
            Map.entry("terms_accepted_ip", "NULL"),
            Map.entry("admin_note", "NULL"),
            Map.entry("cancel_reason", "NULL"),
            Map.entry("tracking_number", "CASE WHEN tracking_number IS NOT NULL THEN 'anonymised' END"),
            Map.entry("access_token_hash", "?"));

    private static final String CLEAR_SQL = CLEARED.entrySet().stream()
            .map(e -> e.getKey() + " = " + e.getValue())
            .collect(Collectors.joining(", "));

    private static final SecureRandom RANDOM = new SecureRandom();

    OrderJobs(
            JdbcTemplate jdbc,
            PlatformTransactionManager txManager,
            Clock clock,
            OrderStateMachine machine,
            OrderReservations reservations,
            OutboxWriter outbox,
            AuditLogWriter audit,
            @Value("${app.jobs.enabled:true}") boolean enabled,
            @Value("${app.jobs.complete-after-days:30}") int completeAfterDays,
            @Value("${app.jobs.retention-days:730}") int retentionDays) {
        // A typo here would destroy customer data early or close orders at once: refuse to start instead.
        if (retentionDays < 365) {
            throw new IllegalStateException(
                    "app.jobs.retention-days must be at least 365 (personal data is kept that long), got "
                            + retentionDays);
        }
        if (completeAfterDays < 1) {
            throw new IllegalStateException(
                    "app.jobs.complete-after-days must be at least 1, got " + completeAfterDays);
        }
        this.completeAfter = Duration.ofDays(completeAfterDays);
        this.retention = Duration.ofDays(retentionDays);
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.machine = machine;
        this.reservations = reservations;
        this.outbox = outbox;
        this.audit = audit;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    void scheduledExpiry() {
        if (enabled) {
            expireUnpaid();
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 180_000)
    void scheduledReminders() {
        if (enabled) {
            remindBalance();
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 240_000)
    void scheduledForfeit() {
        if (enabled) {
            forfeitOverdue();
        }
    }

    @Scheduled(fixedDelay = 86_400_000, initialDelay = 300_000)
    void scheduledComplete() {
        if (enabled) {
            completeShipped();
        }
    }

    @Scheduled(fixedDelay = 86_400_000, initialDelay = 360_000)
    void scheduledAnonymise() {
        if (enabled) {
            anonymiseOld();
        }
    }

    /** SHIPPED for more than complete-after-days: COMPLETED by the state machine (audit + ORDER_COMPLETED email). */
    public int completeShipped() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(completeAfter));
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM customer_order WHERE status = 'SHIPPED' AND shipped_at < ? ORDER BY shipped_at, id LIMIT ?",
                Long.class,
                cutoff,
                BATCH);
        return runEach(ids, "complete", "orderId", id -> {
            List<Integer> version = jdbc.queryForList(
                    "SELECT version FROM customer_order WHERE id = ? AND status = 'SHIPPED' AND shipped_at < ? FOR UPDATE",
                    Integer.class,
                    id,
                    cutoff);
            if (version.isEmpty()) {
                return false;
            }
            machine.transition(id, OrderAction.COMPLETE, version.get(0), OrderActor.SYSTEM);
            return true;
        });
    }

    /**
     * Orders that ended more than retention-days ago lose their personal data (DATABASE.md §6); money, items, statuses
     * and timestamps stay. "Ended" is completed_at for COMPLETED, else updated_at: cancel/forfeit have no column of
     * their own and updated_at is never earlier than the final transition, so data is never cleared early (a later
     * note edit or restock only postpones it). anonymised_at makes a re-run skip the order.
     */
    public int anonymiseOld() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(retention));
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM customer_order WHERE anonymised_at IS NULL AND status IN (" + TERMINAL + ")"
                        + " AND COALESCE(completed_at, updated_at) < ? ORDER BY id LIMIT ?",
                Long.class,
                cutoff,
                BATCH);
        return runEach(ids, "anonymise", "orderId", id -> anonymise(id, cutoff));
    }

    /** One transaction. The conditional UPDATE is lock and re-check in one; false when the order no longer qualifies. */
    private boolean anonymise(long orderId, Timestamp cutoff) {
        byte[] deadHash = new byte[32]; // random, not the hash of any token: the old order link answers 404
        RANDOM.nextBytes(deadHash);
        int updated = jdbc.update(
                "UPDATE customer_order SET " + CLEAR_SQL + ", anonymised_at = ?, version = version + 1"
                        + " WHERE id = ? AND anonymised_at IS NULL AND status IN (" + TERMINAL + ")"
                        + " AND COALESCE(completed_at, updated_at) < ?",
                deadHash,
                Timestamp.from(clock.instant()),
                orderId,
                cutoff);
        if (updated == 0) {
            return false;
        }
        jdbc.update("UPDATE order_progress SET note = '' WHERE order_id = ?", orderId);
        int emails = outbox.anonymise("order:" + orderId + ":%");
        for (long requestId :
                jdbc.queryForList("SELECT id FROM payment_request WHERE order_id = ?", Long.class, orderId)) {
            emails += outbox.anonymise("payment:" + requestId + ":%");
        }
        audit.by(
                OrderActor.SYSTEM.name(),
                "ORDER_ANONYMISED",
                "ORDER",
                orderId,
                null,
                Map.of("fields", CLEARED.size(), "outboxRows", emails)); // counts only, never the cleared values
        return true;
    }

    /** DEPOSIT/FULL requests past due on an order still waiting for them: request EXPIRED, order CANCELLED. */
    public int expireUnpaid() {
        List<Long> ids = jdbc.queryForList(
                "SELECT pr.id FROM payment_request pr JOIN customer_order o ON o.id = pr.order_id"
                        + " WHERE pr.status = 'PENDING' AND pr.type IN ('DEPOSIT','FULL') AND pr.due_at <= ?"
                        + " AND o.status IN ('AWAITING_DEPOSIT','AWAITING_PAYMENT') ORDER BY pr.due_at, pr.id LIMIT ?",
                Long.class,
                Timestamp.from(clock.instant()),
                BATCH);
        return runEach(ids, "expiry", id -> closeOverdue(id, false));
    }

    /** BALANCE requests past due on an AWAITING_BALANCE order: request EXPIRED, order FORFEITED (email by state machine). */
    public int forfeitOverdue() {
        List<Long> ids = jdbc.queryForList(
                "SELECT pr.id FROM payment_request pr JOIN customer_order o ON o.id = pr.order_id"
                        + " WHERE pr.status = 'PENDING' AND pr.type = 'BALANCE' AND pr.due_at <= ?"
                        + " AND o.status = 'AWAITING_BALANCE' ORDER BY pr.due_at, pr.id LIMIT ?",
                Long.class,
                Timestamp.from(clock.instant()),
                BATCH);
        return runEach(ids, "forfeit", id -> closeOverdue(id, true));
    }

    /** Returns how many orders were actually changed. */
    private int runEach(List<Long> requestIds, String job, LongPredicate step) {
        return runEach(requestIds, job, "paymentRequestId", step);
    }

    private int runEach(List<Long> ids, String job, String idName, LongPredicate step) {
        int done = 0;
        for (long id : ids) {
            try {
                if (Boolean.TRUE.equals(tx.execute(s -> step.test(id)))) {
                    done++;
                }
            } catch (ApiException e) {
                // A concurrent admin action won (VERSION_CONFLICT / INVALID_TRANSITION): skip, but leave a trace.
                if (e.code() == ErrorCode.VERSION_CONFLICT || e.code() == ErrorCode.INVALID_TRANSITION) {
                    log.warn("Order job {} skipped: {}={} error={}", job, idName, id, e.code());
                } else {
                    log.error("Order job {} failed: {}={} error={}", job, idName, id, e.code());
                }
            } catch (RuntimeException e) {
                log.error(
                        "Order job {} failed: {}={} error={}",
                        job,
                        idName,
                        id,
                        e.getClass().getSimpleName());
            }
        }
        return done;
    }

    /** One transaction. Returns false when nothing is due any more (a concurrent confirm/cancel won). */
    private boolean closeOverdue(long requestId, boolean forfeit) {
        Instant now = clock.instant();
        List<Long> req = jdbc.queryForList(
                "SELECT order_id FROM payment_request WHERE id = ? AND status = 'PENDING' AND due_at <= ?"
                        + " AND type " + (forfeit ? "= 'BALANCE'" : "IN ('DEPOSIT','FULL')") + " FOR UPDATE",
                Long.class,
                requestId,
                Timestamp.from(now));
        if (req.isEmpty()) {
            return false;
        }
        long orderId = req.get(0);
        List<Object[]> order = jdbc.query(
                "SELECT version, fulfillment_type FROM customer_order WHERE id = ? FOR UPDATE",
                (rs, i) -> new Object[] {rs.getInt(1), rs.getString(2)},
                orderId);
        if (order.isEmpty()) {
            return false;
        }
        int version = (int) order.get(0)[0];
        FulfillmentType type = FulfillmentType.valueOf((String) order.get(0)[1]);
        jdbc.update("UPDATE payment_request SET status = 'EXPIRED', version = version + 1 WHERE id = ?", requestId);
        // A wrong order status makes the state machine throw INVALID_TRANSITION: runEach rolls back and skips.
        machine.transition(
                orderId,
                forfeit ? OrderAction.FORFEIT : OrderAction.CANCEL,
                version,
                OrderActor.SYSTEM,
                new Details(null, forfeit ? "balance not paid by due date" : "payment window expired"));
        if (!forfeit) {
            reservations.release(orderId, type, true);
        }
        audit.by(
                OrderActor.SYSTEM.name(),
                "PAYMENT_REQUEST_EXPIRED",
                "PAYMENT_REQUEST",
                requestId,
                Map.of("status", "PENDING"),
                Map.of("status", "EXPIRED", "orderId", orderId));
        return true;
    }

    /** 7-day and 1-day balance reminders; only the newest mark that is due is queued, once (dedupe_key). */
    public int remindBalance() {
        Instant nowInstant = clock.instant();
        Timestamp now = Timestamp.from(nowInstant);
        // The NOT EXISTS keeps already-reminded rows from filling the batch for days.
        List<Reminder> due = jdbc.query(
                "SELECT pr.id, o.id, o.public_code, o.customer_email, pr.amount_cents, pr.due_at, pr.payment_url"
                        + " FROM payment_request pr JOIN customer_order o ON o.id = pr.order_id"
                        + " WHERE pr.status = 'PENDING' AND pr.type = 'BALANCE' AND o.status = 'AWAITING_BALANCE'"
                        + " AND pr.due_at > ? AND pr.due_at - interval '7 days' <= ?"
                        + " AND NOT EXISTS (SELECT 1 FROM email_outbox e WHERE e.dedupe_key = 'order:' || o.id"
                        + " || ':pr:' || pr.id || ':balance-reminder-' || CASE WHEN pr.due_at - interval '1 day' <= ? THEN '1d' ELSE '7d' END)"
                        + " ORDER BY pr.due_at, pr.id LIMIT ?",
                (rs, i) -> new Reminder(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getInt(5),
                        rs.getTimestamp(6).toInstant(),
                        rs.getString(7)),
                now,
                now,
                now,
                BATCH);
        int queued = 0;
        for (Reminder r : due) {
            String mark = r.dueAt().minus(Duration.ofDays(1)).isAfter(nowInstant) ? "7d" : "1d";
            try {
                Boolean ok = tx.execute(s -> {
                    // Lock the request first and re-check: a confirm may have landed since the query.
                    List<Long> still = jdbc.queryForList(
                            "SELECT id FROM payment_request WHERE id = ? AND status = 'PENDING' FOR UPDATE",
                            Long.class,
                            r.requestId());
                    if (still.isEmpty()) {
                        return false;
                    }
                    outbox.enqueue(
                            "order:" + r.orderId() + ":pr:" + r.requestId() + ":balance-reminder-" + mark,
                            r.email(),
                            "ORDER_REMINDER",
                            Map.of(
                                    "publicCode", r.publicCode(),
                                    "amountCents", r.amountCents(),
                                    "dueAt", r.dueAt().toString(),
                                    "paymentUrl", r.url()));
                    return true;
                });
                if (Boolean.TRUE.equals(ok)) {
                    queued++;
                }
            } catch (RuntimeException e) {
                log.error(
                        "Order job reminder failed: orderId={} error={}",
                        r.orderId(),
                        e.getClass().getSimpleName());
            }
        }
        return queued;
    }
}
