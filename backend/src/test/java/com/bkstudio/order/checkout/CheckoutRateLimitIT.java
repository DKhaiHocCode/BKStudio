package com.bkstudio.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"app.checkout.rate-limit-max=2", "app.checkout.rate-limit-window=1h"})
class CheckoutRateLimitIT extends CheckoutTestBase {

    @Test
    void thirdOrderFromSameIp_429WithRetryAfter_replayAndOtherIpUnaffected() throws Exception {
        long v = stockVariant(1_000, 10);
        String ip = nextIp();
        UUID first = UUID.randomUUID();

        assertThat(place(first, order("IN_STOCK", v, 1), ip).status()).isEqualTo(201);
        // a replay does not consume an attempt
        assertThat(place(first, order("IN_STOCK", v, 1), ip).status()).isEqualTo(200);
        assertThat(place(UUID.randomUUID(), order("IN_STOCK", v, 1), ip).status())
                .isEqualTo(201);

        Res limited = place(UUID.randomUUID(), order("IN_STOCK", v, 1), ip);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.<String>json("$.code")).isEqualTo("RATE_LIMITED");
        assertThat(limited.retryAfter()).isNotNull();
        assertThat(Long.parseLong(limited.retryAfter())).isBetween(1L, 3600L);
        assertThat(stock(v)).isEqualTo(8);

        // a blocked address is refused up front (before any DB work), even for an earlier key
        Res blockedReplay = place(first, order("IN_STOCK", v, 1), ip);
        assertThat(blockedReplay.status()).isEqualTo(429);
        assertThat(blockedReplay.retryAfter()).isNotNull();
        assertThat(orders()).isEqualTo(2);
        // another address is not limited
        assertThat(place(UUID.randomUUID(), order("IN_STOCK", v, 1), nextIp()).status())
                .isEqualTo(201);

        // window passes -> allowed again
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(place(UUID.randomUUID(), order("IN_STOCK", v, 1), ip).status())
                .isEqualTo(201);
    }
}
