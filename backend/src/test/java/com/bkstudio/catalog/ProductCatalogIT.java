package com.bkstudio.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.support.AbstractPostgresTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class ProductCatalogIT extends AbstractPostgresTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper om;

    @Value("${app.catalog.image-base-url}")
    String imageBase;

    @BeforeEach
    void clean() {
        // ITs run serially on a shared container.
        jdbc.execute("TRUNCATE product RESTART IDENTITY CASCADE");
    }

    // ---------- helpers ----------

    private long product(String slug, String status, String series, int ageDays) {
        return jdbc.queryForObject(
                "INSERT INTO product (slug, name, series, description, status, created_at) "
                        + "VALUES (?, ?, ?, 'desc', ?, ?) RETURNING id",
                Long.class,
                slug,
                "Name " + slug,
                series,
                status,
                Timestamp.from(BASE.minus(ageDays, ChronoUnit.DAYS)));
    }

    private long variant(
            long productId, String scale, int price, int stock, boolean mto, Integer quota, boolean active) {
        return jdbc.queryForObject(
                "INSERT INTO product_variant (product_id, scale, price_cents, stock_qty, made_to_order_enabled, "
                        + "made_to_order_quota, active) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                productId,
                scale,
                price,
                stock,
                mto,
                quota,
                active);
    }

    private long activeProduct(String slug, int ageDays) {
        long id = product(slug, "ACTIVE", null, ageDays);
        variant(id, "1/7", 10000, 1, false, null, true);
        return id;
    }

    private void image(long productId, String key, int sortOrder) {
        jdbc.update(
                "INSERT INTO product_image (product_id, storage_key, alt_text, width, height, sort_order) "
                        + "VALUES (?, ?, ?, 1200, 1600, ?)",
                productId,
                key,
                "alt " + key,
                sortOrder);
    }

    private static java.util.Set<String> keys(JsonNode n) {
        java.util.Set<String> out = new java.util.HashSet<>();
        out.addAll(n.propertyNames());
        return out;
    }

    // ---------- list ----------

    @Test
    void list_pageAboveMaxIsRejected_noOverflow() throws Exception {
        mvc.perform(get("/api/products").param("page", "2147483647"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void list_overlongSeriesIsRejected() throws Exception {
        mvc.perform(get("/api/products").param("series", "x".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void list_seriesFilterExcludesUnlistedProductWithSameSeries() throws Exception {
        long a = product("ser-active", "ACTIVE", "Shared", 1);
        variant(a, "1/7", 1000, 1, false, null, true);
        long u = product("ser-unlisted", "UNLISTED", "Shared", 1);
        variant(u, "1/7", 1000, 1, false, null, true);

        mvc.perform(get("/api/products").param("series", "Shared"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("ser-active"));
    }

    @Test
    void list_variantBothInStockAndMadeToOrderShowsBothFlags() throws Exception {
        long id = product("both", "ACTIVE", null, 1);
        variant(id, "1/7", 1000, 2, true, null, true);

        mvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.items[0].inStock").value(true))
                .andExpect(jsonPath("$.items[0].madeToOrder").value(true));
    }

    @Test
    void list_nonNumericPageIsClientErrorWithRequestId() throws Exception {
        mvc.perform(get("/api/products").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void list_returnsOnlyActiveProducts() throws Exception {
        activeProduct("active-one", 1);
        for (String s : new String[] {"DRAFT", "ARCHIVED", "UNLISTED"}) {
            long id = product(s.toLowerCase() + "-one", s, null, 1);
            variant(id, "1/7", 5000, 1, false, null, true);
        }

        mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("active-one"));
    }

    @Test
    void list_hidesActiveProductWithoutActiveVariant_andKeepsTotalConsistent() throws Exception {
        activeProduct("p-a", 1);
        activeProduct("p-b", 2);
        activeProduct("p-c", 3);
        long hidden = product("p-hidden", "ACTIVE", null, 0);
        variant(hidden, "1/7", 5000, 1, false, null, false);
        product("p-novariant", "ACTIVE", null, 0);

        mvc.perform(get("/api/products").param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/products").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("p-c"));
    }

    @Test
    void list_itemShape_usesCheapestActiveVariantAndFirstImage() throws Exception {
        long id = product("shape", "ACTIVE", "Xenoblade", 1);
        variant(id, "1/8", 5000, 3, false, null, false); // inactive and cheaper: ignored
        variant(id, "1/7", 20000, 0, true, 2, true);
        variant(id, "1/6", 15000, 0, false, null, true);
        image(id, "k-second", 2);
        image(id, "k-first", 1);

        mvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.items[0].slug").value("shape"))
                .andExpect(jsonPath("$.items[0].name").value("Name shape"))
                .andExpect(jsonPath("$.items[0].series").value("Xenoblade"))
                .andExpect(jsonPath("$.items[0].fromPriceCents").value(15000))
                .andExpect(jsonPath("$.items[0].inStock").value(false))
                .andExpect(jsonPath("$.items[0].madeToOrder").value(true))
                .andExpect(jsonPath("$.items[0].coverImage.url").value(imageBase + "/k-first/1000.webp"))
                .andExpect(jsonPath("$.items[0].coverImage.alt").value("alt k-first"))
                .andExpect(jsonPath("$.items[0].coverImage.width").value(1200))
                .andExpect(jsonPath("$.items[0].coverImage.height").value(1600));
    }

    @Test
    void list_coverImageIsNullWhenProductHasNoImages() throws Exception {
        activeProduct("no-img", 1);

        mvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.items[0].coverImage").doesNotExist())
                .andExpect(jsonPath("$.items[0].inStock").value(true))
                .andExpect(jsonPath("$.items[0].madeToOrder").value(false));
    }

    @Test
    void list_seriesFilterIsExact_andBlankSeriesMeansNoFilter() throws Exception {
        long a = product("s-a", "ACTIVE", "Alpha", 1);
        variant(a, "1/7", 1000, 1, false, null, true);
        long b = product("s-b", "ACTIVE", "Alpha Beta", 2);
        variant(b, "1/7", 1000, 1, false, null, true);

        mvc.perform(get("/api/products").param("series", "Alpha"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].slug").value("s-a"));
        mvc.perform(get("/api/products").param("series", "  "))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void list_ordersNewestFirst() throws Exception {
        activeProduct("old", 10);
        activeProduct("newest", 1);
        activeProduct("middle", 5);

        mvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.items[0].slug").value("newest"))
                .andExpect(jsonPath("$.items[1].slug").value("middle"))
                .andExpect(jsonPath("$.items[2].slug").value("old"));
    }

    @Test
    void list_defaultsToPage0Size20() throws Exception {
        mvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void list_sizeAboveMaxIsClampedTo50AndEchoed() throws Exception {
        mvc.perform(get("/api/products").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void list_invalidPagingIsRejectedWithValidationFailed() throws Exception {
        mvc.perform(get("/api/products").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        mvc.perform(get("/api/products").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    // ---------- detail ----------

    @Test
    void detail_activeProductIsListedAndUnlistedProductIsReachableByLink() throws Exception {
        activeProduct("act", 1);
        long u = product("secret-commission", "UNLISTED", null, 1);
        variant(u, "1/6", 30000, 0, true, 1, true);

        mvc.perform(get("/api/products/act"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("act"))
                .andExpect(jsonPath("$.description").value("desc"))
                .andExpect(jsonPath("$.unlisted").value(false));
        mvc.perform(get("/api/products/secret-commission"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlisted").value(true));
    }

    @Test
    void detail_draftArchivedUnknownAndMalformedSlugsGiveIdenticalNotFound() throws Exception {
        long d = product("draft-one", "DRAFT", null, 1);
        variant(d, "1/7", 1000, 1, false, null, true);
        long a = product("archived-one", "ARCHIVED", null, 1);
        variant(a, "1/7", 1000, 1, false, null, true);

        String[] slugs = {"draft-one", "archived-one", "does-not-exist", "Bad_Slug", "a--b"};
        String first = null;
        for (String slug : slugs) {
            String body = mvc.perform(get("/api/products/" + slug))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            // requestId differs per request; compare everything else.
            String normalized =
                    body.replaceAll("\"requestId\":\"[^\"]*\"", "").replaceAll("\"instance\":\"[^\"]*\"", "");
            if (first == null) {
                first = normalized;
            }
            assertThat(normalized).as("body for %s", slug).isEqualTo(first);
        }
    }

    @Test
    void detail_overlongSlugIsNotFound() throws Exception {
        mvc.perform(get("/api/products/" + "a".repeat(201)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void detail_imagesAreOrderedBySortOrderWithRenditionUrls() throws Exception {
        long id = activeProduct("imgs", 1);
        image(id, "k-b", 2);
        image(id, "k-a", 1);

        mvc.perform(get("/api/products/imgs"))
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.images[0].url").value(imageBase + "/k-a/1000.webp"))
                .andExpect(jsonPath("$.images[0].srcset['400']").value(imageBase + "/k-a/400.webp"))
                .andExpect(jsonPath("$.images[0].srcset['1000']").value(imageBase + "/k-a/1000.webp"))
                .andExpect(jsonPath("$.images[0].srcset['2000']").value(imageBase + "/k-a/2000.webp"))
                .andExpect(jsonPath("$.images[0].alt").value("alt k-a"))
                .andExpect(jsonPath("$.images[0].width").value(1200))
                .andExpect(jsonPath("$.images[0].height").value(1600))
                .andExpect(jsonPath("$.images[1].url").value(imageBase + "/k-b/1000.webp"));
    }

    @Test
    void detail_returnsOnlyActiveVariantsOrderedByIdWithDbPrice() throws Exception {
        long id = product("vars", "ACTIVE", null, 1);
        long v1 = variant(id, "1/7", 12345, 1, false, null, true);
        variant(id, "1/8", 999, 1, false, null, false);
        long v3 = variant(id, "1/6", 23456, 0, false, null, true);

        mvc.perform(get("/api/products/vars"))
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[0].id").value(v1))
                .andExpect(jsonPath("$.variants[0].scale").value("1/7"))
                .andExpect(jsonPath("$.variants[0].priceCents").value(12345))
                .andExpect(jsonPath("$.variants[1].id").value(v3))
                .andExpect(jsonPath("$.variants[1].priceCents").value(23456));
    }

    @Test
    void detail_madeToOrderAvailabilityFollowsEnabledFlagAndQuota() throws Exception {
        long id = product("mto", "ACTIVE", null, 1);
        variant(id, "1/7", 1000, 0, true, null, true); // unlimited
        variant(id, "1/6", 1000, 0, true, 3, true); // quota left
        variant(id, "1/5", 1000, 0, true, 0, true); // quota exhausted
        variant(id, "1/4", 1000, 0, false, 5, true); // disabled

        mvc.perform(get("/api/products/mto"))
                .andExpect(jsonPath("$.variants[0].madeToOrder.available").value(true))
                .andExpect(jsonPath("$.variants[1].madeToOrder.available").value(true))
                .andExpect(jsonPath("$.variants[2].madeToOrder.available").value(false))
                .andExpect(jsonPath("$.variants[3].madeToOrder.available").value(false))
                .andExpect(jsonPath("$.variants[0].madeToOrder.estimatedStartMonth")
                        .doesNotExist());
    }

    @Test
    void detail_inStockIsTrueOnlyWhenStockPositive() throws Exception {
        long id = product("stk", "ACTIVE", null, 1);
        variant(id, "1/7", 1000, 4, false, null, true);
        variant(id, "1/6", 1000, 0, false, null, true);

        mvc.perform(get("/api/products/stk"))
                .andExpect(jsonPath("$.variants[0].inStock.available").value(true))
                .andExpect(jsonPath("$.variants[0].inStock.shipsWithinDays").value(7))
                .andExpect(jsonPath("$.variants[1].inStock.available").value(false))
                .andExpect(jsonPath("$.variants[1].inStock.shipsWithinDays").doesNotExist());
    }

    @Test
    void detail_neverLeaksStockOrQuotaNumbers() throws Exception {
        long id = product("leak", "ACTIVE", null, 1);
        variant(id, "1/7", 1000, 37, true, 5, true);
        activeProduct("leak-list", 2);

        String detail = mvc.perform(get("/api/products/leak"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String list =
                mvc.perform(get("/api/products")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        for (String body : new String[] {detail, list}) {
            assertThat(body.toLowerCase()).doesNotContain("stock_qty", "stockqty", "quota", "qty", "\"stock\"");
        }
        // Exact key sets: nothing beyond the documented fields can leak.
        mvc.perform(get("/api/products/leak"))
                .andExpect(jsonPath("$.variants[0].inStock.length()").value(2))
                .andExpect(jsonPath("$.variants[0].madeToOrder.length()").value(2));
        assertThat(keys(om.readTree(detail).at("/variants/0")))
                .containsExactlyInAnyOrder("id", "scale", "priceCents", "inStock", "madeToOrder");
        assertThat(keys(om.readTree(list).at("/items/0")))
                .containsExactlyInAnyOrder(
                        "slug", "name", "series", "coverImage", "fromPriceCents", "inStock", "madeToOrder");
    }

    // ---------- method not allowed ----------

    @Test
    void writeMethodsOnProductsAreRejectedWithErrorFormat() throws Exception {
        mvc.perform(post("/api/products"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        mvc.perform(delete("/api/products/anything"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
