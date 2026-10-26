package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** A1 (no access without completed TOTP), A3 (CSRF), error shape, secret leakage and session cookie config. */
class AdminAuthAccessIT extends AdminAuthTestBase {

    private static final String OTHER_ADMIN_PATH = "/api/admin/does-not-exist-t012";

    @Autowired
    Environment env;

    @Test
    void publicEndpointsStayOpen() throws Exception {
        mvc.perform(getFrom("/api/health", null, nextIp())).andExpect(status().isOk());
    }

    // ---- A1 ----

    @Test
    void anonymous_isRejectedOnEveryAdminPath() throws Exception {
        expectProblem(mvc.perform(getFrom("/api/admin/auth/me", null, nextIp())), 401, "UNAUTHENTICATED");
        expectProblem(mvc.perform(getFrom(OTHER_ADMIN_PATH, null, nextIp())), 401, "UNAUTHENTICATED");
    }

    @Test
    void passwordOnlySession_isRejectedOnMeAndOnOtherAdminPaths() throws Exception {
        MockHttpSession pending = login(newAdmin());

        expectProblem(mvc.perform(getFrom("/api/admin/auth/me", pending, nextIp())), 401, "UNAUTHENTICATED");
        expectProblem(mvc.perform(getFrom(OTHER_ADMIN_PATH, pending, nextIp())), 401, "UNAUTHENTICATED");
    }

    @Test
    void afterTotp_sessionReachesAdminPaths() throws Exception {
        MockHttpSession session = fullLogin(newAdmin());

        mvc.perform(getFrom("/api/admin/auth/me", session, nextIp())).andExpect(status().isOk());
        // Authorised: the request reaches routing and finds no handler, rather than being refused.
        mvc.perform(getFrom(OTHER_ADMIN_PATH, session, nextIp())).andExpect(status().isNotFound());
    }

    @Test
    void wrongTotp_leavesTheSessionUnauthenticated() throws Exception {
        TestAdmin a = newAdmin();
        MockHttpSession pending = login(a);
        totpCode(pending, wrongCode(a)).andExpect(status().isUnauthorized());

        mvc.perform(getFrom("/api/admin/auth/me", pending, nextIp())).andExpect(status().isUnauthorized());
    }

    // ---- A3 ----

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void unsafeMethodWithoutCsrfToken_is403Problem_evenWhenAuthenticated(String method) throws Exception {
        MockHttpSession session = fullLogin(newAdmin());
        MockHttpServletRequestBuilder anonymous =
                MockMvcRequestBuilders.request(HttpMethod.valueOf(method), OTHER_ADMIN_PATH + "/1");
        expectProblem(mvc.perform(anonymous), 403, "FORBIDDEN");
        expectProblem(mvc.perform(anonymous.session(session)), 403, "FORBIDDEN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"login", "totp", "logout"})
    void authEndpointsWithoutCsrfToken_are403(String step) throws Exception {
        MockHttpServletRequestBuilder noToken = MockMvcRequestBuilders.post("/api/admin/auth/" + step)
                .contentType("application/json")
                .content("{}");
        expectProblem(mvc.perform(noToken), 403, "FORBIDDEN");
    }

    @Test
    void mismatchedCsrfHeader_is403() throws Exception {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.post("/api/admin/auth/login")
                .contentType("application/json")
                .content(credentials("a@example.com", "whatever-password"))
                .cookie(new Cookie("XSRF-TOKEN", csrfToken()))
                .header("X-XSRF-TOKEN", csrfToken());

        expectProblem(mvc.perform(b), 403, "FORBIDDEN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void unsafeMethodWithValidCsrfToken_passesTheCsrfCheck(String method) throws Exception {
        HttpMethod m = HttpMethod.valueOf(method);
        // Anonymous: CSRF passes, authorisation then says 401 (not 403).
        expectProblem(mvc.perform(withCsrf(m, OTHER_ADMIN_PATH + "/1", "{}", null, nextIp())), 401, "UNAUTHENTICATED");
        // Authenticated: CSRF and authorisation pass, so it reaches routing (no such handler yet -> 404).
        MockHttpSession session = fullLogin(newAdmin());
        mvc.perform(withCsrf(m, OTHER_ADMIN_PATH + "/1", "{}", session, nextIp()))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_neverNeedsToken_andSetsReadableXsrfCookie() throws Exception {
        mvc.perform(getFrom("/api/admin/auth/me", null, nextIp()))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
        MockHttpSession session = fullLogin(newAdmin());
        mvc.perform(getFrom("/api/admin/auth/me", session, nextIp())).andExpect(status().isOk());
    }

    @Test
    void logoutWithCsrfToken_succeedsEvenWithoutSession() throws Exception {
        mvc.perform(post("/api/admin/auth/logout", null, null, nextIp())).andExpect(status().isNoContent());
    }

    // ---- error shape ----

    @Test
    void validationErrorOnAuthEndpoint_isProblemDetails() throws Exception {
        expectProblem(totpRequest(null, "{}", nextIp()), 400, "VALIDATION_FAILED");
        expectProblem(loginRequest("", "", nextIp()), 400, "VALIDATION_FAILED");
    }

    // ---- secrets never leave ----

    @Test
    void responses_neverContainHashSecretOrOtherUsersEmail() throws Exception {
        TestAdmin a = newAdmin();
        TestAdmin other = newAdmin();
        String hash = admins.findById(a.id()).orElseThrow().passwordHash();
        List<String> bodies = new ArrayList<>();

        MockHttpSession pending = login(a);
        bodies.add(body(loginRequest(a.email(), "wrong-password-1", nextIp())));
        bodies.add(body(mvc.perform(getFrom("/api/admin/auth/me", pending, nextIp()))));
        bodies.add(body(totpCode(pending, wrongCode(a))));
        bodies.add(body(totpCode(pending, code(a, 0))));
        bodies.add(body(mvc.perform(getFrom("/api/admin/auth/me", pending, nextIp()))));
        bodies.add(body(mvc.perform(getFrom(OTHER_ADMIN_PATH, null, nextIp()))));
        bodies.add(body(mvc.perform(MockMvcRequestBuilders.post("/api/admin/auth/login"))));

        for (String b : bodies) {
            assertThat(b)
                    .doesNotContain(hash)
                    .doesNotContain("argon2")
                    .doesNotContain(a.secretBase32())
                    .doesNotContain(other.email());
        }
    }

    private static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    // ---- session cookie / timeout config (Tomcat applies it; MockMvc does not, so assert the bound config) ----

    @Test
    void sessionConfig_isThirtyMinutesIdle_httpOnly_sameSiteLax_secure() {
        assertThat(env.getProperty("server.servlet.session.timeout", Duration.class))
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(env.getProperty("server.servlet.session.cookie.http-only", Boolean.class))
                .isTrue();
        assertThat(env.getProperty("server.servlet.session.cookie.same-site")).isEqualToIgnoringCase("lax");
        assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class))
                .isTrue();
    }

    @Test
    void getWithoutSession_doesNotCreateOne() throws Exception {
        assertThat(mvc.perform(MockMvcRequestBuilders.get(OTHER_ADMIN_PATH))
                        .andReturn()
                        .getRequest()
                        .getSession(false))
                .isNull();
    }
}
