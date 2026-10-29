package com.bkstudio.order.customer.domain;

import com.bkstudio.order.checkout.domain.CheckoutRateLimiter;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Limits of the public order API, all in memory (1 instance, reset on restart; same trade-off as checkout).
 * shortcut: fixed numbers, not configurable; move to properties if the shop needs to tune them.
 */
@Component
public class CustomerOrderLimiters {

    /** GET order and cancel, per client IP. */
    private final CheckoutRateLimiter access;
    /** POST lookup, per client IP. */
    private final CheckoutRateLimiter lookupByIp;
    /** Token rotations per order (ADR-0012). */
    private final CheckoutRateLimiter rotations;
    /** A retry right after a rotation must not rotate again and kill the link just emailed. */
    private final CheckoutRateLimiter rotationCooldown;

    CustomerOrderLimiters(Clock clock) {
        this.access = new CheckoutRateLimiter(clock, 60, Duration.ofMinutes(1));
        this.lookupByIp = new CheckoutRateLimiter(clock, 10, Duration.ofHours(1));
        this.rotations = new CheckoutRateLimiter(clock, 3, Duration.ofHours(1));
        this.rotationCooldown = new CheckoutRateLimiter(clock, 1, Duration.ofMinutes(2));
    }

    /** Throws 429 RATE_LIMITED (with Retry-After) when the client is over the GET/cancel cap. */
    public void checkAccess(String ip) {
        access.tryAcquire(ip).ifPresent(wait -> {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many requests. Try again later.", wait);
        });
    }

    /** True when this lookup may proceed; the caller still answers 202 either way. */
    public boolean allowLookup(String ip) {
        return lookupByIp.tryAcquire(ip).isEmpty();
    }

    /** True when the order may get a new token now (3 per hour and not twice within 2 minutes). */
    public boolean allowRotation(long orderId) {
        String key = "order-" + orderId;
        Optional<Duration> cooling = rotationCooldown.tryAcquire(key);
        return cooling.isEmpty() && rotations.tryAcquire(key).isEmpty();
    }
}
