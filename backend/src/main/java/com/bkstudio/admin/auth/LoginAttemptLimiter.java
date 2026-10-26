package com.bkstudio.admin.auth;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-IP brake on the password step and the second-factor step: 5 attempts per 15 minutes. An attempt is reserved
 * atomically BEFORE it is verified ({@link #tryAcquire}), so parallel requests cannot overshoot the budget; a
 * successful step gives its own reservation back ({@link #release}), so legitimate logins leave no residue.
 * IPv6 clients are keyed by their /64 (one subscriber owns a whole /64, so per-address keys would be free to dodge).
 * shortcut: in memory, so it resets on restart and is not shared between instances. The map is capped at
 * {@link #MAX_TRACKED} windows; when full the window with the earliest start is evicted to make room (a new key is
 * never refused, or an attacker with many addresses could lock the admin out; the cost is that a flood can reset
 * old budgets). The eviction scan is O(n) and the cap can be overshot slightly under races. Expired windows are
 * purged at most once a minute.
 * The client IP is request.getRemoteAddr() only (X-Forwarded-For is spoofable); behind Caddy that is the proxy's IP,
 * so T-043 must configure trusted forwarded-header handling before this limit means anything per client. Upgrade
 * to a DB or Redis counter if we ever run several instances.
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_TRACKED = 10_000;
    private static final Duration PURGE_EVERY = Duration.ofMinutes(1);

    private record Window(Instant start, int used) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile Instant nextPurge = Instant.MIN;

    public LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Takes one attempt. Empty means granted; otherwise the time to wait until the window ends. */
    public Optional<Duration> tryAcquire(String ip) {
        Instant now = clock.instant();
        purgeIfDue(now);
        boolean[] granted = {false};
        String key = key(ip);
        if (windows.size() >= MAX_TRACKED && !windows.containsKey(key)) {
            windows.entrySet().stream()
                    .min(java.util.Comparator.comparing(e -> e.getValue().start()))
                    .ifPresent(e -> windows.remove(e.getKey(), e.getValue()));
        }
        Window w = windows.compute(key, (k, old) -> {
            if (old == null || !old.start().plus(WINDOW).isAfter(now)) {
                granted[0] = true;
                return new Window(now, 1);
            }
            if (old.used() >= MAX_ATTEMPTS) {
                return old;
            }
            granted[0] = true;
            return new Window(old.start(), old.used() + 1);
        });
        if (granted[0]) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, w.start().plus(WINDOW)));
    }

    /** Gives back one reservation made by {@link #tryAcquire} for a step that turned out legitimate. */
    public void release(String ip) {
        windows.computeIfPresent(key(ip), (k, w) -> w.used() <= 1 ? null : new Window(w.start(), w.used() - 1));
    }

    private void purgeIfDue(Instant now) {
        if (now.isBefore(nextPurge)) {
            return;
        }
        nextPurge = now.plus(PURGE_EVERY);
        windows.values().removeIf(w -> !w.start().plus(WINDOW).isAfter(now));
    }

    static String key(String ip) {
        if (ip != null && ip.indexOf(':') >= 0) {
            try {
                byte[] b = InetAddress.getByName(ip).getAddress(); // literal address: no DNS lookup
                if (b.length == 16) {
                    return HexFormat.of().formatHex(b, 0, 8);
                }
            } catch (java.net.UnknownHostException | SecurityException e) {
                // fall through: use the raw string
            }
        }
        return String.valueOf(ip);
    }
}
