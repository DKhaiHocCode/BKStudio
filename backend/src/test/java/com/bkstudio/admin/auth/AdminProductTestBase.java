package com.bkstudio.admin.auth;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared setup for the admin product API tests (T-013): a logged-in admin per test, an empty product table
 * before and after every test, and small helpers for requests, JSON, audit rows and sample images.
 * Lives in the admin.auth package only to reuse the package-private login helpers of {@link AdminAuthTestBase}.
 */
abstract class AdminProductTestBase extends AdminAuthTestBase {

    static final String PRODUCTS = "/api/admin/products";
    static final long UNKNOWN_ID = 999_999_999L;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper om;

    @Value("${app.catalog.image-base-url}")
    String imageBase;

    TestAdmin admin;
    MockHttpSession session;

    record Audit(
            String action,
            String actor,
            Long actorId,
            String entityType,
            long entityId,
            String before,
            String after,
            String requestId,
            Instant occurredAt) {}

    @BeforeEach
    void loginAndCleanCatalog() throws Exception {
        truncateCatalog();
        admin = newAdmin();
        session = fullLogin(admin);
    }

    @AfterEach
    void cleanCatalog() {
        truncateCatalog();
    }

    private void truncateCatalog() {
        // No RESTART IDENTITY: audit_log is not truncated, so reused ids would mix up audit rows of other tests.
        // Wipes product and everything referencing it: these tests must never run in parallel with each other.
        jdbc.execute("TRUNCATE product CASCADE");
    }

    // ---- requests ----

    ResultActions call(HttpMethod method, String path, String body) throws Exception {
        return mvc.perform(withCsrf(method, path, body, session, nextIp()));
    }

    ResultActions post(String path, String body) throws Exception {
        return call(HttpMethod.POST, path, body);
    }

    ResultActions put(String path, String body) throws Exception {
        return call(HttpMethod.PUT, path, body);
    }

    ResultActions fetch(String path) throws Exception {
        return mvc.perform(getFrom(path, session, nextIp()));
    }

    /** Public (anonymous) GET. */
    ResultActions publicGet(String path) throws Exception {
        return mvc.perform(getFrom(path, null, nextIp()));
    }

    AbstractMockHttpServletRequestBuilder<?> multipart(
            String path, MockHttpSession s, String csrf, String altText, MockMultipartFile... files) {
        var b = MockMvcRequestBuilders.multipart(path).with(r -> {
            r.setRemoteAddr(nextIp());
            return r;
        });
        for (MockMultipartFile f : files) {
            b.file(f);
        }
        if (altText != null) {
            b.param("altText", altText);
        }
        if (csrf != null) {
            b.cookie(new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf);
        }
        return s == null ? b : b.session(s);
    }

    ResultActions upload(long productId, String filename, String contentType, byte[] bytes, String altText)
            throws Exception {
        return mvc.perform(multipart(
                PRODUCTS + "/" + productId + "/images",
                session,
                csrfToken(),
                altText,
                new MockMultipartFile("file", filename, contentType, bytes)));
    }

    // ---- json ----

    /** Builds a JSON object from alternating key / value arguments (null values are kept). */
    String j(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return om.writeValueAsString(m);
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    static <T> T read(ResultActions r, String jsonPath) throws Exception {
        return JsonPath.read(body(r), jsonPath);
    }

    static long readId(ResultActions r, String jsonPath) throws Exception {
        return ((Number) JsonPath.<Object>read(body(r), jsonPath)).longValue();
    }

    static Instant readInstant(ResultActions r, String jsonPath) throws Exception {
        return Instant.parse(JsonPath.<String>read(body(r), jsonPath));
    }

    // ---- fixtures through the API ----

    /** A short lower-case token that makes names, series and slugs unique within the shared database. */
    static String token() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    long createProduct(String name) throws Exception {
        return readId(post(PRODUCTS, j("name", name)).andExpect(status().isCreated()), "$.id");
    }

    long createProduct(Object... kv) throws Exception {
        return readId(post(PRODUCTS, j(kv)).andExpect(status().isCreated()), "$.id");
    }

    long createVariant(long productId, String scale, int priceCents, int stockQty) throws Exception {
        return readId(
                post(
                                PRODUCTS + "/" + productId + "/variants",
                                j("scale", scale, "priceCents", priceCents, "stockQty", stockQty))
                        .andExpect(status().isCreated()),
                "$.id");
    }

    /** Changes only the status; name, series and description are sent back unchanged. */
    void setStatus(long productId, String newStatus) throws Exception {
        var row = jdbc.queryForMap("SELECT name, series, description, version FROM product WHERE id = ?", productId);
        put(
                        PRODUCTS + "/" + productId,
                        j(
                                "name",
                                row.get("name"),
                                "series",
                                row.get("series"),
                                "description",
                                row.get("description"),
                                "status",
                                newStatus,
                                "version",
                                row.get("version")))
                .andExpect(status().isOk());
    }

    /** Inserts an image row directly (no storage object); returns its id. */
    long insertImageRow(long productId, String key, int sortOrder) {
        return jdbc.queryForObject(
                "INSERT INTO product_image (product_id, storage_key, alt_text, width, height, sort_order)"
                        + " VALUES (?, ?, ?, 1200, 1600, ?) RETURNING id",
                Long.class,
                productId,
                key,
                "alt " + key,
                sortOrder);
    }

    static String randomImageKey() {
        return "products/" + UUID.randomUUID();
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ---- audit ----

    List<Audit> audits(String entityType, long entityId) {
        return jdbc.query(
                "SELECT action, actor, actor_id, entity_type, entity_id, before::text AS before, after::text AS after,"
                        + " request_id, occurred_at FROM audit_log WHERE actor_id = ? AND entity_type = ?"
                        + " AND entity_id = ? ORDER BY id",
                (rs, n) -> new Audit(
                        rs.getString("action"),
                        rs.getString("actor"),
                        (Long) rs.getObject("actor_id"),
                        rs.getString("entity_type"),
                        rs.getLong("entity_id"),
                        rs.getString("before"),
                        rs.getString("after"),
                        rs.getString("request_id"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()),
                admin.id(),
                entityType,
                entityId);
    }

    /** Number of audit rows written by this test's admin, whatever the entity. */
    int auditRows() {
        return count("SELECT count(*) FROM audit_log WHERE actor_id = ?", admin.id());
    }

    // ---- sample images (JPEG / PNG / WebP encoded in memory) ----

    static BufferedImage sample(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        return img;
    }

    static byte[] jpeg(int w, int h) {
        return encode(sample(w, h), "jpg");
    }

    static byte[] png(int w, int h) {
        return encode(sample(w, h), "png");
    }

    static byte[] webp(int w, int h) {
        try {
            ImageWriter writer = ImageIO.getImageWritersByMIMEType("image/webp").next();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(sample(w, h), null, null), null);
            }
            writer.dispose();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!ImageIO.write(img, format, bos)) {
                throw new IllegalStateException("no writer for " + format);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
