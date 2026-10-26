package com.bkstudio.admin.auth;

import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

/**
 * Two-step admin login: password (session becomes "pending", NOT authenticated) then TOTP or recovery code
 * (session becomes authenticated, with a new session id). Never log or return anything that tells which part failed.
 * Not class-level @Transactional on purpose: each repository call is its own transaction, and a transaction must
 * not stay open (holding a DB connection) while Argon2 runs.
 */
@Service
public class AdminAuthService {

    static final String PENDING_ADMIN_ID = "pendingAdminId";
    static final String PENDING_AT = "pendingAt";
    static final Duration PENDING_TTL = Duration.ofMinutes(5);

    private final AdminUserRepository admins;
    private final RecoveryCodeRepository recoveryCodes;
    private final PasswordHasher hasher;
    private final TotpService totp;
    private final TotpSecretCipher cipher;
    private final TotpReplayGuard replayGuard;
    private final LoginAttemptLimiter ipLimiter;
    private final Clock clock;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    AdminAuthService(
            AdminUserRepository admins,
            RecoveryCodeRepository recoveryCodes,
            PasswordHasher hasher,
            TotpService totp,
            TotpSecretCipher cipher,
            TotpReplayGuard replayGuard,
            LoginAttemptLimiter ipLimiter,
            Clock clock) {
        this.admins = admins;
        this.recoveryCodes = recoveryCodes;
        this.hasher = hasher;
        this.totp = totp;
        this.cipher = cipher;
        this.replayGuard = replayGuard;
        this.ipLimiter = ipLimiter;
        this.clock = clock;
    }

    /**
     * Step 1. Success only means "password ok, TOTP needed": the session stays unauthenticated.
     * Password failures are throttled by the per-IP limiter (reserved before hashing, only a correct password gives
     * its own reservation back) and by Argon2 cost; they never lock the ACCOUNT, otherwise anyone who knows the
     * admin's email could lock the admin out. The account lock counts second-factor failures only (completeLogin).
     */
    public void login(HttpServletRequest request, String email, String password) {
        String ip = request.getRemoteAddr();
        acquireIpAttempt(ip);
        Optional<AdminUser> found = admins.findByEmail(email.trim());
        if (found.isEmpty()) {
            hasher.matchesDummy(password); // same cost as a real check: no account enumeration by timing
            throw unauthenticated();
        }
        AdminUser user = found.get();
        boolean passwordOk = hasher.matches(password, user.passwordHash()); // always hash first: same timing
        if (!passwordOk || !user.totpEnabled()) {
            throw unauthenticated(); // reservation stays consumed
        }
        ipLimiter.release(ip);
        // Fresh session id for the pending state; also drops any previous (even authenticated) session state.
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(PENDING_ADMIN_ID, user.id());
        session.setAttribute(PENDING_AT, clock.instant());
    }

    /** Step 2: exactly one of code / recoveryCode is non-null. Returns the admin's email. */
    public String completeLogin(
            HttpServletRequest request, HttpServletResponse response, String code, String recoveryCode) {
        String ip = request.getRemoteAddr();
        acquireIpAttempt(ip);
        HttpSession session = request.getSession(false);
        Long adminId = pendingAdminId(session);
        if (adminId == null) {
            throw unauthenticated();
        }
        AdminUser user = admins.findById(adminId).orElseThrow(AdminAuthService::unauthenticated);
        // Reserve the attempt BEFORE verifying (atomic): parallel guesses cannot out-run the lockout. Only
        // reservations here count towards the account lock; a success below clears them.
        if (admins.tryReserveAttempt(user.id()).isEmpty()) {
            throw tooManyAttempts(lockRemaining(user.id()));
        }

        boolean ok;
        if (code != null) {
            OptionalLong step = totp.verify(cipher.decrypt(user.totpSecretEncrypted()), code);
            ok = step.isPresent() && replayGuard.accept(user.id(), step.getAsLong());
        } else {
            ok = recoveryCodes.consume(user.id(), RecoveryCodes.hash(recoveryCode));
        }
        if (!ok) {
            throw unauthenticated(); // attempt and IP reservation stay consumed
        }
        ipLimiter.release(ip);

        request.changeSessionId(); // session fixation defence: the id seen before this step is now dead
        session.removeAttribute(PENDING_ADMIN_ID);
        session.removeAttribute(PENDING_AT);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                String.valueOf(user.id()), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        session.setAttribute(AbsoluteSessionTimeoutFilter.LOGIN_AT, clock.instant());
        admins.recordSuccessfulLogin(user.id());
        return user.email();
    }

    public void logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /** Email of the admin whose id is the authentication name; 401 if the account no longer exists. */
    public String emailOf(String authenticationName) {
        return admins.findById(Long.parseLong(authenticationName))
                .map(AdminUser::email)
                .orElseThrow(AdminAuthService::unauthenticated);
    }

    private Long pendingAdminId(HttpSession session) {
        if (session == null
                || !(session.getAttribute(PENDING_ADMIN_ID) instanceof Long id)
                || !(session.getAttribute(PENDING_AT) instanceof Instant at)) {
            return null;
        }
        if (!at.plus(PENDING_TTL).isAfter(clock.instant())) {
            session.removeAttribute(PENDING_ADMIN_ID);
            session.removeAttribute(PENDING_AT);
            return null;
        }
        return id;
    }

    private void acquireIpAttempt(String ip) {
        ipLimiter.tryAcquire(ip).ifPresent(wait -> {
            throw tooManyAttempts(wait);
        });
    }

    private Duration lockRemaining(long adminId) {
        Instant now = clock.instant();
        return admins.findById(adminId)
                .map(AdminUser::lockedUntil)
                .map(until -> Duration.between(now, until))
                .filter(Duration::isPositive)
                .orElse(Duration.ofSeconds(1));
    }

    private static ApiException unauthenticated() {
        return new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid credentials.");
    }

    private static ApiException tooManyAttempts(Duration retryAfter) {
        return new ApiException(ErrorCode.RATE_LIMITED, "Too many failed attempts. Try again later.", retryAfter);
    }
}
