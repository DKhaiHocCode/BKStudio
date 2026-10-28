package com.bkstudio.payment.domain;

import com.bkstudio.shared.web.ApiException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Checks an admin-supplied payment link against the allow-list from settings (SECURITY: only https links to
 * approved hosts reach the customer). Host match is exact or "." + entry; never contains/regex-loose matching.
 *
 * <p>Caveat: an entry such as "com.sg" (a public suffix) would allow every *.com.sg host. Admins must list
 * registrable domains (e.g. "artistree.io"); the validator cannot tell the difference without a suffix list.
 */
final class PaymentUrlValidator {

    static final int MAX_LENGTH = 500;
    static final String FIELD = "paymentUrl";

    private PaymentUrlValidator() {}

    /** Throws VALIDATION_FAILED on field "paymentUrl" when the link is malformed or its host is not allowed. */
    static void validate(String url, List<String> allowedDomains) {
        if (url == null || url.isEmpty() || url.length() > MAX_LENGTH) {
            throw bad("paymentUrl must be 1 to " + MAX_LENGTH + " characters.");
        }
        if (url.chars().anyMatch(c -> c <= 0x20 || Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw bad("paymentUrl must not contain whitespace or control characters.");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw bad("paymentUrl is not a valid URL.");
        }
        if (!"https".equals(uri.getScheme())) {
            throw bad("paymentUrl must use https.");
        }
        if (uri.getRawUserInfo() != null || uri.getPort() != -1) {
            throw bad("paymentUrl must not contain credentials or a port.");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw bad("paymentUrl must have a host.");
        }
        host = host.toLowerCase(Locale.ROOT);
        // Plain DNS labels only: this rejects IPv6 literals ("[..]"), a trailing dot and all-numeric (IPv4) hosts.
        if (!host.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")
                || host.matches("[0-9.]+")) {
            throw bad("paymentUrl host is not allowed.");
        }
        for (String entry : allowedDomains) {
            if (entry == null) {
                continue;
            }
            String e = entry.trim().toLowerCase(Locale.ROOT);
            if (!e.isEmpty() && (host.equals(e) || host.endsWith("." + e))) {
                return;
            }
        }
        throw bad("paymentUrl host is not in the allowed payment domains.");
    }

    private static ApiException bad(String detail) {
        return ApiException.invalidField(FIELD, detail);
    }
}
