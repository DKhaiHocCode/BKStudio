package com.bkstudio.order.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Default config trusts no proxy: a forged X-Forwarded-For must not buy a fresh rate-limit bucket. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=none")
class ClientIpUntrustedProxyIT extends ClientIpTestBase {

    @Test
    void forgedForwardedFor_isIgnored_sameBucketIs429() throws Exception {
        assertThat(overLimitWithDistinctHeaders()).isEqualTo(429);
    }
}
