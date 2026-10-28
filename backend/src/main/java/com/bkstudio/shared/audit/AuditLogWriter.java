package com.bkstudio.shared.audit;

import com.bkstudio.shared.web.RequestIdFilter;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends one audit_log row for an admin action. MANDATORY propagation: it joins the caller's transaction, so the
 * audit row and the change commit or roll back together. Pass business fields only (never email, address, tokens).
 */
@Component
public class AuditLogWriter {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper json;

    AuditLogWriter(JdbcTemplate jdbc, Clock clock, JsonMapper json) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void admin(String action, String entityType, long entityId, Map<String, ?> before, Map<String, ?> after) {
        write("ADMIN", currentAdminId(), action, entityType, entityId, before, after);
    }

    /** For non-admin actors ({@code SYSTEM} jobs, {@code CUSTOMER}); actor id is recorded only for ADMIN. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void by(
            String actor,
            String action,
            String entityType,
            long entityId,
            Map<String, ?> before,
            Map<String, ?> after) {
        write(actor, "ADMIN".equals(actor) ? currentAdminId() : null, action, entityType, entityId, before, after);
    }

    private void write(
            String actor,
            Long actorId,
            String action,
            String entityType,
            long entityId,
            Map<String, ?> before,
            Map<String, ?> after) {
        jdbc.update(
                "INSERT INTO audit_log (occurred_at, actor, actor_id, action, entity_type, entity_id, before, after,"
                        + " request_id) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)",
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                actor,
                actorId,
                action,
                entityType,
                entityId,
                before == null ? null : json.writeValueAsString(before),
                after == null ? null : json.writeValueAsString(after),
                MDC.get(RequestIdFilter.MDC_KEY));
    }

    private static Long currentAdminId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        try {
            return a == null ? null : Long.valueOf(a.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
