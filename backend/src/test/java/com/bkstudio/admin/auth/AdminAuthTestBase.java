package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bkstudio.support.AbstractPostgresTest;
import com.bkstudio.support.MutableClock;
import com.bkstudio.support.TestClockConfig;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared setup for the admin-auth integration tests: real Postgres, MockMvc, a controllable Clock, and helpers
 * to create admins and drive the HTTP login flow. Every test makes its own admin and uses fresh client IPs, so
 * the in-memory limiter and replay guard never couple tests together.
 */
// Test-only key (32 zero bytes, base64); not a secret.
@TestPropertySource(properties = "app.admin.totp-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
abstract class AdminAuthTestBase extends AbstractPostgresTest {

    static final String PASSWORD = "a-long-enough-password";
    /** Epoch seconds of 00:00:00 are a multiple of 30, so +10 s sits safely inside a TOTP step. */
    static final Instant START = Instant.parse("2030-01-01T00:00:10Z");

    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    record TestAdmin(String email, long id, byte[] secret, String secretBase32, List<String> recoveryCodes) {}

    @Autowired
    MockMvc mvc;

    @Autowired
    AdminBootstrap bootstrap;

    @Autowired
    AdminUserRepository admins;

    @Autowired
    TotpSecretCipher cipher;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(START);
    }

    /** A client address nobody else in the JVM has used. */
    static String nextIp() {
        int n = IP_SEQ.incrementAndGet();
        return "10." + (n >> 16 & 255) + "." + (n >> 8 & 255) + "." + (n & 255);
    }

    TestAdmin newAdmin() {
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        AdminBootstrap.Created c = bootstrap.create(email, PASSWORD);
        AdminUser u = admins.findByEmail(email).orElseThrow();
        return new TestAdmin(
                email, u.id(), cipher.decrypt(u.totpSecretEncrypted()), c.totpSecretBase32(), c.recoveryCodes());
    }

    /** TOTP code of the step {@code stepOffset} steps away from the clock's current step. */
    String code(TestAdmin a, int stepOffset) {
        long step = clock.instant().getEpochSecond() / TotpService.STEP_SECONDS + stepOffset;
        return TotpService.generate(a.secret(), step, 6);
    }

    /** A 6-digit code that is invalid for every step in a +-2 window. */
    String wrongCode(TestAdmin a) {
        List<String> valid = List.of(code(a, -2), code(a, -1), code(a, 0), code(a, 1), code(a, 2));
        for (int i = 0; ; i++) {
            String candidate = String.format("%06d", i);
            if (!valid.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** An anonymous GET hands out the CSRF cookie (SPA flow); returns its value. */
    String csrfToken() throws Exception {
        Cookie c =
                mvc.perform(get("/api/admin/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(c).isNotNull();
        return c.getValue();
    }

    /** Request carrying a matching XSRF cookie + header, the given client IP and optional session. */
    MockHttpServletRequestBuilder withCsrf(
            HttpMethod method, String path, String body, MockHttpSession session, String ip) throws Exception {
        String csrf = csrfToken();
        MockHttpServletRequestBuilder b = request(method, path)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .cookie(new Cookie("XSRF-TOKEN", csrf))
                .header("X-XSRF-TOKEN", csrf);
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return session == null ? b : b.session(session);
    }

    MockHttpServletRequestBuilder post(String path, String body, MockHttpSession session, String ip) throws Exception {
        return withCsrf(HttpMethod.POST, path, body, session, ip);
    }

    static MockHttpServletRequestBuilder getFrom(String path, MockHttpSession session, String ip) {
        MockHttpServletRequestBuilder b = get(path).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
        return session == null ? b : b.session(session);
    }

    static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    ResultActions loginRequest(String email, String password, String ip) throws Exception {
        return mvc.perform(post("/api/admin/auth/login", credentials(email, password), null, ip));
    }

    /** Step 1 with the right password; returns the pending session. */
    MockHttpSession login(TestAdmin a) throws Exception {
        MvcResult r = loginRequest(a.email(), PASSWORD, nextIp())
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    ResultActions totpRequest(MockHttpSession session, String json, String ip) throws Exception {
        return mvc.perform(post("/api/admin/auth/totp", json, session, ip));
    }

    ResultActions totpCode(MockHttpSession session, String code) throws Exception {
        return totpRequest(session, "{\"code\":\"" + code + "\"}", nextIp());
    }

    ResultActions recovery(MockHttpSession session, String recoveryCode) throws Exception {
        return totpRequest(session, "{\"recoveryCode\":\"" + recoveryCode + "\"}", nextIp());
    }

    /** Both steps with the current code; returns the fully authenticated session. */
    MockHttpSession fullLogin(TestAdmin a) throws Exception {
        MockHttpSession s = login(a);
        totpCode(s, code(a, 0)).andExpect(status().isOk());
        return s;
    }

    /** The project Problem Details shape (API_SPEC section 1) with the expected status and code. */
    static ResultActions expectProblem(ResultActions r, int httpStatus, String code) throws Exception {
        String slug = code.toLowerCase().replace('_', '-');
        return r.andExpect(status().is(httpStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://bkstudio.xxx/errors/" + slug))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(httpStatus))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(res -> jsonPath("$.requestId")
                        .value(res.getResponse().getHeader("X-Request-Id"))
                        .match(res));
    }
}
