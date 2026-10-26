package com.bkstudio.admin.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class RecoveryCodeRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    RecoveryCodeRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void insertAll(long adminId, List<String> codeHashes) {
        jdbc.batchUpdate(
                "INSERT INTO admin_recovery_code (admin_id, code_hash) VALUES (?, ?)",
                codeHashes,
                codeHashes.size(),
                (ps, hash) -> {
                    ps.setLong(1, adminId);
                    ps.setString(2, hash);
                });
    }

    /** Marks the code used. One atomic UPDATE, so two concurrent calls cannot both succeed. */
    public boolean consume(long adminId, String codeHash) {
        return jdbc.update(
                        "UPDATE admin_recovery_code SET used_at = ?"
                                + " WHERE admin_id = ? AND code_hash = ? AND used_at IS NULL",
                        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                        adminId,
                        codeHash)
                == 1;
    }
}
