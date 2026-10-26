package com.bkstudio.catalog.admin.infra;

import com.bkstudio.catalog.admin.domain.AdminRows.ImageRow;
import com.bkstudio.catalog.admin.domain.AdminRows.ListRow;
import com.bkstudio.catalog.admin.domain.AdminRows.ProductRow;
import com.bkstudio.catalog.admin.domain.AdminRows.VariantRow;
import com.bkstudio.catalog.domain.ProductStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain-SQL writes and reads for the admin catalog. No transaction of its own: the services own them. */
@Repository
public class AdminProductRepository {

    private static final String PRODUCT_COLS =
            "id, slug, name, series, description, status, version, created_at, updated_at";
    private static final String VARIANT_COLS = "id, product_id, scale, price_cents, stock_qty, made_to_order_enabled,"
            + " made_to_order_quota, active, version";
    private static final String IMAGE_COLS = "id, product_id, storage_key, alt_text, width, height, sort_order";

    private final JdbcTemplate jdbc;

    AdminProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- products ----

    /** {@code likePattern} is already escaped and wrapped in % by the caller; null = no text filter. */
    public long countProducts(ProductStatus status, String likePattern) {
        List<Object> args = new ArrayList<>();
        String where = where(status, likePattern, args);
        return jdbc.queryForObject("SELECT count(*) FROM product p" + where, Long.class, args.toArray());
    }

    public List<ListRow> listProducts(ProductStatus status, String likePattern, int limit, long offset) {
        List<Object> args = new ArrayList<>();
        String where = where(status, likePattern, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(
                "SELECT p.id, p.slug, p.name, p.series, p.status, p.version, p.updated_at,"
                        + " (SELECT count(*) FROM product_variant v WHERE v.product_id = p.id) AS variant_count,"
                        + " (SELECT count(*) FROM product_image i WHERE i.product_id = p.id) AS image_count,"
                        + " c.storage_key, c.alt_text, c.width, c.height"
                        + " FROM product p LEFT JOIN LATERAL (SELECT storage_key, alt_text, width, height"
                        + " FROM product_image i WHERE i.product_id = p.id ORDER BY i.sort_order, i.id LIMIT 1) c"
                        + " ON true" + where + " ORDER BY p.updated_at DESC, p.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new ListRow(
                        rs.getLong("id"),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("series"),
                        ProductStatus.valueOf(rs.getString("status")),
                        rs.getInt("version"),
                        instant(rs, "updated_at"),
                        rs.getInt("variant_count"),
                        rs.getInt("image_count"),
                        rs.getString("storage_key"),
                        rs.getString("alt_text"),
                        (Integer) rs.getObject("width"),
                        (Integer) rs.getObject("height")),
                args.toArray());
    }

    private static String where(ProductStatus status, String likePattern, List<Object> args) {
        List<String> parts = new ArrayList<>();
        if (status != null) {
            parts.add("p.status = ?");
            args.add(status.name());
        }
        if (likePattern != null) {
            parts.add("(lower(p.name) LIKE ? ESCAPE '\\' OR p.slug LIKE ? ESCAPE '\\')");
            args.add(likePattern);
            args.add(likePattern);
        }
        return parts.isEmpty() ? "" : " WHERE " + String.join(" AND ", parts);
    }

    public Optional<ProductRow> findProduct(long id) {
        return jdbc
                .query("SELECT " + PRODUCT_COLS + " FROM product WHERE id = ?", AdminProductRepository::product, id)
                .stream()
                .findFirst();
    }

    /** Row lock: serialises concurrent image uploads / reorders of one product. False = unknown id. */
    public boolean lockProduct(long id) {
        return !jdbc.queryForList("SELECT id FROM product WHERE id = ? FOR UPDATE", Long.class, id)
                .isEmpty();
    }

    /** Inserts unless the slug is taken (no exception, so the surrounding transaction survives); empty = taken. */
    public Optional<Long> insertProduct(
            String slug, String name, String series, String description, ProductStatus status, Instant now) {
        OffsetDateTime t = utc(now);
        return jdbc
                .queryForList(
                        "INSERT INTO product (slug, name, series, description, status, created_at, updated_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (slug) DO NOTHING RETURNING id",
                        Long.class,
                        slug,
                        name,
                        series,
                        description,
                        status.name(),
                        t,
                        t)
                .stream()
                .findFirst();
    }

    /**
     * Optimistic update; 0 = unknown id, stale version, or (when {@code newSlug} is set) slug already taken: the
     * caller tells them apart. A null {@code newSlug} keeps the slug.
     */
    public int updateProduct(
            long id,
            String newSlug,
            String name,
            String series,
            String description,
            ProductStatus status,
            int version,
            Instant now) {
        return jdbc.update(
                "UPDATE product SET slug = COALESCE(?, slug), name = ?, series = ?, description = ?, status = ?,"
                        + " version = version + 1, updated_at = ? WHERE id = ? AND version = ?"
                        + " AND (?::text IS NULL OR NOT EXISTS (SELECT 1 FROM product WHERE slug = ?))",
                newSlug,
                name,
                series,
                description,
                status.name(),
                utc(now),
                id,
                version,
                newSlug,
                newSlug);
    }

