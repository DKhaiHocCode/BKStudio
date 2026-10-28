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
}
