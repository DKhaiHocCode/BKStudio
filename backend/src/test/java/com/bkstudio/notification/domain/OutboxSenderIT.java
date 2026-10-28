package com.bkstudio.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.support.AbstractPostgresTest;
import com.bkstudio.support.MutableClock;
import com.bkstudio.support.TestClockConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** T-024: OutboxSender.runOnce() against real Postgres with a programmable fake provider. */
@Import({TestClockConfig.class, OutboxSenderIT.FakeConfig.class})
class OutboxSenderIT extends AbstractPostgresTest {

    static final String URL = "https://shop.example/orders/SECRET_TOKEN";
    static final String JSON = "{\"publicCode\":\"BK-1\",\"orderUrl\":\"" + URL + "\"}";

    record Call(EmailMessage msg, String key) {}

    /** Records every call, then delegates to a behaviour the test sets. */
    static class FakeSender implements EmailSender {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        volatile EmailSender behaviour = (m, k) -> {};

        @Override
        public void send(EmailMessage m, String key) throws EmailSendException {
            calls.add(new Call(m, key));
            behaviour.send(m, key);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeConfig {
        @Bean
        @Primary
        FakeSender fakeSender() {
            return new FakeSender();
        }
    }

    @Autowired
    OutboxSender outbox;

    @Autowired
    OutboxWriter writer;

    @Autowired
    FakeSender fake;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    PlatformTransactionManager tm;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2030-01-01T00:00:10Z"));
        jdbc.execute("TRUNCATE email_outbox");
        fake.calls.clear();
        fake.behaviour = (m, k) -> {};
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("TRUNCATE email_outbox");
    }

    private long row(String dedupe, String to, String template, String payload, Instant due) {
        return jdbc.queryForObject(
                "INSERT INTO email_outbox (dedupe_key, to_email, template, payload, next_attempt_at, created_at)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?, ?) RETURNING id",
                Long.class,
                dedupe,
                to,
                template,
                payload,
                Timestamp.from(due),
                Timestamp.from(
                        clock.instant())); // the fake clock, not DB now(), so the retention scrub is deterministic
    }

    private long row(String dedupe) {
        return row(dedupe, "buyer.secret@example.com", "ORDER_RECEIVED", JSON, clock.instant());
    }

    private String str(long id, String col) {
        return jdbc.queryForObject("SELECT " + col + " FROM email_outbox WHERE id = ?", String.class, id);
    }

    private int attempts(long id) {
        return jdbc.queryForObject("SELECT attempts FROM email_outbox WHERE id = ?", Integer.class, id);
    }

    private Instant instant(long id, String col) {
        OffsetDateTime t =
                jdbc.queryForObject("SELECT " + col + " FROM email_outbox WHERE id = ?", OffsetDateTime.class, id);
        return t == null ? null : t.toInstant();
    }

    private String payloadKeys(long id) {
        return jdbc.queryForObject(
                "SELECT string_agg(k, ',' ORDER BY k) FROM email_outbox, jsonb_object_keys(payload) k WHERE id = ?",
                String.class,
                id);
    }

    private static EmailSender failing(boolean transientFailure) {
        return (m, k) -> {
            throw new EmailSendException("boom for " + m.to(), transientFailure);
        };
    }

    @Test
    void runOnce_success_marksSentAndStripsOrderUrl() {
        long id = row("k1");

        assertThat(outbox.runOnce()).isEqualTo(1);

        assertThat(str(id, "status")).isEqualTo("SENT");
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(instant(id, "sent_at")).isEqualTo(clock.instant());
        assertThat(str(id, "last_error")).isNull();
        assertThat(payloadKeys(id)).isEqualTo("publicCode");
        assertThat(fake.calls).hasSize(1);
        assertThat(fake.calls.get(0).msg().to()).isEqualTo("buyer.secret@example.com");
        assertThat(fake.calls.get(0).msg().html()).contains(URL);
    }

    @Test
    void runOnce_afterSent_secondRunSendsNothing() {
        row("k1");
        outbox.runOnce();

        assertThat(outbox.runOnce()).isZero();
        assertThat(fake.calls).hasSize(1);
    }

    @Test
    void runOnce_idempotencyKeyPassedToSender_isTheDedupeKey() {
        row("order:42:received");
        outbox.runOnce();
        assertThat(fake.calls.get(0).key()).isEqualTo("order:42:received");
    }

