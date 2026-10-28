package com.bkstudio.payment.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class PaymentDtos {

    private PaymentDtos() {}

    public enum PaymentType {
        DEPOSIT,
        BALANCE,
        FULL
    }

    public enum PaymentChannel {
        ARTISTREE,
        SHOPEE_PROXY,
        OTHER
    }

    /** Also the command used by other modules (T-026 creates the BALANCE request through the service). */
    public record CreatePaymentRequest(
            @NotNull PaymentType type,
            @NotNull @Min(1) Integer amountCents,
            @Min(0) @Max(10_000_000) Integer shippingCents,
            @NotNull PaymentChannel channel,
            @NotNull @Size(max = 500) String paymentUrl) {}

    // DTO max (200/600) is only a trim margin over the service limits (100/500), which stay authoritative.
    public record ConfirmRequest(@NotNull @Size(max = 200) String externalRef) {}

    public record CancelRequest(@NotNull @Size(max = 600) String reason) {}

    public record PaymentRequestView(
            long id,
            long orderId,
            PaymentType type,
            int amountCents,
            PaymentChannel channel,
            String paymentUrl,
            String status,
            Instant dueAt) {}

    public record Confirmed(
            long id, long orderId, PaymentType type, String status, String orderStatus, Instant confirmedAt) {}
}
