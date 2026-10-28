package com.bkstudio.notification.domain;

import java.util.regex.Pattern;

/**
 * Send failure. {@code transientFailure} = worth retrying (network, 429, 5xx). The message is always passed through
 * {@link #safe(String)}, so it is safe to store and log.
 */
public class EmailSendException extends Exception {

    private static final Pattern EMAIL = Pattern.compile("[^\\s@<>\"',;:()\\[\\]]+@[^\\s@<>\"',;()\\[\\]]+");

    private final boolean transientFailure;

    public EmailSendException(String message, boolean transientFailure) {
        super(safe(message));
        this.transientFailure = transientFailure;
    }

    public boolean transientFailure() {
        return transientFailure;
    }

    /** Redacts email addresses and truncates to 200 characters. */
    public static String safe(String s) {
        String r = EMAIL.matcher(s == null ? "" : s).replaceAll("[redacted]");
        return r.length() > 200 ? r.substring(0, 200) : r;
    }
}