    @Test
    void runOnce_transientFailure_backsOffExponentiallyThenFailsOnEighthAttempt() {
        long id = row("k1");
        fake.behaviour = failing(true);

        long[] minutes = {1, 2, 4, 8, 16, 32, 64};
        for (int i = 0; i < minutes.length; i++) {
            Instant before = clock.instant();
            assertThat(outbox.runOnce()).as("attempt %d", i + 1).isEqualTo(1);
            assertThat(str(id, "status")).isEqualTo("PENDING");
            assertThat(attempts(id)).isEqualTo(i + 1);
            assertThat(instant(id, "next_attempt_at")).isEqualTo(before.plus(Duration.ofMinutes(minutes[i])));
            assertThat(str(id, "last_error")).isNotBlank();

            // not due yet: nothing is sent
            int callsBefore = fake.calls.size();
            assertThat(outbox.runOnce()).isZero();
            clock.advance(Duration.ofMinutes(minutes[i]).minusSeconds(1));
            assertThat(outbox.runOnce()).isZero();
            assertThat(fake.calls).hasSize(callsBefore);
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(outbox.runOnce()).isEqualTo(1);
        assertThat(str(id, "status")).isEqualTo("FAILED");
        assertThat(attempts(id)).isEqualTo(8);
        assertThat(fake.calls).hasSize(8);
        assertThat(payloadKeys(id)).isEqualTo("orderUrl,publicCode");
        assertThat(outbox.runOnce()).isZero();
    }

    @Test
    void runOnce_transientThenSuccess_endsSentWithClearedError() {
        long id = row("k1");
        fake.behaviour = failing(true);
        outbox.runOnce();
        fake.behaviour = (m, k) -> {};
        clock.advance(Duration.ofMinutes(1));

        assertThat(outbox.runOnce()).isEqualTo(1);

        assertThat(str(id, "status")).isEqualTo("SENT");
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(str(id, "last_error")).isNull();
    }

    @Test
    void runOnce_permanentFailure_failsImmediatelyAndKeepsOrderUrl() {
        long id = row("k1");
        fake.behaviour = failing(false);

        outbox.runOnce();

        assertThat(str(id, "status")).isEqualTo("FAILED");
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(payloadKeys(id)).isEqualTo("orderUrl,publicCode");
        assertThat(outbox.runOnce()).isZero();
    }

    @Test
    void runOnce_lastError_neverContainsRecipient() {
        long transientId = row("k1", "alice.secret@example.com", "ORDER_RECEIVED", JSON, clock.instant());
        long permanentId = row("k2", "bob.secret@example.com", "ORDER_RECEIVED", JSON, clock.instant());
        fake.behaviour = (m, k) -> {
            throw new EmailSendException("rejected " + m.to(), m.to().startsWith("alice"));
        };

        outbox.runOnce();

        assertThat(str(transientId, "last_error")).doesNotContain("alice").contains("[redacted]");
        assertThat(str(permanentId, "last_error")).doesNotContain("bob").contains("[redacted]");
    }

    @Test
    void runOnce_unknownTemplate_failsRowAndBatchContinues() {
        Instant t = clock.instant();
        long bad = row("k1", "a@example.com", "NOPE", JSON, t.minusSeconds(5));
        long good = row("k2", "b@example.com", "ORDER_RECEIVED", JSON, t);

        assertThat(outbox.runOnce()).isEqualTo(2);

        assertThat(str(bad, "status")).isEqualTo("FAILED");
        assertThat(str(bad, "last_error")).isEqualTo("Render failed: IllegalArgumentException");
        assertThat(str(good, "status")).isEqualTo("SENT");
        assertThat(fake.calls).hasSize(1);
    }

    @Test
    void runOnce_rowThrowingRuntimeException_doesNotBlockOthers() {
        Instant t = clock.instant();
        long boom = row("k1", "boom@example.com", "ORDER_RECEIVED", JSON, t.minusSeconds(5));
        long ok = row("k2", "ok@example.com", "ORDER_RECEIVED", JSON, t);
        fake.behaviour = (m, k) -> {
            if (m.to().startsWith("boom")) {
                throw new IllegalStateException("unexpected");
            }
        };

        assertThat(outbox.runOnce()).isEqualTo(2);

        assertThat(str(boom, "status")).isEqualTo("FAILED");
        assertThat(attempts(boom)).isEqualTo(1);
        assertThat(str(boom, "last_error")).isEqualTo("Send error: IllegalStateException");
        assertThat(str(ok, "status")).isEqualTo("SENT");
    }

    @Test
    void enqueue_sameDedupeKeyTwice_oneRowAndOneSend() {
        TransactionTemplate tx = new TransactionTemplate(tm);
        for (int i = 0; i < 2; i++) {
            tx.executeWithoutResult(s -> writer.enqueue(
                    "order:7:received",
                    "buyer@example.com",
                    "ORDER_RECEIVED",
                    Map.of("publicCode", "BK-7", "orderUrl", URL)));
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox", Integer.class))
                .isEqualTo(1);
        jdbc.update(
                "UPDATE email_outbox SET created_at = ?", Timestamp.from(clock.instant())); // fake clock vs DB now()
        outbox.runOnce();
        outbox.runOnce();
        assertThat(fake.calls).hasSize(1);
    }

    @Test
    void runOnce_batchCap_attemptsTwentyThenRemainder() {
        for (int i = 0; i < 25; i++) {
            row("k" + i);
        }

        assertThat(outbox.runOnce()).isEqualTo(20);
        assertThat(fake.calls).hasSize(20);
        assertThat(outbox.runOnce()).isEqualTo(5);
        assertThat(fake.calls).hasSize(25);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE status = 'SENT'", Integer.class))
                .isEqualTo(25);
    }

    @Test
    void runOnce_rowNotDueYet_isNotGivenToSenderUntilDue() {
        long id = row(
                "k1", "a@example.com", "ORDER_RECEIVED", JSON, clock.instant().plus(Duration.ofMinutes(10)));

        assertThat(outbox.runOnce()).isZero();
        assertThat(fake.calls).isEmpty();

        clock.advance(Duration.ofMinutes(10));
        assertThat(outbox.runOnce()).isEqualTo(1);
        assertThat(str(id, "status")).isEqualTo("SENT");
    }

    @Test
    void runOnce_retentionScrub_removesLinksFromOldRowsOnly() {
        String both = "{\"publicCode\":\"BK-1\",\"orderUrl\":\"" + URL + "\",\"paymentUrl\":\"https://pay.example/x\"}";
        Instant far = clock.instant().plus(Duration.ofDays(30)); // never due, so only the scrub touches them
        long oldPending = row("o1", "a@example.com", "ORDER_RECEIVED", both, far);
        long oldFailed = row("o2", "a@example.com", "ORDER_RECEIVED", both, far);
        long fresh = row("o3", "a@example.com", "ORDER_RECEIVED", both, far);
        jdbc.update("UPDATE email_outbox SET status = 'FAILED' WHERE id = ?", oldFailed);
        Timestamp eightDaysAgo = Timestamp.from(clock.instant().minus(Duration.ofDays(8)));
        jdbc.update("UPDATE email_outbox SET created_at = ? WHERE id IN (?, ?)", eightDaysAgo, oldPending, oldFailed);

        outbox.runOnce();

        assertThat(payloadKeys(oldPending)).isEqualTo("publicCode");
        assertThat(payloadKeys(oldFailed)).isEqualTo("publicCode");
        assertThat(payloadKeys(fresh)).isEqualTo("orderUrl,paymentUrl,publicCode");
    }

    @Test
    void runOnce_success_alsoRemovesPaymentUrl() {
        long id = row(
                "k1",
                "a@example.com",
                "ORDER_PAYMENT_LINK",
                "{\"publicCode\":\"BK-1\",\"paymentUrl\":\"https://pay.example/x\",\"amountCents\":100}",
                clock.instant());

        outbox.runOnce();

        assertThat(str(id, "status")).isEqualTo("SENT");
        assertThat(payloadKeys(id)).isEqualTo("amountCents,publicCode");
    }

    @Test
    void scheduled_runsOnlyWhenEnabled() {
        row("k1");
        OutboxSender off = new OutboxSender(
                jdbc,
                fake,
                new EmailTemplates(new org.springframework.mock.env.MockEnvironment()),
                new tools.jackson.databind.json.JsonMapper(),
                clock,
                new NotificationProperties("f", "", new NotificationProperties.Outbox(false)));
        off.scheduled();
        assertThat(fake.calls).isEmpty();

        OutboxSender on = new OutboxSender(
                jdbc,
                fake,
                new EmailTemplates(new org.springframework.mock.env.MockEnvironment()),
                new tools.jackson.databind.json.JsonMapper(),
                clock,
                new NotificationProperties("f", "", new NotificationProperties.Outbox(true)));
        on.scheduled();
        assertThat(fake.calls).hasSize(1);
    }

    @Test
    void properties_toString_masksApiKey() {
        String s = new NotificationProperties("from", "re_supersecret", new NotificationProperties.Outbox(true))
                .toString();
        assertThat(s).doesNotContain("re_supersecret").contains("from");
    }

    @Test
    void runOnce_scrubbedOldOrderReceived_failsWithoutSending() {
        long id = row("o1");
        jdbc.update(
                "UPDATE email_outbox SET created_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(8))),
                id);

        assertThat(outbox.runOnce()).isEqualTo(1);

        assertThat(str(id, "status")).isEqualTo("FAILED");
        assertThat(str(id, "last_error")).isEqualTo("Render failed: IllegalArgumentException");
        assertThat(fake.calls).isEmpty();
    }
}
