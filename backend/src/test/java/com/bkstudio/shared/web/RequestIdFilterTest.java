package com.bkstudio.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void keepsSafeIncomingId() throws Exception {
        MockHttpServletResponse response = run("abc-12345678");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc-12345678");
    }

    @Test
    void replacesUnsafeIncomingId() throws Exception {
        MockHttpServletResponse response = run("bad id with spaces & symbols!");
        assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f]{32}");
    }

    @Test
    void generatesIdWhenMissingAndClearsMdcAfterwards() throws Exception {
        MockHttpServletResponse response = run(null);
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void idIsAvailableInMdcWhileRequestRuns() throws Exception {
        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = MDC.get("requestId");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "mdc-12345678");
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(seen[0]).isEqualTo("mdc-12345678");
    }

    private MockHttpServletResponse run(String incomingId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (incomingId != null) {
            request.addHeader("X-Request-Id", incomingId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        return response;
    }
}
