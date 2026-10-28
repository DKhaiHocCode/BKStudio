package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

class SettingsApiIT extends SettingsTestBase {

    private static final Map<String, Object> NO_SOCIAL = Map.of();

    private static Map<String, Object> allSocial() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reddit", "https://old.reddit.com/r/bk");
        m.put("facebook", "https://www.facebook.com/bk");
        m.put("instagram", "https://m.instagram.com/bk");
        m.put("x", "https://x.com/bk");
        return m;
    }

    // ---- public site-info ----

    @Test
    void siteInfo_anonymous_unsetReturnsFourNullKeysWithPublicCache() throws Exception {
        publicGet(SITE_INFO)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=60")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
                .andExpect(jsonPath("$.reddit").value((Object) null))
                .andExpect(jsonPath("$.facebook").value((Object) null))
                .andExpect(jsonPath("$.instagram").value((Object) null))
                .andExpect(jsonPath("$.x").value((Object) null));
        JsonNode n = om.readTree(body(publicGet(SITE_INFO)));
        assertThat(n.propertyNames()).containsExactlyInAnyOrder("reddit", "facebook", "instagram", "x");
    }

    @Test
    void siteInfo_exposesOnlyTheFourSocialKeys_neverCapacityOrDomains() throws Exception {
        putSettings(12, List.of("pay.example.com"), allSocial()).andExpect(status().isOk());

        String json = body(publicGet(SITE_INFO).andExpect(status().isOk()));
        JsonNode n = om.readTree(json);
        assertThat(n.propertyNames()).containsExactlyInAnyOrder("reddit", "facebook", "instagram", "x");
        assertThat(n.get("x").asString()).isEqualTo("https://x.com/bk");
        assertThat(json).doesNotContain("pay.example.com").doesNotContain("defaultCapacity");
    }

    @Test
    void siteInfo_createsNoSession_andAdminDataIsNotReadable() throws Exception {
        putSettings(7, List.of("secret.example.com"), NO_SOCIAL).andExpect(status().isOk());

        var res = publicGet(SITE_INFO).andExpect(status().isOk()).andReturn();
        assertThat(res.getResponse().getCookie("JSESSIONID")).isNull();
        assertThat(res.getRequest().getSession(false)).isNull();

        expectProblem(publicGet(SETTINGS), 401, "UNAUTHENTICATED");
        expectProblem(publicGet(TERMS), 401, "UNAUTHENTICATED");
    }

    // ---- admin access ----

    @Test
    void adminSettings_withoutSession_401() throws Exception {
        expectProblem(publicGet(SETTINGS), 401, "UNAUTHENTICATED");
        expectProblem(mvc.perform(withCsrf(HttpMethod.PUT, SETTINGS, "{}", null, nextIp())), 401, "UNAUTHENTICATED");
        expectProblem(mvc.perform(withCsrf(HttpMethod.POST, TERMS, "{}", null, nextIp())), 401, "UNAUTHENTICATED");
    }

    @Test
    void adminWrites_withSessionButNoCsrf_403_andNothingChanges() throws Exception {
        String body = j("defaultCapacity", 9, "paymentAllowedDomains", List.of(), "social", NO_SOCIAL);
        for (HttpMethod m : List.of(HttpMethod.PUT)) {
            expectProblem(
                    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(m, SETTINGS)
                            .session(session)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(body)),
                    403,
                    "FORBIDDEN");
        }
        expectProblem(
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(TERMS)
                        .session(session)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(j("contentMd", "x"))),
                403,
                "FORBIDDEN");
        assertThat(settingRows()).isZero();
        assertThat(count("SELECT count(*) FROM terms_document")).isZero();
    }

    // ---- GET / defaults / round trip ----

    @Test
    void getSettings_noRows_returnsDefaults() throws Exception {
        fetch(SETTINGS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultCapacity").value(5))
                .andExpect(jsonPath("$.paymentAllowedDomains").isEmpty())
                .andExpect(jsonPath("$.social.reddit").value((Object) null))
                .andExpect(jsonPath("$.social.x").value((Object) null));
    }

    @Test
    void putSettings_thenGet_returnsExactlyWhatWasPut() throws Exception {
        ResultActions put = putSettings(50, List.of("b.example.com", "a.example.com"), allSocial())
                .andExpect(status().isOk());
        String putBody = body(put);
        assertThat(om.readTree(body(fetch(SETTINGS).andExpect(status().isOk()))))
                .isEqualTo(om.readTree(putBody));
        assertThat(om.readTree(putBody).get("paymentAllowedDomains").get(0).asString())
                .isEqualTo("b.example.com");
    }

    @Test
    void putSettings_emptyDomainList_roundTrips() throws Exception {
        putSettings(5, List.of("a.example.com"), NO_SOCIAL).andExpect(status().isOk());
        putSettings(5, List.of(), NO_SOCIAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentAllowedDomains").isEmpty());
        fetch(SETTINGS).andExpect(jsonPath("$.paymentAllowedDomains").isEmpty());
    }

    // ---- defaultCapacity ----

    @ParameterizedTest
    @ValueSource(ints = {1, 50})
    void defaultCapacity_boundsAccepted(int v) throws Exception {
        putSettings(v, List.of(), NO_SOCIAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultCapacity").value(v));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 51, -1})
    void defaultCapacity_outOfRangeRejected(int v) throws Exception {
        expectFieldError(putSettings(v, List.of(), NO_SOCIAL), "defaultCapacity");
    }

    @Test
    void defaultCapacity_nullMissingOrNotANumber_400() throws Exception {
        expectFieldError(putSettings(null, List.of(), NO_SOCIAL), "defaultCapacity");
        expectProblem(
                put(SETTINGS, j("paymentAllowedDomains", List.of(), "social", NO_SOCIAL)), 400, "VALIDATION_FAILED");
        expectProblem(putSettings("abc", List.of(), NO_SOCIAL), 400, "VALIDATION_FAILED");
        assertThat(settingRows()).isZero();
    }

    // ---- paymentAllowedDomains ----

    private static List<String> domains(int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add("d" + i + ".example.com");
        }
        return l;
    }

    @Test
    void domains_20ok_21rejected() throws Exception {
        putSettings(5, domains(20), NO_SOCIAL).andExpect(status().isOk());
        expectFieldError(putSettings(5, domains(21), NO_SOCIAL), "paymentAllowedDomains");
        fetch(SETTINGS).andExpect(jsonPath("$.paymentAllowedDomains.length()").value(20));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Pay.Example.com",
                "https://pay.example.com",
                "pay.example.com/x",
                "pay.example.com:8080",
                "*.example.com",
                "localhost",
                "-pay.example.com",
                "pay-.example.com",
                "",
                " "
            })
    void domains_invalidElement_reportedByIndex(String bad) throws Exception {
        expectFieldError(putSettings(5, list("ok.example.com", bad), NO_SOCIAL), "paymentAllowedDomains[1]");
    }

    @Test
    void domains_labelOver63AndTotalOver253_rejected() throws Exception {
        expectFieldError(putSettings(5, list("a".repeat(64) + ".com"), NO_SOCIAL), "paymentAllowedDomains[0]");
        String l = "a".repeat(61);
        expectFieldError(
                putSettings(5, list("a.aa" + l + "." + l + "." + l + "." + l + ".com"), NO_SOCIAL),
                "paymentAllowedDomains[0]");
    }

    @Test
    void domains_nullElementOrMissingOrNullList_400() throws Exception {
        expectFieldError(putSettings(5, list("ok.example.com", null), NO_SOCIAL), "paymentAllowedDomains[1]");
        expectProblem(put(SETTINGS, j("defaultCapacity", 5, "social", NO_SOCIAL)), 400, "VALIDATION_FAILED");
        expectFieldError(putSettings(5, null, NO_SOCIAL), "paymentAllowedDomains");
    }

    @Test
    void domains_duplicatesRemovedKeepingOrder() throws Exception {
        putSettings(5, List.of("b.example.com", "a.example.com", "b.example.com"), NO_SOCIAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentAllowedDomains.length()").value(2))
                .andExpect(jsonPath("$.paymentAllowedDomains[0]").value("b.example.com"))
                .andExpect(jsonPath("$.paymentAllowedDomains[1]").value("a.example.com"));
    }

    // ---- social ----

    @ParameterizedTest
    @ValueSource(strings = {"https://old.reddit.com/r/x", "https://WWW.REDDIT.COM/r/x", "https://reddit.com/r/x"})
    void social_acceptedUrls(String url) throws Exception {
        putSocial("reddit", url)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.social.reddit").value(url));
    }

    @Test
    void social_eachPlatformAcceptsItsOwnDomains() throws Exception {
        putSocial("facebook", "https://www.facebook.com/p").andExpect(status().isOk());
        putSocial("instagram", "https://m.instagram.com/p").andExpect(status().isOk());
        putSocial("x", "https://x.com/p").andExpect(status().isOk());
        putSocial("x", "https://twitter.com/p").andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://reddit.com/r/x",
                "https://evilreddit.com",
                "https://reddit.com.evil.com",
                "https://user@reddit.com",
                "javascript:alert(1)",
                "data:text/html,hi",
                "https://reddit.com/a b",
                "https://reddit.com/a\tb",
                "https://reddit.com/a\nb",
                "https://reddit.com\\@evil.com",
                "https://reddit.com:evil",
                "//reddit.com/x",
                "https://reddit.com:443/x"
            })
    void social_rejectedUrls_reportedAsSocialReddit(String url) throws Exception {
        expectFieldError(putSocial("reddit", url), "social.reddit");
        assertThat(settingRows()).isZero();
    }

    @Test
    void social_length300ok_301rejected() throws Exception {
        String base = "https://reddit.com/";
        putSocial("reddit", base + "a".repeat(300 - base.length())).andExpect(status().isOk());
        expectFieldError(putSocial("reddit", base + "a".repeat(301 - base.length())), "social.reddit");
    }

    @Test
    void social_wrongPlatformInField_rejected() throws Exception {
        expectFieldError(putSocial("facebook", "https://reddit.com/r/x"), "social.facebook");
        expectFieldError(putSocial("x", "https://facebook.com/x"), "social.x");
        expectFieldError(putSocial("instagram", "https://twitter.com/x"), "social.instagram");
    }

    @Test
    void social_blankOrNullUnsetsAndDeletesTheRow() throws Exception {
        putSocial("reddit", "https://reddit.com/r/x").andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM app_setting WHERE key = 'social_reddit'"))
                .isEqualTo(1);

        putSocial("reddit", "  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.social.reddit").value((Object) null));
        assertThat(count("SELECT count(*) FROM app_setting WHERE key = 'social_reddit'"))
                .isZero();

        putSocial("reddit", "https://reddit.com/r/x").andExpect(status().isOk());
        Map<String, Object> nullReddit = new LinkedHashMap<>();
        nullReddit.put("reddit", null);
        putSettings(5, List.of(), nullReddit).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM app_setting WHERE key = 'social_reddit'"))
                .isZero();
        publicGet(SITE_INFO).andExpect(jsonPath("$.reddit").value((Object) null));
    }

    @Test
    void putSettings_missingSocial_400() throws Exception {
        expectProblem(
                put(SETTINGS, j("defaultCapacity", 5, "paymentAllowedDomains", List.of())), 400, "VALIDATION_FAILED");
        expectFieldError(putSettings(5, List.of(), null), "social");
    }

    // ---- change detection + audit ----

    @Test
    void putSettings_identicalValues_noWriteNoAuditRowUpdatedAtUnchanged() throws Exception {
        putSettings(8, List.of("a.example.com"), allSocial()).andExpect(status().isOk());
        Instant first = settingUpdatedAt("default_capacity");
        int audits = audits("SETTINGS", 0).size();
        assertThat(audits).isEqualTo(1);

        clock.advance(Duration.ofHours(1));
        putSettings(8, List.of("a.example.com", "a.example.com"), allSocial()).andExpect(status().isOk());

        assertThat(audits("SETTINGS", 0)).hasSize(1);
        assertThat(settingUpdatedAt("default_capacity")).isEqualTo(first);
        assertThat(settingUpdatedAt("social_x")).isEqualTo(first);
    }

    @Test
    void putSettings_defaultsOnEmptyDb_writesNothing() throws Exception {
        putSettings(5, List.of(), NO_SOCIAL).andExpect(status().isOk());
        assertThat(settingRows()).isZero();
        assertThat(audits("SETTINGS", 0)).isEmpty();
    }

    @Test
    void putSettings_realChange_oneAuditRowWithBeforeAfterAndClockTime() throws Exception {
        putSettings(8, List.of("a.example.com"), allSocial()).andExpect(status().isOk());
        clock.advance(Duration.ofHours(2));
        Instant changeAt = clock.instant();

        Map<String, Object> social = allSocial();
        social.put("x", "https://twitter.com/bk");
        putSettings(9, List.of("a.example.com", "c.example.com"), social).andExpect(status().isOk());

        var rows = audits("SETTINGS", 0);
        assertThat(rows).hasSize(2);
        var a = rows.get(1);
        assertThat(a.action()).isEqualTo("SETTINGS_UPDATED");
        assertThat(a.actor()).isEqualTo("ADMIN");
        assertThat(a.occurredAt()).isEqualTo(changeAt);
        JsonNode before = om.readTree(a.before());
        JsonNode after = om.readTree(a.after());
        assertThat(before.get("defaultCapacity").asInt()).isEqualTo(8);
        assertThat(after.get("defaultCapacity").asInt()).isEqualTo(9);
        assertThat(before.get("paymentAllowedDomains")).hasSize(1);
        assertThat(after.get("paymentAllowedDomains")).hasSize(2);
        assertThat(before.get("x").asString()).isEqualTo("https://x.com/bk");
        assertThat(after.get("x").asString()).isEqualTo("https://twitter.com/bk");
        assertThat(settingUpdatedAt("default_capacity")).isEqualTo(changeAt);
        // first-ever change: before holds the defaults
        JsonNode firstBefore = om.readTree(rows.get(0).before());
        assertThat(firstBefore.get("defaultCapacity").asInt()).isEqualTo(5);
        assertThat(firstBefore.get("reddit").isNull()).isTrue();
    }

    @Test
    void putSettings_unknownJsonFieldsIgnored() throws Exception {
        put(SETTINGS, "{\"defaultCapacity\":6,\"paymentAllowedDomains\":[],\"social\":{},\"extra\":1}")
                .andExpect(status().isOk());
    }

    // ---- concurrency and corrupt storage ----

    @Test
    void concurrentPuts_serialise_finalStateIsExactlyOnePayloadWithCoherentAudit() throws Exception {
        var a = new Object[] {7, List.of("a.example.com"), Map.<String, Object>of("x", "https://x.com/a")};
        var b = new Object[] {9, List.of("b.example.com"), Map.<String, Object>of("x", "https://x.com/b")};
        long lastAuditId = jdbc.queryForObject("SELECT COALESCE(max(id), 0) FROM audit_log", Long.class);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (Object[] p : List.of(a, b)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> social = (Map<String, Object>) p[2];
                futures.add(pool.submit(() -> {
                    start.await();
                    return putSettings(p[0], p[1], social)
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
            for (var f : futures) {
                assertThat(f.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        JsonNode now = om.readTree(body(fetch(SETTINGS).andExpect(status().isOk())));
        int cap = now.get("defaultCapacity").asInt();
        assertThat(cap).isIn(7, 9);
        String letter = cap == 7 ? "a" : "b";
        assertThat(now.get("paymentAllowedDomains").get(0).asString()).isEqualTo(letter + ".example.com");
        assertThat(now.get("social").get("x").asString()).isEqualTo("https://x.com/" + letter);

        var rows = jdbc.queryForList(
                "SELECT before ->> 'defaultCapacity' AS b, after ->> 'defaultCapacity' AS a FROM audit_log"
                        + " WHERE action = 'SETTINGS_UPDATED' AND id > ? ORDER BY id",
                lastAuditId);
        assertThat(rows).hasSize(2);
        // Serialised: the first row starts from the defaults, the second starts from the first one's result.
        assertThat(rows.get(0).get("b")).isEqualTo("5");
        assertThat(rows.get(1).get("b")).isEqualTo(rows.get(0).get("a"));
        assertThat(rows.get(1).get("a")).isEqualTo(String.valueOf(cap));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "99", "0", ""})
    void corruptStoredCapacity_readsAsFive_andPutOfFiveRepairsTheRow(String stored) throws Exception {
        jdbc.update("INSERT INTO app_setting (key, value) VALUES ('default_capacity', ?)", stored);

        fetch(SETTINGS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultCapacity").value(5));

        putSettings(5, List.of(), NO_SOCIAL).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT value FROM app_setting WHERE key = 'default_capacity'", String.class))
                .isEqualTo("5");
    }
}
