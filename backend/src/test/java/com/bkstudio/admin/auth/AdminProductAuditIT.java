package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** Every admin mutation writes exactly one audit_log row with business fields only. */
class AdminProductAuditIT extends AdminProductTestBase {

    private static final String SECRET_DESCRIPTION = "PRIVATE-DESCRIPTION-MARKER-123";

    private static String requestId(ResultActions r) {
        return r.andReturn().getResponse().getHeader("X-Request-Id");
    }

    private Set<String> keys(String json) {
        JsonNode n = om.readTree(json);
        return Set.copyOf(n.propertyNames());
    }

    private Audit onlyAudit(String entityType, long entityId) {
        List<Audit> rows = audits(entityType, entityId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    void createProduct_writesOneRowWithBusinessFieldsOnly() throws Exception {
        String name = "Audit create " + token();

        ResultActions r = post(PRODUCTS, j("name", name, "series", "S", "description", SECRET_DESCRIPTION))
                .andExpect(status().isCreated());
        long pid = readId(r, "$.id");

        Audit a = onlyAudit("PRODUCT", pid);
        assertThat(a.action()).isEqualTo("PRODUCT_CREATED");
        assertThat(a.actor()).isEqualTo("ADMIN");
        assertThat(a.actorId()).isEqualTo(admin.id());
        assertThat(a.entityId()).isEqualTo(pid);
        assertThat(a.requestId()).isNotBlank().isEqualTo(requestId(r));
        assertThat(a.occurredAt()).isEqualTo(START);
        assertThat(a.before()).isNull();
        assertThat(keys(a.after())).containsExactlyInAnyOrder("slug", "name", "series", "status");
        assertThat(om.readTree(a.after()).path("name").asString()).isEqualTo(name);
        assertThat(a.after()).doesNotContain(SECRET_DESCRIPTION);
        assertThat(auditRows()).isEqualTo(1);
    }

    @Test
    void updateProduct_writesOneRowWithBeforeAndAfter_noDescriptionText() throws Exception {
        long pid = createProduct("name", "Audit update " + token(), "series", "Old", "description", SECRET_DESCRIPTION);
        clock.advance(Duration.ofMinutes(3));

        ResultActions r = put(
                        PRODUCTS + "/" + pid,
                        j(
                                "name",
                                "New name",
                                "series",
                                "New",
                                "description",
                                "different text",
                                "status",
                                "ACTIVE",
                                "version",
                                0))
                .andExpect(status().isOk());

        List<Audit> rows = audits("PRODUCT", pid);
        assertThat(rows).extracting(Audit::action).containsExactly("PRODUCT_CREATED", "PRODUCT_UPDATED");
        Audit a = rows.get(1);
        assertThat(a.requestId()).isEqualTo(requestId(r));
        assertThat(a.occurredAt()).isEqualTo(START.plus(Duration.ofMinutes(3)));
        assertThat(keys(a.before())).containsExactlyInAnyOrder("slug", "name", "series", "status", "descriptionLength");
        assertThat(keys(a.after())).containsExactlyInAnyOrder("slug", "name", "series", "status", "descriptionLength");
        assertThat(om.readTree(a.before()).path("status").asString()).isEqualTo("DRAFT");
        assertThat(om.readTree(a.before()).path("series").asString()).isEqualTo("Old");
        assertThat(om.readTree(a.before()).path("descriptionLength").asInt()).isEqualTo(SECRET_DESCRIPTION.length());
        assertThat(om.readTree(a.after()).path("status").asString()).isEqualTo("ACTIVE");
        assertThat(om.readTree(a.after()).path("name").asString()).isEqualTo("New name");
        assertThat(om.readTree(a.after()).path("descriptionLength").asInt()).isEqualTo("different text".length());
        assertThat(a.before() + a.after()).doesNotContain(SECRET_DESCRIPTION, "different text");
    }

    @Test
    void createAndUpdateVariant_writeOneRowEach() throws Exception {
        long pid = createProduct("Audit variant " + token());
        int base = auditRows();

        ResultActions created = post(
                        PRODUCTS + "/" + pid + "/variants",
                        j("scale", "1/7", "priceCents", 1000, "stockQty", 5, "madeToOrderQuota", 2))
                .andExpect(status().isCreated());
        long vid = readId(created, "$.id");
        Audit c = onlyAudit("VARIANT", vid);
        assertThat(c.action()).isEqualTo("VARIANT_CREATED");
        assertThat(c.before()).isNull();
        assertThat(c.requestId()).isEqualTo(requestId(created));
        assertThat(keys(c.after()))
                .containsExactlyInAnyOrder(
                        "productId",
                        "scale",
                        "priceCents",
                        "stockQty",
                        "madeToOrderEnabled",
                        "madeToOrderQuota",
                        "active");
        assertThat(om.readTree(c.after()).path("productId").asLong()).isEqualTo(pid);
        assertThat(auditRows()).isEqualTo(base + 1);

        ResultActions updated = put(
                        "/api/admin/variants/" + vid,
                        j(
                                "priceCents",
                                2000,
                                "stockQty",
                                4,
                                "madeToOrderEnabled",
                                true,
                                "madeToOrderQuota",
                                9,
                                "active",
                                false,
                                "version",
                                0))
                .andExpect(status().isOk());
        List<Audit> rows = audits("VARIANT", vid);
        assertThat(rows).extracting(Audit::action).containsExactly("VARIANT_CREATED", "VARIANT_UPDATED");
        Audit u = rows.get(1);
        assertThat(u.requestId()).isEqualTo(requestId(updated));
        assertThat(om.readTree(u.before()).path("priceCents").asInt()).isEqualTo(1000);
        assertThat(om.readTree(u.after()).path("priceCents").asInt()).isEqualTo(2000);
        assertThat(om.readTree(u.after()).path("active").asBoolean()).isFalse();
        assertThat(auditRows()).isEqualTo(base + 2);
    }

    @Test
    void reorderAndAltUpdate_writeOneRowEach() throws Exception {
        long pid = createProduct("Audit images " + token());
        long a = insertImageRow(pid, randomImageKey(), 0);
        long b = insertImageRow(pid, randomImageKey(), 1);
        int base = auditRows();

        ResultActions reorder = put(PRODUCTS + "/" + pid + "/images/order", j("imageIds", List.of(b, a)))
                .andExpect(status().isOk());
        Audit o = audits("PRODUCT", pid).stream()
                .filter(x -> x.action().equals("IMAGE_REORDERED"))
                .findFirst()
                .orElseThrow();
        assertThat(o.requestId()).isEqualTo(requestId(reorder));
        assertThat(keys(o.before())).containsExactly("imageIds");
        assertThat(om.readTree(o.before()).path("imageIds").toString()).isEqualTo("[" + a + "," + b + "]");
        assertThat(om.readTree(o.after()).path("imageIds").toString()).isEqualTo("[" + b + "," + a + "]");
        assertThat(auditRows()).isEqualTo(base + 1);

        ResultActions alt = call(HttpMethod.PATCH, "/api/admin/images/" + a, j("altText", "new alt"))
                .andExpect(status().isOk());
        Audit t = onlyAudit("IMAGE", a);
        assertThat(t.action()).isEqualTo("IMAGE_ALT_UPDATED");
        assertThat(t.requestId()).isEqualTo(requestId(alt));
        assertThat(om.readTree(t.before()).path("altText").asString()).startsWith("alt ");
        assertThat(om.readTree(t.after()).path("altText").asString()).isEqualTo("new alt");
        assertThat(auditRows()).isEqualTo(base + 2);
    }

    @Test
    void failedRequests_writeNoAuditRow() throws Exception {
        long pid = createProduct("Audit failures " + token());
        long vid = createVariant(pid, "1/7", 1000, 1);
        int base = auditRows();

        expectProblem(post(PRODUCTS, "{}"), 400, "VALIDATION_FAILED");
        expectProblem(
                put(PRODUCTS + "/" + pid, j("name", "x", "status", "DRAFT", "version", 5)), 409, "VERSION_CONFLICT");
        expectProblem(
                put(PRODUCTS + "/" + UNKNOWN_ID, j("name", "x", "status", "DRAFT", "version", 0)), 404, "NOT_FOUND");
        expectProblem(post(PRODUCTS + "/" + pid + "/variants", j("scale", "1/7", "priceCents", 5)), 409, "CONFLICT");
        expectProblem(
                put(
                        "/api/admin/variants/" + vid,
                        j("priceCents", 1, "stockQty", 1, "madeToOrderEnabled", false, "active", true, "version", 9)),
                409,
                "VERSION_CONFLICT");
        expectProblem(call(HttpMethod.PATCH, "/api/admin/images/" + UNKNOWN_ID, j("altText", "x")), 404, "NOT_FOUND");
        expectProblem(call(HttpMethod.DELETE, "/api/admin/images/" + UNKNOWN_ID, null), 404, "NOT_FOUND");

        assertThat(auditRows()).isEqualTo(base);
    }

    @Test
    void readOnlyRequests_writeNoAuditRow() throws Exception {
        long pid = createProduct("Audit reads " + token());
        int base = auditRows();

        fetch(PRODUCTS).andExpect(status().isOk());
        fetch(PRODUCTS + "/" + pid).andExpect(status().isOk());

        assertThat(auditRows()).isEqualTo(base);
    }

    @Test
    void auditRows_neverContainEmailPasswordHashOrDescriptionText() throws Exception {
        long pid = createProduct("name", "Audit pii " + token(), "description", SECRET_DESCRIPTION);
        createVariant(pid, "1/7", 1000, 1);
        put(
                        PRODUCTS + "/" + pid,
                        j("name", "Audit pii 2", "description", SECRET_DESCRIPTION, "status", "ACTIVE", "version", 0))
                .andExpect(status().isOk());

        List<String> texts = new ArrayList<>(jdbc.queryForList(
                "SELECT coalesce(before::text, '') || coalesce(after::text, '') || action || entity_type"
                        + " || coalesce(request_id, '') FROM audit_log WHERE actor_id = ?",
                String.class,
                admin.id()));

        assertThat(texts).hasSize(3);
        for (String t : texts) {
            assertThat(t.toLowerCase())
                    .doesNotContain(admin.email().toLowerCase(), "email", "password", "hash", "argon", "totp")
                    .doesNotContain(SECRET_DESCRIPTION.toLowerCase());
        }
    }
}
