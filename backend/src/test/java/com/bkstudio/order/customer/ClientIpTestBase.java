package com.bkstudio.order.customer;

import com.bkstudio.support.AbstractPostgresTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Real HTTP against embedded Tomcat: MockMvc has no RemoteIpValve. GET /api/orders/{token} is limited to 60/min/IP. */
abstract class ClientIpTestBase extends AbstractPostgresTest {

    static final int LIMIT = 60;

    @LocalServerPort
    int port;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    int getOrder(String forwardedFor) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders/no-such-token"))
                .header("X-Forwarded-For", forwardedFor)
                .GET()
                .build();
        return http.send(r, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Status of the call after {@code LIMIT} calls, each with a different forged X-Forwarded-For. */
    int overLimitWithDistinctHeaders() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            getOrder("203.0.113." + i);
        }
        return getOrder("203.0.113.200");
    }
}
