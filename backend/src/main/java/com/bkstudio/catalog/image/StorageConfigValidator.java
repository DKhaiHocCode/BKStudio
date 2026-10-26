package com.bkstudio.catalog.image;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fails startup in prod when object storage is misconfigured, instead of failing on the first upload. */
@Component
@Profile("prod")
class StorageConfigValidator {

    private final StorageProperties props;

    StorageConfigValidator(StorageProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        validate(props);
    }

    static void validate(StorageProperties p) {
        if (isBlank(p.endpoint()) || !p.endpoint().startsWith("https://")) {
            throw new IllegalStateException("app.storage.endpoint must be set and start with https://");
        }
        if (isBlank(p.accessKey()) || isBlank(p.secretKey()) || isBlank(p.bucket())) {
            throw new IllegalStateException("app.storage access-key, secret-key and bucket must be set");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
