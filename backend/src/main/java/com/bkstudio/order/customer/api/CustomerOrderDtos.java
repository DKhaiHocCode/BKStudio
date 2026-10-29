package com.bkstudio.order.customer.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Shapes of the public order API (API_SPEC section 2). Nothing here may carry personal data or internal ids. */
public final class CustomerOrderDtos {

    private CustomerOrderDtos() {}

    public record Item(String productName, String variantLabel, int quantity, long unitPriceCents) {}

    /** Only PENDING requests are shown. */
    public record OpenPayment(String type, long amountCents, String paymentUrl, Instant dueAt) {}

    public record HistoryEntry(String status, Instant at) {}

    /**
     * {@code depositCents} is 0 for in-stock orders. {@code estimatedProductionMonth} ("YYYY-MM", earliest month of
     * the order's open slots) is null unless made-to-order with slots. {@code carrier}/{@code trackingNumber} are set
     * only for SHIPPED and COMPLETED. {@code cancelWarning} is true when cancelling forfeits the deposit.
     */
    public record OrderView(
            String publicCode,
            String fulfillmentType,
            String status,
            long subtotalCents,
            long depositCents,
            long shippingCents,
            List<Item> items,
            List<OpenPayment> paymentRequests,
            String estimatedProductionMonth,
            String carrier,
            String trackingNumber,
            List<HistoryEntry> history,
            Instant createdAt,
            boolean canCancel,
            boolean cancelWarning) {}

    /** Body may be empty or omit the flag; it only matters for QUEUED orders. */
    public record CancelRequest(Boolean acknowledgeDepositLost) {}

    public record LookupRequest(
            @NotBlank @Size(max = 20) String code,
            @NotBlank @Size(max = 254) String email) {}

    /** Identical for every lookup, whether or not the code and email matched. */
    public record LookupAccepted(String message) {}
}
