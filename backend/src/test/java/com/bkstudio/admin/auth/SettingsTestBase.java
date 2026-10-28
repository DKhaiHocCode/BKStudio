package com.bkstudio.admin.auth;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.ResultActions;

/** T-020 setup: settings and terms tables are empty before and after every test. */
abstract class SettingsTestBase extends AdminProductTestBase {

    static final String SETTINGS = "/api/admin/settings";
    static final String TERMS = "/api/admin/terms";
    static final String SITE_INFO = "/api/site-info";
    static final String TERMS_CURRENT = "/api/terms/current";

    @BeforeEach
    void cleanSettings() {
        wipe();
    }

    @AfterEach
    void cleanSettingsAfter() {
        wipe();
    }

    private void wipe() {
        jdbc.execute("TRUNCATE app_setting");
        // customer_order references terms_document; CASCADE clears it (and its children) too.
        jdbc.execute("TRUNCATE terms_document CASCADE");
    }

    /** Like List.of but allows null elements. */
    static List<String> list(String... items) {
        return Arrays.asList(items);
    }

    ResultActions putSettings(Object capacity, Object domains, Map<String, Object> social) throws Exception {
        return put(SETTINGS, j("defaultCapacity", capacity, "paymentAllowedDomains", domains, "social", social));
    }

    /** Valid settings except one social field. */
    ResultActions putSocial(String key, String value) throws Exception {
        return putSettings(5, List.of(), Map.of(key, value));
    }

    /** Expects 400 VALIDATION_FAILED with an errors[] entry for exactly this field. */
    static void expectFieldError(ResultActions r, String field) throws Exception {
        expectProblem(r, 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.errors[?(@.field=='" + field + "')]").isNotEmpty());
    }

    Instant settingUpdatedAt(String key) {
        return jdbc.queryForObject("SELECT updated_at FROM app_setting WHERE key = ?", OffsetDateTime.class, key)
                .toInstant();
    }

    int settingRows() {
        return count("SELECT count(*) FROM app_setting");
    }
}
