package com.bkstudio.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class CheckoutConcurrencyIT extends CheckoutTestBase {

    private static final int THREADS = 8;

    @Test
    void concurrentSameIdempotencyKey_createsOneOrderAndDecrementsStockOnce() throws Exception {
        long v = stockVariant(1_000, 10);
        UUID key = UUID.randomUUID();
        var body = order("IN_STOCK", v, 1);
        List<Callable<Res>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> place(key, body, nextIp()));
        }

        List<Res> results = concurrently(calls);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).isIn(200, 201));
        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(results.stream().map(r -> r.<String>json("$.code")).distinct())
                .hasSize(1);
        assertThat(orders()).isEqualTo(1);
        assertThat(stock(v)).isEqualTo(9);
    }

    @RepeatedTest(5)
    void concurrentLastItem_exactlyOneWins_stockNeverNegative() throws Exception {
        long v = stockVariant(1_000, 1);
        List<Callable<Res>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> place(UUID.randomUUID(), order("IN_STOCK", v, 1), nextIp()));
        }

        List<Res> results = concurrently(calls);

        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == 409)).hasSize(THREADS - 1);
        assertThat(results.stream().filter(r -> r.status() == 409))
                .allSatisfy(r -> assertThat(r.<String>json("$.code")).isEqualTo("OUT_OF_STOCK"));
        assertThat(stock(v)).isZero();
        assertThat(orders()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM order_item")).isEqualTo(1);
    }

    @RepeatedTest(3)
    void concurrentLastQuota_exactlyOneWins_quotaNeverNegative() throws Exception {
        long v = variant(product("ACTIVE"), "1/7", 1_000, 0, true, 1);
        List<Callable<Res>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> place(UUID.randomUUID(), order("MADE_TO_ORDER", v, 1), nextIp()));
        }

        List<Res> results = concurrently(calls);

        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == 409)).hasSize(THREADS - 1);
        assertThat(quota(v)).isZero();
        assertThat(orders()).isEqualTo(1);
    }

    @RepeatedTest(3)
    void concurrentOppositeLineOrder_noDeadlockNoServerError() throws Exception {
        long a = stockVariant(1_000, 3);
        long b = stockVariant(2_000, 3);
        List<Callable<Res>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Object[] lines = i % 2 == 0 ? new Object[] {a, 1, b, 1} : new Object[] {b, 1, a, 1};
            calls.add(() -> place(UUID.randomUUID(), order("IN_STOCK", lines), nextIp()));
        }

        List<Res> results = concurrently(calls);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));
        long created = results.stream().filter(r -> r.status() == 201).count();
        assertThat(created).isEqualTo(3);
        assertThat(stock(a)).isZero();
        assertThat(stock(b)).isZero();
        assertThat(orders()).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM order_item")).isEqualTo(6);
    }
}
