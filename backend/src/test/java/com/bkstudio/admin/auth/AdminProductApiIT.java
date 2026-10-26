package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;

/** API_SPEC section 3 "Sản phẩm": create, update, validation, optimistic locking, 404 and 405 behaviour. */
class AdminProductApiIT extends AdminProductTestBase {

    // ---------------- create ----------------

    @Test
    void createProduct_defaultsToDraftWithVersionZeroAndClockTimestamps() throws Exception {
        String name = "Default " + token();

        post(PRODUCTS, j("name", name))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(PRODUCTS + "/")))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.series").doesNotExist())
                .andExpect(jsonPath("$.createdAt").value(START.toString()))
                .andExpect(jsonPath("$.updatedAt").value(START.toString()))
                .andExpect(jsonPath("$.variants.length()").value(0))
                .andExpect(jsonPath("$.images.length()").value(0));
    }

    @Test
    void createProduct_storesGivenFieldsAndExplicitStatus() throws Exception {
        String name = "Full " + token();

        post(PRODUCTS, j("name", name, "series", "Genshin", "description", "Hand painted", "status", "ARCHIVED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.series").value("Genshin"))
                .andExpect(jsonPath("$.description").value("Hand painted"))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void createProduct_blankSeriesIsStoredAsNull() throws Exception {
        post(PRODUCTS, j("name", "Blank series " + token(), "series", "   "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.series").doesNotExist());
    }

    @Test
    void createProduct_atMaximumLengths_isAccepted() throws Exception {
        post(PRODUCTS, j("name", "n".repeat(200), "series", "s".repeat(200), "description", "d".repeat(10_000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("n".repeat(80)));
    }

    // ---- slug generation ----

    static Stream<Arguments> generatedSlugs() {
        return Stream.of(
                Arguments.of("Raiden Shōgun Figure", "raiden-shogun-figure"),
                Arguments.of("  Hello   World  ", "hello-world"),
                Arguments.of("Nguyễn Đặc Biệt Đồ Chơi", "nguyen-dac-biet-do-choi"),
                Arguments.of("A&B: C/D #1!", "a-b-c-d-1"),
                Arguments.of("Café—Crème (1/7)", "cafe-creme-1-7"),
                Arguments.of("!!!???", "product"),
                Arguments.of("日本語のフィギュア", "product"),
                Arguments.of("x".repeat(150), "x".repeat(80)),
                // cut at 80 chars lands right after a hyphen: the trailing hyphen must be dropped
                Arguments.of("word ".repeat(40), "word-".repeat(15) + "word"));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @MethodSource("generatedSlugs")
    void createProduct_generatesSlugFromName(String name, String expectedSlug) throws Exception {
        post(PRODUCTS, j("name", name))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(expectedSlug));
    }

    @Test
    void createProduct_generatedSlugAlwaysSatisfiesTheDbSlugFormat() throws Exception {
        for (String name : new String[] {"-", "--a--", "_x_", "a  b", "1", "Ünï©ödé"}) {
            String slug = read(post(PRODUCTS, j("name", name)).andExpect(status().isCreated()), "$.slug");
            assertThat(slug).matches("^[a-z0-9]+(-[a-z0-9]+)*$").hasSizeLessThanOrEqualTo(83);
        }
    }

    @Test
    void createProduct_duplicateGeneratedSlug_getsNumericSuffixes() throws Exception {
        String name = "Dup " + token();
        String base = name.toLowerCase().replace(' ', '-');

        post(PRODUCTS, j("name", name)).andExpect(jsonPath("$.slug").value(base));
        post(PRODUCTS, j("name", name)).andExpect(jsonPath("$.slug").value(base + "-2"));
        post(PRODUCTS, j("name", name)).andExpect(jsonPath("$.slug").value(base + "-3"));
        assertThat(count("SELECT count(*) FROM product")).isEqualTo(3);
    }

    @Test
    void createProduct_clientSlugIsUsedVerbatim() throws Exception {
        String slug = "my-slug-" + token();

        post(PRODUCTS, j("name", "Whatever name", "slug", slug))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(slug));
    }

    @ParameterizedTest(name = "[{index}] slug \"{0}\"")
    @ValueSource(
            strings = {
                "Has Space",
                "UPPER",
                "under_score",
                "-leading",
                "trailing-",
                "double--hyphen",
                "dot.dot",
                "unicode-é",
                ""
            })
    void createProduct_invalidClientSlug_is400(String slug) throws Exception {
        expectProblem(post(PRODUCTS, j("name", "Bad slug", "slug", slug)), 400, "VALIDATION_FAILED");
        assertThat(count("SELECT count(*) FROM product")).isZero();
    }

    @Test
    void createProduct_clientSlugOver80Chars_is400() throws Exception {
        expectProblem(post(PRODUCTS, j("name", "Long slug", "slug", "a".repeat(81))), 400, "VALIDATION_FAILED");
        post(PRODUCTS, j("name", "Long slug ok", "slug", "a".repeat(80))).andExpect(status().isCreated());
    }

    @Test
    void createProduct_duplicateClientSlug_is409ConflictAndCreatesNothing() throws Exception {
        String slug = "taken-" + token();
        createProduct("name", "First", "slug", slug);
        int audits = auditRows();

        expectProblem(post(PRODUCTS, j("name", "Second", "slug", slug)), 409, "CONFLICT");

        assertThat(count("SELECT count(*) FROM product")).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void createProduct_unlisted_alwaysGetsRandomTenCharSuffix() throws Exception {
        String name = "Commission " + token();
        String base = name.toLowerCase().replace(' ', '-');

        String first =
                read(post(PRODUCTS, j("name", name, "status", "UNLISTED")).andExpect(status().isCreated()), "$.slug");
        String second =
                read(post(PRODUCTS, j("name", name, "status", "UNLISTED")).andExpect(status().isCreated()), "$.slug");

        assertThat(first).matches("^" + base + "-[a-z0-9]{10}$");
        assertThat(second).matches("^" + base + "-[a-z0-9]{10}$");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void createProduct_unlistedWithClientSlug_stillGetsSuffix_andNeverConflicts() throws Exception {
        String slug = "private-" + token();

        String first = read(post(PRODUCTS, j("name", "A", "slug", slug, "status", "UNLISTED")), "$.slug");
        String second = read(post(PRODUCTS, j("name", "B", "slug", slug, "status", "UNLISTED")), "$.slug");

        assertThat(first).matches("^" + slug + "-[a-z0-9]{10}$");
        assertThat(second).matches("^" + slug + "-[a-z0-9]{10}$").isNotEqualTo(first);
    }

    @Test
    void createProduct_unlistedWithUnusableName_usesFallbackBasePlusSuffix() throws Exception {
        String slug = read(post(PRODUCTS, j("name", "???", "status", "UNLISTED")), "$.slug");

        assertThat(slug).matches("^product-[a-z0-9]{10}$");
    }

    // ---- create validation ----

    static Stream<Arguments> invalidProductBodies() {
        String ok = "\"name\":\"Valid name\"";
        return Stream.of(
                Arguments.of("empty object", "{}"),
                Arguments.of("empty name", "{\"name\":\"\"}"),
                Arguments.of("blank name", "{\"name\":\"   \"}"),
                Arguments.of("null name", "{\"name\":null}"),
                Arguments.of("name 201 chars", "{\"name\":\"" + "n".repeat(201) + "\"}"),
                Arguments.of("description 10001 chars", "{" + ok + ",\"description\":\"" + "d".repeat(10_001) + "\"}"),
                Arguments.of("series 201 chars", "{" + ok + ",\"series\":\"" + "s".repeat(201) + "\"}"),
                Arguments.of("unknown status", "{" + ok + ",\"status\":\"PUBLISHED\"}"),
                Arguments.of("lower-case status", "{" + ok + ",\"status\":\"active\"}"),
                Arguments.of("malformed json", "{\"name\":"),
                Arguments.of("json array", "[]"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidProductBodies")
    void createProduct_invalidBody_is400ProblemAndStoresNothing(String label, String body) throws Exception {
        expectProblem(post(PRODUCTS, body), 400, "VALIDATION_FAILED");

        assertThat(count("SELECT count(*) FROM product")).isZero();
        assertThat(auditRows()).isZero();
    }

    /** Jackson coerces JSON numbers to enum ordinals by default; a status must be one of the four names. */
    @Test
    void createProduct_numericStatus_isRejected() throws Exception {
        expectProblem(post(PRODUCTS, "{\"name\":\"Numeric status\",\"status\":1}"), 400, "VALIDATION_FAILED");

        assertThat(count("SELECT count(*) FROM product")).isZero();
    }

    @Test
    void updateProduct_numericStatus_isRejected() throws Exception {
        long id = createProduct("Numeric update " + token());

        expectProblem(
                put(PRODUCTS + "/" + id, "{\"name\":\"N\",\"status\":1,\"version\":0}"), 400, "VALIDATION_FAILED");

        assertThat(jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, id))
                .isEqualTo("DRAFT");
    }

    @Test
    void createProduct_validationErrorNamesTheField() throws Exception {
        post(PRODUCTS, "{\"name\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void createProduct_nonJsonContentType_is415() throws Exception {
        mvc.perform(withCsrf(HttpMethod.POST, PRODUCTS, null, session, nextIp())
                        .contentType("text/plain")
                        .content("name=x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    // ---------------- update ----------------

    @Test
    void updateProduct_correctVersion_bumpsVersionAndStampsUpdatedAtFromClock() throws Exception {
        long id = createProduct("Before " + token());
        String slug = read(fetch(PRODUCTS + "/" + id), "$.slug");
        Instant later = START.plus(Duration.ofMinutes(5));
        clock.set(later);

        put(
                        PRODUCTS + "/" + id,
                        j("name", "After", "series", "S", "description", "D", "status", "ACTIVE", "version", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.name").value("After"))
                .andExpect(jsonPath("$.series").value("S"))
                .andExpect(jsonPath("$.description").value("D"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.createdAt").value(START.toString()))
                .andExpect(jsonPath("$.updatedAt").value(later.toString()));

        clock.set(later.plusSeconds(60));
        put(PRODUCTS + "/" + id, j("name", "After 2", "status", "ACTIVE", "version", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.updatedAt").value(later.plusSeconds(60).toString()));
    }

    @Test
    void updateProduct_staleVersion_is409VersionConflictAndChangesNothing() throws Exception {
        long id = createProduct("Stale " + token());
        put(PRODUCTS + "/" + id, j("name", "Winner", "status", "DRAFT", "version", 0))
                .andExpect(status().isOk());
        int audits = auditRows();

        expectProblem(
                put(PRODUCTS + "/" + id, j("name", "Loser", "status", "ACTIVE", "version", 0)),
                409,
                "VERSION_CONFLICT");

        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, id))
                .isEqualTo("Winner");
        assertThat(count("SELECT version FROM product WHERE id = ?", id)).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void updateProduct_futureVersion_is409VersionConflict() throws Exception {
        long id = createProduct("Future " + token());

        expectProblem(
                put(PRODUCTS + "/" + id, j("name", "X", "status", "DRAFT", "version", 7)), 409, "VERSION_CONFLICT");
    }

    @Test
    void updateProduct_slugInBodyIsIgnored_slugIsImmutable() throws Exception {
        long id = createProduct("Immutable " + token());
        String slug = read(fetch(PRODUCTS + "/" + id), "$.slug");

        put(
                        PRODUCTS + "/" + id,
                        j("name", "Renamed completely", "slug", "hacked-slug", "status", "DRAFT", "version", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug));
        assertThat(jdbc.queryForObject("SELECT slug FROM product WHERE id = ?", String.class, id))
                .isEqualTo(slug);
    }

    @Test
    void updateProduct_nullDescriptionBecomesEmpty_blankSeriesBecomesNull() throws Exception {
        long id = createProduct("name", "Nulls " + token(), "series", "Old", "description", "Old text");

        put(
                        PRODUCTS + "/" + id,
                        j("name", "Nulls", "series", " ", "description", null, "status", "DRAFT", "version", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.series").doesNotExist());
    }

    @Test
    void updateProduct_canMoveThroughEveryStatus() throws Exception {
        long id = createProduct("Statuses " + token());
        int version = 0;
        for (String s : new String[] {"ACTIVE", "UNLISTED", "ARCHIVED", "DRAFT"}) {
            put(PRODUCTS + "/" + id, j("name", "Statuses", "status", s, "version", version++))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(s));
        }
    }

    static Stream<Arguments> invalidUpdateBodies() {
        return Stream.of(
                Arguments.of("empty object", "{}"),
                Arguments.of("blank name", "{\"name\":\" \",\"status\":\"DRAFT\",\"version\":0}"),
                Arguments.of(
                        "name 201 chars", "{\"name\":\"" + "n".repeat(201) + "\",\"status\":\"DRAFT\",\"version\":0}"),
                Arguments.of("missing status", "{\"name\":\"N\",\"version\":0}"),
                Arguments.of("invalid status", "{\"name\":\"N\",\"status\":\"GONE\",\"version\":0}"),
                Arguments.of("missing version", "{\"name\":\"N\",\"status\":\"DRAFT\"}"),
                Arguments.of("string version", "{\"name\":\"N\",\"status\":\"DRAFT\",\"version\":\"abc\"}"),
                Arguments.of(
                        "description 10001 chars",
                        "{\"name\":\"N\",\"status\":\"DRAFT\",\"version\":0,\"description\":\"" + "d".repeat(10_001)
                                + "\"}"),
                Arguments.of(
                        "series 201 chars",
                        "{\"name\":\"N\",\"status\":\"DRAFT\",\"version\":0,\"series\":\"" + "s".repeat(201) + "\"}"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidUpdateBodies")
    void updateProduct_invalidBody_is400AndChangesNothing(String label, String body) throws Exception {
        String name = "Keep " + token();
        long id = createProduct(name);

        expectProblem(put(PRODUCTS + "/" + id, body), 400, "VALIDATION_FAILED");

        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, id))
                .isEqualTo(name);
        assertThat(count("SELECT version FROM product WHERE id = ?", id)).isZero();
    }

    // ---------------- detail ----------------

    @Test
    void getProduct_returnsFullDetailForAnyStatus() throws Exception {
        long id = createProduct("name", "Detail " + token(), "description", "Long text", "status", "ARCHIVED");

        fetch(PRODUCTS + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.description").value("Long text"));
    }

    @Test
    void getProduct_nonNumericId_is400Problem() throws Exception {
        expectProblem(fetch(PRODUCTS + "/abc"), 400, "VALIDATION_FAILED");
    }

    // ---------------- 404 on every id-based endpoint ----------------

    static Stream<Arguments> unknownIdRequests() {
        long id = UNKNOWN_ID;
        String product = "{\"name\":\"N\",\"status\":\"DRAFT\",\"version\":0}";
        String variant = "{\"scale\":\"1/7\",\"priceCents\":100}";
        String variantUpdate =
                "{\"priceCents\":100,\"stockQty\":1,\"madeToOrderEnabled\":false,\"active\":true,\"version\":0}";
        return Stream.of(
                Arguments.of("GET", "/api/admin/products/" + id, null),
                Arguments.of("PUT", "/api/admin/products/" + id, product),
                Arguments.of("POST", "/api/admin/products/" + id + "/variants", variant),
                Arguments.of("PUT", "/api/admin/variants/" + id, variantUpdate),
                Arguments.of("PUT", "/api/admin/products/" + id + "/images/order", "{\"imageIds\":[]}"),
                Arguments.of("PATCH", "/api/admin/images/" + id, "{\"altText\":\"x\"}"),
                Arguments.of("DELETE", "/api/admin/images/" + id, null));
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("unknownIdRequests")
    void unknownId_is404ProblemWithCodeAndRequestId(String method, String path, String body) throws Exception {
        var result = HttpMethod.GET.matches(method) ? fetch(path) : call(HttpMethod.valueOf(method), path, body);

        expectProblem(result, 404, "NOT_FOUND");
    }

    @Test
    void uploadImage_unknownProduct_is404BeforeAnyProcessing() throws Exception {
        expectProblem(
                mvc.perform(multipart(
                        PRODUCTS + "/" + UNKNOWN_ID + "/images",
                        session,
                        csrfToken(),
                        null,
                        new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[] {1, 2, 3}))),
                404,
                "NOT_FOUND");
    }

    // ---------------- no delete endpoints for products and variants ----------------

    @Test
    void productAndVariantCannotBeDeleted_noDeleteEndpoint() throws Exception {
        long id = createProduct("Undeletable " + token());
        long variantId = createVariant(id, "1/7", 1000, 1);

        expectProblem(call(HttpMethod.DELETE, PRODUCTS + "/" + id, null), 405, "BAD_REQUEST");
        expectProblem(call(HttpMethod.DELETE, "/api/admin/variants/" + variantId, null), 405, "BAD_REQUEST");
        expectProblem(call(HttpMethod.DELETE, PRODUCTS + "/" + id + "/variants", null), 405, "BAD_REQUEST");

        assertThat(count("SELECT count(*) FROM product WHERE id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM product_variant WHERE id = ?", variantId))
                .isEqualTo(1);
    }
}
