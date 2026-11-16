package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.order.checkout.domain.CheckoutService;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;

/**
 * T-050: auto-complete of shipped orders and anonymisation of orders that ended more than 2 years ago, against real
 * Postgres with the test clock (2030-01-01). Jobs are called directly; both must be safe to run twice.
 */
class OrderRetentionJobsIT extends OrderJobsTestBase {

    private static final String SECRET_NAME = "Secret Buyer";
    private static final String SECRET_PHONE = "+65 8111 2222";
    private static final String SECRET_IP = "203.0.113.9";
    private static final String PLACEHOLDER_EMAIL = "anonymised@invalid.invalid";

    /** type, terminal status */
    private static final List<String[]> TERMINAL = List.of(
            new String[] {"IN_STOCK", "COMPLETED"},
            new String[] {"IN_STOCK", "CANCELLED"},
            new String[] {"IN_STOCK", "CANCELLED_REFUNDED"},
            new String[] {"MADE_TO_ORDER", "CANCELLED_DEPOSIT_KEPT"},
            new String[] {"MADE_TO_ORDER", "FORFEITED"});

    private String token;

    // ---- fixtures ----

    private long shipped(Duration ago) {
        long id = order("IN_STOCK", "SHIPPED", 1);
        jdbc.update(
                "UPDATE customer_order SET shipped_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minus(ago)),
                id);
        return id;
    }

    /** Order full of personal data whose last change (and completion, if COMPLETED) was {@code ago}. */
    private long ended(String type, String status, Duration ago) {
        long id = order(type, status, 2);
        Timestamp at = Timestamp.from(clock.instant().minus(ago));
        token = CheckoutService.newToken();
        jdbc.update(
                "UPDATE customer_order SET access_token_hash = ?, customer_name = ?, phone = ?, ship_line2 = 'Unit 7',"
                        + " ship_region = 'Central', ship_postal_code = '238801', terms_accepted_ip = ?::inet,"
                        + " admin_note = 'call the buyer', cancel_reason = 'asked by phone', shipping_cents = 500,"
                        + " updated_at = ?, completed_at = CASE WHEN status = 'COMPLETED' THEN ?::timestamptz END"
                        + " WHERE id = ?",
                CheckoutService.hashToken(token),
                SECRET_NAME,
                SECRET_PHONE,
                SECRET_IP,
                at,
                at,
                id);
        return id;
    }

    private void mail(String dedupeKey, String status) {
        jdbc.update(
                "INSERT INTO email_outbox (dedupe_key, to_email, template, payload, status) VALUES (?, ?, 'ORDER_RECEIVED',"
                        + " '{\"publicCode\":\"BK-1\",\"orderUrl\":\"https://shop.example/orders/t\","
                        + "\"paymentUrl\":\"https://artistree.io/p/secret\"}'::jsonb, ?)",
                dedupeKey,
                SECRET_EMAIL,
                status);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM customer_order WHERE id = ?", id);
    }

    private String hashHex(long id) {
        return jdbc.queryForObject(
                "SELECT encode(access_token_hash, 'hex') FROM customer_order WHERE id = ?", String.class, id);
    }

    private Map<String, Object> mailRow(String dedupeKey) {
        return jdbc.queryForMap(
                "SELECT to_email, status, sent_at, last_error, payload::text AS payload FROM email_outbox"
                        + " WHERE dedupe_key = ?",
                dedupeKey);
    }

    // ---- auto-complete ----

    @Test
    void completeShipped_shipped29Days_notCompleted() {
        long order = shipped(Duration.ofDays(29));

        assertThat(jobs.completeShipped()).isZero();

        assertThat(orderStatus(order)).isEqualTo("SHIPPED");
        assertThat(outbox("ORDER_COMPLETED", order)).isZero();
    }

    @Test
    void completeShipped_shipped31Days_completedOnceBySystem() {
        long order = shipped(Duration.ofDays(31));
        long fresh = shipped(Duration.ofDays(1));

        assertThat(jobs.completeShipped()).isEqualTo(1);

        assertThat(orderStatus(order)).isEqualTo("COMPLETED");
        assertThat(orderStatus(fresh)).isEqualTo("SHIPPED");
        assertThat(((Timestamp) row(order).get("completed_at")).toInstant()).isEqualTo(clock.instant());
        assertThat(outbox("ORDER_COMPLETED", order)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT actor FROM audit_log WHERE action = 'ORDER_TRANSITION' AND entity_id = ?",
                        String.class,
                        order))
                .isEqualTo("SYSTEM");

