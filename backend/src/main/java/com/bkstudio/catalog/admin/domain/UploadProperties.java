package com.bkstudio.catalog.admin.domain;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Image-processing limit per instance: {@code maxConcurrent} at once, others wait up to {@code maxWait} then get 429. */
@Validated
@ConfigurationProperties("app.admin.upload")
public record UploadProperties(
        @DefaultValue("2") @Min(1) int maxConcurrent,
        @DefaultValue("5s") @NotNull Duration maxWait) {}
