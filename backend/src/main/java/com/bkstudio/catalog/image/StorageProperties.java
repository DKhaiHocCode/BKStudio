package com.bkstudio.catalog.image;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** S3-compatible object storage (Cloudflare R2 in prod, MinIO locally). Credentials come from env only. */
@Validated
@ConfigurationProperties("app.storage")
public record StorageProperties(
        @NotBlank String endpoint,
        @NotBlank String region,
        @NotBlank String bucket,
        String accessKey,
        String secretKey,
        boolean pathStyle) {

    // Keep secrets out of logs / actuator dumps.
    @Override
    public String toString() {
        return "StorageProperties[endpoint=" + endpoint + ", bucket=" + bucket + "]";
    }
}
