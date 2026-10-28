package com.bkstudio.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.bkstudio.order.checkout.domain.CheckoutProperties;
import com.bkstudio.order.checkout.domain.CheckoutRateLimiter;
import com.bkstudio.support.MutableClock;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CheckoutRateLimiterTest {

    private MutableClock clock;
    private CheckoutRateLimiter limiter;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
        limiter = newLimiter(2, Duration.ofHours(1));
    }

    private CheckoutRateLimiter newLimiter(int max, Duration window) throws Exception {
        Constructor<CheckoutRateLimiter> c =
                CheckoutRateLimiter.class.getDeclaredConstructor(java.time.Clock.class, CheckoutProperties.class);
        c.setAccessible(true);
        return c.newInstance(clock, new CheckoutProperties("http://x", max, window));
    }

    @Test
    void tryAcquire_overMax_deniedWithRemainingWindow() {
        assertThat(limiter.tryAcquire("1.1.1.1")).isEmpty();
        clock.advance(Duration.ofMinutes(10));
        assertThat(limiter.tryAcquire("1.1.1.1")).isEmpty();
        assertThat(limiter.tryAcquire("1.1.1.1")).contains(Duration.ofMinutes(50));
        assertThat(limiter.tryAcquire("2.2.2.2")).isEmpty();
    }

    @Test
    void tryAcquire_afterWindow_resets() {
        limiter.tryAcquire("1.1.1.1");
        limiter.tryAcquire("1.1.1.1");
        assertThat(limiter.tryAcquire("1.1.1.1")).isPresent();
        clock.advance(Duration.ofHours(1));
        assertThat(limiter.tryAcquire("1.1.1.1")).isEmpty();
    }

    @Test
    void tryAcquire_manyIps_mapStaysNearCapAndNewIpsAreStillServed() throws Exception {
        limiter = newLimiter(1, Duration.ofHours(1));
        int n = 10_000 + 50;
        for (int i = 0; i < n; i++) {
            clock.advance(Duration.ofMillis(1));
            assertThat(limiter.tryAcquire("ip-" + i)).isEmpty();
        }
        // eviction is arbitrary, so only the newest entry is guaranteed to still be limited
        assertThat(limiter.tryAcquire("ip-" + (n - 1))).isPresent();
    }

    @Test
    void ipv6_sameSlash64SharesOneWindow_otherPrefixDoesNot() {
        assertThat(limiter.tryAcquire("2001:db8:1:2::1")).isEmpty();
        assertThat(limiter.tryAcquire("2001:db8:1:2:aaaa:bbbb:cccc:dddd")).isEmpty();
        assertThat(limiter.tryAcquire("2001:db8:1:2::99")).isPresent();
        assertThat(limiter.tryAcquire("2001:db8:1:3::1")).isEmpty();
    }

    @Test
    void ipv4MappedIpv6_sharesWindowWithIpv4() {
        limiter.tryAcquire("1.2.3.4");
        limiter.tryAcquire("::ffff:1.2.3.4");
        assertThat(limiter.tryAcquire("1.2.3.4")).isPresent();
    }

    @Test
    void isBlocked_doesNotConsume_andReportsRemainingWindow() {
        assertThat(limiter.isBlocked("1.1.1.1")).isEmpty();
        limiter.tryAcquire("1.1.1.1");
        assertThat(limiter.isBlocked("1.1.1.1")).isEmpty();
        limiter.tryAcquire("1.1.1.1");
        clock.advance(Duration.ofMinutes(10));
        assertThat(limiter.isBlocked("1.1.1.1")).contains(Duration.ofMinutes(50));
        assertThat(limiter.isBlocked("1.1.1.1")).isPresent(); // peeking twice changes nothing
        clock.advance(Duration.ofHours(1));
        assertThat(limiter.isBlocked("1.1.1.1")).isEmpty();
    }
}
