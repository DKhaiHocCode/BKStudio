package com.bkstudio.notification.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only view of outbox rows that exhausted their retries. Never exposes the recipient or payload. */
@Service
@Transactional(readOnly = true)
public class OutboxFailures {

    public record Failed(long id, String template, Instant createdAt) {}

    private final JdbcTemplate jdbc;

    OutboxFailures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE status = 'FAILED'", Long.class);
        return n == null ? 0 : n;
    }

    /** Oldest first. */
    public List<Failed> oldest(int limit) {
        return jdbc.query(
                "SELECT id, template, created_at FROM email_outbox WHERE status = 'FAILED'"
                        + " ORDER BY created_at, id LIMIT ?",
                (rs, i) -> new Failed(rs.getLong(1), rs.getString(2), ((Timestamp) rs.getTimestamp(3)).toInstant()),
                limit);
    }
}
