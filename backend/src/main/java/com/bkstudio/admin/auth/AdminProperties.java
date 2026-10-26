package com.bkstudio.admin.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Admin auth settings. {@code totpEncryptionKey} is base64 of 32 bytes, from env TOTP_ENCRYPTION_KEY only. */
@ConfigurationProperties("app.admin")
public record AdminProperties(String totpEncryptionKey) {

    // Keep the key out of logs / actuator dumps.
    @Override
    public String toString() {
        return "AdminProperties[totpEncryptionKey=***]";
    }
}
