package com.bkstudio.order.checkout.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-client cap on order creations (default 5 per hour). A window starts at the first counted attempt and resets
 * after the window length; the attempt is reserved atomically. Same limits as {@code LoginAttemptLimiter}: in memory
 * (reset on restart, not shared between instances), keyed by request.getRemoteAddr() (T-043 must fix proxy headers),
 * IPv6 keyed by its /64 prefix. Map capped at {@link #MAX_TRACKED}: expired entries are swept at most once a minute,
 * and if still full an arbitrary entry is evicted (the cap can be overshot slightly under races).
 * shortcut: arbitrary eviction lets an attacker with 10k+ addresses reset others' windows; upgrade with a real cache.
 */
@Component
public class CheckoutRateLimiter {

    static final int MAX_TRACKED = 10_000;
    private static final Duration PURGE_EVERY = Duration.ofMinutes(1);

    private record Window(Instant start, int used) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int max;
    private final Duration window;
    private volatile Instant nextPurge = Instant.MIN;

    CheckoutRateLimiter(Clock clock, CheckoutProperties props) {
        this.clock = clock;
        this.max = props.rateLimitMax();
        this.window = props.rateLimitWindow();
    }

    /** Takes one attempt. Empty means granted; otherwise the time until the window ends. */
    public Optional<Duration> tryAcquire(String ip) {
        Instant now = clock.instant();
        purgeIfDue(now);
        String key = keyOf(ip);
        if (windows.size() >= MAX_TRACKED && !windows.containsKey(key)) {
            var it = windows.keySet().iterator();
            if (it.hasNext()) {
                windows.remove(it.next());
            }
        }
        boolean[] granted = {false};
        Window w = windows.compute(key, (k, old) -> {
            if (old == null || !old.start().plus(window).isAfter(now)) {
                granted[0] = true;
                return new Window(now, 1);
            }
            if (old.used() >= max) {
                return old;
            }
            granted[0] = true;
            return new Window(old.start(), old.used() + 1);
        });
        return granted[0]
                ? Optional.empty()
                : Optional.of(Duration.between(now, w.start().plus(window)));
    }

    /** Non-consuming check: the time left if this client is already at the cap, else empty. */
    public Optional<Duration> isBlocked(String ip) {
        Instant now = clock.instant();
        Window w = windows.get(keyOf(ip));
        if (w == null || !w.start().plus(window).isAfter(now) || w.used() < max) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, w.start().plus(window)));
    }

    /** IPv4 as is, IPv4-mapped IPv6 as its IPv4, other IPv6 masked to the /64 prefix. */
    static String keyOf(String ip) {
        String s = String.valueOf(ip);
        if (s.indexOf(':') < 0) {
            return s;
        }
        try {
            byte[] b = java.net.InetAddress.ofLiteral(s).getAddress(); // literal only: never does a DNS lookup
            if (b.length == 4) { // the JDK already unwraps ::ffff:a.b.c.d
                return java.net.InetAddress.getByAddress(b).getHostAddress();
            }
            byte[] masked = b.clone();
            java.util.Arrays.fill(masked, 8, 16, (byte) 0);
            return java.net.InetAddress.getByAddress(masked).getHostAddress();
        } catch (java.io.IOException | IllegalArgumentException e) {
            return s;
        }
    }

    private void purgeIfDue(Instant now) {
        if (now.isBefore(nextPurge)) {
            return;
        }
        nextPurge = now.plus(PURGE_EVERY);
        windows.values().removeIf(w -> !w.start().plus(window).isAfter(now));
    }
}
