package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;

/** Parallel admin requests: optimistic locking and slug allocation hold under real concurrency. */
class AdminProductConcurrencyIT extends AdminProductTestBase {

    private record Response(int status, String body) {}

    /** Starts all tasks at the same instant and returns their responses. */
    private List<Response> runTogether(List<Callable<MvcResult>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> t : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Response> out = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                MvcResult r = f.get(60, TimeUnit.SECONDS);
                out.add(new Response(
                        r.getResponse().getStatus(),
                        r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<MvcResult> request(HttpMethod method, String path, String body) {
        return () ->
                mvc.perform(withCsrf(method, path, body, session, nextIp())).andReturn();
    }

    @Test
    void parallelProductUpdatesWithTheSameVersion_exactlyOneWins_theOtherGets409() throws Exception {
        for (int round = 0; round < 5; round++) {
            long pid = createProduct("Race " + round + " " + token());

            List<Response> rs = runTogether(List.of(
                    request(
                            HttpMethod.PUT,
                            PRODUCTS + "/" + pid,
                            j("name", "Writer A", "status", "DRAFT", "version", 0)),
                    request(
                            HttpMethod.PUT,
                            PRODUCTS + "/" + pid,
                            j("name", "Writer B", "status", "DRAFT", "version", 0))));

            assertThat(rs).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
            assertThat(rs.stream()
                            .filter(r -> r.status() == 409)
                            .findFirst()
                            .orElseThrow()
                            .body())
                    .contains("VERSION_CONFLICT");
            assertThat(count("SELECT version FROM product WHERE id = ?", pid)).isEqualTo(1);
            assertThat(audits("PRODUCT", pid))
                    .extracting(Audit::action)
                    .containsExactly("PRODUCT_CREATED", "PRODUCT_UPDATED");
        }
    }

    @Test
    void manyParallelProductUpdatesWithTheSameVersion_exactlyOneSucceeds() throws Exception {
        long pid = createProduct("Race many " + token());
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(request(
                    HttpMethod.PUT, PRODUCTS + "/" + pid, j("name", "Writer " + i, "status", "DRAFT", "version", 0)));
        }

        List<Response> rs = runTogether(tasks);

        assertThat(rs.stream().filter(r -> r.status() == 200)).hasSize(1);
        assertThat(rs.stream().filter(r -> r.status() == 409)).hasSize(7);
        assertThat(count("SELECT version FROM product WHERE id = ?", pid)).isEqualTo(1);
    }

    @Test
    void parallelVariantUpdatesWithTheSameVersion_exactlyOneWins_theOtherGets409() throws Exception {
        for (int round = 0; round < 5; round++) {
            long pid = createProduct("Variant race " + round + " " + token());
            long vid = createVariant(pid, "1/7", 1000, 10);
            String a = j("priceCents", 2000, "stockQty", 1, "madeToOrderEnabled", false, "active", true, "version", 0);
            String b = j("priceCents", 3000, "stockQty", 2, "madeToOrderEnabled", false, "active", true, "version", 0);

            List<Response> rs = runTogether(List.of(
                    request(HttpMethod.PUT, "/api/admin/variants/" + vid, a),
                    request(HttpMethod.PUT, "/api/admin/variants/" + vid, b)));

            assertThat(rs).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
            assertThat(count("SELECT version FROM product_variant WHERE id = ?", vid))
                    .isEqualTo(1);
            // price and stock come from the same single winner, never a mix of both writers
            int price = count("SELECT price_cents FROM product_variant WHERE id = ?", vid);
            int stock = count("SELECT stock_qty FROM product_variant WHERE id = ?", vid);
            assertThat(price == 2000 && stock == 1 || price == 3000 && stock == 2)
                    .isTrue();
            assertThat(audits("VARIANT", vid))
                    .extracting(Audit::action)
                    .containsExactly("VARIANT_CREATED", "VARIANT_UPDATED");
        }
    }

    @Test
    void parallelCreatesWithTheSameName_allSucceedWithDistinctSlugs() throws Exception {
        String name = "Same name " + token();
        String base = name.toLowerCase().replace(' ', '-');
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(request(HttpMethod.POST, PRODUCTS, j("name", name)));
        }

        List<Response> rs = runTogether(tasks);

        assertThat(rs).extracting(Response::status).containsOnly(201);
        List<String> slugs = jdbc.queryForList("SELECT slug FROM product WHERE name = ?", String.class, name);
        assertThat(slugs).hasSize(6).doesNotHaveDuplicates().contains(base);
        assertThat(slugs).allMatch(s -> s.equals(base) || s.matches("^" + base + "-[0-9]+$"));
        assertThat(auditRows()).isEqualTo(6);
    }

    @Test
    void parallelCreatesWithTheSameClientSlug_exactlyOneSucceeds_othersGet409() throws Exception {
        String slug = "race-slug-" + token();
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(request(HttpMethod.POST, PRODUCTS, j("name", "Client slug " + i, "slug", slug)));
        }

        List<Response> rs = runTogether(tasks);

        assertThat(rs.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(rs.stream().filter(r -> r.status() == 409)).hasSize(4);
        assertThat(count("SELECT count(*) FROM product WHERE slug = ?", slug)).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(1);
    }

    @Test
    void parallelVariantCreatesWithTheSameScale_exactlyOneSucceeds() throws Exception {
        long pid = createProduct("Scale race " + token());
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(request(
                    HttpMethod.POST, PRODUCTS + "/" + pid + "/variants", j("scale", "1/7", "priceCents", 1000 + i)));
        }

        List<Response> rs = runTogether(tasks);

        assertThat(rs.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(rs.stream().filter(r -> r.status() == 409)).hasSize(4);
        assertThat(count("SELECT count(*) FROM product_variant WHERE product_id = ?", pid))
                .isEqualTo(1);
    }

    @Test
    void parallelUnlistedCreatesWithTheSameName_allSucceedWithDistinctSlugs() throws Exception {
        String name = "Commission race " + token();
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(request(HttpMethod.POST, PRODUCTS, j("name", name, "status", "UNLISTED")));
        }

        List<Response> rs = runTogether(tasks);

        assertThat(rs).extracting(Response::status).containsOnly(201);
        assertThat(jdbc.queryForList("SELECT slug FROM product WHERE name = ?", String.class, name))
                .hasSize(6)
                .doesNotHaveDuplicates();
    }
}
