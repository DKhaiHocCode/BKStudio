package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** GET /api/admin/products: filters, text search, paging, ordering, counts and cover image. */
class AdminProductListIT extends AdminProductTestBase {

    private List<String> slugs(String query) throws Exception {
        return read(fetch(PRODUCTS + query).andExpect(status().isOk()), "$.items[*].slug");
    }

    @Test
    void list_emptyCatalog_returnsDefaultsPage0Size20() throws Exception {
        fetch(PRODUCTS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void list_noFilter_includesProductsOfEveryStatus() throws Exception {
        for (String s : new String[] {"DRAFT", "ACTIVE", "UNLISTED", "ARCHIVED"}) {
            createProduct("name", "Status " + s + " " + token(), "status", s);
        }

        fetch(PRODUCTS)
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items.length()").value(4));
    }

    @Test
    void list_statusFilter_returnsOnlyThatStatus() throws Exception {
        createProduct("name", "Draft one " + token());
        createProduct("name", "Active one " + token(), "status", "ACTIVE");
        createProduct("name", "Active two " + token(), "status", "ACTIVE");
        createProduct("name", "Archived one " + token(), "status", "ARCHIVED");

        fetch(PRODUCTS + "?status=ACTIVE")
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].status")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ACTIVE"))));
        fetch(PRODUCTS + "?status=DRAFT").andExpect(jsonPath("$.total").value(1));
        fetch(PRODUCTS + "?status=UNLISTED").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void list_unknownStatus_is400() throws Exception {
        expectProblem(fetch(PRODUCTS + "?status=PUBLISHED"), 400, "VALIDATION_FAILED");
    }

    @Test
    void list_q_matchesNameCaseInsensitively() throws Exception {
        String t = token();
        createProduct("Raiden SHOGUN " + t);
        createProduct("Unrelated " + token());

        assertThat(slugs("?q=raiden shogun " + t)).hasSize(1);
        assertThat(slugs("?q=RAIDEN sHoGuN")).hasSize(1);
        assertThat(slugs("?q=  RAIDEN  ")).hasSize(1); // surrounding blanks are stripped
    }

    @Test
    void list_q_matchesSlugEvenWhenNameDiffers() throws Exception {
        String slug = "only-in-slug-" + token();
        createProduct("name", "Completely different", "slug", slug);

        assertThat(slugs("?q=only-in-slug")).containsExactly(slug);
        assertThat(slugs("?q=ONLY-IN-SLUG")).containsExactly(slug);
    }

    @Test
    void list_q_blankMeansNoFilter() throws Exception {
        createProduct("Blank q " + token());

        assertThat(slugs("?q=")).hasSize(1);
        assertThat(slugs("?q=  ")).hasSize(1);
    }

    @Test
    void list_q_percentSignIsLiteral() throws Exception {
        String t = token();
        createProduct("name", "Pct 100% real " + t, "slug", "pct-literal-" + t);
        createProduct("name", "Pct 1000 real " + t, "slug", "pct-other-" + t);

        // an unescaped % would match both names ("100% real" vs "1000 real" -> "100%" matches "1000" as prefix)
        assertThat(slugs("?q=100% real " + t)).containsExactly("pct-literal-" + t);
        assertThat(slugs("?q=%")).containsExactly("pct-literal-" + t); // only one name contains a percent sign
    }

    @Test
    void list_q_underscoreIsLiteral() throws Exception {
        String t = token();
        createProduct("name", "Under_score " + t, "slug", "under-literal-" + t);
        createProduct("name", "UnderXscore " + t, "slug", "under-other-" + t);

        assertThat(slugs("?q=under_score " + t)).containsExactly("under-literal-" + t);
        assertThat(slugs("?q=_")).containsExactly("under-literal-" + t);
    }

    @Test
    void list_q_backslashIsLiteralAndDoesNotBreakTheQuery() throws Exception {
        String t = token();
        createProduct("name", "Back\\slash " + t, "slug", "backslash-" + t);

        assertThat(slugs("?q=back\\slash " + t)).containsExactly("backslash-" + t);
        assertThat(slugs("?q=\\")).containsExactly("backslash-" + t);
    }

    @Test
    void list_q_sqlMetaCharactersAreHarmless() throws Exception {
        createProduct("Safe " + token());

        assertThat(slugs("?q='; DROP TABLE product; --")).isEmpty();
        assertThat(count("SELECT count(*) FROM product")).isEqualTo(1);
    }

    @Test
    void list_q_over200Chars_is400() throws Exception {
        expectProblem(fetch(PRODUCTS + "?q=" + "a".repeat(201)), 400, "VALIDATION_FAILED");
        fetch(PRODUCTS + "?q=" + "a".repeat(200)).andExpect(status().isOk());
    }

    @Test
    void list_q_combinesWithStatusFilter() throws Exception {
        String t = token();
        createProduct("name", "Combo draft " + t);
        createProduct("name", "Combo active " + t, "status", "ACTIVE");

        fetch(PRODUCTS + "?status=ACTIVE&q=combo active " + t)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
    }

    // ---- paging ----

    @Test
    void list_sizeAbove50_isClampedAndEchoed() throws Exception {
        fetch(PRODUCTS + "?size=500").andExpect(jsonPath("$.size").value(50));
        fetch(PRODUCTS + "?size=2147483647").andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void list_pagesSliceTheResultWithConsistentTotal() throws Exception {
        for (int i = 0; i < 5; i++) {
            createProduct("Paged " + i + " " + token());
            clock.advance(Duration.ofSeconds(1));
        }

        fetch(PRODUCTS + "?size=2&page=0")
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(2));
        fetch(PRODUCTS + "?size=2&page=2")
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(2));
        fetch(PRODUCTS + "?size=2&page=3")
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void list_pageCap_10000IsEmptyOk_10001IsRejected() throws Exception {
        fetch(PRODUCTS + "?page=10000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        expectProblem(fetch(PRODUCTS + "?page=10001"), 400, "VALIDATION_FAILED");
        expectProblem(fetch(PRODUCTS + "?page=2147483647"), 400, "VALIDATION_FAILED");
    }

    @Test
    void list_invalidPaging_is400() throws Exception {
        expectProblem(fetch(PRODUCTS + "?page=-1"), 400, "VALIDATION_FAILED");
        expectProblem(fetch(PRODUCTS + "?size=0"), 400, "VALIDATION_FAILED");
        expectProblem(fetch(PRODUCTS + "?size=-3"), 400, "VALIDATION_FAILED");
        expectProblem(fetch(PRODUCTS + "?page=abc"), 400, "VALIDATION_FAILED");
    }

    // ---- ordering ----

    @Test
    void list_sortsByUpdatedAtDescending_andAnEditMovesAProductToTheTop() throws Exception {
        long a = createProduct("Sort A " + token());
        clock.advance(Duration.ofMinutes(1));
        long b = createProduct("Sort B " + token());
        clock.advance(Duration.ofMinutes(1));
        long c = createProduct("Sort C " + token());

        assertThat(ids("")).containsExactly(c, b, a);

        clock.advance(Duration.ofMinutes(1));
        put(PRODUCTS + "/" + a, j("name", "Sort A edited", "status", "DRAFT", "version", 0))
                .andExpect(status().isOk());

        assertThat(ids("")).containsExactly(a, c, b);
    }

    @Test
    void list_sameUpdatedAt_fallsBackToNewestIdFirst() throws Exception {
        long a = createProduct("Tie A " + token());
        long b = createProduct("Tie B " + token()); // same fixed clock instant

        assertThat(ids("")).containsExactly(b, a);
    }

    private List<Long> ids(String query) throws Exception {
        List<Number> raw = read(fetch(PRODUCTS + query).andExpect(status().isOk()), "$.items[*].id");
        return raw.stream().map(Number::longValue).toList();
    }

    // ---- counts and cover image ----

    @Test
    void list_itemShape_countsAllVariantsAndImages_andCoversWithLowestSortOrder() throws Exception {
        long pid = createProduct("name", "Shape " + token(), "series", "Series X", "status", "ACTIVE");
        createVariant(pid, "1/7", 1000, 1);
        post(PRODUCTS + "/" + pid + "/variants", j("scale", "1/8", "priceCents", 900, "active", false))
                .andExpect(status().isCreated());
        String lastKey = randomImageKey();
        String firstKey = randomImageKey();
        insertImageRow(pid, lastKey, 5);
        insertImageRow(pid, firstKey, 2);

        fetch(PRODUCTS)
                .andExpect(jsonPath("$.items[0].id").value(pid))
                .andExpect(jsonPath("$.items[0].series").value("Series X"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].version").value(0))
                .andExpect(jsonPath("$.items[0].updatedAt").value(START.toString()))
                .andExpect(jsonPath("$.items[0].variantCount").value(2))
                .andExpect(jsonPath("$.items[0].imageCount").value(2))
                .andExpect(jsonPath("$.items[0].coverImage.url").value(imageBase + "/" + firstKey + "/1000.webp"))
                .andExpect(jsonPath("$.items[0].coverImage.alt").value("alt " + firstKey))
                .andExpect(jsonPath("$.items[0].coverImage.width").value(1200))
                .andExpect(jsonPath("$.items[0].coverImage.height").value(1600));
    }

    @Test
    void list_productWithoutImagesOrVariants_hasNullCoverAndZeroCounts() throws Exception {
        createProduct("Bare " + token());

        fetch(PRODUCTS)
                .andExpect(jsonPath("$.items[0].coverImage").doesNotExist())
                .andExpect(jsonPath("$.items[0].variantCount").value(0))
                .andExpect(jsonPath("$.items[0].imageCount").value(0));
    }

    @Test
    void list_doesNotLeakDescription() throws Exception {
        createProduct("name", "No desc " + token(), "description", "PRIVATE-DESCRIPTION-TEXT");

        assertThat(body(fetch(PRODUCTS))).doesNotContain("PRIVATE-DESCRIPTION-TEXT");
    }
}
