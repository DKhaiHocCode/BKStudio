package com.bkstudio.queue;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL fixtures for queue tests (shared by the service tests here and the admin API test). Clock is 2030-01. */
public final class QueueFixtures {

    /** Current month under the test clock (TestClockConfig starts at 2030-01-01). */
    public static final LocalDate M0 = LocalDate.of(2030, 1, 1);

    private QueueFixtures() {}

    public static LocalDate month(int offset) {
        return M0.plusMonths(offset);
    }

    public static void wipe(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE app_setting");
        jdbc.execute("TRUNCATE production_month CASCADE");
        jdbc.execute("TRUNCATE customer_order CASCADE");
        jdbc.execute("TRUNCATE product CASCADE");
    }

    /** All 24 window months with the given capacity and nothing allocated. */
    public static void window(JdbcTemplate jdbc, int capacity) {
        for (int i = 0; i < 24; i++) {
            setMonth(jdbc, i, capacity, 0);
        }
    }

    public static void setMonth(JdbcTemplate jdbc, int offset, int capacity, int allocated) {
        jdbc.update(
                "INSERT INTO production_month (month, capacity, allocated) VALUES (?, ?, ?)"
                        + " ON CONFLICT (month) DO UPDATE SET capacity = EXCLUDED.capacity,"
                        + " allocated = EXCLUDED.allocated",
                java.sql.Date.valueOf(month(offset)),
                capacity,
                allocated);
    }

    /** Creates n minimal made-to-order orders with one item each; returns the order_item ids. */
    public static long[] orderItems(JdbcTemplate jdbc, int n) {
        String tag = java.util.UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO terms_document (version, content_md) VALUES ('qt', 'x') ON CONFLICT DO NOTHING");
        long product = jdbc.queryForObject(
                "INSERT INTO product (slug, name) VALUES (?, 'Queue test') RETURNING id", Long.class, "qt-" + tag);
        long variant = jdbc.queryForObject(
                "INSERT INTO product_variant (product_id, scale, price_cents, made_to_order_enabled)"
                        + " VALUES (?, '1/7', 10000, true) RETURNING id",
                Long.class,
                product);
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            long order = jdbc.queryForObject(
                    "INSERT INTO customer_order (public_code, access_token_hash, idempotency_key, fulfillment_type,"
                            + " status, customer_name, customer_email, ship_line1, ship_city, ship_country,"
                            + " subtotal_cents, terms_version, terms_accepted_at)"
                            + " VALUES (?, decode(md5(random()::text) || md5(random()::text), 'hex'),"
                            + " gen_random_uuid(), 'MADE_TO_ORDER', 'AWAITING_DEPOSIT', 'T', 't@example.com', 'l1',"
                            + " 'c', 'SG', 10000, 'qt', now()) RETURNING id",
                    Long.class,
                    "QT-" + tag + "-" + i);
            ids[i] = jdbc.queryForObject(
                    "INSERT INTO order_item (order_id, variant_id, product_name, scale, unit_price_cents, quantity)"
                            + " VALUES (?, ?, 'Queue test', '1/7', 10000, 2) RETURNING id",
                    Long.class,
                    order,
                    variant);
        }
        return ids;
    }
}
