package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T-028 POST /api/orders/lookup (ADR-0012): the token rotates only for a matching pair within the limits. */
class CustomerOrderLookupIT extends CustomerOrderTestBase {

    private int status(String token) throws Exception {
        return viewOrder(token).andReturn().getResponse().getStatus();
    }

    private Res lookupRes(long id) throws Exception {
        return res(lookup(codeOf(id), SECRET_EMAIL));
    }

    @Test
    void matchingPair_202_oldLinkDies_newLinkWorks_oneEmailAndAudit() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        String oldToken = tokenFor(id);
        int versionBefore = version(id);

        var resp = lookup(codeOf(id).toLowerCase(), SECRET_EMAIL.toUpperCase())
                .andReturn()
                .getResponse();

        assertThat(resp.getStatus()).isEqualTo(202);
        assertThat(resp.getHeader("Cache-Control")).contains("no-store");
        assertThat(status(oldToken)).isEqualTo(404);
        String newToken = linkToken(id);
        Res view = res(viewOrder(newToken));
        assertThat(view.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(view.body(), "$.publicCode")).isEqualTo(codeOf(id));
        assertThat(version(id)).isEqualTo(versionBefore + 1);
        assertThat(lookupMails(id)).isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM email_outbox WHERE dedupe_key = ?",
                        "order:" + id + ":lookup:" + (versionBefore + 1)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT to_email FROM email_outbox WHERE template = 'ORDER_LOOKUP_LINK'", String.class))
                .isEqualToIgnoringCase(SECRET_EMAIL);
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM email_outbox WHERE template = 'ORDER_LOOKUP_LINK'", String.class);
        assertThat(payload).doesNotContainIgnoringCase(SECRET_EMAIL).contains(newToken);
        assertThat(auditCount("ORDER_LINK_REISSUED", id)).isEqualTo(1);
        String audit = auditAfter("ORDER_LINK_REISSUED", id);
        assertThat(audit)
                .doesNotContainIgnoringCase("secret.buyer")
                .doesNotContain(newToken)
                .doesNotContain(oldToken);
        assertThat(jdbc.queryForObject(
                        "SELECT actor FROM audit_log WHERE action = 'ORDER_LINK_REISSUED' AND entity_id = ?",
                        String.class,
                        id))
                .isEqualTo("CUSTOMER");
    }

    @Test
    void wrongEmailOrUnknownCode_sameBody_noTokenChange_noEmail() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        String token = tokenFor(id);
        String hash = hashHex(id);
        int version = version(id);
        String code = codeOf(id);

        Res wrongEmail = res(lookup(code, "someone.else@example.com"));
        Res unknownCode = res(lookup("BK-NOPE1234", SECRET_EMAIL));
        assertThat(wrongEmail.status()).isEqualTo(202);
        assertThat(unknownCode.status()).isEqualTo(202);
        assertThat(wrongEmail.body()).isEqualTo(unknownCode.body());
        assertThat(hashHex(id)).isEqualTo(hash);
        assertThat(version(id)).isEqualTo(version);
        assertThat(count("SELECT count(*) FROM email_outbox")).isZero();
        assertThat(status(token)).isEqualTo(200);
        assertThat(auditCount("ORDER_LINK_REISSUED", id)).isZero();

        // a real match answers with the very same bytes
        Res match = res(lookup(code, SECRET_EMAIL));
        assertThat(match.status()).isEqualTo(202);
        assertThat(match.body()).isEqualTo(wrongEmail.body());
    }

    @Test
    void malformedBody_400() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        for (String body : List.of(
                "{}",
                "{\"code\":\"BK-1\"}",
                "{\"email\":\"a@b.co\"}",
                "{\"code\":\"\",\"email\":\"a@b.co\"}",
                "{\"code\":\"BK-1\",\"email\":\"  \"}",
                "{\"code\":\"" + "X".repeat(21) + "\",\"email\":\"a@b.co\"}",
                "{\"code\":\"BK-1\",\"email\":\"" + "a".repeat(250) + "@b.co\"}",
                "{not json")) {
            assertThat(res(lookupRaw(body)).status()).as(body).isEqualTo(400);
        }
        assertThat(count("SELECT count(*) FROM email_outbox")).isZero();
        assertThat(version(id)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions lookupRaw(String body) throws Exception {
        return mvc.perform(
                withCsrf(org.springframework.http.HttpMethod.POST, "/api/orders/lookup", body, null, nextIp()));
    }

    @Test
    void missingCsrf_403AndNothingChanged() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        String token = tokenFor(id);

        Res r = res(postWithoutCsrf("/api/orders/lookup", j("code", codeOf(id), "email", SECRET_EMAIL)));

        assertThat(r.status()).isEqualTo(403);
        assertThat(status(token)).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM email_outbox")).isZero();
    }

    @Test
    void secondLookupWithinCooldown_doesNothing_firstLinkStillValid() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        tokenFor(id);

        assertThat(lookupRes(id).status()).isEqualTo(202);
        String first = linkToken(id);
        int version = version(id);

        clock.advance(Duration.ofSeconds(90));
        assertThat(lookupRes(id).status()).isEqualTo(202);

        assertThat(lookupMails(id)).isEqualTo(1);
        assertThat(version(id)).isEqualTo(version);
        assertThat(status(first)).isEqualTo(200);
        assertThat(linkToken(id)).isEqualTo(first);
    }

    @Test
    void rotations_cappedAtThreePerHourPerOrder_thenAllowedAgainAfterTheWindow() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        tokenFor(id);
        String last = null;
        for (int i = 1; i <= 3; i++) {
            clock.advance(Duration.ofMinutes(3));
            assertThat(lookupRes(id).status()).isEqualTo(202);
            String link = linkToken(id);
            assertThat(link).isNotEqualTo(last);
            last = link;
            assertThat(lookupMails(id)).isEqualTo(i);
        }
        int version = version(id);

        clock.advance(Duration.ofMinutes(3));
        assertThat(lookupRes(id).status()).isEqualTo(202); // fourth in the hour: nothing happens

        assertThat(lookupMails(id)).isEqualTo(3);
        assertThat(version(id)).isEqualTo(version);
        assertThat(status(last)).isEqualTo(200);

        clock.advance(Duration.ofHours(1));
        assertThat(lookupRes(id).status()).isEqualTo(202);
        assertThat(lookupMails(id)).isEqualTo(4);
        assertThat(status(last)).isEqualTo(404);
        assertThat(status(linkToken(id))).isEqualTo(200);
    }

    @Test
    void lookup_over10PerHourPerIp_still202ButNothingHappens_thenWorksAfterTheWindow() throws Exception {
        long id = order("IN_STOCK", "AWAITING_PAYMENT", 1);
        String token = tokenFor(id);
        String ip = nextIp();
        for (int i = 0; i < 10; i++) {
            assertThat(res(lookupFrom("BK-NOPE0000", "nobody@example.com", ip)).status())
                    .isEqualTo(202);
        }

        Res limited = res(lookupFrom(codeOf(id), SECRET_EMAIL, ip));

        assertThat(limited.status()).isEqualTo(202);
        assertThat(lookupMails(id)).isZero();
        assertThat(status(token)).isEqualTo(200);

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(res(lookupFrom(codeOf(id), SECRET_EMAIL, ip)).status()).isEqualTo(202);
        assertThat(lookupMails(id)).isEqualTo(1);
        assertThat(status(token)).isEqualTo(404);
    }
}
