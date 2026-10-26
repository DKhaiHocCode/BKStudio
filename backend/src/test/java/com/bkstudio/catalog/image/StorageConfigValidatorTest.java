package com.bkstudio.catalog.image;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StorageConfigValidatorTest {

    private static StorageProperties props(String endpoint, String access, String secret, String bucket) {
        return new StorageProperties(endpoint, "auto", bucket, access, secret, false);
    }

    @Test
    void validConfig_passes() {
        assertThatCode(() -> StorageConfigValidator.validate(props("https://x.r2.example.com", "a", "s", "b")))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidConfigs_failFast() {
        for (StorageProperties bad : new StorageProperties[] {
            props("http://x.example.com", "a", "s", "b"),
            props("", "a", "s", "b"),
            props(null, "a", "s", "b"),
            props("https://x", null, "s", "b"),
            props("https://x", " ", "s", "b"),
            props("https://x", "a", "", "b"),
            props("https://x", "a", "s", " "),
        }) {
            assertThatThrownBy(() -> StorageConfigValidator.validate(bad)).isInstanceOf(IllegalStateException.class);
        }
    }
}
