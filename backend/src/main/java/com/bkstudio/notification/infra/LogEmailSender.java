package com.bkstudio.notification.infra;

import com.bkstudio.notification.domain.EmailMessage;
import com.bkstudio.notification.domain.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** Dev/test sender used when no Resend key is configured. Logs only the idempotency key; never recipient or body. */
@Component
@ConditionalOnExpression("'${app.notification.resend-api-key:}'.isBlank()")
class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    @Override
    public void send(EmailMessage m, String idempotencyKey) {
        log.info("Email not sent (no Resend key), key={}", idempotencyKey);
    }
}
