package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.order.checkout.domain.CheckoutService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T-029 GET /api/orders/{token}/progress: stage states, photos, no leaks, uniform 404, headers, rate limit. */
class CustomerProgressApiIT extends ProgressTestBase {

    private static final String ALL = "RAW_PRINT,PREP,PAINT";

    /** D / C / P per stage, in response order. */
    private String states(Res r) {
        List<String> s = JsonPath.read(r.body(), "$.stages[*].state");
        StringBuilder sb = new StringBuilder();
        s.forEach(x -> sb.append(x.charAt(0)));
        return sb.toString();
    }

    private Res progress(String token) throws Exception {
        return res(progressOf(token));
    }

    private String statesOf(String status, int approved) throws Exception {
        long id = order("MADE_TO_ORDER", status, 1);
        String[] stages = ALL.split(",");
        for (int i = 0; i < approved; i++) {
            seedStage(id, stages[i], "", 0);
        }
        Res r = progress(tokenFor(id));
        assertThat(r.status()).as(status).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(r.body(), "$.stages[*].stage"))
                .containsExactly("AWAITING_APPROVAL", "DEPOSIT_RECEIVED", "RAW_PRINT", "PREP", "PAINT", "FINISHED");
        return states(r);
    }

    @Test
    void awaitingDeposit_firstStageCurrent() throws Exception {
        assertThat(statesOf("AWAITING_DEPOSIT", 0)).isEqualTo("CPPPPP");
    }

    @Test
    void queued_depositReceivedCurrent() throws Exception {
        assertThat(statesOf("QUEUED", 0)).isEqualTo("DCPPPP");
    }

    @Test
    void inProduction_zeroOneThreeApproved() throws Exception {
        assertThat(statesOf("IN_PRODUCTION", 0)).isEqualTo("DDCPPP");
        assertThat(statesOf("IN_PRODUCTION", 1)).isEqualTo("DDDCPP");
        assertThat(statesOf("IN_PRODUCTION", 3)).isEqualTo("DDDDDP");
    }

    @Test
    void awaitingBalance_finishedDone_noCurrent() throws Exception {
        assertThat(statesOf("AWAITING_BALANCE", 3)).isEqualTo("DDDDDD");
        assertThat(statesOf("AWAITING_BALANCE", 1)).isEqualTo("DDDDDD");
    }

    @Test
    void shipped_everythingDoneOrNotApplicable() throws Exception {
        assertThat(statesOf("SHIPPED", 3)).isEqualTo("DDDDDD");
    }

    @Test
    void cancelledBeforeDeposit_nothingDoneNoCurrent() throws Exception {
        assertThat(statesOf("CANCELLED", 0)).isEqualTo("PPPPPP");
    }

    @Test
    void forfeited_depositAndFinishedDone() throws Exception {
        assertThat(statesOf("FORFEITED", 0)).isEqualTo("DDDDDD");
    }

    @Test
    void photosAndNote_onlyOnApprovedStages_noInternalDataLeaks() throws Exception {
        long id = inProduction();
        jdbc.update("UPDATE customer_order SET admin_note = ? WHERE id = ?", MARKER + "admin-note", id);
        seedStage(id, "RAW_PRINT", "nice print", 2);
        seedStage(id, "PREP", "", 0);
        Res r = progress(tokenFor(id));
        assertThat(r.status()).isEqualTo(200);

        assertThat(JsonPath.<Integer>read(r.body(), "$.stages[2].images.length()"))
                .isEqualTo(2);
        assertThat(JsonPath.<String>read(r.body(), "$.stages[2].images[0].url")).endsWith("/1000.webp");
        assertThat(JsonPath.<Integer>read(r.body(), "$.stages[2].images[0].width"))
                .isEqualTo(1000);
        assertThat(JsonPath.<String>read(r.body(), "$.stages[2].note")).isEqualTo("nice print");
        assertThat(JsonPath.<String>read(r.body(), "$.stages[2].approvedAt")).isNotBlank();
        // approved without photos: empty list, empty note
        assertThat(JsonPath.<Integer>read(r.body(), "$.stages[3].images.length()"))
                .isZero();
        assertThat(JsonPath.<Object>read(r.body(), "$.stages[3].note")).isEqualTo("");
        // not approved or derived: no photos, no approvedAt
        for (int i : new int[] {0, 1, 4, 5}) {
            assertThat(JsonPath.<Object>read(r.body(), "$.stages[" + i + "].images"))
                    .isNull();
            assertThat(JsonPath.<Object>read(r.body(), "$.stages[" + i + "].approvedAt"))
                    .isNull();
        }

        String body = r.body();
        assertThat(body)
                .doesNotContain("storage_key")
                .doesNotContain("storageKey")
                .doesNotContain("adminNote")
                .doesNotContain("admin_note")
                .doesNotContain("approvedBy")
                .doesNotContain("\"id\"")
                .doesNotContain("orderId")
                .doesNotContain(MARKER)
                .doesNotContain(SECRET_EMAIL)
                .doesNotContain(SECRET_STREET)
                .doesNotContain(codeOf(id));
        secretsOf(id).forEach(s -> assertThat(body).doesNotContain(s));
        assertThat(body).doesNotContain(admin.email());
    }

    @Test
    void headers_noStoreAndNoReferrer() throws Exception {
        var ok = progressOf(tokenFor(inProduction())).andReturn().getResponse();
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getHeader("Cache-Control")).contains("no-store");
        assertThat(ok.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void badShapeUnknownAndInStockToken_identical404() throws Exception {
        String inStock = tokenFor(order("IN_STOCK", "PAID_IN_FULL", 1));
        Res bad = progress("x");
        Res unknown = progress(CheckoutService.newToken());
        Res notMto = progress(inStock);

        assertThat(bad.status()).isEqualTo(404);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(notMto.status()).isEqualTo(404);
        assertThat(bad.code()).isEqualTo("NOT_FOUND");
        // same body apart from the per-request id and the token-free detail
        List<String> shapes = List.of(bad, unknown, notMto).stream()
                .map(r -> JsonPath.read(r.body(), "$.type") + "|" + JsonPath.read(r.body(), "$.title") + "|"
                        + JsonPath.read(r.body(), "$.detail") + "|" + JsonPath.read(r.body(), "$.code"))
                .toList();
        assertThat(shapes).containsOnly(shapes.get(0));
        assertThat(notMto.body()).doesNotContain(inStock);
    }

    @Test
    void over60PerMinutePerIp_429WithRetryAfter_otherIpAndLaterWindowFine() throws Exception {
        String ip = nextIp();
        String unknown = CheckoutService.newToken();
        for (int i = 0; i < 60; i++) {
            assertThat(mvc.perform(getFrom("/api/orders/" + unknown + "/progress", null, ip))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .isEqualTo(404);
        }
        var limited = expectProblem(
                        mvc.perform(getFrom("/api/orders/" + unknown + "/progress", null, ip)), 429, "RATE_LIMITED")
                .andReturn()
                .getResponse();
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        assertThat(limited.getContentAsString()).doesNotContain(unknown);
        assertThat(progress(unknown).status()).isEqualTo(404);

        clock.advance(Duration.ofSeconds(61));
        assertThat(mvc.perform(getFrom("/api/orders/" + unknown + "/progress", null, ip))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }
}
