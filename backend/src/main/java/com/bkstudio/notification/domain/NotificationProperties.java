package com.bkstudio.notification.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mailFrom} (env MAIL_FROM) and {@code resendApiKey} (env RESEND_API_KEY, secret, never logged). Blank key means
 * dev/test: emails are only logged, not sent. Prod refuses to start without both.
 */
@Validated
@ConfigurationProperties("app.notification")
public record NotificationProperties(
        String mailFrom, String resendApiKey, @Valid Outbox outbox) {

    @Override
    public String toString() {
        return "NotificationProperties[mailFrom=" + mailFrom + ", resendApiKey=****, outbox=" + outbox + "]";
    }

    /**
     * {@code enabled=false} turns the scheduled job off; tests then call {@code OutboxSender.runOnce()}.
     * {@code dailyCap} is the most emails sent per rolling 24 hours (provider free-plan quota).
     */
    public record Outbox(
            boolean enabled, @DefaultValue("80") @Min(1) int dailyCap) {}
}
