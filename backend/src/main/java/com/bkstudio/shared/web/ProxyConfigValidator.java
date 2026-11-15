package com.bkstudio.shared.web;

import jakarta.annotation.PostConstruct;
import java.net.Inet4Address;
import java.net.InetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fails startup in prod unless the client IP comes from X-Forwarded-For of a trusted proxy only (rate limits), and
 * unless TRUSTED_PROXIES is narrow: a too-wide value would let any peer forge its IP and dodge every rate limit.
 */
@Component
@Profile("prod")
class ProxyConfigValidator {

    static final int MIN_IPV4_PREFIX = 24;
    static final int MIN_IPV6_PREFIX = 64;

    private final String strategy;
    private final String trustedProxies;

    ProxyConfigValidator(
            @Value("${server.forward-headers-strategy:none}") String strategy,
            @Value("${server.tomcat.remoteip.internal-proxies:}") String trustedProxies) {
        this.strategy = strategy;
        this.trustedProxies = trustedProxies;
    }

    @PostConstruct
    void init() {
        if (!"native".equalsIgnoreCase(strategy.trim()) || trustedProxies.isBlank()) {
            throw new IllegalStateException(
                    "prod needs FORWARD_HEADERS_STRATEGY=native and a non-blank TRUSTED_PROXIES (the proxy IP or CIDR)");
        }
        String value = trustedProxies.trim();
        if (value.contains("/")) { // Tomcat reads a value with '/' as a comma-separated CIDR list
            for (String entry : value.split(",")) {
                requireNarrow(entry.trim());
            }
        } else if (!(address(value.replace("\\.", ".")) instanceof Inet4Address)) {
            // Without '/' Tomcat reads a regex. Only one literal IPv4 (dots may be escaped) is accepted, so patterns
            // like ".*" or "10\..*" cannot slip through; list several proxies as CIDRs instead. An IPv6 literal is
            // refused too: as a regex it only matches one exact textual form, so IPv6 must be a CIDR.
            throw tooWide(value);
        }
    }

    private static void requireNarrow(String entry) {
        int slash = entry.indexOf('/');
        InetAddress addr = address(slash < 0 ? entry : entry.substring(0, slash));
        if (addr == null) {
            throw tooWide(entry);
        }
        if (slash < 0) {
            return; // bare address = one host
        }
        int prefix;
        try {
            prefix = Integer.parseInt(entry.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw tooWide(entry);
        }
        int bits = addr.getAddress().length * 8;
        if (prefix > bits || prefix < (bits == 32 ? MIN_IPV4_PREFIX : MIN_IPV6_PREFIX)) {
            throw tooWide(entry);
        }
    }

    /** Literal only (never a DNS lookup); null when the text is not an IP address. */
    private static InetAddress address(String text) {
        try {
            return InetAddress.ofLiteral(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static IllegalStateException tooWide(String entry) {
        return new IllegalStateException("TRUSTED_PROXIES entry '" + entry + "' is invalid or too wide: use the proxy's"
                + " IP or a CIDR no broader than /" + MIN_IPV4_PREFIX + " (IPv4) or /" + MIN_IPV6_PREFIX
                + " (IPv6), e.g. 172.30.0.10/32 or fd00::10/128 (IPv6 only as CIDR)");
    }
}
