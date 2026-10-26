package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bkstudio.support.AbstractPostgresTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.TestPropertySource;

/**
 * The create-admin CLI. The real {@code --create-admin} start-up is not launched (it would boot a second app and
 * exit the JVM); the runner is built by hand with the real AdminBootstrap and a mock Environment, which exercises
 * exactly the code the flag runs. The {@code @ConditionalOnProperty("create-admin")} wiring is not covered.
 */
// Test-only key (32 zero bytes, base64); not a secret.
@TestPropertySource(properties = "app.admin.totp-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class CreateAdminRunnerIT extends AbstractPostgresTest {

    private static final String PASSWORD = "cli-password-123456";
    private static final Pattern CODE = Pattern.compile("^ {2}([A-Z2-7]{4}(?:-[A-Z2-7]{4}){3})$", Pattern.MULTILINE);

    @Autowired
    AdminBootstrap bootstrap;

    @Autowired
    AdminUserRepository admins;

    @Autowired
    TotpSecretCipher cipher;

    @Autowired
    JdbcTemplate jdbc;

    private CreateAdminRunner runner(String password) {
        MockEnvironment env = new MockEnvironment();
        if (password != null) {
            env.setProperty("ADMIN_INITIAL_PASSWORD", password);
        }
        return new CreateAdminRunner(bootstrap, env);
    }

    private static String run(CreateAdminRunner runner, String email) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            runner.run(new DefaultApplicationArguments("--create-admin=" + email));
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void createsAdminWithEnabledTotp_encryptedSecret_andTenHashedRecoveryCodes() {
        String email = "Cli-" + UUID.randomUUID() + "@Example.com";

        String out = run(runner(PASSWORD), email);

        AdminUser u = admins.findByEmail(email).orElseThrow();
        assertThat(u.totpEnabled()).isTrue();
        byte[] plain = cipher.decrypt(u.totpSecretEncrypted());
        assertThat(u.totpSecretEncrypted()).isNotEqualTo(plain);
        assertThat(Arrays.equals(u.totpSecretEncrypted(), plain)).isFalse();
        assertThat(u.passwordHash()).startsWith("$argon2id$").doesNotContain(PASSWORD);

        List<String> hashes = jdbc.queryForList(
                "SELECT code_hash FROM admin_recovery_code WHERE admin_id = ? AND used_at IS NULL",
                String.class,
                u.id());
        assertThat(hashes).hasSize(10).allMatch(h -> h.matches("[0-9a-f]{64}"));

        // stdout: secret, URI, ten codes (whose hashes are the stored rows); never the email or password.
        assertThat(out).contains(Base32.encode(plain)).contains("otpauth://totp/");
        Matcher m = CODE.matcher(out);
        List<String> printed = m.results().map(r -> r.group(1)).toList();
        assertThat(printed).hasSize(10).doesNotHaveDuplicates();
        assertThat(printed.stream().map(RecoveryCodes::hash)).containsExactlyInAnyOrderElementsOf(hashes);
        assertThat(out)
                .doesNotContain(PASSWORD)
                .doesNotContain(email)
                .doesNotContain(email.toLowerCase())
                .doesNotContainIgnoringCase("example.com");
    }

    @Test
    void duplicateEmail_caseInsensitive_isRefused() {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        run(runner(PASSWORD), email);

        assertThatThrownBy(() -> run(runner(PASSWORD), email.toUpperCase()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM admin_user WHERE lower(email) = ?", Integer.class, email.toLowerCase());
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void passwordShorterThanTwelve_isRefused_andNothingIsCreated() {
        String email = "short-" + UUID.randomUUID() + "@example.com";

        assertThatThrownBy(() -> run(runner("elevenchars"), email)).isInstanceOf(IllegalStateException.class);

        assertThat(admins.findByEmail(email)).isEmpty();
    }

    @Test
    void missingPassword_isRefused_andNothingIsCreated() {
        String email = "nopw-" + UUID.randomUUID() + "@example.com";

        assertThatThrownBy(() -> run(runner(null), email)).isInstanceOf(IllegalStateException.class);

        assertThat(admins.findByEmail(email)).isEmpty();
    }

    @Test
    void passwordOfExactlyTwelveChars_isAccepted() {
        String email = "twelve-" + UUID.randomUUID() + "@example.com";

        run(runner("twelve-chars"), email);

        assertThat(admins.findByEmail(email)).isPresent();
    }
}
