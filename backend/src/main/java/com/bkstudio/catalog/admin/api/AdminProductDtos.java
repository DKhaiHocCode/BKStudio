package com.bkstudio.catalog.admin.api;

import com.bkstudio.catalog.api.ProductDtos;
import com.bkstudio.catalog.domain.ProductStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response shapes of the admin product API (API_SPEC section 3, "Sản phẩm"). */
public final class AdminProductDtos {

    public static final String SLUG_REGEX = "^[a-z0-9]+(-[a-z0-9]+)*$";
    public static final String SCALE_REGEX = "^1/[0-9]{1,2}$";

    private AdminProductDtos() {}

    // ---- requests ----

    public record CreateProduct(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 80) @Pattern(regexp = SLUG_REGEX) String slug,
            @Size(max = 200) String series,
            @Size(max = 10_000) String description,
            ProductStatus status) {}

    public record UpdateProduct(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String series,
            @Size(max = 10_000) String description,
            @NotNull ProductStatus status,
            @NotNull Integer version) {}

    public record CreateVariant(
            @NotNull @Pattern(regexp = SCALE_REGEX) String scale,
            @NotNull @Min(1) @Max(10_000_000) Integer priceCents,
            @Min(0) @Max(100_000) Integer stockQty,
            Boolean madeToOrderEnabled,
            @Min(0) @Max(100_000) Integer madeToOrderQuota,
            Boolean active) {}

    public record UpdateVariant(
            @NotNull @Min(1) @Max(10_000_000) Integer priceCents,
            @NotNull @Min(0) @Max(100_000) Integer stockQty,
            @NotNull Boolean madeToOrderEnabled,
            @Min(0) @Max(100_000) Integer madeToOrderQuota,
            @NotNull Boolean active,
            @NotNull Integer version) {}

    public record ReorderImages(@NotNull @Size(max = 20) List<@NotNull Long> imageIds) {}

    public record UpdateAlt(@NotNull @Size(max = 200) String altText) {}

    // ---- responses ----

    public record ProductPage(List<ListItem> items, int page, int size, long total) {}

    public record ListItem(
            long id,
            String slug,
            String name,
            String series,
            ProductStatus status,
            int version,
            Instant updatedAt,
            int variantCount,
            int imageCount,
            ProductDtos.Cover coverImage) {}

    public record Detail(
            long id,
            String slug,
            String name,
            String series,
            String description,
            ProductStatus status,
            int version,
            Instant createdAt,
            Instant updatedAt,
            List<Variant> variants,
            List<Image> images) {}

    public record Variant(
            long id,
            String scale,
            int priceCents,
            int stockQty,
            boolean madeToOrderEnabled,
            Integer madeToOrderQuota,
            boolean active,
            int version) {}

    /** {@code urls} keys are "400", "1000", "2000". */
    public record Image(long id, int sortOrder, String altText, Map<String, String> urls, int width, int height) {}
}
