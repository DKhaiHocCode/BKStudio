package com.bkstudio.admin.auth;

import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the first admin (used by the create-admin CLI). */
@Service
@Transactional
public class AdminBootstrap {

    static final int MIN_PASSWORD_LENGTH = 12;

    /** Secrets to show the operator exactly once. */
    public record Created(String totpSecretBase32, String otpauthUri, List<String> recoveryCodes) {}

    private final AdminUserRepository admins;
    private final RecoveryCodeRepository recoveryCodes;
    private final PasswordHasher hasher;
    private final TotpService totp;
    private final TotpSecretCipher cipher;

    AdminBootstrap(
            AdminUserRepository admins,
            RecoveryCodeRepository recoveryCodes,
            PasswordHasher hasher,
            TotpService totp,
            TotpSecretCipher cipher) {
        this.admins = admins;
        this.recoveryCodes = recoveryCodes;
        this.hasher = hasher;
        this.totp = totp;
        this.cipher = cipher;
    }

    public Created create(String email, String password) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        if (admins.findByEmail(normalised).isPresent()) {
            throw alreadyExists();
        }
        byte[] secret = totp.generateSecret();
        long id;
        try {
            id = admins.insert(normalised, hasher.hash(password), cipher.encrypt(secret));
        } catch (DuplicateKeyException e) {
            throw alreadyExists(); // lost a race on the unique email index; do not leak the DB detail
        }
        List<String> codes = RecoveryCodes.generate();
        this.recoveryCodes.insertAll(id, codes.stream().map(RecoveryCodes::hash).toList());
        // The account label is fixed so the operator's email never reaches stdout.
        return new Created(totp.toBase32(secret), totp.otpauthUri("BKStudio", "admin", secret), codes);
    }

    private static IllegalStateException alreadyExists() {
        return new IllegalStateException("an admin with that email already exists");
    }
}
