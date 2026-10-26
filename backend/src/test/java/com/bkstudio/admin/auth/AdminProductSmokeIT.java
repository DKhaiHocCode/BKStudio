package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.catalog.image.ImageService;
import com.bkstudio.catalog.image.StoredImage;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Minimal end-to-end smoke test of the admin product API (T-013 step 1): create, variant, image, update, audit.
 * Lives in the admin.auth package only to reuse the package-private login helpers; ImageService is mocked so no
 * object store is needed. The exhaustive matrix is step 2.
 */
class AdminProductSmokeIT extends AdminAuthTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ImageService imageService;

    @Test
    void createProductVariantImageUpdateAndAudit() throws Exception {
        MockHttpSession session = fullLogin(newAdmin());
        String ip = nextIp();

        MvcResult created = mvc.perform(post("/api/admin/products", "{\"name\":\"Raiden Shōgun Figure\"}", session, ip))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("raiden-shogun-figure"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        long id =
                Long.parseLong(created.getResponse().getHeader("Location").substring("/api/admin/products/".length()));

        mvc.perform(post(
                        "/api/admin/products/" + id + "/variants",
                        "{\"scale\":\"1/7\",\"priceCents\":15000,\"stockQty\":2}",
                        session,
                        ip))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scale").value("1/7"));

        when(imageService.store(any())).thenReturn(new StoredImage("products/" + UUID.randomUUID(), 2000, 1500));
        String csrf = csrfToken();
        mvc.perform(multipart("/api/admin/products/" + id + "/images")
                        .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[] {1, 2, 3}))
                        .param("altText", "front")
                        .session(session)
                        .cookie(new Cookie("XSRF-TOKEN", csrf))
                        .header("X-XSRF-TOKEN", csrf))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortOrder").value(0))
                .andExpect(jsonPath("$.altText").value("front"))
                .andExpect(jsonPath("$.urls.1000").isNotEmpty());

        mvc.perform(withCsrf(
                        HttpMethod.PUT,
                        "/api/admin/products/" + id,
                        "{\"name\":\"Raiden\",\"status\":\"ACTIVE\",\"version\":0}",
                        session,
                        ip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.images.length()").value(1));

        mvc.perform(getFrom("/api/admin/products?q=raid", session, ip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].imageCount").value(1))
                .andExpect(jsonPath("$.items[0].coverImage.url").isNotEmpty());

        // stale version -> 409
        expectProblem(
                mvc.perform(withCsrf(
                        HttpMethod.PUT,
                        "/api/admin/products/" + id,
                        "{\"name\":\"Raiden\",\"status\":\"ACTIVE\",\"version\":0}",
                        session,
                        ip)),
                409,
                "VERSION_CONFLICT");

        Long audits = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor = 'ADMIN' AND actor_id IS NOT NULL AND request_id IS NOT NULL"
                        + " AND ((entity_type = 'PRODUCT' AND entity_id = ?) OR entity_type IN ('VARIANT', 'IMAGE'))",
                Long.class,
                id);
        assertThat(audits).isGreaterThanOrEqualTo(4); // PRODUCT_CREATED, VARIANT_CREATED, IMAGE_ADDED, PRODUCT_UPDATED

        // image delete
        long imageId = jdbc.queryForObject("SELECT id FROM product_image WHERE product_id = ?", Long.class, id);
        String csrf2 = csrfToken();
        mvc.perform(delete("/api/admin/images/" + imageId)
                        .session(session)
                        .cookie(new Cookie("XSRF-TOKEN", csrf2))
                        .header("X-XSRF-TOKEN", csrf2))
                .andExpect(status().isNoContent());
    }
}
