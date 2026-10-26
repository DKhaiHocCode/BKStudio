package com.bkstudio.admin.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class AdminUserRepository {

    private static final String COLUMNS = "id, email, password_hash, totp_secret_encrypted, totp_enabled,"
            + " failed_attempts, locked_until, last_login_at";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    AdminUserRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<AdminUser> findByEmail(String email) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM admin_user WHERE lower(email) = ?",
                        AdminUserRepository::map,
                        email.toLowerCase(Locale.ROOT))
                .stream()
                .findFirst();
    }

    public Optional<AdminUser> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM admin_user WHERE id = ?", AdminUserRepository::map, id).stream()
                .findFirst();
    }

    /** Creates an admin with TOTP enabled (the DB requires a secret when enabled). Returns the new id. */
    public long insert(String email, String passwordHash, byte[] totpSecretEncrypted) {
        return jdbc.queryForObject(
                "INSERT INTO admin_user (email, password_hash, totp_secret_encrypted, totp_enabled, created_at)"
                        + " VALUES (?, ?, ?, true, ?) RETURNING id",
                Long.class,
                email,
                passwordHash,
                totpSecretEncrypted,
                utc(clock.instant()));
    }

    /**
     * Reserves one second-factor attempt BEFORE it is verified, in a single atomic UPDATE: returns the new attempt
     * count, or empty while the account is locked. So parallel guesses cannot beat the lockout: at most
     * {@link AuthPolicy#MAX_FAILED_ATTEMPTS} verifications happen per lock window, however many requests race.
     * An expired lock restarts the budget at 1; the fifth reservation sets the lock. A success clears all of it
     * ({@link #recordSuccessfulLogin}).
     */
    public Optional<Integer> tryReserveAttempt(long id) {
        String next = "CASE WHEN locked_until IS NOT NULL THEN 1 ELSE failed_attempts + 1 END";
        return jdbc
                .query(
                        "UPDATE admin_user SET failed_attempts = " + next + ", locked_until = CASE WHEN " + next
                                + " >= ? THEN ? ELSE NULL END"
                                + " WHERE id = ? AND (locked_until IS NULL OR locked_until <= ?)"
                                + " RETURNING failed_attempts",
                        (rs, row) -> rs.getInt(1),
                        AuthPolicy.MAX_FAILED_ATTEMPTS,
                        utc(clock.instant().plus(AuthPolicy.LOCK_DURATION)),
                        id,
                        utc(clock.instant()))
                .stream()
                .findFirst();
    }

    /** Clears the failure counter and lock, and stamps last_login_at. */
    public void recordSuccessfulLogin(long id) {
        jdbc.update(
                "UPDATE admin_user SET failed_attempts = 0, locked_until = NULL, last_login_at = ? WHERE id = ?",
                utc(clock.instant()),
                id);
    }

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static AdminUser map(ResultSet rs, int row) throws SQLException {
        return new AdminUser(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getBytes("totp_secret_encrypted"),
                rs.getBoolean("totp_enabled"),
                rs.getInt("failed_attempts"),
                instant(rs, "locked_until"),
                instant(rs, "last_login_at"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