    // ---- variants ----

    public List<VariantRow> listVariants(long productId) {
        return jdbc.query(
                "SELECT " + VARIANT_COLS + " FROM product_variant WHERE product_id = ? ORDER BY id",
                AdminProductRepository::variant,
                productId);
    }

    public Optional<VariantRow> findVariant(long id) {
        return jdbc
                .query(
                        "SELECT " + VARIANT_COLS + " FROM product_variant WHERE id = ?",
                        AdminProductRepository::variant,
                        id)
                .stream()
                .findFirst();
    }

    /** Empty = (product, scale) already exists. */
    public Optional<Long> insertVariant(
            long productId,
            String scale,
            int priceCents,
            int stockQty,
            boolean madeToOrderEnabled,
            Integer quota,
            boolean active,
            Instant now) {
        OffsetDateTime t = utc(now);
        return jdbc
                .queryForList(
                        "INSERT INTO product_variant (product_id, scale, price_cents, stock_qty,"
                                + " made_to_order_enabled, made_to_order_quota, active, created_at, updated_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (product_id, scale) DO NOTHING"
                                + " RETURNING id",
                        Long.class,
                        productId,
                        scale,
                        priceCents,
                        stockQty,
                        madeToOrderEnabled,
                        quota,
                        active,
                        t,
                        t)
                .stream()
                .findFirst();
    }

    public int updateVariant(
            long id,
            int priceCents,
            int stockQty,
            boolean madeToOrderEnabled,
            Integer quota,
            boolean active,
            int version,
            Instant now) {
        return jdbc.update(
                "UPDATE product_variant SET price_cents = ?, stock_qty = ?, made_to_order_enabled = ?,"
                        + " made_to_order_quota = ?, active = ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ?",
                priceCents,
                stockQty,
                madeToOrderEnabled,
                quota,
                active,
                utc(now),
                id,
                version);
    }

    // ---- images ----

    public List<ImageRow> listImages(long productId) {
        return jdbc.query(
                "SELECT " + IMAGE_COLS + " FROM product_image WHERE product_id = ? ORDER BY sort_order, id",
                AdminProductRepository::image,
                productId);
    }

    public Optional<ImageRow> findImage(long id) {
        return jdbc
                .query("SELECT " + IMAGE_COLS + " FROM product_image WHERE id = ?", AdminProductRepository::image, id)
                .stream()
                .findFirst();
    }

    public int countImages(long productId) {
        return jdbc.queryForObject("SELECT count(*) FROM product_image WHERE product_id = ?", Integer.class, productId);
    }

    /** Appends at the end (max sort_order + 1). Call under {@link #lockProduct}. */
    public long insertImage(long productId, String storageKey, String altText, int width, int height, Instant now) {
        return jdbc.queryForObject(
                "INSERT INTO product_image (product_id, storage_key, alt_text, width, height, sort_order, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort_order), -1) + 1 FROM product_image"
                        + " WHERE product_id = ?), ?) RETURNING id",
                Long.class,
                productId,
                storageKey,
                altText,
                width,
                height,
                productId,
                utc(now));
    }

    public void setSortOrders(long productId, List<Long> orderedImageIds) {
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < orderedImageIds.size(); i++) {
            batch.add(new Object[] {i, orderedImageIds.get(i), productId});
        }
        jdbc.batchUpdate("UPDATE product_image SET sort_order = ? WHERE id = ? AND product_id = ?", batch);
    }

    public int updateAlt(long id, String altText) {
        return jdbc.update("UPDATE product_image SET alt_text = ? WHERE id = ?", altText, id);
    }

    public int deleteImage(long id) {
        return jdbc.update("DELETE FROM product_image WHERE id = ?", id);
    }

    // ---- mapping ----

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, OffsetDateTime.class).toInstant();
    }

    private static ProductRow product(ResultSet rs, int n) throws SQLException {
        return new ProductRow(
                rs.getLong("id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("series"),
                rs.getString("description"),
                ProductStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static VariantRow variant(ResultSet rs, int n) throws SQLException {
        return new VariantRow(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("scale"),
                rs.getInt("price_cents"),
                rs.getInt("stock_qty"),
                rs.getBoolean("made_to_order_enabled"),
                (Integer) rs.getObject("made_to_order_quota"),
                rs.getBoolean("active"),
                rs.getInt("version"));
    }

    private static ImageRow image(ResultSet rs, int n) throws SQLException {
        return new ImageRow(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("storage_key"),
                rs.getString("alt_text"),
                rs.getInt("width"),
                rs.getInt("height"),
                rs.getInt("sort_order"));
    }
}
