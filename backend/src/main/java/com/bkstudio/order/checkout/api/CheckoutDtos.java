package com.bkstudio.order.checkout.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Shapes of POST /api/orders (API_SPEC section 2). Prices are never part of the request. */
public final class CheckoutDtos {

    public static final int MAX_LINES = 10;
    public static final int MAX_QUANTITY = 5;

    /** No control (NUL, CR, LF, tab), format (bidi overrides, zero-width) or line/paragraph separator characters. */
    static final String NO_CONTROL = "^[^\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]*$";
    /** Same shape as chk_order_customer_email_format; surrounding spaces are trimmed by the service. */
    static final String EMAIL = "^\\s*[^@\\s\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+@[^@\\s\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+"
            + "\\.[^@\\s\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+\\s*$";

    private CheckoutDtos() {}

    public enum FulfillmentType {
        IN_STOCK,
        MADE_TO_ORDER
    }

    public record Item(
            @NotNull @Positive Long variantId,
            @NotNull @Min(1) @Max(MAX_QUANTITY) Integer quantity) {}

    public record Customer(
            @NotBlank @Size(max = 120) @Pattern(regexp = NO_CONTROL)
            String name,

            @NotBlank @Size(max = 254) @Pattern(regexp = EMAIL)
            String email,

            @Size(max = 40) @Pattern(regexp = NO_CONTROL) String phone) {}

    public record Address(
            @NotBlank @Size(max = 200) @Pattern(regexp = NO_CONTROL)
            String line1,

            @Size(max = 200) @Pattern(regexp = NO_CONTROL) String line2,

            @NotBlank @Size(max = 100) @Pattern(regexp = NO_CONTROL)
            String city,

            @Size(max = 100) @Pattern(regexp = NO_CONTROL) String region,
            @Size(max = 20) @Pattern(regexp = NO_CONTROL) String postalCode,

            @NotNull @Pattern(regexp = "SG|AU", message = "must be SG or AU")
            String country) {}

    public record CreateOrder(
            @NotNull FulfillmentType fulfillmentType,
            @NotNull @Size(min = 1, max = MAX_LINES) List<@NotNull @Valid Item> items,
            @NotNull @Valid Customer customer,
            @NotNull @Valid Address shippingAddress,

            @NotBlank @Size(max = 64) @Pattern(regexp = NO_CONTROL)
            String acceptedTermsVersion) {}

    /** {@code orderUrl} is null on an idempotent replay: only the token hash is stored. */
    public record OrderCreated(String code, String status, String orderUrl, int subtotalCents, int depositCents) {}
}
