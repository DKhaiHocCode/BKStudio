package com.bkstudio.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.bkstudio.queue.QueueFixtures;
import com.bkstudio.support.AbstractPostgresTest;
import com.bkstudio.support.MutableClock;
import com.bkstudio.support.TestClockConfig;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/** T-022 setup: real Postgres, MockMvc, fake clock, empty catalog / order / terms tables before and after each test. */
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
abstract class CheckoutTestBase extends AbstractPostgresTest {

    static final String ORDERS = "/api/orders";
    static final String TERMS_V = "2030-01-01";
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    /** HTTP outcome of one call, captured so concurrent threads can hand it back. */
    record Res(int status, String body, String retryAfter, String cacheControl) {
        <T> T json(String path) {
            return JsonPath.read(body, path);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper om;

    @Autowired
    MutableClock clock;

    private String csrf;

    @BeforeEach
    void setUpCheckout() throws Exception {
        clock.set(Instant.parse("2030-01-01T00:00:10Z"));
        wipe();
        csrf = mvc.perform(get("/api/admin/auth/me"))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN")
                .getValue();
        publishTerms(TERMS_V);
    }

    @AfterEach
    void cleanUpCheckout() {
        wipe();
    }

    private void wipe() {
        QueueFixtures.wipe(jdbc);
        jdbc.execute("TRUNCATE terms_document CASCADE");
    }

    // ---- fixtures ----

    static String nextIp() {
        int n = IP_SEQ.incrementAndGet();
        return "172." + (n >> 16 & 255) + "." + (n >> 8 & 255) + "." + (n & 255);
    }

    void publishTerms(String version) {
        publishTerms(version, clock.instant());
    }

    void publishTerms(String version, Instant at) {
        jdbc.update(
                "INSERT INTO terms_document (version, content_md, published_at) VALUES (?, 'x', ?)",
                version,
                java.sql.Timestamp.from(at));
    }

    long product(String status) {
        return jdbc.queryForObject(
                "INSERT INTO product (slug, name, status) VALUES (?, 'Figure Name', ?) RETURNING id",
                Long.class,
                "p-" + UUID.randomUUID().toString().substring(0, 8),
                status);
    }

    long variant(long productId, String scale, int price, int stock, boolean mto, Integer quota) {
        return jdbc.queryForObject(
                "INSERT INTO product_variant (product_id, scale, price_cents, stock_qty, made_to_order_enabled,"
                        + " made_to_order_quota) VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class,
                productId,
                scale,
                price,
                stock,
                mto,
                quota);
    }

    /** ACTIVE product with one 1/7 in-stock variant. */
    long stockVariant(int price, int stock) {
        return variant(product("ACTIVE"), "1/7", price, stock, false, null);
    }

    // ---- request bodies ----

    Map<String, Object> order(String type, Object... variantQty) {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < variantQty.length; i += 2) {
            items.add(new LinkedHashMap<>(Map.of("variantId", variantQty[i], "quantity", variantQty[i + 1])));
        }
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", "Jane Doe");
        customer.put("email", "jane.secret@example.com");
        customer.put("phone", "+65 9123 4567");
        Map<String, Object> addr = new LinkedHashMap<>();
        addr.put("line1", "77 Secret Street");
        addr.put("city", "Singapore");
        addr.put("postalCode", "123456");
        addr.put("country", "SG");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fulfillmentType", type);
        body.put("items", items);
        body.put("customer", customer);
        body.put("shippingAddress", addr);
        body.put("acceptedTermsVersion", TERMS_V);
        return body;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sub(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(Map<String, Object> m) {
        return (List<Map<String, Object>>) m.get("items");
    }

    // ---- calls ----

    MockHttpServletRequestBuilder req(UUID key, String rawBody, String ip, boolean withCsrf) {
        MockHttpServletRequestBuilder b = post(ORDERS)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody);
        if (key != null) {
            b.header("Idempotency-Key", key.toString());
        }
        return withCsrf ? b.cookie(new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf) : b;
    }

    Res send(MockHttpServletRequestBuilder b) throws Exception {
        var r = mvc.perform(b).andReturn().getResponse();
        return new Res(
                r.getStatus(),
                r.getContentAsString(StandardCharsets.UTF_8),
                r.getHeader("Retry-After"),
                r.getHeader("Cache-Control"));
    }

    Res place(UUID key, Object body, String ip) throws Exception {
        return send(req(key, om.writeValueAsString(body), ip, true));
    }

    Res place(Object body) throws Exception {
        return place(UUID.randomUUID(), body, nextIp());
    }

    ResultActions raw(UUID key, String rawBody) throws Exception {
        return mvc.perform(req(key, rawBody, nextIp(), true));
    }

    /** Runs the calls on separate threads released together; returns their results in submission order. */
    List<Res> concurrently(List<Callable<Res>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Res>> fs = new ArrayList<>();
            for (Callable<Res> c : calls) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return c.call();
                }));
            }
            go.countDown();
            List<Res> out = new ArrayList<>();
            for (Future<Res> f : fs) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- db reads ----

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    int stock(long variantId) {
        return count("SELECT stock_qty FROM product_variant WHERE id = ?", variantId);
    }

    Integer quota(long variantId) {
        return jdbc.queryForObject(
                "SELECT made_to_order_quota FROM product_variant WHERE id = ?", Integer.class, variantId);
    }

    int version(long variantId) {
        return count("SELECT version FROM product_variant WHERE id = ?", variantId);
    }

    int orders() {
        return count("SELECT count(*) FROM customer_order");
    }

    static String tokenOf(String orderUrl) {
        String token = orderUrl.substring(orderUrl.lastIndexOf('/') + 1);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        return token;
    }
}
