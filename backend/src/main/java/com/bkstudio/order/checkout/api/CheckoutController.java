package com.bkstudio.order.checkout.api;

import com.bkstudio.order.checkout.api.CheckoutDtos.CreateOrder;
import com.bkstudio.order.checkout.api.CheckoutDtos.OrderCreated;
import com.bkstudio.order.checkout.domain.CheckoutRateLimiter;
import com.bkstudio.order.checkout.domain.CheckoutService;
import com.bkstudio.order.checkout.domain.CheckoutService.Placement;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Public checkout. permitAll in SecurityConfig, but CSRF is enforced like every other POST. */
@RestController
public class CheckoutController {

    private final CheckoutService checkout;
    private final CheckoutRateLimiter limiter;

    public CheckoutController(CheckoutService checkout, CheckoutRateLimiter limiter) {
        this.checkout = checkout;
        this.limiter = limiter;
    }

    @PostMapping("/api/orders")
    public ResponseEntity<OrderCreated> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrder req,
            HttpServletRequest request) {
        UUID key = parseKey(idempotencyKey);
        // Peek before the @Transactional service so a blocked IP never takes a DB connection.
        limiter.isBlocked(request.getRemoteAddr()).ifPresent(wait -> {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many orders from this address. Try again later.", wait);
        });
        Placement placement = checkout.place(key, req, request.getRemoteAddr());
        return ResponseEntity.status(placement.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(placement.order());
    }

    private static UUID parseKey(String raw) {
        if (raw == null || raw.length() != 36) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key header must be a UUID.");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key header must be a UUID.");
        }
    }
}
