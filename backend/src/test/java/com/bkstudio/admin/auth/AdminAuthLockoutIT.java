package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lockout semantics: the ACCOUNT lock counts second-factor failures only (wrong passwords must not let a stranger
 * lock the admin out); passwords are throttled per IP. Attempts are reserved atomically before verification.
 */
class AdminAuthLockoutIT extends AdminAuthTestBase {

    private static long retryAfterSeconds(ResultActions r) throws Exception {
        return Long.parseLong(r.andReturn().getResponse().getHeader("Retry-After"));
    }

    private List<Integer> runParallel(int n, Callable<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(task));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(120, java.util.concurrent.TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- H2: passwords never lock the account ----

    @Test
    void tenWrongPasswordsFromFreshIps_doNotLockTheAccount() throws Exception {
        TestAdmin a = newAdmin();
        for (int i = 0; i < 10; i++) {
            expectProblem(loginRequest(a.email(), "wrong-password-" + i, nextIp()), 401, "UNAUTHENTICATED");
        }

        MockHttpSession pending = login(a); // the correct password still reaches the pending step
        totpCode(pending, code(a, 0)).andExpect(status().isOk());
        AdminUser u = admins.findById(a.id()).orElseThrow();
        assertThat(u.lockedUntil()).isNull();
        assertThat(u.failedAttempts()).isZero();
    }

    // ---- account lock: TOTP / recovery stage ----

    @Test
    void fiveWrongTotpCodes_lockAccount_sixthWithCorrectCodeIs429WithRetryAfter() throws Exception {
        TestAdmin a = newAdmin();
        MockHttpSession pending = login(a);
        for (int i = 0; i < 5; i++) {
            expectProblem(totpCode(pending, wrongCode(a)), 401, "UNAUTHENTICATED");
        }

        ResultActions sixth = totpCode(pending, code(a, 0));

        expectProblem(sixth, 429, "RATE_LIMITED").andExpect(header().exists("Retry-After"));
        assertThat(retryAfterSeconds(sixth)).isBetween(1L, 900L);
        AdminUser u = admins.findById(a.id()).orElseThrow();
        assertThat(u.failedAttempts()).isEqualTo(5);
        assertThat(u.lockedUntil()).isEqualTo(clock.instant().plus(AuthPolicy.LOCK_DURATION));
    }

    @Test
    void fourWrongTotpCodes_doNotLock_fifthAttemptWithCorrectCodeSucceeds() throws Exception {
        TestAdmin a = newAdmin();
        MockHttpSession pending = login(a);
        for (int i = 0; i < 4; i++) {
            totpCode(pending, wrongCode(a)).andExpect(status().isUnauthorized());
        }

        totpCode(pending, code(a, 0)).andExpect(status().isOk());
        AdminUser u = admins.findById(a.id()).orElseThrow();
        assertThat(u.lockedUntil()).isNull();
        assertThat(u.failedAttempts()).isZero();
    }

    @Test
    void lockedAccount_passwordStepStillWorks_butTotpStepIs429_untilTheLockExpires() throws Exception {
        TestAdmin a = newAdmin();
        MockHttpSession pending = login(a);
        for (int i = 0; i < 5; i++) {
            totpCode(pending, wrongCode(a)).andExpect(status().isUnauthorized());
        }

        MockHttpSession again = login(a); // the password is right, so step 1 succeeds even while locked
        expectProblem(totpCode(again, code(a, 0)), 429, "RATE_LIMITED").andExpect(header().exists("Retry-After"));

        clock.advance(AuthPolicy.LOCK_DURATION.plusSeconds(1)); // the pending state (5 min) is gone too: log in again
        MockHttpSession afterExpiry = login(a);
        totpCode(afterExpiry, code(a, 0)).andExpect(status().isOk());
    }

    @Test
    void afterTheLockExpires_theBudgetIsFresh_oneWrongCodeDoesNotRelock() throws Exception {
        TestAdmin a = newAdmin();
        MockHttpSession pending = login(a);
        for (int i = 0; i < 5; i++) {
            totpCode(pending, wrongCode(a)).andExpect(status().isUnauthorized());
        }
        clock.advance(AuthPolicy.LOCK_DURATION.plusSeconds(1));
        MockHttpSession again = login(a);

        totpCode(again, wrongCode(a)).andExpect(status().isUnauthorized());

        AdminUser u = admins.findById(a.id()).orElseThrow();
        assertThat(u.failedAttempts()).isEqualTo(1);
        assertThat(u.lockedUntil()).isNull();
        totpCode(again, code(a, 0)).andExpect(status().isOk());
    }

    @Test
    void wrongRecoveryCodes_countTowardsLockout() throws Exception {
        TestAdmin a = newAdmin();
        MockHttpSession pending = login(a);
        for (int i = 0; i < 5; i++) {
            recovery(pending, "AAAA-AAAA-AAAA-AAA" + i).andExpect(status().isUnauthorized());
        }

        recovery(pending, a.recoveryCodes().getFirst()).andExpect(status().isTooManyRequests());
    }

    @Test
    void unknownEmail_givesSame401AsWrongPassword_exceptRequestId() throws Exception {
        TestAdmin a = newAdmin();
        String wrong = loginRequest(a.email(), "wrong-password-1", nextIp())
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknown = loginRequest("nobody-" + a.id() + "@example.com", "wrong-password-1", nextIp())
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(unknown.replaceAll("\"requestId\":\"[^\"]*\"", ""))
                .isEqualTo(wrong.replaceAll("\"requestId\":\"[^\"]*\"", ""));
    }

    // ---- per-IP limiter ----

    @Test
    void fiveWrongPasswordsFromOneIp_block_thatIpOnly_andExpireAfterFifteenMinutes() throws Exception {
        String badIp = nextIp();
        for (int i = 0; i < 5; i++) {
            loginRequest("ghost-" + i + "@example.com", "wrong-password-1", badIp)
                    .andExpect(status().isUnauthorized());
        }
        TestAdmin a = newAdmin();

        ResultActions blocked = loginRequest(a.email(), PASSWORD, badIp);
        expectProblem(blocked, 429, "RATE_LIMITED").andExpect(header().exists("Retry-After"));
        assertThat(retryAfterSeconds(blocked)).isBetween(1L, 900L);
        loginRequest(a.email(), PASSWORD, nextIp()).andExpect(status().isOk()); // other clients unaffected

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        loginRequest(a.email(), PASSWORD, badIp).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(2));
        loginRequest(a.email(), PASSWORD, badIp).andExpect(status().isOk());
    }

