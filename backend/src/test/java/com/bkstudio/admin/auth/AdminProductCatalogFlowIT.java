package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** What an admin edit changes in the PUBLIC catalog (the "create to catalog" flow) and what stays hidden. */
class AdminProductCatalogFlowIT extends AdminProductTestBase {

    private String variantPut(long variantId, boolean active, int version) throws Exception {
        return j(
                "priceCents",
                12_000,
                "stockQty",
                3,
                "madeToOrderEnabled",
                false,
                "madeToOrderQuota",
                null,
                "active",
                active,
                "version",
                version);
    }

    private List<String> publicSlugs(String series) throws Exception {
        return read(publicGet("/api/products?series=" + series).andExpect(status().isOk()), "$.items[*].slug");
    }

    @Test
    void createToCatalog_draftIsInvisible_activeWithActiveVariantBecomesVisible() throws Exception {
        String series = "series" + token();
        long pid = createProduct("name", "Flow " + token(), "series", series);
        String slug = read(fetch(PRODUCTS + "/" + pid), "$.slug");

        // DRAFT: hidden everywhere in the public API, even with an active variant
        long vid = createVariant(pid, "1/7", 12_000, 3);
        expectProblem(publicGet("/api/products/" + slug), 404, "NOT_FOUND");
        assertThat(publicSlugs(series)).isEmpty();

        // ACTIVE + active variant: visible
        setStatus(pid, "ACTIVE");
        publicGet("/api/products/" + slug)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].id").value(vid))
                .andExpect(jsonPath("$.variants[0].priceCents").value(12_000))
                .andExpect(jsonPath("$.variants[0].inStock.available").value(true));
        assertThat(publicSlugs(series)).containsExactly(slug);
        publicGet("/api/products?series=" + series)
                .andExpect(jsonPath("$.items[0].fromPriceCents").value(12_000));
    }

    @Test
    void deactivatingTheOnlyVariant_hidesTheProductFromTheListAndTheVariantFromTheDetail() throws Exception {
        String series = "series" + token();
        long pid = createProduct("name", "Deactivate " + token(), "series", series, "status", "ACTIVE");
        String slug = read(fetch(PRODUCTS + "/" + pid), "$.slug");
        long vid = createVariant(pid, "1/7", 12_000, 3);
        assertThat(publicSlugs(series)).containsExactly(slug);

        put("/api/admin/variants/" + vid, variantPut(vid, false, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(publicSlugs(series)).isEmpty();
        publicGet("/api/products/" + slug)
                .andExpect(jsonPath("$.variants.length()").value(0));
        // the admin still sees the inactive variant with its stock number
        fetch(PRODUCTS + "/" + pid)
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].active").value(false))
                .andExpect(jsonPath("$.variants[0].stockQty").value(3));

        put("/api/admin/variants/" + vid, variantPut(vid, true, 1)).andExpect(status().isOk());
        assertThat(publicSlugs(series)).containsExactly(slug);
    }

    @Test
    void publicDetail_listsOnlyActiveVariants_andNeverExposesStockOrQuota() throws Exception {
        long pid = createProduct("name", "Hidden stock " + token(), "status", "ACTIVE");
        String slug = read(fetch(PRODUCTS + "/" + pid), "$.slug");
        post(
                        PRODUCTS + "/" + pid + "/variants",
                        j(
                                "scale",
                                "1/7",
                                "priceCents",
                                1000,
                                "stockQty",
                                4242,
                                "madeToOrderEnabled",
                                true,
                                "madeToOrderQuota",
                                4343))
                .andExpect(status().isCreated());
        post(PRODUCTS + "/" + pid + "/variants", j("scale", "1/8", "priceCents", 900, "stockQty", 7, "active", false))
                .andExpect(status().isCreated());

        String publicBody = body(publicGet("/api/products/" + slug)
                .andExpect(jsonPath("$.variants.length()").value(1)));

        assertThat(publicBody).doesNotContain("4242", "4343", "stockQty", "madeToOrderQuota", "1/8");
        fetch(PRODUCTS + "/" + pid)
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[0].stockQty").value(4242))
                .andExpect(jsonPath("$.variants[0].madeToOrderQuota").value(4343));
    }

    @Test
    void archivingAnActiveProduct_removesItFromThePublicApi() throws Exception {
        String series = "series" + token();
        long pid = createProduct("name", "Archive " + token(), "series", series, "status", "ACTIVE");
        String slug = read(fetch(PRODUCTS + "/" + pid), "$.slug");
        createVariant(pid, "1/7", 1000, 1);
        publicGet("/api/products/" + slug).andExpect(status().isOk());

        setStatus(pid, "ARCHIVED");

        expectProblem(publicGet("/api/products/" + slug), 404, "NOT_FOUND");
        assertThat(publicSlugs(series)).isEmpty();
        fetch(PRODUCTS + "/" + pid).andExpect(jsonPath("$.status").value("ARCHIVED")); // admin still sees it
    }

    @Test
    void unlistedProduct_isReachableByItsRandomSlugButNotListed() throws Exception {
        String series = "series" + token();
        long pid = createProduct("name", "Commission " + token(), "series", series, "status", "UNLISTED");
        String slug = read(fetch(PRODUCTS + "/" + pid), "$.slug");
        createVariant(pid, "1/6", 30_000, 0);

        publicGet("/api/products/" + slug)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlisted").value(true));
        assertThat(publicSlugs(series)).isEmpty();
        fetch(PRODUCTS + "?status=UNLISTED").andExpect(jsonPath("$.total").value(1)); // admin list shows it
    }

    @Test
    void draftProductWithActiveVariantAndImages_staysNotFoundPublicly() throws Exception {
        long pid = createProduct("Draft " + token());
        String slug = read(fetch(PRODUCTS + "/" + pid), "$.slug");
        createVariant(pid, "1/7", 1000, 1);
        insertImageRow(pid, randomImageKey(), 0);

        expectProblem(publicGet("/api/products/" + slug), 404, "NOT_FOUND");
    }

    // ---------------- reorder / alt text (no storage involved) ----------------

    @Test
    void reorderImages_persistsTheOrder_andDetailAdminListAndPublicCoverFollow() throws Exception {
        String series = "series" + token();
        long pid = createProduct("name", "Reorder " + token(), "series", series, "status", "ACTIVE");
        String slug = read(fetch(PRODUCTS + "/" + pid), "$.slug");
        createVariant(pid, "1/7", 1000, 1);
        String k0 = randomImageKey();
        String k1 = randomImageKey();
        String k2 = randomImageKey();
        long i0 = insertImageRow(pid, k0, 0);
        long i1 = insertImageRow(pid, k1, 1);
        long i2 = insertImageRow(pid, k2, 2);
        publicGet("/api/products/" + slug)
                .andExpect(jsonPath("$.images[0].url").value(imageBase + "/" + k0 + "/1000.webp"));

        put(PRODUCTS + "/" + pid + "/images/order", j("imageIds", List.of(i2, i0, i1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(i2))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[1].id").value(i0))
                .andExpect(jsonPath("$[1].sortOrder").value(1))
                .andExpect(jsonPath("$[2].id").value(i1))
                .andExpect(jsonPath("$[2].sortOrder").value(2));

        fetch(PRODUCTS + "/" + pid)
                .andExpect(jsonPath("$.images[0].id").value(i2))
                .andExpect(jsonPath("$.images[1].id").value(i0))
                .andExpect(jsonPath("$.images[2].id").value(i1))
                .andExpect(jsonPath("$.images[0].urls['400']").value(imageBase + "/" + k2 + "/400.webp"))
                .andExpect(jsonPath("$.images[0].urls['1000']").value(imageBase + "/" + k2 + "/1000.webp"))
                .andExpect(jsonPath("$.images[0].urls['2000']").value(imageBase + "/" + k2 + "/2000.webp"));
        fetch(PRODUCTS).andExpect(jsonPath("$.items[0].coverImage.url").value(imageBase + "/" + k2 + "/1000.webp"));
        publicGet("/api/products/" + slug)
                .andExpect(jsonPath("$.images[0].url").value(imageBase + "/" + k2 + "/1000.webp"));
        publicGet("/api/products?series=" + series)
                .andExpect(jsonPath("$.items[0].coverImage.url").value(imageBase + "/" + k2 + "/1000.webp"));
    }

    @Test
    void reorderImages_sameOrderAgain_isAccepted() throws Exception {
        long pid = createProduct("Same order " + token());
        long a = insertImageRow(pid, randomImageKey(), 0);
        long b = insertImageRow(pid, randomImageKey(), 1);

        put(PRODUCTS + "/" + pid + "/images/order", j("imageIds", List.of(a, b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(a));
    }

    @Test
    void reorderImages_emptyListOnProductWithoutImages_isOk() throws Exception {
        long pid = createProduct("No images " + token());

        put(PRODUCTS + "/" + pid + "/images/order", j("imageIds", List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void reorderImages_mustListTheExactSet_otherwise400AndNothingChanges() throws Exception {
        long pid = createProduct("Exact set " + token());
        long a = insertImageRow(pid, randomImageKey(), 0);
        long b = insertImageRow(pid, randomImageKey(), 1);
        long c = insertImageRow(pid, randomImageKey(), 2);
        long other = createProduct("Other " + token());
        long foreign = insertImageRow(other, randomImageKey(), 0);
        int audits = auditRows();

        List<List<Long>> bad = List.of(
                List.of(a, b), // missing one
                List.of(a, b, c, foreign), // extra image of another product
                List.of(a, b, foreign), // foreign id instead of c
                List.of(a, a, b), // duplicate, right size
                List.of(a, b, c, c), // duplicate, too long
                List.of(a, b, UNKNOWN_ID), // nonexistent id
                List.of()); // empty although the product has images
        for (List<Long> ids : bad) {
            expectProblem(put(PRODUCTS + "/" + pid + "/images/order", j("imageIds", ids)), 400, "VALIDATION_FAILED");
        }

        List<Number> order = jdbc.queryForList(
                "SELECT id FROM product_image WHERE product_id = ? ORDER BY sort_order, id", Number.class, pid);
        assertThat(order.stream().map(Number::longValue)).containsExactly(a, b, c);
        assertThat(count("SELECT sort_order FROM product_image WHERE id = ?", foreign))
                .isZero();
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void reorderImages_invalidBody_is400() throws Exception {
        long pid = createProduct("Bad body " + token());

        expectProblem(put(PRODUCTS + "/" + pid + "/images/order", "{}"), 400, "VALIDATION_FAILED");
        expectProblem(put(PRODUCTS + "/" + pid + "/images/order", "{\"imageIds\":null}"), 400, "VALIDATION_FAILED");
        expectProblem(put(PRODUCTS + "/" + pid + "/images/order", "{\"imageIds\":[1,null]}"), 400, "VALIDATION_FAILED");
        expectProblem(put(PRODUCTS + "/" + pid + "/images/order", "{\"imageIds\":[\"x\"]}"), 400, "VALIDATION_FAILED");
    }

    @Test
    void reorderImages_moreThan20Ids_is400() throws Exception {
        long pid = createProduct("Too many ids " + token());
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 21).boxed().toList();

        expectProblem(put(PRODUCTS + "/" + pid + "/images/order", j("imageIds", ids)), 400, "VALIDATION_FAILED");
    }

    @Test
    void updateAltText_200CharsOk_201Rejected_emptyOk() throws Exception {
        long pid = createProduct("Alt " + token());
        long img = insertImageRow(pid, randomImageKey(), 0);
        String path = "/api/admin/images/" + img;

        call(org.springframework.http.HttpMethod.PATCH, path, j("altText", "ß".repeat(200)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(img))
                .andExpect(jsonPath("$.altText").value("ß".repeat(200)));
        expectProblem(
                call(org.springframework.http.HttpMethod.PATCH, path, j("altText", "a".repeat(201))),
                400,
                "VALIDATION_FAILED");
        call(org.springframework.http.HttpMethod.PATCH, path, j("altText", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.altText").value(""));
        expectProblem(call(org.springframework.http.HttpMethod.PATCH, path, "{}"), 400, "VALIDATION_FAILED");
        expectProblem(
                call(org.springframework.http.HttpMethod.PATCH, path, "{\"altText\":null}"), 400, "VALIDATION_FAILED");

        assertThat(jdbc.queryForObject("SELECT alt_text FROM product_image WHERE id = ?", String.class, img))
                .isEmpty();
    }

    // ---------------- going UNLISTED rewrites the slug ----------------

    private ResultActions putStatus(long pid, String newStatus, int version) throws Exception {
        String name = read(fetch(PRODUCTS + "/" + pid), "$.name");
        return put(PRODUCTS + "/" + pid, j("name", name, "status", newStatus, "version", version));
    }

    private void assertSlugReplacedByUnguessableOne(long pid, String oldSlug, String newSlug) throws Exception {
        assertThat(newSlug).matches("^" + oldSlug + "-[a-z0-9]{10}$");
        expectProblem(publicGet("/api/products/" + oldSlug), 404, "NOT_FOUND");
        publicGet("/api/products/" + newSlug)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlisted").value(true));
        Audit a = audits("PRODUCT", pid).getLast();
        assertThat(om.readTree(a.before()).path("slug").asString()).isEqualTo(oldSlug);
        assertThat(om.readTree(a.after()).path("slug").asString()).isEqualTo(newSlug);
    }

    @Test
    void draftToUnlisted_replacesTheSlugWithARandomOne_andTheOldSlugIs404() throws Exception {
        long pid = createProduct("Going private " + token());
        String oldSlug = read(fetch(PRODUCTS + "/" + pid), "$.slug");
        createVariant(pid, "1/6", 30_000, 0);

        String newSlug = read(putStatus(pid, "UNLISTED", 0).andExpect(status().isOk()), "$.slug");

        assertSlugReplacedByUnguessableOne(pid, oldSlug, newSlug);
    }

    @Test
    void activeToUnlisted_replacesTheSlug_andActiveListingNoLongerShowsIt() throws Exception {
        String series = "series" + token();
        long pid = createProduct("name", "Was public " + token(), "series", series, "status", "ACTIVE");
        String oldSlug = read(fetch(PRODUCTS + "/" + pid), "$.slug");
        createVariant(pid, "1/6", 30_000, 3);
        assertThat(publicSlugs(series)).containsExactly(oldSlug);

        String newSlug = read(putStatus(pid, "UNLISTED", 0).andExpect(status().isOk()), "$.slug");

        assertSlugReplacedByUnguessableOne(pid, oldSlug, newSlug);
        assertThat(publicSlugs(series)).isEmpty();
    }

    @Test
    void unlistedToActive_keepsTheSlug_andUpdatingAnUnlistedProductKeepsItToo() throws Exception {
        long pid = createProduct("Stays " + token());
        createVariant(pid, "1/6", 30_000, 3);
        String unlistedSlug = read(putStatus(pid, "UNLISTED", 0).andExpect(status().isOk()), "$.slug");

        String same = read(putStatus(pid, "UNLISTED", 1).andExpect(status().isOk()), "$.slug"); // already unlisted
        String active = read(putStatus(pid, "ACTIVE", 2).andExpect(status().isOk()), "$.slug");

        assertThat(same).isEqualTo(unlistedSlug);
        assertThat(active).isEqualTo(unlistedSlug);
        publicGet("/api/products/" + unlistedSlug).andExpect(status().isOk());
    }

    @Test
    void staleVersionWhenGoingUnlisted_is409_andLeavesTheSlugUntouched() throws Exception {
        long pid = createProduct("Stale " + token());
        String slug = read(fetch(PRODUCTS + "/" + pid), "$.slug");
        int audits = auditRows();

        expectProblem(putStatus(pid, "UNLISTED", 7), 409, "VERSION_CONFLICT");

        assertThat(jdbc.queryForObject("SELECT slug FROM product WHERE id = ?", String.class, pid))
                .isEqualTo(slug);
        assertThat(jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, pid))
                .isEqualTo("DRAFT");
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void goingUnlistedWithALongSlug_staysAValidSlug() throws Exception {
        String base = ("a".repeat(39) + "-").repeat(2) + "b".repeat(10); // 90 chars; the 80-char cut ends in '-'
        long pid = createProduct("Long " + token());
        jdbc.update("UPDATE product SET slug = ? WHERE id = ?", base, pid);

        String newSlug = read(putStatus(pid, "UNLISTED", 0).andExpect(status().isOk()), "$.slug");

        assertThat(newSlug).matches("^[a-z0-9]+(-[a-z0-9]+)*$").hasSizeLessThanOrEqualTo(91);
        assertThat(newSlug).startsWith(base.substring(0, 79) + "-");
    }
}
