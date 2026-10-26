package com.bkstudio.catalog.domain;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.catalog")
public record CatalogProperties(
        @NotBlank String imageBaseUrl, @Min(0) int shipsWithinDays) {

    /** Public URL of one rendition (400 / 1000 / 2000) of an image. */
    public String imageUrl(String storageKey, int width) {
        String base = imageBaseUrl.endsWith("/") ? imageBaseUrl.substring(0, imageBaseUrl.length() - 1) : imageBaseUrl;
        return base + "/" + storageKey + "/" + width + ".webp";
    }
}
