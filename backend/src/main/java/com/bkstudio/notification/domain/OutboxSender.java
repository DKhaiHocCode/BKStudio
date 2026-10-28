package com.bkstudio.notification.domain;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends PENDING rows of email_outbox (WORKFLOWS §10). Each row is its own step with no long transaction, so one bad
 * row never blocks the batch. A crash between send and the SENT update re-sends with the same Idempotency-Key, which
 * the provider ignores.
 *
 * <p>shortcut: no row claiming (FOR UPDATE SKIP LOCKED); correct for the single backend instance, add it before
 * running two instances.
 */
@Component
public class OutboxSender {

    static final int BATCH = 20;
    static final int MAX_ATTEMPTS = 8;
    static final int RETENTION_DAYS = 7;

    private static final Logger log = LoggerFactory.getLogger(OutboxSender.class);

    private record Row(long id, String dedupeKey, String to, String template, String payload, int attempts) {}

    private final JdbcTemplate jdbc;
    private final EmailSender sender;
    private final EmailTemplates templates;
    private final JsonMapper json;
    private final Clock clock;
    private final NotificationProperties props;

    OutboxSender(
            JdbcTemplate jdbc,
            EmailSender sender,
            EmailTemplates templates,
            JsonMapper json,
            Clock clock,
            NotificationProperties props) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.templates = templates;
        this.json = json;
        this.clock = clock;
        this.props = props;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void scheduled() {
        if (props.outbox() == null || props.outbox().enabled()) {
            runOnce();
        }
    }

    /** Processes up to 20 due rows; returns how many were attempted. */
    public int runOnce() {
        // Links in payloads are secrets: whatever was never sent (FAILED, stuck PENDING) loses them after 7 days.
        jdbc.update(
                "UPDATE email_outbox SET payload = payload - 'orderUrl' - 'paymentUrl' WHERE created_at < ?"
                        + " AND (jsonb_exists(payload, 'orderUrl') OR jsonb_exists(payload, 'paymentUrl'))",
                Timestamp.from(clock.instant().minus(Duration.ofDays(RETENTION_DAYS))));
        List<Row> rows = jdbc.query(
                "SELECT id, dedupe_key, to_email, template, payload::text, attempts FROM email_outbox"
                        + " WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY next_attempt_at LIMIT ?",
                (rs, i) -> new Row(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getInt(6)),
                Timestamp.from(clock.instant()),
                BATCH);
        for (Row r : rows) {
            try {
                process(r);
            } catch (RuntimeException e) {
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
        return rows.size();
    }

    private void process(Row r) {
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
