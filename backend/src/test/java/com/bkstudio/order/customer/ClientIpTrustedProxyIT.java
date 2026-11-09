package com.bkstudio.order.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Loopback peer configured as the trusted proxy: the client IP comes from X-Forwarded-For. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.forward-headers-strategy=native",
            "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1"
        })
class ClientIpTrustedProxyIT extends ClientIpTestBase {

    @Test
    void trustedProxy_usesForwardedFor_perClientBuckets() throws Exception {
        assertThat(overLimitWithDistinctHeaders()).isNotEqualTo(429); // 61 clients, one request each

        for (int i = 0; i < LIMIT; i++) {
            getOrder("198.51.100.7");
        }
        assertThat(getOrder("198.51.100.7")).isEqualTo(429); // same client is limited
        assertThat(getOrder("198.51.100.8")).isNotEqualTo(429); // another client is not
    }
}
