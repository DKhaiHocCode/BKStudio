package com.bkstudio.notification.domain;

/** A rendered email. Never log any field: {@code to} is personal data and the body may hold order links. */
public record EmailMessage(String to, String subject, String html, String text) {}