    @Test
    void successfulLogins_leaveNoResidueInTheIpBudget() throws Exception {
        TestAdmin a = newAdmin();
        String ip = nextIp();
        for (int i = 0; i < 4; i++) {
            loginRequest(a.email(), "wrong-password-" + i, ip).andExpect(status().isUnauthorized());
        }

        // 4 of 5 are used; if a success kept its reservation, the second one here would already be a 429.
        for (int i = 0; i < 10; i++) {
            loginRequest(a.email(), PASSWORD, ip).andExpect(status().isOk());
        }
        loginRequest(a.email(), "wrong-password-x", ip).andExpect(status().isUnauthorized()); // 5th failure
        loginRequest(a.email(), PASSWORD, ip).andExpect(status().isTooManyRequests());
    }

    // ---- concurrency: the budget is reserved before verifying ----

    @Test
    void hundredParallelWrongTotpCodes_atMostFiveAreVerified_andTheRightCodeStaysRefused() throws Exception {
        TestAdmin a = newAdmin();
        int n = 100;
        List<MockHttpSession> sessions = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            sessions.add(login(a));
        }
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        String wrong = wrongCode(a);

        List<Integer> statuses = runParallel(
                n,
                () -> totpCode(sessions.get(next.getAndIncrement()), wrong)
                        .andReturn()
                        .getResponse()
                        .getStatus());

        assertThat(statuses).filteredOn(s -> s == 429).hasSizeGreaterThanOrEqualTo(95);
        assertThat(statuses).filteredOn(s -> s == 401).hasSizeLessThanOrEqualTo(5);
        assertThat(statuses).allMatch(s -> s == 401 || s == 429);
        AdminUser u = admins.findById(a.id()).orElseThrow();
        assertThat(u.failedAttempts()).isEqualTo(5);
        assertThat(u.lockedUntil()).isEqualTo(clock.instant().plus(AuthPolicy.LOCK_DURATION));
        expectProblem(totpCode(login(a), code(a, 0)), 429, "RATE_LIMITED");
    }

    @Test
    void hundredParallelWrongPasswordsFromOneIp_atMostFiveReachThePasswordCheck() throws Exception {
        TestAdmin a = newAdmin();
        String ip = nextIp();

        List<Integer> statuses = runParallel(
                100,
                () -> loginRequest(a.email(), "wrong-password-1", ip)
                        .andReturn()
                        .getResponse()
                        .getStatus());

        assertThat(statuses).filteredOn(s -> s == 401).hasSizeLessThanOrEqualTo(5);
        assertThat(statuses).filteredOn(s -> s == 429).hasSizeGreaterThanOrEqualTo(95);
        assertThat(statuses).allMatch(s -> s == 401 || s == 429);
        assertThat(admins.findById(a.id()).orElseThrow().lockedUntil()).isNull(); // passwords never lock the account
    }
}
