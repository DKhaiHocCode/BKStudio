package com.bkstudio.shared.settings.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Shapes of the settings, site-info and terms API (API_SPEC sections 2 and 3). */
public final class SettingsDtos {

    /** Lowercase hostname, labels [a-z0-9-], at least one dot, no scheme/path/port/wildcard, at most 253 chars. */
    public static final String HOSTNAME_REGEX =
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$";

    public static final String TERMS_VERSION_REGEX = "^[0-9]{4}-[0-9]{2}-[0-9]{2}(-[0-9]{1,3})?$";

    private SettingsDtos() {}

    /** Request and response of /api/admin/settings. */
    public record Settings(
            @NotNull @Min(1) @Max(50) Integer defaultCapacity,
            @NotNull @Size(max = 20) List<@NotNull @Pattern(regexp = HOSTNAME_REGEX) String> paymentAllowedDomains,
            @NotNull @Valid Social social) {}

    public record Social(
            @SocialUrl(hosts = {"reddit.com"}) String reddit,
            @SocialUrl(hosts = {"facebook.com"}) String facebook,
            @SocialUrl(hosts = {"instagram.com"}) String instagram,
            @SocialUrl(hosts = {"x.com", "twitter.com"}) String x) {}

    public record PublishTerms(
            @Pattern(regexp = TERMS_VERSION_REGEX) String version,
            @NotBlank @Size(max = 50_000) String contentMd) {}

    public record Terms(String version, String contentMd) {}

    public record PublishedTerms(String version, String contentMd, Instant publishedAt) {}
}