        int outboxAfterFirst = outboxTotal();
        int auditAfterFirst = auditTotal();
        assertThat(jobs.completeShipped()).isZero();
        assertThat(outboxTotal()).isEqualTo(outboxAfterFirst);
        assertThat(auditTotal()).isEqualTo(auditAfterFirst);
        assertThat(version(order)).isEqualTo(1);
    }

    // ---- anonymise ----

    @Test
    void anonymiseOld_nonTerminalOrder_neverAnonymisedHoweverOld() {
        List<String[]> open = List.of(
                new String[] {"IN_STOCK", "AWAITING_PAYMENT"},
                new String[] {"IN_STOCK", "PAID_IN_FULL"},
                new String[] {"IN_STOCK", "SHIPPED"},
                new String[] {"MADE_TO_ORDER", "AWAITING_DEPOSIT"},
                new String[] {"MADE_TO_ORDER", "QUEUED"},
                new String[] {"MADE_TO_ORDER", "IN_PRODUCTION"},
                new String[] {"MADE_TO_ORDER", "AWAITING_BALANCE"});
        List<Long> ids =
                open.stream().map(o -> ended(o[0], o[1], Duration.ofDays(3650))).toList();

        assertThat(jobs.anonymiseOld()).isZero();

        for (long id : ids) {
            assertThat(row(id).get("customer_email")).isEqualTo(SECRET_EMAIL);
            assertThat(row(id).get("anonymised_at")).isNull();
        }
    }

    @Test
    void anonymiseOld_terminalOneDayBeforeTwoYears_untouched() throws Exception {
        int auditBefore = auditTotal(); // audit_log is not wiped between tests
        for (String[] t : TERMINAL) {
            long id = ended(t[0], t[1], Duration.ofDays(729));
            mail("order:" + id + ":received", "SENT");

            assertThat(jobs.anonymiseOld()).as(t[1]).isZero();

            assertThat(row(id).get("customer_name")).isEqualTo(SECRET_NAME);
            assertThat(row(id).get("anonymised_at")).isNull();
            assertThat(mailRow("order:" + id + ":received").get("to_email")).isEqualTo(SECRET_EMAIL);
            mvc.perform(getFrom("/api/orders/" + token, null, nextIp())).andExpect(status().isOk());
        }
        assertThat(auditTotal()).isEqualTo(auditBefore);
    }

    @Test
    void anonymiseOld_everyTerminalStatusPastTwoYears_anonymised() {
        for (String[] t : TERMINAL) {
            long id = ended(t[0], t[1], Duration.ofDays(731));

            assertThat(jobs.anonymiseOld()).as(t[1]).isEqualTo(1);

            assertThat(row(id).get("customer_email")).as(t[1]).isEqualTo(PLACEHOLDER_EMAIL);
            assertThat(orderStatus(id)).isEqualTo(t[1]);
        }
    }

    @Test
    void anonymiseOld_completedLongAgoButNoteEditedRecently_stillAnonymised() {
        long id = ended("IN_STOCK", "COMPLETED", Duration.ofDays(731));
        jdbc.update("UPDATE customer_order SET updated_at = ? WHERE id = ?", Timestamp.from(clock.instant()), id);

        assertThat(jobs.anonymiseOld()).isEqualTo(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void anonymiseOld_pastTwoYears_clearsPersonalDataKeepsMoneyAndKillsLink(CapturedOutput output) throws Exception {
        long id = ended("IN_STOCK", "COMPLETED", Duration.ofDays(731));
        String ownToken = token;
        long other = ended("IN_STOCK", "COMPLETED", Duration.ofDays(10)); // recent: must stay intact
        String otherToken = token;
        long request = pendingRequest(id, "FULL");
        jdbc.update("UPDATE payment_request SET status = 'CANCELLED' WHERE id = ?", request);
        mail("order:" + id + ":received", "SENT");
        mail("order:" + id + ":lookup:3", "PENDING");
        mail("payment:" + request + ":link", "SENT");
        mail("order:" + other + ":received", "SENT");
        mail("order:" + id + "0:received", "SENT"); // id prefix of another order: must not match
        Map<String, Object> before = row(id);
        String oldHash = hashHex(id);

        assertThat(jobs.anonymiseOld()).isEqualTo(1);

        Map<String, Object> after = row(id);
        assertThat(after.get("customer_name")).isEqualTo("anonymised");
        assertThat(after.get("customer_email")).isEqualTo(PLACEHOLDER_EMAIL);
        assertThat(after.get("ship_line1")).isEqualTo("anonymised");
        assertThat(after.get("ship_city")).isEqualTo("anonymised");
        assertThat(after.get("tracking_number")).isEqualTo("anonymised");
        for (String cleared : List.of(
                "phone",
                "ship_line2",
                "ship_region",
                "ship_postal_code",
                "terms_accepted_ip",
                "admin_note",
                "cancel_reason")) {
            assertThat(after.get(cleared)).as(cleared).isNull();
        }
        assertThat(((Timestamp) after.get("anonymised_at")).toInstant()).isEqualTo(clock.instant());
        assertThat(after.toString())
                .doesNotContain(SECRET_EMAIL, SECRET_STREET, SECRET_NAME, SECRET_PHONE, SECRET_IP, "Unit 7", "238801");
        // statistics survive
        for (String kept : List.of(
                "public_code",
                "status",
                "fulfillment_type",
                "subtotal_cents",
                "shipping_cents",
                "ship_country",
                "created_at",
                "updated_at",
                "shipped_at",
                "completed_at",
                "terms_version")) {
            assertThat(after.get(kept)).as(kept).isEqualTo(before.get(kept));
        }
        assertThat(count("SELECT count(*) FROM order_item WHERE order_id = ? AND quantity = 2", id))
                .isEqualTo(1);
        assertThat(requestStatus(request)).isEqualTo("CANCELLED");

        // outbox: recipient and link gone, a PENDING row can no longer be sent
        for (String key :
                List.of("order:" + id + ":received", "order:" + id + ":lookup:3", "payment:" + request + ":link")) {
            Map<String, Object> m = mailRow(key);
            assertThat(m.get("to_email")).as(key).isEqualTo(PLACEHOLDER_EMAIL);
            assertThat((String) m.get("payload"))
                    .doesNotContain("orderUrl", "paymentUrl", "artistree")
                    .contains("BK-1");
        }
        assertThat(mailRow("order:" + id + ":received").get("status")).isEqualTo("SENT");
        assertThat(mailRow("order:" + id + ":received").get("last_error")).isNull();
        // PENDING row: closed as "deliberately not sent" (SENT, no sent_at), so it is neither mailed, nor counted
        // against the daily cap, nor shown as FAILED on the dashboard
        Map<String, Object> skipped = mailRow("order:" + id + ":lookup:3");
        assertThat(skipped.get("status")).isEqualTo("SENT");
        assertThat(skipped.get("sent_at")).isNull();
        assertThat(skipped.get("last_error")).isEqualTo("Skipped: anonymised");
        assertThat(count("SELECT count(*) FROM email_outbox WHERE status IN ('PENDING','FAILED')"))
                .isZero();
        assertThat(mailRow("order:" + other + ":received").get("to_email")).isEqualTo(SECRET_EMAIL);
        assertThat(mailRow("order:" + id + "0:received").get("to_email")).isEqualTo(SECRET_EMAIL);

        // audit: counts only
        Map<String, Object> audit = JsonPath.read(auditAfter("ORDER_ANONYMISED", id), "$");
        assertThat(audit).containsOnlyKeys("fields", "outboxRows");
        assertThat(audit.get("outboxRows")).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT string_agg(coalesce(before::text, '') || after::text, ' ') FROM audit_log"
                                + " WHERE entity_type = 'ORDER' AND entity_id = ?",
                        String.class,
                        id))
                .doesNotContain(SECRET_EMAIL, SECRET_STREET, SECRET_NAME, SECRET_PHONE, SECRET_IP);

        // logs of the run (checked before the HTTP calls below put a token in a request)
        assertThat(output.getAll())
                .doesNotContain(
                        SECRET_EMAIL, SECRET_STREET, SECRET_NAME, SECRET_PHONE, SECRET_IP, ownToken, otherToken);

        // the old link is dead; the recent order is intact and its link still works
        assertThat(hashHex(id)).isNotEqualTo(oldHash);
        expectProblem(mvc.perform(getFrom("/api/orders/" + ownToken, null, nextIp())), 404, "NOT_FOUND");
        assertThat(row(other).get("customer_name")).isEqualTo(SECRET_NAME);
        assertThat(row(other).get("anonymised_at")).isNull();
        mvc.perform(getFrom("/api/orders/" + otherToken, null, nextIp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalCents").value(2000));
    }

    @Test
    void anonymiseOld_pastTwoYears_oldLinkAnswers404AndLookupSendsNothing() throws Exception {
        long id = ended("MADE_TO_ORDER", "FORFEITED", Duration.ofDays(731));
        String code = (String) row(id).get("public_code");
        mvc.perform(getFrom("/api/orders/" + token, null, nextIp())).andExpect(status().isOk());

        assertThat(jobs.anonymiseOld()).isEqualTo(1);

        expectProblem(mvc.perform(getFrom("/api/orders/" + token, null, nextIp())), 404, "NOT_FOUND");
        int outboxBefore = outboxTotal();
        int versionBefore = version(id);
        mvc.perform(withCsrf(
                        HttpMethod.POST,
                        "/api/orders/lookup",
                        j("code", code, "email", PLACEHOLDER_EMAIL),
                        null,
                        nextIp()))
                .andExpect(status().isAccepted());
        assertThat(outboxTotal()).isEqualTo(outboxBefore);
        assertThat(version(id)).isEqualTo(versionBefore);
    }

    @Test
    void anonymiseOld_neverShippedCancelledOrder_trackingStaysNull() {
        long id = ended("IN_STOCK", "CANCELLED", Duration.ofDays(731));
        assertThat(row(id).get("tracking_number")).isNull();

        assertThat(jobs.anonymiseOld()).isEqualTo(1);

        assertThat(row(id).get("tracking_number")).isNull();
        assertThat(row(id).get("anonymised_at")).isNotNull();
    }

    @Test
    void anonymiseOld_progressNotes_clearedButStagesKept() {
        long id = ended("MADE_TO_ORDER", "FORFEITED", Duration.ofDays(731));
        long recent = ended("MADE_TO_ORDER", "FORFEITED", Duration.ofDays(10));
        for (long order : List.of(id, recent)) {
            for (String stage : List.of("RAW_PRINT", "PREP")) {
                jdbc.update(
                        "INSERT INTO order_progress (order_id, stage, note, approved_by) VALUES (?, ?, ?, ?)",
                        order,
                        stage,
                        "for " + SECRET_NAME,
                        admin.id());
            }
        }

        assertThat(jobs.anonymiseOld()).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT note FROM order_progress WHERE order_id = ?", String.class, id))
                .containsExactly("", "");
        assertThat(jdbc.queryForList("SELECT note FROM order_progress WHERE order_id = ?", String.class, recent))
                .containsExactly("for " + SECRET_NAME, "for " + SECRET_NAME);
    }

    @Test
    void anonymiseOld_secondRun_changesNothing() {
        long id = ended("IN_STOCK", "CANCELLED", Duration.ofDays(731));
        mail("order:" + id + ":received", "SENT");

        assertThat(jobs.anonymiseOld()).isEqualTo(1);
        Map<String, Object> afterFirst = row(id);
        String hashAfterFirst = hashHex(id);
        int auditAfterFirst = auditTotal();

        clock.advance(Duration.ofDays(1));
        assertThat(jobs.anonymiseOld()).isZero();

        Map<String, Object> afterSecond = row(id);
        afterFirst.remove("access_token_hash"); // byte[] has identity equals: compared as hex
        afterSecond.remove("access_token_hash");
        assertThat(afterSecond).isEqualTo(afterFirst);
        assertThat(hashHex(id)).isEqualTo(hashAfterFirst);
        assertThat(auditTotal()).isEqualTo(auditAfterFirst);
        assertThat(auditCount("ORDER_ANONYMISED", id)).isEqualTo(1);
    }
}
