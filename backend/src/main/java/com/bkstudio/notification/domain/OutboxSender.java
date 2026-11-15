package com.bkstudio.notification.domain;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends PENDING rows of email_outbox (WORKFLOWS §10). Each row is its own step with no long transaction, so one bad
 * row never blocks the batch. A crash between send and the SENT update re-sends with the same Idempotency-Key, which
 * the provider ignores.
 *
 * <p>Each row is claimed with FOR UPDATE SKIP LOCKED in its own transaction that spans the send, so two instances
 * (or two overlapping runs) never send the same row. At most {@code dailyCap} emails go out per rolling 24 hours;
 * rows over the cap stay PENDING and are sent when the window frees up.
 * shortcut: the cap is read once per run without a lock, so two instances can overshoot it by one batch (20).
 */
@Component
public class OutboxSender {

    static final int BATCH = 20;
    static final int MAX_ATTEMPTS = 8;
    static final int RETENTION_DAYS = 7;
    static final Duration CAP_WARN_INTERVAL = Duration.ofHours(1);

    private static final Pattern PAYMENT_LINK_KEY = Pattern.compile("payment:(\\d+):link");

    private static final Logger log = LoggerFactory.getLogger(OutboxSender.class);

    private record Row(long id, String dedupeKey, String to, String template, String payload, int attempts) {}

    private final JdbcTemplate jdbc;
    private final EmailSender sender;
    private final EmailTemplates templates;
    private final JsonMapper json;
    private final Clock clock;
    private final NotificationProperties props;
    private final TransactionTemplate tx;
    private volatile Instant capWarnedAt; // last "cap reached" WARN, so it is logged once an hour, not every run

