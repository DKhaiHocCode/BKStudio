package com.bkstudio.notification.domain;

/** Provider seam (ADR-0009): a new provider is one new implementation. */
public interface EmailSender {

    /** The provider drops a repeat of the same idempotencyKey, so a retry after a crash never double-sends. */
    void send(EmailMessage m, String idempotencyKey) throws EmailSendException;
}
