package com.bkstudio.notification.domain;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fails startup in prod when the Resend key or the sender address is missing. */
@Component
@Profile("prod")
class NotificationConfigValidator {

    private final NotificationProperties props;

    NotificationConfigValidator(NotificationProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        if (props.resendApiKey() == null || props.resendApiKey().isBlank()) {
            throw new IllegalStateException("app.notification.resend-api-key (RESEND_API_KEY) must be set");
        }
        if (props.mailFrom() == null || props.mailFrom().isBlank()) {
            throw new IllegalStateException("app.notification.mail-from (MAIL_FROM) must be set");
        }
    }
}
