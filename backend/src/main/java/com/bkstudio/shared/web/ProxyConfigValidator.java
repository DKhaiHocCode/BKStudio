package com.bkstudio.shared.web;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fails startup in prod unless the client IP comes from X-Forwarded-For of a trusted proxy only (rate limits). */
@Component
@Profile("prod")
class ProxyConfigValidator {

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
                    "prod needs FORWARD_HEADERS_STRATEGY=native and a non-blank TRUSTED_PROXIES (regex of the proxy IP)");
        }
    }
}
