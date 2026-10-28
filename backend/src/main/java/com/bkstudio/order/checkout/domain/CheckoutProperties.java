package com.bkstudio.order.checkout.domain;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code siteBaseUrl} builds the customer order link; the limit is orders per IP per window. */
@Validated
@ConfigurationProperties("app.checkout")
public record CheckoutProperties(
        @NotBlank String siteBaseUrl,
        @Min(1) int rateLimitMax,
        @NotNull Duration rateLimitWindow) {

    public String orderUrl(String token) {
        String base = siteBaseUrl.endsWith("/") ? siteBaseUrl.substring(0, siteBaseUrl.length() - 1) : siteBaseUrl;
        return base + "/orders/" + token;
    }
}
