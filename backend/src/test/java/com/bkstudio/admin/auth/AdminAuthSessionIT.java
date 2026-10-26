package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/** TOTP window/replay, recovery codes (A4), session fixation, logout and timeouts. */
class AdminAuthSessionIT extends AdminAuthTestBase {

    @Autowired
    JdbcTemplate jdbc;

    // ---- TOTP ----

    @Test
    void sameTotpCodeTwiceInOneStep_secondLoginIs401() throws Exception {
        TestAdmin a = newAdmin();
        String code = code(a, 0);
        totpCode(login(a), code).andExpect(status().isOk());

        expectProblem(totpCode(login(a), code), 401, "UNAUTHENTICATED");
    }

    @Test
    void codeOfPreviousStep_isAccepted() throws Exception {
        TestAdmin a = newAdmin();
        totpCode(login(a), code(a, -1)).andExpect(status().isOk());
    }

    @Test
    void codeTwoStepsOld_isRejected() throws Exception {
        TestAdmin a = newAdmin();
        totpCode(login(a), code(a, -2)).andExpect(status().isUnauthorized());
    }

    @Test
    void codeAcceptanceFollowsTheClock() throws Exception {
        TestAdmin a = newAdmin();
        String old = code(a, 0);
        clock.advance(Duration.ofSeconds(60)); // the code is now two steps old
        totpCode(login(a), old).andExpect(status().isUnauthorized());
        totpCode(login(a), code(a, 0)).andExpect(status().isOk());
    }

    @Test
    void olderStepCode_isRejectedAfterNewerOneWasUsed() throws Exception {
        TestAdmin a = newAdmin();
        totpCode(login(a), code(a, 0)).andExpect(status().isOk());

        totpCode(login(a), code(a, -1)).andExpect(status().isUnauthorized());
    }

    @Test
    void nonNumericOrShortCode_is401() throws Exception {
        MockHttpSession pending = login(newAdmin());
        totpCode(pending, "abcdef").andExpect(status().isUnauthorized());
        totpCode(pending, "12345").andExpect(status().isUnauthorized());
    }

    // ---- A4 recovery codes ----

    @Test
    void recoveryCode_worksOnce_secondUseIs401() throws Exception {
        TestAdmin a = newAdmin();
        String code = a.recoveryCodes().getFirst();
        recovery(login(a), code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(a.email()));

        expectProblem(recovery(login(a), code), 401, "UNAUTHENTICATED");
        assertThat(usedRecoveryRows(a)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"asIs", "lowercase", "noDashes", "lowercaseNoDashes", "spaces"})
    void recoveryCode_normalisation_isAccepted(String variant) throws Exception {
        TestAdmin a = newAdmin();
        String shown = a.recoveryCodes().getFirst();
        String typed = switch (variant) {
            case "lowercase" -> shown.toLowerCase(Locale.ROOT);
            case "noDashes" -> shown.replace("-", "");
            case "lowercaseNoDashes" -> shown.replace("-", "").toLowerCase(Locale.ROOT);
            case "spaces" -> shown.replace("-", " ");
            default -> shown;
        };

        recovery(login(a), typed).andExpect(status().isOk());
    }

    @Test
    void recoveryCode_ofAnotherAdmin_isRejected() throws Exception {
        TestAdmin a = newAdmin();
        TestAdmin other = newAdmin();

        recovery(login(a), other.recoveryCodes().getFirst()).andExpect(status().isUnauthorized());
        assertThat(usedRecoveryRows(other)).isZero();
    }

    @Test
    void sameRecoveryCode_eightConcurrentRequests_exactlyOneSucceeds() throws Exception {
        TestAdmin a = newAdmin();
        String code = a.recoveryCodes().getFirst();
        int threads = 8;
        List<MockHttpSession> sessions = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            sessions.add(login(a));
        }
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (MockHttpSession s : sessions) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return recovery(s, code).andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }

            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
            assertThat(statuses).filteredOn(s -> s != 200).allMatch(s -> s == 401 || s == 429);
            assertThat(usedRecoveryRows(a)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private int usedRecoveryRows(TestAdmin a) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM admin_recovery_code WHERE admin_id = ? AND used_at IS NOT NULL",
                Integer.class,
                a.id());
    }

    // ---- session fixation / logout ----

    @Test
    void password_step_replacesAttackerSuppliedSession() throws Exception {
        TestAdmin a = newAdmin();
        MockHttpSession planted = new MockHttpSession();
        String plantedId = planted.getId();

        MvcResult r = mvc.perform(post("/api/admin/auth/login", credentials(a.email(), PASSWORD), planted, nextIp()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(planted.isInvalid()).isTrue();
        assertThat(r.getRequest().getSession(false).getId()).isNotEqualTo(plantedId);
    }

    @Test
    void totp_step_changesSessionId() throws Exception {
        TestAdmin a = newAdmin();
        MockHttpSession session = login(a);
        String pendingId = session.getId();

        totpCode(session, code(a, 0)).andExpect(status().isOk());

        assertThat(session.getId()).isNotEqualTo(pendingId);
        mvc.perform(getFrom("/api/admin/auth/me", session, nextIp())).andExpect(status().isOk());
    }

    @Test
    void loggingInAgainOnAnAuthenticatedSession_dropsTheOldAuthentication() throws Exception {
        TestAdmin a = newAdmin();
        MockHttpSession session = fullLogin(a);

        mvc.perform(post("/api/admin/auth/login", credentials(a.email(), PASSWORD), session, nextIp()))
                .andExpect(status().isOk());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void logout_invalidatesTheSession() throws Exception {
        MockHttpSession session = fullLogin(newAdmin());

        mvc.perform(post("/api/admin/auth/logout", null, session, nextIp())).andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
        mvc.perform(getFrom("/api/admin/auth/me", session, nextIp())).andExpect(status().isUnauthorized());
    }

    // ---- timeouts ----

    @Test
    void session_endsAtTwelveHoursAbsolute() throws Exception {
        MockHttpSession session = fullLogin(newAdmin());

        clock.advance(Duration.ofHours(11).plusMinutes(59).plusSeconds(59));
        mvc.perform(getFrom("/api/admin/auth/me", session, nextIp())).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(2)); // 12 h + 1 s since the TOTP step
        mvc.perform(getFrom("/api/admin/auth/me", session, nextIp())).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void pendingLogin_expiresAfterFiveMinutes() throws Exception {
        TestAdmin a = newAdmin();
        MockHttpSession session = login(a);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        expectProblem(totpCode(session, code(a, 0)), 401, "UNAUTHENTICATED");
    }

    @Test
    void pendingLogin_stillValidJustBeforeFiveMinutes() throws Exception {
        TestAdmin a = newAdmin();
        MockHttpSession session = login(a);

        clock.advance(Duration.ofMinutes(4).plusSeconds(59));

        totpCode(session, code(a, 0)).andExpect(status().isOk());
    }
}
