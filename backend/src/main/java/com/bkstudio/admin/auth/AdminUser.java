package com.bkstudio.admin.auth;

import java.time.Instant;

/** Row of admin_user. Never log it: it holds the email and secrets. */
public record AdminUser(
        long id,
        String email,
        String passwordHash,
        byte[] totpSecretEncrypted,
        boolean totpEnabled,
        int failedAttempts,
        Instant lockedUntil,
        Instant lastLoginAt) {

    @Override
    public String toString() {
        return "AdminUser[id=" + id + "]";
    }
}
