package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

class TermsApiIT extends SettingsTestBase {

    private ResultActionsHolder publish(String version, String content) throws Exception {
        String body = version == null ? j("contentMd", content) : j("version", version, "contentMd", content);
        return new ResultActionsHolder(
                post(TERMS, body).andReturn().getResponse().getStatus(), body);
    }

    /** Status of a publish call. */
    private record ResultActionsHolder(int status, String request) {}

    private int publishStatus(String version, String content) throws Exception {
        return publish(version, content).status();
    }

    // ---- current ----

    @Test
    void currentTerms_none_404NotFound() throws Exception {
        expectProblem(publicGet(TERMS_CURRENT), 404, "NOT_FOUND");
    }

    @Test
    void currentTerms_anonymous_returnsVersionAndContentWithPublicCache() throws Exception {
        post(TERMS, j("version", "2030-01-01", "contentMd", "# Terms")).andExpect(status().isCreated());
        publicGet(TERMS_CURRENT)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=60")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
                .andExpect(jsonPath("$.version").value("2030-01-01"))
                .andExpect(jsonPath("$.contentMd").value("# Terms"));
    }

    @Test
    void currentTerms_isLatestByPublishedAt_notByVersionString() throws Exception {
        post(TERMS, j("version", "2030-06-01", "contentMd", "first")).andExpect(status().isCreated());
        clock.advance(Duration.ofHours(1));
        post(TERMS, j("version", "2029-01-01", "contentMd", "second")).andExpect(status().isCreated());

        publicGet(TERMS_CURRENT).andExpect(jsonPath("$.version").value("2029-01-01"));

        clock.advance(Duration.ofHours(1));
        post(TERMS, j("version", "2028-01-01", "contentMd", "third")).andExpect(status().isCreated());
        publicGet(TERMS_CURRENT).andExpect(jsonPath("$.version").value("2028-01-01"));
    }

    @Test
    void currentTerms_samePublishedAt_tieBrokenByHigherVersion() throws Exception {
        post(TERMS, j("version", "2030-01-01", "contentMd", "a")).andExpect(status().isCreated());
        post(TERMS, j("version", "2030-01-01-2", "contentMd", "b")).andExpect(status().isCreated());
        publicGet(TERMS_CURRENT).andExpect(jsonPath("$.version").value("2030-01-01-2"));
    }

    // ---- version ----

