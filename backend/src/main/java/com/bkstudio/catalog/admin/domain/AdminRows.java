package com.bkstudio.catalog.admin.domain;

import com.bkstudio.catalog.domain.ProductStatus;
import java.time.Instant;

/** Plain rows read by {@code AdminProductRepository}. Never returned to clients; the service maps them to DTOs. */
public final class AdminRows {

    private AdminRows() {}

    public record ProductRow(
            long id,
            String slug,
            String name,
            String series,
            String description,
            ProductStatus status,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    public record ListRow(
            long id,
            String slug,
            String name,
            String series,
            ProductStatus status,
            int version,
            Instant updatedAt,
            int variantCount,
            int imageCount,
            String coverKey,
            String coverAlt,
            Integer coverWidth,
            Integer coverHeight) {}

    public record VariantRow(
            long id,
            long productId,
            String scale,
            int priceCents,
            int stockQty,
            boolean madeToOrderEnabled,
            Integer madeToOrderQuota,
            boolean active,
            int version) {}

    public record ImageRow(
            long id, long productId, String storageKey, String altText, int width, int height, int sortOrder) {}
}
