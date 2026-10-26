package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Every product / variant / image admin endpoint: no anonymous, no pre-TOTP session, no request without CSRF. */
class AdminProductSecurityIT extends AdminProductTestBase {

    private static final String JSON_PRODUCT = "{\"name\":\"Sneaky\",\"status\":\"ACTIVE\",\"version\":0}";
    private static final String JSON_VARIANT = "{\"scale\":\"1/7\",\"priceCents\":100}";
    private static final String JSON_VARIANT_UPDATE =
            "{\"priceCents\":100,\"stockQty\":1,\"madeToOrderEnabled\":false,\"active\":true,\"version\":0}";

    /** method, path, kind (none | json | multipart), body. All 10 endpoints of API_SPEC section 3 "Sản phẩm". */
    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("GET", "/api/admin/products", "none", null),
                Arguments.of("GET", "/api/admin/products/1", "none", null),
                Arguments.of("POST", "/api/admin/products", "json", "{\"name\":\"Sneaky\"}"),
                Arguments.of("PUT", "/api/admin/products/1", "json", JSON_PRODUCT),
                Arguments.of("POST", "/api/admin/products/1/variants", "json", JSON_VARIANT),
                Arguments.of("PUT", "/api/admin/variants/1", "json", JSON_VARIANT_UPDATE),
                Arguments.of("POST", "/api/admin/products/1/images", "multipart", null),
                Arguments.of("PUT", "/api/admin/products/1/images/order", "json", "{\"imageIds\":[]}"),
                Arguments.of("PATCH", "/api/admin/images/1", "json", "{\"altText\":\"x\"}"),
                Arguments.of("DELETE", "/api/admin/images/1", "none", null));
    }

    private AbstractMockHttpServletRequestBuilder<?> build(
            String method, String path, String kind, String body, MockHttpSession s, String csrf) {
        AbstractMockHttpServletRequestBuilder<?> b;
        if (kind.equals("multipart")) {
            return multipart(path, s, csrf, null, new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[] {1}));
        }
        b = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path).with(r -> {
            r.setRemoteAddr(nextIp());
            return r;
        });
        if (kind.equals("json")) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (csrf != null) {
            b = b.cookie(new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf);
        }
        return s == null ? b : b.session(s);
    }

    private static boolean unsafe(String method) {
        return !method.equals("GET");
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("endpoints")
    void anonymousRequest_is401(String method, String path, String kind, String body) throws Exception {
        // a valid CSRF token is attached, so the 401 comes from authorisation and not from the CSRF check
        expectProblem(mvc.perform(build(method, path, kind, body, null, csrfToken())), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("endpoints")
    void passwordOnlySession_beforeTotp_is401(String method, String path, String kind, String body) throws Exception {
        MockHttpSession pending = login(newAdmin());

        expectProblem(mvc.perform(build(method, path, kind, body, pending, csrfToken())), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("endpoints")
    void authenticatedRequestWithoutCsrfToken_is403_forEveryUnsafeMethod(
            String method, String path, String kind, String body) throws Exception {
        if (!unsafe(method)) {
            return; // GET never needs a token (A3)
        }
        expectProblem(mvc.perform(build(method, path, kind, body, session, null)), 403, "FORBIDDEN");
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("endpoints")
    void authenticatedRequestWithMismatchedCsrfToken_is403_forEveryUnsafeMethod(
            String method, String path, String kind, String body) throws Exception {
        if (!unsafe(method)) {
            return;
        }
        AbstractMockHttpServletRequestBuilder<?> b = build(method, path, kind, body, session, "cookie-token")
                .with(r -> {
                    r.removeHeader("X-XSRF-TOKEN"); // replace the matching header added by build()
                    r.addHeader("X-XSRF-TOKEN", "different-token");
                    return r;
                });

        expectProblem(mvc.perform(b), 403, "FORBIDDEN");
    }

    @Test
    void rejectedRequests_changeNothing() throws Exception {
        int audits = auditRows();
        for (Arguments a : endpoints().toList()) {
            Object[] v = a.get();
            String method = (String) v[0];
            if (unsafe(method)) {
                mvc.perform(build(method, (String) v[1], (String) v[2], (String) v[3], null, csrfToken()));
                mvc.perform(build(method, (String) v[1], (String) v[2], (String) v[3], session, null));
            }
        }

        assertThat(count("SELECT count(*) FROM product")).isZero();
        assertThat(auditRows()).isEqualTo(audits);
    }

    @Test
    void sessionOfAnotherLoginAttempt_withWrongTotp_staysRejected() throws Exception {
        TestAdmin other = newAdmin();
        MockHttpSession pending = login(other);
        totpCode(pending, wrongCode(other));

        expectProblem(
                mvc.perform(build("GET", "/api/admin/products", "none", null, pending, null)), 401, "UNAUTHENTICATED");
    }
}
