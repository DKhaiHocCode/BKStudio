package com.bkstudio.order.checkout.domain;

import com.bkstudio.notification.domain.OutboxWriter;
import com.bkstudio.order.checkout.api.CheckoutDtos.CreateOrder;
import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import com.bkstudio.order.checkout.api.CheckoutDtos.Item;
import com.bkstudio.order.checkout.api.CheckoutDtos.OrderCreated;
import com.bkstudio.order.checkout.infra.CheckoutRepository;
import com.bkstudio.order.checkout.infra.CheckoutRepository.ExistingOrder;
import com.bkstudio.order.checkout.infra.CheckoutRepository.NewOrder;
import com.bkstudio.order.checkout.infra.CheckoutRepository.VariantRow;
import com.bkstudio.shared.settings.domain.SettingsService;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout: validates, reserves stock / made-to-order quota and creates the order in ONE transaction. Prices come
 * from product_variant. Slots are NOT allocated here (T-025 does it on deposit confirmation).
 */
@Service
@Transactional
public class CheckoutService {

    /** {@code replayed} = an earlier request with the same Idempotency-Key already created this order. */
    public record Placement(OrderCreated order, boolean replayed) {}

    static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    static final int CODE_LENGTH = 6;
    static final int CODE_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final CheckoutRepository repo;
    private final SettingsService settings;
    private final CheckoutRateLimiter limiter;
    private final CheckoutProperties props;
    private final Clock clock;
    private final OutboxWriter outbox;
    private final SecureRandom random = new SecureRandom();

    CheckoutService(
            CheckoutRepository repo,
            SettingsService settings,
            CheckoutRateLimiter limiter,
            CheckoutProperties props,
            Clock clock,
            OutboxWriter outbox) {
        this.outbox = outbox;
        this.repo = repo;
        this.settings = settings;
        this.limiter = limiter;
        this.props = props;
        this.clock = clock;
    }

    public Placement place(UUID key, CreateOrder req, String clientIp) {
        Set<Long> seen = new HashSet<>();
        for (Item item : req.items()) {
            if (!seen.add(item.variantId())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Duplicate variantId in items.");
            }
        }
        String email = req.customer().email().trim();

        // Same-key requests queue here, so after the lock the lookup sees any committed twin.
        repo.lockIdempotencyKey(key);
        Optional<ExistingOrder> existing = repo.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replay(existing.get(), req, email);
        }

        Optional<Duration> wait = limiter.tryAcquire(clientIp);
        if (wait.isPresent()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMITED, "Too many orders from this address. Try again later.", wait.get());
        }

        requireCurrentTerms(req.acceptedTermsVersion());

        boolean mto = req.fulfillmentType() == FulfillmentType.MADE_TO_ORDER;
        Instant now = clock.instant();
        List<Item> lines = req.items().stream()
                .sorted(Comparator.comparingLong(Item::variantId))
                .toList();
        Map<Long, VariantRow> variants =
                repo.lockSellableVariants(lines.stream().map(Item::variantId).toList()).stream()
                        .collect(Collectors.toMap(VariantRow::id, Function.identity()));
        if (variants.size() != lines.size()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Variant not available.");
        }

        int subtotalCents;
        try {
            long subtotal = 0;
            for (Item line : lines) {
                VariantRow v = variants.get(line.variantId());
                int qty = line.quantity();
                reserve(v, qty, mto, now);
                subtotal = Math.addExact(subtotal, Math.multiplyExact((long) v.priceCents(), qty));
            }
            subtotalCents = Math.toIntExact(subtotal);
        } catch (ArithmeticException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Order total is too large.");
        }

        String token = newToken();
        String status = mto ? "AWAITING_DEPOSIT" : "AWAITING_PAYMENT";
        var customer = req.customer();
        var addr = req.shippingAddress();
        String code = null;
        long orderId = 0;
        for (int attempt = 0; attempt < CODE_ATTEMPTS && code == null; attempt++) {
            String candidate = newPublicCode();
            Optional<Long> id = repo.insertOrder(new NewOrder(
                    candidate,
                    hashToken(token),
                    key,
                    req.fulfillmentType().name(),
                    status,
                    customer.name().trim(),
                    email,
                    blankToNull(customer.phone()),
                    addr.line1().trim(),
                    blankToNull(addr.line2()),
                    addr.city().trim(),
                    blankToNull(addr.region()),
                    blankToNull(addr.postalCode()),
                    addr.country(),
                    subtotalCents,
                    req.acceptedTermsVersion(),
                    now,
                    CheckoutRepository.inetOrNull(clientIp)));
            if (id.isPresent()) {
                code = candidate;
                orderId = id.get();
            }
        }
        if (code == null) {
            throw new IllegalStateException("Could not allocate a unique order code");
        }
        for (Item line : lines) {
            repo.insertItem(orderId, variants.get(line.variantId()), line.quantity());
        }

        // The same link goes to the client now and to the customer by email (the DB keeps only the token hash).
        String orderUrl = props.orderUrl(token);
        outbox.enqueue(
                "order:" + orderId + ":received",
                email,
                "ORDER_RECEIVED",
                Map.of("publicCode", code, "orderUrl", orderUrl));

        log.info("Order created code={}", code);
        return new Placement(
                new OrderCreated(code, status, orderUrl, subtotalCents, deposit(mto, subtotalCents)), false);
    }

    private void reserve(VariantRow v, int qty, boolean mto, Instant now) {
        if (mto) {
            if (!v.madeToOrderEnabled()) {
                throw outOfStock();
            }
            // Unlimited (NULL quota) variants have nothing to decrement; the row is locked so the flag is current.
            if (v.madeToOrderQuota() != null && repo.decrementQuota(v.id(), qty, now) == 0) {
                throw outOfStock();
            }
        } else if (repo.decrementStock(v.id(), qty, now) == 0) {
            throw outOfStock();
        }
    }

    private Placement replay(ExistingOrder o, CreateOrder req, String email) {
        if (!o.fulfillmentType().equals(req.fulfillmentType().name())
                || !o.email().equalsIgnoreCase(email)) {
            throw new ApiException(ErrorCode.CONFLICT, "Idempotency-Key was used for a different order.");
        }
        boolean mto = o.fulfillmentType().equals(FulfillmentType.MADE_TO_ORDER.name());
        return new Placement(
                new OrderCreated(o.code(), o.status(), null, o.subtotalCents(), deposit(mto, o.subtotalCents())), true);
    }

    private void requireCurrentTerms(String accepted) {
        String current;
        try {
            current = settings.currentTerms().version();
        } catch (ApiException e) {
            if (e.code() != ErrorCode.NOT_FOUND) {
                throw e;
            }
            current = null; // nothing published yet
        }
        if (current == null || !current.equals(accepted)) {
            throw new ApiException(
                    ErrorCode.TERMS_OUTDATED, "The terms have changed. Please review and accept the current terms.");
        }
    }

    private static ApiException outOfStock() {
        return new ApiException(ErrorCode.OUT_OF_STOCK, "An item is out of stock.");
    }

    private static int deposit(boolean mto, int subtotalCents) {
        return mto ? (int) (((long) subtotalCents + 1) / 2) : 0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String newPublicCode() {
        StringBuilder sb = new StringBuilder("BK-");
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** SHA-256 of the token's ASCII text; T-023 must hash the URL token the same way to look an order up. */
    public static byte[] hashToken(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
