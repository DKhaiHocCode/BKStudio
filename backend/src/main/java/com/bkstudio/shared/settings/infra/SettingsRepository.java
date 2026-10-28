package com.bkstudio.shared.settings.infra;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** Plain-SQL access to app_setting and terms_document. No transaction of its own: the service owns it. */
@Repository
public class SettingsRepository {

    public record TermsRow(String version, String contentMd, Instant publishedAt) {}

    private final JdbcTemplate jdbc;

    SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Transaction-scoped advisory lock: concurrent holders of the same key queue until commit/rollback. */
    public void advisoryLock(long key) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", (RowCallbackHandler) rs -> {}, key);
    }

    public Map<String, String> findAll() {
        Map<String, String> out = new HashMap<>();
        jdbc.query("SELECT key, value FROM app_setting", rs -> {
            out.put(rs.getString(1), rs.getString(2));
        });
        return out;
    }

    public void upsert(String key, String value, Instant now) {
        jdbc.update(
                "INSERT INTO app_setting (key, value, updated_at) VALUES (?, ?, ?)"
                        + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at",
                key,
                value,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    public void delete(String key) {
        jdbc.update("DELETE FROM app_setting WHERE key = ?", key);
    }

    public Optional<TermsRow> latestTerms() {
        return jdbc
                .query(
                        "SELECT version, content_md, published_at FROM terms_document"
                                + " ORDER BY published_at DESC, version DESC LIMIT 1",
                        (rs, i) -> new TermsRow(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getObject(3, OffsetDateTime.class).toInstant()))
                .stream()
                .findFirst();
    }

    /** Atomic: returns false when the version already exists (no unique-violation race). */
    public boolean insertTerms(String version, String contentMd, Instant now) {
        return jdbc.update(
                        "INSERT INTO terms_document (version, content_md, published_at) VALUES (?, ?, ?)"
                                + " ON CONFLICT (version) DO NOTHING",
                        version,
                        contentMd,
                        OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                == 1;
    }
}
