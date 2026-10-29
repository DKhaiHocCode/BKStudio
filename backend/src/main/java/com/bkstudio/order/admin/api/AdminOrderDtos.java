package com.bkstudio.order.admin.api;

import com.bkstudio.payment.api.PaymentDtos.PaymentRequestView;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AdminOrderDtos {

    private AdminOrderDtos() {}

    /** No customer contact data in the list. */
    public record ListItem(
            long id,
            String publicCode,
            String status,
            String fulfillmentType,
            int subtotalCents,
            int shippingCents,
            int version,
            Instant createdAt) {}

    public record OrderPage(List<ListItem> items, int page, int size, long total) {}

    public record Customer(
            String name,
            String email,
            String phone,
            String line1,
            String line2,
            String city,
            String region,
            String postalCode,
            String country) {}

    public record Item(long id, long variantId, String productName, String scale, int unitPriceCents, int quantity) {}

    public record Detail(
            long id,
            String publicCode,
            String status,
            String fulfillmentType,
            int version,
            int subtotalCents,
            int shippingCents,
            String currency,
            Customer customer,
            List<Item> items,
            List<PaymentRequestView> paymentRequests,
            String carrier,
            String trackingNumber,
            String cancelReason,
            String adminNote,
            Instant createdAt,
            Instant updatedAt,
            Instant shippedAt,
            Instant completedAt,
            List<String> allowedActions) {}

    public record VersionOnly(@NotNull Integer version) {}

    /** {@code photoImageIds} is accepted and ignored for now. */
    public record MarkFinished(
            @NotNull Integer version, @Size(max = 20) List<Long> photoImageIds) {}

    // Field limits are enforced in the service (authoritative, VALIDATION_FAILED per field).
    public record Ship(@NotNull Integer version, String carrier, String trackingNumber) {}

    public record CancelByShop(@NotNull Integer version, String reason, String refundRef) {}

    public record NoteRequest(String adminNote) {}

    public record NoteView(long id, String adminNote) {}

    public record ActionResult(long id, String status, int version) {}
}
