package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Variants: create, update with optimistic locking, validation matrix, duplicate scale. */
class AdminProductVariantIT extends AdminProductTestBase {

    private String variantsOf(long productId) {
        return PRODUCTS + "/" + productId + "/variants";
    }

    private String updateBody(int price, int stock, boolean mto, Integer quota, boolean active, int version) {
        return j(
                "priceCents", price,
                "stockQty", stock,
                "madeToOrderEnabled", mto,
                "madeToOrderQuota", quota,
                "active", active,
                "version", version);
    }

    @Test
    void createVariant_minimalBody_usesDefaults() throws Exception {
        long pid = createProduct("Defaults " + token());

        post(variantsOf(pid), j("scale", "1/7", "priceCents", 15_000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scale").value("1/7"))
                .andExpect(jsonPath("$.priceCents").value(15_000))
                .andExpect(jsonPath("$.stockQty").value(0))
                .andExpect(jsonPath("$.madeToOrderEnabled").value(false))
                .andExpect(jsonPath("$.madeToOrderQuota").doesNotExist())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void createVariant_allFields_areStored() throws Exception {
        long pid = createProduct("All fields " + token());

        post(
                        variantsOf(pid),
                        j(
                                "scale",
                                "1/6",
                                "priceCents",
                                10_000_000,
                                "stockQty",
                                100_000,
                                "madeToOrderEnabled",
                                true,
                                "madeToOrderQuota",
                                100_000,
                                "active",
                                false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceCents").value(10_000_000))
                .andExpect(jsonPath("$.stockQty").value(100_000))
                .andExpect(jsonPath("$.madeToOrderEnabled").value(true))
                .andExpect(jsonPath("$.madeToOrderQuota").value(100_000))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void createVariant_acceptsTwoDigitScaleAndZeroQuota() throws Exception {
        long pid = createProduct("Scales " + token());

        post(variantsOf(pid), j("scale", "1/12", "priceCents", 1, "stockQty", 0, "madeToOrderQuota", 0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.madeToOrderQuota").value(0));
    }

    @Test
    void createVariant_duplicateScaleOnSameProduct_is409Conflict() throws Exception {
        long pid = createProduct("Dup scale " + token());
        createVariant(pid, "1/7", 1000, 1);
        int audits = auditRows();

        expectProblem(post(variantsOf(pid), j("scale", "1/7", "priceCents", 2000)), 409, "CONFLICT");

        assertThat(count("SELECT count(*) FROM product_variant WHERE product_id = ?", pid))
                .isEqualTo(1);
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void createVariant_sameScaleOnAnotherProduct_isAllowed() throws Exception {
        long a = createProduct("A " + token());
        long b = createProduct("B " + token());
        createVariant(a, "1/7", 1000, 1);

        post(variantsOf(b), j("scale", "1/7", "priceCents", 1000)).andExpect(status().isCreated());
    }

    static Stream<Arguments> invalidCreateBodies() {
        return Stream.of(
                Arguments.of("empty object", "{}"),
                Arguments.of("missing scale", "{\"priceCents\":100}"),
                Arguments.of("missing price", "{\"scale\":\"1/7\"}"),
                Arguments.of("price 0", "{\"scale\":\"1/7\",\"priceCents\":0}"),
                Arguments.of("price negative", "{\"scale\":\"1/7\",\"priceCents\":-5}"),
                Arguments.of("price over max", "{\"scale\":\"1/7\",\"priceCents\":10000001}"),
                Arguments.of("price decimal string", "{\"scale\":\"1/7\",\"priceCents\":\"abc\"}"),
                Arguments.of("scale 3 digits", "{\"scale\":\"1/100\",\"priceCents\":100}"),
                Arguments.of("scale without slash", "{\"scale\":\"17\",\"priceCents\":100}"),
                Arguments.of("scale empty denominator", "{\"scale\":\"1/\",\"priceCents\":100}"),
                Arguments.of("scale letters", "{\"scale\":\"a/7\",\"priceCents\":100}"),
                Arguments.of("scale trailing space", "{\"scale\":\"1/7 \",\"priceCents\":100}"),
                Arguments.of("scale decimal", "{\"scale\":\"1/7.5\",\"priceCents\":100}"),
                Arguments.of("stock negative", "{\"scale\":\"1/7\",\"priceCents\":100,\"stockQty\":-1}"),
                Arguments.of("stock over max", "{\"scale\":\"1/7\",\"priceCents\":100,\"stockQty\":100001}"),
                Arguments.of("quota negative", "{\"scale\":\"1/7\",\"priceCents\":100,\"madeToOrderQuota\":-1}"),
                Arguments.of("quota over max", "{\"scale\":\"1/7\",\"priceCents\":100,\"madeToOrderQuota\":100001}"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidCreateBodies")
    void createVariant_invalidBody_is400AndStoresNothing(String label, String body) throws Exception {
        long pid = createProduct("Invalid " + token());

        expectProblem(post(variantsOf(pid), body), 400, "VALIDATION_FAILED");

        assertThat(count("SELECT count(*) FROM product_variant")).isZero();
    }

    // ---------------- update ----------------

    @Test
    void updateVariant_correctVersion_bumpsVersionAndStampsUpdatedAtFromClock() throws Exception {
        long pid = createProduct("Update " + token());
        long vid = createVariant(pid, "1/7", 1000, 5);
        Instant later = START.plus(Duration.ofHours(1));
        clock.set(later);

        put("/api/admin/variants/" + vid, updateBody(2500, 3, true, 2, true, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(vid))
                .andExpect(jsonPath("$.priceCents").value(2500))
                .andExpect(jsonPath("$.stockQty").value(3))
                .andExpect(jsonPath("$.madeToOrderEnabled").value(true))
                .andExpect(jsonPath("$.madeToOrderQuota").value(2))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(jdbc.queryForObject("SELECT updated_at FROM product_variant WHERE id = ?", OffsetDateTime.class, vid)
                        .toInstant())
                .isEqualTo(later);
        // the scale is not editable and stays as created
        assertThat(jdbc.queryForObject("SELECT scale FROM product_variant WHERE id = ?", String.class, vid))
                .isEqualTo("1/7");
    }

    @Test
    void updateVariant_staleVersion_is409VersionConflictAndChangesNothing() throws Exception {
        long pid = createProduct("Stale variant " + token());
        long vid = createVariant(pid, "1/7", 1000, 5);
        put("/api/admin/variants/" + vid, updateBody(1500, 5, false, null, true, 0))
                .andExpect(status().isOk());
        int audits = auditRows();

        expectProblem(
                put("/api/admin/variants/" + vid, updateBody(9999, 99, false, null, true, 0)), 409, "VERSION_CONFLICT");

        assertThat(count("SELECT price_cents FROM product_variant WHERE id = ?", vid))
                .isEqualTo(1500);
        assertThat(count("SELECT stock_qty FROM product_variant WHERE id = ?", vid))
                .isEqualTo(5);
        assertThat(count("SELECT version FROM product_variant WHERE id = ?", vid))
                .isEqualTo(1);
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void updateVariant_nullQuotaMeansUnlimited() throws Exception {
        long pid = createProduct("Quota " + token());
        long vid = readId(
                post(
                        variantsOf(pid),
                        j("scale", "1/7", "priceCents", 100, "madeToOrderEnabled", true, "madeToOrderQuota", 3)),
                "$.id");

        put("/api/admin/variants/" + vid, updateBody(100, 0, true, null, true, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.madeToOrderQuota").doesNotExist());
    }

    static Stream<Arguments> invalidUpdateBodies() {
        String ok = "\"priceCents\":100,\"stockQty\":1,\"madeToOrderEnabled\":false,\"active\":true,\"version\":0";
        return Stream.of(
                Arguments.of("empty object", "{}"),
                Arguments.of(
                        "missing price", "{\"stockQty\":1,\"madeToOrderEnabled\":false,\"active\":true,\"version\":0}"),
                Arguments.of(
                        "missing stock",
                        "{\"priceCents\":100,\"madeToOrderEnabled\":false,\"active\":true,\"version\":0}"),
                Arguments.of(
                        "missing madeToOrderEnabled",
                        "{\"priceCents\":100,\"stockQty\":1,\"active\":true,\"version\":0}"),
                Arguments.of(
                        "missing active",
                        "{\"priceCents\":100,\"stockQty\":1,\"madeToOrderEnabled\":false,\"version\":0}"),
                Arguments.of(
                        "missing version",
                        "{\"priceCents\":100,\"stockQty\":1,\"madeToOrderEnabled\":false,\"active\":true}"),
                Arguments.of("price 0", ok.replace("\"priceCents\":100", "\"priceCents\":0")),
                Arguments.of("price negative", ok.replace("\"priceCents\":100", "\"priceCents\":-1")),
                Arguments.of("price over max", ok.replace("\"priceCents\":100", "\"priceCents\":10000001")),
                Arguments.of("stock negative", ok.replace("\"stockQty\":1", "\"stockQty\":-1")),
                Arguments.of("stock over max", ok.replace("\"stockQty\":1", "\"stockQty\":100001")),
                Arguments.of("quota negative", ok + ",\"madeToOrderQuota\":-1"),
                Arguments.of("quota over max", ok + ",\"madeToOrderQuota\":100001"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidUpdateBodies")
    void updateVariant_invalidBody_is400AndChangesNothing(String label, String body) throws Exception {
        long pid = createProduct("Invalid update " + token());
        long vid = createVariant(pid, "1/7", 1000, 5);

        expectProblem(
                put("/api/admin/variants/" + vid, "{" + body.replaceAll("^\\{|}$", "") + "}"),
                400,
                "VALIDATION_FAILED");

        assertThat(count("SELECT price_cents FROM product_variant WHERE id = ?", vid))
                .isEqualTo(1000);
        assertThat(count("SELECT version FROM product_variant WHERE id = ?", vid))
                .isZero();
    }

    // ---------------- detail shows what the public API hides ----------------

    @Test
    void adminDetail_showsInactiveVariantsWithStockNumbers_inIdOrder() throws Exception {
        long pid = createProduct("Admin view " + token());
        long v1 = createVariant(pid, "1/7", 12_345, 37);
        long v2 = readId(
                post(variantsOf(pid), j("scale", "1/8", "priceCents", 999, "stockQty", 4, "active", false)), "$.id");

        fetch(PRODUCTS + "/" + pid)
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[0].id").value(v1))
                .andExpect(jsonPath("$.variants[0].stockQty").value(37))
                .andExpect(jsonPath("$.variants[0].priceCents").value(12_345))
                .andExpect(jsonPath("$.variants[1].id").value(v2))
                .andExpect(jsonPath("$.variants[1].active").value(false))
                .andExpect(jsonPath("$.variants[1].stockQty").value(4));
    }
}
