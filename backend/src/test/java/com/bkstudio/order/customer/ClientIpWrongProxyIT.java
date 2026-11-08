package com.bkstudio.order.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Strategy native but the real peer (loopback) is not in internal-proxies: forged X-Forwarded-For is ignored. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.forward-headers-strategy=native", "server.tomcat.remoteip.internal-proxies=10\\.9\\.9\\.9"
        })
class ClientIpWrongProxyIT extends ClientIpTestBase {

    @Test
    void forgedForwardedFromUntrustedPeer_isIgnored_429() throws Exception {
        assertThat(overLimitWithDistinctHeaders()).isEqualTo(429);
    }
}
