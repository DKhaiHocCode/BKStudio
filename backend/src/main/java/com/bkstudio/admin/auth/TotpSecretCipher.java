package com.bkstudio.admin.auth;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** AES-256-GCM for TOTP secrets at rest. Stored format: 12-byte random IV, then ciphertext + 16-byte tag. */
@Component
public class TotpSecretCipher {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String MISSING = "app.admin.totp-encryption-key (env TOTP_ENCRYPTION_KEY) is not set";

    private final SecretKeySpec key; // null when not configured (non-prod only)
    private final SecureRandom random = new SecureRandom();

    @Autowired
    TotpSecretCipher(AdminProperties props, Environment env) {
        this(props.totpEncryptionKey(), env.acceptsProfiles(Profiles.of("prod")));
    }

    private TotpSecretCipher(String base64Key, boolean required) {
        if (base64Key == null || base64Key.isBlank()) {
            if (required) {
                throw new IllegalStateException(MISSING);
            }
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY is not valid base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** For tests and tools outside Spring: strict (key required). */
    static TotpSecretCipher ofKey(String base64Key) {
        return new TotpSecretCipher(base64Key, true);
    }

    public byte[] encrypt(byte[] plain) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP secret encryption failed");
        }
    }

    /** Throws IllegalStateException if the data was tampered with or the key is wrong. */
    public byte[] decrypt(byte[] stored) {
        requireKey();
        if (stored == null || stored.length < IV_LEN + 16) {
            throw new IllegalStateException("TOTP secret ciphertext is malformed");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(stored, 0, IV_LEN)));
            return c.doFinal(stored, IV_LEN, stored.length - IV_LEN);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP secret decryption failed");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(MISSING);
        }
    }
}
