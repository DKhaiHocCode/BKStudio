package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bkstudio.support.AbstractPostgresTest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// Test-only key (32 zero bytes, base64); not a secret.
@TestPropertySource(properties = "app.admin.totp-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class AdminAuthIT extends AbstractPostgresTest {

    @Autowired
    AdminBootstrap bootstrap;

    @Autowired
    AdminUserRepository admins;

    @Autowired
    RecoveryCodeRepository recoveryCodes;

    @Autowired
    TotpSecretCipher cipher;

    @Autowired
    PasswordHasher hasher;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void bootstrapCreatesAdminWithEncryptedSecretAndHashedCodes() {
        AdminBootstrap.Created c = bootstrap.create("Boot@Example.com", "a-long-enough-password");

        AdminUser u = admins.findByEmail("BOOT@example.com").orElseThrow();
        assertThat(u.totpEnabled()).isTrue();
        assertThat(u.passwordHash()).startsWith("$argon2id$");
        assertThat(hasher.matches("a-long-enough-password", u.passwordHash())).isTrue();
        assertThat(cipher.decrypt(u.totpSecretEncrypted())).hasSize(20);
        assertThat(c.otpauthUri()).doesNotContain("example.com");
        assertThat(c.recoveryCodes()).hasSize(10);
        Integer stored = jdbc.queryForObject(
                "SELECT count(*) FROM admin_recovery_code WHERE admin_id = ? AND used_at IS NULL",
                Integer.class,
                u.id());
        assertThat(stored).isEqualTo(10);
        // Plain codes are never stored.
        assertThat(recoveryCodes.consume(
                        u.id(), RecoveryCodes.hash(c.recoveryCodes().getFirst())))
                .isTrue();

        assertThatThrownBy(() -> bootstrap.create("boot@example.com", "a-long-enough-password"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bootstrap.create("short@example.com", "short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservedAttemptsLockAtFiveRefuseTheSixthAndSuccessResets() {
        bootstrap.create("lock@example.com", "a-long-enough-password");
        AdminUser u = admins.findByEmail("lock@example.com").orElseThrow();

        for (int i = 1; i <= 4; i++) {
            assertThat(admins.tryReserveAttempt(u.id())).contains(i);
        }
        assertThat(admins.findById(u.id()).orElseThrow().lockedUntil()).isNull();
        assertThat(admins.tryReserveAttempt(u.id())).contains(5);
        AdminUser locked = admins.findById(u.id()).orElseThrow();
        assertThat(locked.lockedUntil()).isNotNull();
        assertThat(admins.tryReserveAttempt(u.id())).isEmpty();
        assertThat(admins.findById(u.id()).orElseThrow().failedAttempts()).isEqualTo(5);

        admins.recordSuccessfulLogin(u.id());
        AdminUser ok = admins.findById(u.id()).orElseThrow();
        assertThat(ok.failedAttempts()).isZero();
        assertThat(ok.lockedUntil()).isNull();
        assertThat(ok.lastLoginAt()).isNotNull();
    }

    @Test
    void recoveryCodeIsSingleUseUnderConcurrency() throws Exception {
        AdminBootstrap.Created c = bootstrap.create("race@example.com", "a-long-enough-password");
        long id = admins.findByEmail("race@example.com").orElseThrow().id();
        String hash = RecoveryCodes.hash(c.recoveryCodes().getFirst());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = IntStream.range(0, 8)
                    .<Callable<Boolean>>mapToObj(i -> () -> recoveryCodes.consume(id, hash))
                    .toList();
            long wins = 0;
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                if (f.get()) {
                    wins++;
                }
            }
            assertThat(wins).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
