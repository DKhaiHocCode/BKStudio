package com.bkstudio.order.checkout.domain;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code siteBaseUrl} builds the customer order link; {@code rateLimit*} is orders per IP per window and
 * {@code emailLimit*} orders per customer email per window.
 */
@Validated
@ConfigurationProperties("app.checkout")
public record CheckoutProperties(
        @NotBlank String siteBaseUrl,
        @Min(1) int rateLimitMax,
        @NotNull Duration rateLimitWindow,
        @Min(1) int emailLimitMax,
        @NotNull Duration emailLimitWindow) {

    public String orderUrl(String token) {
        String base = siteBaseUrl.endsWith("/") ? siteBaseUrl.substring(0, siteBaseUrl.length() - 1) : siteBaseUrl;
        return base + "/orders/" + token;
    }
}
