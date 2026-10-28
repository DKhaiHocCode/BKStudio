package com.bkstudio.notification.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mailFrom} (env MAIL_FROM) and {@code resendApiKey} (env RESEND_API_KEY, secret, never logged). Blank key means
 * dev/test: emails are only logged, not sent. Prod refuses to start without both.
 */
@ConfigurationProperties("app.notification")
public record NotificationProperties(String mailFrom, String resendApiKey, Outbox outbox) {

    @Override
    public String toString() {
        return "NotificationProperties[mailFrom=" + mailFrom + ", resendApiKey=****, outbox=" + outbox + "]";
    }

    /** {@code enabled=false} turns the scheduled job off; tests then call {@code OutboxSender.runOnce()}. */
    public record Outbox(boolean enabled) {}
}