    OutboxSender(
            JdbcTemplate jdbc,
            EmailSender sender,
            EmailTemplates templates,
            JsonMapper json,
            Clock clock,
            NotificationProperties props,
            PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.sender = sender;
        this.templates = templates;
        this.json = json;
        this.clock = clock;
        this.props = props;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void scheduled() {
        if (props.outbox().enabled()) {
            runOnce();
        }
    }

    /** Processes up to 20 due rows (fewer near the daily cap); returns how many were attempted. */
    public int runOnce() {
        // Links in payloads are secrets: whatever was never sent (FAILED, stuck PENDING) loses them after 7 days.
        jdbc.update(
                "UPDATE email_outbox SET payload = payload - 'orderUrl' - 'paymentUrl' WHERE created_at < ?"
                        + " AND (jsonb_exists(payload, 'orderUrl') OR jsonb_exists(payload, 'paymentUrl'))",
                Timestamp.from(clock.instant().minus(Duration.ofDays(RETENTION_DAYS))));
        int cap = props.outbox().dailyCap();
        Integer sentToday = jdbc.queryForObject(
                "SELECT count(*) FROM email_outbox WHERE sent_at > ?",
                Integer.class,
                Timestamp.from(clock.instant().minus(Duration.ofDays(1))));
        int room = Math.min(BATCH, cap - sentToday);
        if (room <= 0) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM email_outbox WHERE status = 'PENDING' AND next_attempt_at <= ?",
                    Integer.class,
                    Timestamp.from(clock.instant()));
            Instant now = clock.instant();
            if (waiting > 0 && (capWarnedAt == null || !now.isBefore(capWarnedAt.plus(CAP_WARN_INTERVAL)))) {
                capWarnedAt = now;
                log.warn("Outbox daily cap reached (cap={}): {} due emails stay PENDING", cap, waiting);
            }
            return 0;
        }
        // Near the cap the room goes to business-critical mail (payment links, status changes) first; ORDER_RECEIVED
        // repeats a link the customer already saw on the confirmation page, so it waits.
        List<Row> rows = jdbc.query(
                "SELECT id, dedupe_key, to_email, template, payload::text, attempts FROM email_outbox"
                        + " WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY (template = 'ORDER_RECEIVED'), next_attempt_at LIMIT ?",
                (rs, i) -> new Row(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getInt(6)),
                Timestamp.from(clock.instant()),
                room);
        int attempted = 0;
        for (Row r : rows) {
            try {
                // One transaction per row: the row lock is held until the send and its status update commit.
                boolean handled = tx.execute(status -> {
                    Integer attempts = claim(r);
                    if (attempts == null) {
                        return false;
                    }
                    // attempts of the locked row, not of the batch read: another sender may have retried it since
                    process(new Row(r.id(), r.dedupeKey(), r.to(), r.template(), r.payload(), attempts));
                    return true;
                });
                if (handled) {
                    attempted++;
                }
            } catch (RuntimeException e) {
                attempted++;
                log.error(
                        "Outbox row {} could not be processed: {}",
                        r.id(),
                        e.getClass().getSimpleName());
                // Best effort: push the row back so a poison row cannot starve the queue.
                try {
                    retryLater(r, e.getClass().getSimpleName());
                } catch (RuntimeException ignored) {
                    // DB unavailable: the row stays due and is retried on the next run
                }
            }
        }
        return attempted;
    }

    /**
     * Locks the row and returns its current attempts; null when another sender holds the row or already handled it
     * since the batch was read.
     */
    private Integer claim(Row r) {
        List<Integer> attempts = jdbc.queryForList(
                "SELECT attempts FROM email_outbox WHERE id = ? AND status = 'PENDING' AND next_attempt_at <= ?"
                        + " FOR UPDATE SKIP LOCKED",
                Integer.class,
                r.id(),
                Timestamp.from(clock.instant()));
        return attempts.isEmpty() ? null : attempts.get(0);
    }

    /** Payment link of a request that is no longer PENDING (cancelled, expired, paid) is dead: do not mail it. */
    private boolean paymentLinkIsDead(Row r) {
        Matcher m = PAYMENT_LINK_KEY.matcher(r.dedupeKey());
        if (!"ORDER_PAYMENT_LINK".equals(r.template()) || !m.matches()) {
            return false;
        }
        List<String> status = jdbc.queryForList(
                "SELECT status FROM payment_request WHERE id = ?", String.class, Long.parseLong(m.group(1)));
        return !status.isEmpty() && !"PENDING".equals(status.get(0));
    }

    private void process(Row r) {
        if (paymentLinkIsDead(r)) {
            // No SKIPPED status in the CHECK constraint: terminal SENT with sent_at NULL and a reason marks "not sent".
            jdbc.update(
                    "UPDATE email_outbox SET status = 'SENT', last_error = ?,"
                            + " payload = payload - 'orderUrl' - 'paymentUrl' WHERE id = ?",
                    "Skipped: payment request no longer pending",
                    r.id());
            log.info(
                    "Outbox email skipped id={} template={} (payment request no longer pending)", r.id(), r.template());
            return;
        }
        EmailMessage msg;
        try {
            Map<String, Object> payload = json.readValue(r.payload(), new TypeReference<Map<String, Object>>() {});
            EmailTemplates.Rendered out = templates.render(r.template(), payload);
            msg = new EmailMessage(r.to(), out.subject(), out.html(), out.text());
        } catch (RuntimeException e) {
            fail(r, "Render failed: " + e.getClass().getSimpleName());
            return;
        }
        try {
            sender.send(msg, r.dedupeKey());
        } catch (RuntimeException e) {
            fail(r, "Send error: " + e.getClass().getSimpleName());
            return;
        } catch (EmailSendException e) {
            if (e.transientFailure() && r.attempts() + 1 < MAX_ATTEMPTS) {
                retryLater(r, e.getMessage());
            } else {
                fail(r, e.getMessage());
            }
            return;
        }
        jdbc.update(
                "UPDATE email_outbox SET status = 'SENT', sent_at = ?, attempts = attempts + 1, last_error = NULL,"
                        + " payload = payload - 'orderUrl' - 'paymentUrl' WHERE id = ?",
                Timestamp.from(clock.instant()),
                r.id());
        log.info("Outbox email sent id={} template={}", r.id(), r.template());
    }

    private void retryLater(Row r, String error) {
        long minutes = 1L << r.attempts(); // 2^(newAttempts-1); at most 64 because attempt 8 fails instead
        jdbc.update(
                "UPDATE email_outbox SET attempts = attempts + 1, next_attempt_at = ?, last_error = ? WHERE id = ?",
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(minutes))),
                EmailSendException.safe(error),
                r.id());
        log.warn("Outbox email id={} template={} will retry in {} min", r.id(), r.template(), minutes);
    }

    private void fail(Row r, String error) {
        jdbc.update(
                "UPDATE email_outbox SET status = 'FAILED', attempts = attempts + 1, last_error = ? WHERE id = ?",
                EmailSendException.safe(error),
                r.id());
        // The payload keeps its link until the 7-day retention scrub. A FAILED ORDER_RECEIVED is the customer's only
        // order link, so it is logged loudly; re-queue it within 7 days with status='PENDING', attempts=0.
        log.error(
                "Outbox email id={} template={} FAILED{}",
                r.id(),
                r.template(),
                "ORDER_RECEIVED".equals(r.template()) ? " (customer order link not delivered, re-queue manually)" : "");
    }
}
