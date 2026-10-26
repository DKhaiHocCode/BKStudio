package com.bkstudio.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TotpSecretCipherTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_B = Base64.getEncoder().encodeToString(new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
        31, 32
    });

    @Test
    void roundTrip() {
        TotpSecretCipher c = TotpSecretCipher.ofKey(KEY_A);
        byte[] secret = "12345678901234567890".getBytes();
        byte[] stored = c.encrypt(secret);
        assertThat(stored).hasSize(12 + secret.length + 16);
        assertThat(c.decrypt(stored)).isEqualTo(secret);
    }

    @Test
    void ivIsUniquePerEncryption() {
        TotpSecretCipher c = TotpSecretCipher.ofKey(KEY_A);
        byte[] one = c.encrypt(new byte[20]);
        byte[] two = c.encrypt(new byte[20]);
        assertThat(Arrays.copyOf(one, 12)).isNotEqualTo(Arrays.copyOf(two, 12));
        assertThat(one).isNotEqualTo(two);
    }

    @Test
    void detectsTamperingAndWrongKey() {
        TotpSecretCipher c = TotpSecretCipher.ofKey(KEY_A);
        byte[] stored = c.encrypt(new byte[20]);
        byte[] tampered = stored.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> c.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TotpSecretCipher.ofKey(KEY_B).decrypt(stored))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.decrypt(new byte[5])).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBadKeys() {
        assertThatThrownBy(() -> TotpSecretCipher.ofKey(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TotpSecretCipher.ofKey("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TotpSecretCipher.ofKey("not base64!!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TotpSecretCipher.ofKey(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void propertiesToStringMasksKey() {
        assertThat(new AdminProperties(KEY_A).toString()).doesNotContain(KEY_A);
    }
}