    @Test
    void publish_versionOmitted_defaultsToTodayUtcFromClock() throws Exception {
        clock.set(Instant.parse("2031-03-04T23:59:30Z"));
        session = fullLogin(admin); // the old session is expired at the new clock time
        post(TERMS, j("contentMd", "x"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("2031-03-04"))
                .andExpect(jsonPath("$.contentMd").value("x"))
                .andExpect(jsonPath("$.publishedAt").value("2031-03-04T23:59:30Z"));
        assertThat(count("SELECT count(*) FROM terms_document WHERE version = '2031-03-04'"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-01", "2026-11-01-1", "2026-11-01-999", "2028-02-29"})
    void publish_validVersions_created(String v) throws Exception {
        post(TERMS, j("version", v, "contentMd", "x"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(v));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13-45", "abcd", "2026-02-30", "2026-11-01-1234", "2027-02-29", "", "2026-11-01-"})
    void publish_invalidVersions_400AndNothingStored(String v) throws Exception {
        expectProblem(post(TERMS, j("version", v, "contentMd", "x")), 400, "VALIDATION_FAILED");
        assertThat(count("SELECT count(*) FROM terms_document")).isZero();
        assertThat(audits("TERMS", 0)).isEmpty();
    }

    @Test
    void publish_duplicateVersion_409Conflict_originalKept_noExtraAudit() throws Exception {
        post(TERMS, j("version", "2030-01-01", "contentMd", "original")).andExpect(status().isCreated());
        expectProblem(post(TERMS, j("version", "2030-01-01", "contentMd", "other")), 409, "CONFLICT");

        assertThat(jdbc.queryForObject("SELECT content_md FROM terms_document", String.class))
                .isEqualTo("original");
        assertThat(audits("TERMS", 0)).hasSize(1);
    }

    @Test
    void publish_sameVersionConcurrently_exactlyOneCreated() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String content = "from thread " + i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return publishStatus("2030-05-05", content);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            int created = 0;
            int conflicts = 0;
            for (Future<Integer> f : results) {
                int s = f.get(30, TimeUnit.SECONDS);
                if (s == 201) {
                    created++;
                } else if (s == 409) {
                    conflicts++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(conflicts).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM terms_document")).isEqualTo(1);
        assertThat(audits("TERMS", 0)).hasSize(1);
    }

    // ---- content ----

    @Test
    void publish_contentBlankOrMissing_400() throws Exception {
        expectFieldError(post(TERMS, j("version", "2030-01-01", "contentMd", "")), "contentMd");
        expectFieldError(post(TERMS, j("version", "2030-01-01", "contentMd", "   \n ")), "contentMd");
        expectFieldError(post(TERMS, j("version", "2030-01-01")), "contentMd");
        assertThat(count("SELECT count(*) FROM terms_document")).isZero();
    }

    @Test
    void publish_content50000ok_50001rejected() throws Exception {
        post(TERMS, j("version", "2030-01-01", "contentMd", "a".repeat(50_000))).andExpect(status().isCreated());
        expectFieldError(post(TERMS, j("version", "2030-01-02", "contentMd", "a".repeat(50_001))), "contentMd");
        assertThat(count("SELECT length(content_md) FROM terms_document")).isEqualTo(50_000);
    }

    @Test
    void publish_nulCharacterInContent_400NotServerError() throws Exception {
        expectProblem(post(TERMS, j("version", "2030-01-01", "contentMd", "ab\u0000cd")), 400, "VALIDATION_FAILED");
        assertThat(count("SELECT count(*) FROM terms_document")).isZero();
        assertThat(audits("TERMS", 0)).isEmpty();
    }

    @Test
    void publish_unknownJsonFieldsIgnored() throws Exception {
        post(
                        TERMS,
                        "{\"version\":\"2030-01-01\",\"contentMd\":\"x\",\"publishedAt\":\"1999-01-01T00:00:00Z\",\"foo\":1}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.publishedAt").value("2030-01-01T00:00:10Z"));
    }

    @Test
    void publish_nonStringContent_numberIsCoercedToText_structuresRejected() throws Exception {
        // JacksonConfig only disables enum-from-number; Jackson's default scalar-to-String coercion stays on.
        post(TERMS, "{\"version\":\"2030-01-01\",\"contentMd\":123}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentMd").value("123"));
        expectProblem(post(TERMS, "{\"version\":\"2030-01-02\",\"contentMd\":[\"a\"]}"), 400, "VALIDATION_FAILED");
        expectProblem(post(TERMS, "{\"version\":\"2030-01-03\",\"contentMd\":{\"a\":1}}"), 400, "VALIDATION_FAILED");
    }

    @Test
    void publish_unicodeContent_storedAndServedIntact() throws Exception {
        String md = "# Điều khoản 😀\n- mục 1";
        post(TERMS, j("version", "2030-01-01", "contentMd", md)).andExpect(status().isCreated());
        JsonNode n = om.readTree(body(publicGet(TERMS_CURRENT)));
        assertThat(n.get("contentMd").asString()).isEqualTo(md);
    }

    // ---- audit ----

    @Test
    void publish_writesAuditWithVersionAndContentLength_butNotTheContent() throws Exception {
        String content = "Secret terms body " + token();
        clock.advance(Duration.ofMinutes(5));
        post(TERMS, j("version", "2030-01-01", "contentMd", content)).andExpect(status().isCreated());

        var rows = audits("TERMS", 0);
        assertThat(rows).hasSize(1);
        var a = rows.get(0);
        assertThat(a.action()).isEqualTo("TERMS_PUBLISHED");
        assertThat(a.actor()).isEqualTo("ADMIN");
        assertThat(a.before()).isNull();
        assertThat(a.occurredAt()).isEqualTo(clock.instant());
        JsonNode after = om.readTree(a.after());
        assertThat(after.get("version").asString()).isEqualTo("2030-01-01");
        assertThat(after.get("contentLength").asInt()).isEqualTo(content.length());
        assertThat(after.propertyNames()).containsExactlyInAnyOrder("version", "contentLength");
        assertThat(a.after()).doesNotContain(content);
    }

    // ---- immutability ----

    @Test
    void terms_cannotBeChangedOrDeleted_viaApi() throws Exception {
        post(TERMS, j("version", "2030-01-01", "contentMd", "original")).andExpect(status().isCreated());
        for (String path : List.of(TERMS, TERMS + "/2030-01-01")) {
            for (HttpMethod m : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                int s = call(m, path, j("contentMd", "tampered"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                assertThat(s).as("%s %s", m, path).isIn(404, 405);
            }
        }
        assertThat(jdbc.queryForObject("SELECT content_md FROM terms_document", String.class))
                .isEqualTo("original");
    }

    @Test
    void terms_getOnAdminPath_notAPublicReadOfAnything() throws Exception {
        post(TERMS, j("version", "2030-01-01", "contentMd", "x")).andExpect(status().isCreated());
        // authenticated GET on the POST-only path is a 405/404, never terms data
        int s = fetch(TERMS).andReturn().getResponse().getStatus();
        assertThat(s).isIn(404, 405);
    }
}
