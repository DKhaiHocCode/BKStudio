package com.bkstudio.order.checkout.infra;

import java.net.InetAddress;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** Plain-SQL access for checkout. No transaction of its own: the service owns it. */
@Repository
public class CheckoutRepository {

    public record VariantRow(
            long id,
            String productName,
            String scale,
            int priceCents,
            boolean madeToOrderEnabled,
            Integer madeToOrderQuota) {}

    public record ExistingOrder(String code, String status, String fulfillmentType, String email, int subtotalCents) {}

    public record NewOrder(
            String publicCode,
            byte[] tokenHash,
            UUID idempotencyKey,
            String fulfillmentType,
            String status,
            String name,
            String email,
            String phone,
            String line1,
            String line2,
            String city,
            String region,
            String postalCode,
            String country,
            int subtotalCents,
            String termsVersion,
            Instant now,
            String termsAcceptedIp) {}

    private final JdbcTemplate jdbc;

    CheckoutRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Transaction-scoped lock per idempotency key: same-key requests queue, so the lookup below is race free. */
    public void lockIdempotencyKey(UUID key) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (RowCallbackHandler) rs -> {}, key.toString());
    }

    public Optional<ExistingOrder> findByIdempotencyKey(UUID key) {
        return jdbc
                .query(
                        "SELECT public_code, status, fulfillment_type, customer_email, subtotal_cents"
                                + " FROM customer_order WHERE idempotency_key = ?",
                        (rs, i) -> new ExistingOrder(
                                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)),
                        key)
                .stream()
                .findFirst();
    }

    /**
     * Sellable variants (active, product ACTIVE or UNLISTED) among {@code sortedIds}, row-locked in id order so
     * parallel checkouts cannot deadlock and the price read here is the price charged.
     */
    public List<VariantRow> lockSellableVariants(List<Long> sortedIds) {
        String in = String.join(",", Collections.nCopies(sortedIds.size(), "?"));
        return jdbc.query(
                "SELECT v.id, p.name, v.scale, v.price_cents, v.made_to_order_enabled,"
                        + " v.made_to_order_quota FROM product_variant v JOIN product p ON p.id = v.product_id"
                        + " WHERE v.id IN (" + in + ") AND v.active AND p.status IN ('ACTIVE','UNLISTED')"
                        + " ORDER BY v.id FOR NO KEY UPDATE OF v",
                (rs, i) -> new VariantRow(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getBoolean(5),
                        rs.getObject(6, Integer.class)),
                sortedIds.toArray());
    }

    /** 1 = reserved, 0 = not enough stock. */
    public int decrementStock(long variantId, int qty, Instant now) {
        return jdbc.update(
                "UPDATE product_variant SET stock_qty = stock_qty - ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND active AND stock_qty >= ?",
                qty,
                utc(now),
                variantId,
                qty);
    }

    /** Only for limited variants (quota NOT NULL). 1 = reserved, 0 = quota exhausted. */
    public int decrementQuota(long variantId, int qty, Instant now) {
        return jdbc.update(
                "UPDATE product_variant SET made_to_order_quota = made_to_order_quota - ?, version = version + 1,"
                        + " updated_at = ? WHERE id = ? AND made_to_order_enabled AND made_to_order_quota IS NOT NULL"
                        + " AND made_to_order_quota >= ?",
                qty,
                utc(now),
                variantId,
                qty);
    }

    /** Empty when the public code is taken (ON CONFLICT on that one constraint only; any other conflict throws). */
    public Optional<Long> insertOrder(NewOrder o) {
        return jdbc
                .query(
                        "INSERT INTO customer_order (public_code, access_token_hash, idempotency_key, fulfillment_type,"
                                + " status, customer_name, customer_email, phone, ship_line1, ship_line2, ship_city,"
                                + " ship_region, ship_postal_code, ship_country, subtotal_cents, terms_version,"
                                + " terms_accepted_at, terms_accepted_ip, created_at, updated_at)"
                                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::inet,?,?)"
                                + " ON CONFLICT (public_code) DO NOTHING RETURNING id",
                        (rs, i) -> rs.getLong(1),
                        o.publicCode(),
                        o.tokenHash(),
                        o.idempotencyKey(),
                        o.fulfillmentType(),
                        o.status(),
                        o.name(),
                        o.email(),
                        o.phone(),
                        o.line1(),
                        o.line2(),
                        o.city(),
                        o.region(),
                        o.postalCode(),
                        o.country(),
                        o.subtotalCents(),
                        o.termsVersion(),
                        utc(o.now()),
                        o.termsAcceptedIp(),
                        utc(o.now()),
                        utc(o.now()))
                .stream()
                .findFirst();
    }

    public void insertItem(long orderId, VariantRow v, int qty) {
        jdbc.update(
                "INSERT INTO order_item (order_id, variant_id, product_name, scale, unit_price_cents, quantity)"
                        + " VALUES (?,?,?,?,?,?)",
                orderId,
                v.id(),
                v.productName(),
                v.scale(),
                v.priceCents(),
                qty);
    }

    /** Textual IP for the inet column, or null when unusable (zone ids like fe80::1%eth0 are cut). */
    public static String inetOrNull(String addr) {
        if (addr == null || addr.isBlank()) {
            return null;
        }
        String a = addr.contains("%") ? addr.substring(0, addr.indexOf('%')) : addr;
        if (!a.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        try {
            InetAddress.getByName(a); // literal only, no DNS: the regex above excludes hostnames
            return a;
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
