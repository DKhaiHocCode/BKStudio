package com.bkstudio.catalog.api;

import java.util.List;
import java.util.Map;

/** Response shapes of the public catalog API (API_SPEC section 2). Stock and quota numbers are never exposed. */
public final class ProductDtos {

    private ProductDtos() {}

    public record ProductPage(List<ListItem> items, int page, int size, long total) {}

    public record ListItem(
            String slug,
            String name,
            String series,
            Cover coverImage,
            int fromPriceCents,
            boolean inStock,
            boolean madeToOrder) {}

    public record Cover(String url, String alt, int width, int height) {}

    public record Detail(
            String slug,
            String name,
            String series,
            String description,
            boolean unlisted,
            List<Image> images,
            List<Variant> variants) {}

    public record Image(String url, Map<String, String> srcset, String alt, int width, int height) {}

    public record Variant(long id, String scale, int priceCents, InStock inStock, MadeToOrder madeToOrder) {}

    public record InStock(boolean available, Integer shipsWithinDays) {}

    public record MadeToOrder(boolean available, String estimatedStartMonth) {}
}
