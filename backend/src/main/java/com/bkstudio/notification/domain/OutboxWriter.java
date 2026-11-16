package com.bkstudio.notification.domain;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Queues an email in the caller's transaction, so state change and email commit or roll back together. */
@Component
public class OutboxWriter {

    /** Never deliverable (.invalid is a reserved TLD) and passes the email format checks. */
    public static final String ANONYMISED_EMAIL = "anonymised@invalid.invalid";

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    OutboxWriter(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Idempotent per dedupeKey: a repeat is silently ignored. The payload must hold no name, address or phone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String dedupeKey, String toEmail, String template, Map<String, Object> payload) {
        jdbc.update(
                "INSERT INTO email_outbox (dedupe_key, to_email, template, payload) VALUES (?, ?, ?, ?::jsonb)"
                        + " ON CONFLICT (dedupe_key) DO NOTHING",
                dedupeKey,
                toEmail,
                template,
                json.writeValueAsString(payload));
    }

    /**
     * Clears the recipient and any link of the rows whose dedupe_key matches the LIKE pattern (data retention). A row
     * still PENDING is closed the way OutboxSender closes a mail it deliberately does not send: SENT with sent_at
     * NULL and a reason, so it is never mailed to the placeholder, does not count against the daily cap (sent_at) and
     * does not show up as FAILED on the dashboard. Safe to repeat; returns rows changed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int anonymise(String dedupeKeyLike) {
        // Both CASEs read the row's old status (SQL evaluates SET against the row before the update).
        return jdbc.update(
                "UPDATE email_outbox SET to_email = ?, payload = payload - 'orderUrl' - 'paymentUrl',"
                        + " last_error = CASE WHEN status = 'PENDING' THEN 'Skipped: anonymised' ELSE last_error END,"
                        + " status = CASE WHEN status = 'PENDING' THEN 'SENT' ELSE status END"
                        + " WHERE dedupe_key LIKE ? AND to_email <> ?",
                ANONYMISED_EMAIL,
                dedupeKeyLike,
                ANONYMISED_EMAIL);
    }
}
