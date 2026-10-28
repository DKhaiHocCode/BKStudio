package com.bkstudio.order.checkout.domain;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fails startup in prod when APP_BASE_URL is missing or not https (order links are emailed to customers). */
@Component
@Profile("prod")
class CheckoutConfigValidator {

    private final CheckoutProperties props;

    CheckoutConfigValidator(CheckoutProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        if (props.siteBaseUrl() == null || !props.siteBaseUrl().startsWith("https://")) {
            throw new IllegalStateException("app.checkout.site-base-url (APP_BASE_URL) must start with https://");
        }
    }
}
