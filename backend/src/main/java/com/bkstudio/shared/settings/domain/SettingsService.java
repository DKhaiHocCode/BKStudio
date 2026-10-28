package com.bkstudio.shared.settings.domain;

import com.bkstudio.shared.audit.AuditLogWriter;
import com.bkstudio.shared.settings.api.SettingsDtos.PublishTerms;
import com.bkstudio.shared.settings.api.SettingsDtos.PublishedTerms;
import com.bkstudio.shared.settings.api.SettingsDtos.Settings;
import com.bkstudio.shared.settings.api.SettingsDtos.Social;
import com.bkstudio.shared.settings.api.SettingsDtos.Terms;
import com.bkstudio.shared.settings.infra.SettingsRepository;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Site settings (app_setting) and immutable terms documents. */
@Service
@Transactional
public class SettingsService {

    static final String CAPACITY = "default_capacity";
    static final String DOMAINS = "payment_allowed_domains";
    static final String REDDIT = "social_reddit";
    static final String FACEBOOK = "social_facebook";
    static final String INSTAGRAM = "social_instagram";
    static final String X = "social_x";
    static final int DEFAULT_CAPACITY = 5;
    /** Serialises settings updates so the audit "before" is always the previous committed state. */
    static final long UPDATE_LOCK = 20_001L;

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final SettingsRepository repo;
    private final AuditLogWriter audit;
    private final Clock clock;

    SettingsService(SettingsRepository repo, AuditLogWriter audit, Clock clock) {
        this.repo = repo;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Settings get() {
        return toSettings(repo.findAll());
    }

    /** Monthly made-to-order capacity default (1..50); 5 when unset or corrupt. */
    @Transactional(readOnly = true)
    public int defaultCapacity() {
        return get().defaultCapacity();
    }

    /** Public subset: social links only, nothing else from app_setting. */
    @Transactional(readOnly = true)
    public Social siteInfo() {
        return get().social();
    }

    /** Full replace of the three groups. Domains are de-duplicated (order kept); blank social URL = unset. */
    public Settings update(Settings req) {
        repo.advisoryLock(UPDATE_LOCK);
        Map<String, String> rows = repo.findAll();
        Settings before = toSettings(rows);
        Settings next = new Settings(
                req.defaultCapacity(),
                List.copyOf(new LinkedHashSet<>(req.paymentAllowedDomains())),
                new Social(
                        blankToNull(req.social().reddit()),
                        blankToNull(req.social().facebook()),
                        blankToNull(req.social().instagram()),
                        blankToNull(req.social().x())));
        // A corrupt stored capacity reads as the default, so an equal PUT must still repair the row.
        if (next.equals(before) && !isCorruptCapacity(rows.get(CAPACITY))) {
            return before;
        }
        Instant now = clock.instant();
        repo.upsert(CAPACITY, String.valueOf(next.defaultCapacity()), now);
        // Hostnames cannot contain a comma, so a comma-separated value round-trips ("" = empty list).
        repo.upsert(DOMAINS, String.join(",", next.paymentAllowedDomains()), now);
        putOrDelete(REDDIT, next.social().reddit(), now);
        putOrDelete(FACEBOOK, next.social().facebook(), now);
        putOrDelete(INSTAGRAM, next.social().instagram(), now);
        putOrDelete(X, next.social().x(), now);
        audit.admin("SETTINGS_UPDATED", "SETTINGS", 0, fields(before), fields(next));
        return next;
    }

    @Transactional(readOnly = true)
    public Terms currentTerms() {
        return repo.latestTerms()
                .map(t -> new Terms(t.version(), t.contentMd()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No terms published yet."));
    }

    public PublishedTerms publishTerms(PublishTerms req) {
        Instant now = clock.instant();
        String version = req.version() != null
                ? req.version()
                : LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        try {
            LocalDate.parse(version.substring(0, 10));
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "version must start with a valid YYYY-MM-DD date.");
        }
        // Postgres text rejects NUL; fail as a 400 instead of a 500.
        if (req.contentMd().indexOf('\u0000') >= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "contentMd contains an invalid character.");
        }
        if (!repo.insertTerms(version, req.contentMd(), now)) {
            throw new ApiException(ErrorCode.CONFLICT, "Terms version " + version + " already exists.");
        }
        audit.admin(
                "TERMS_PUBLISHED",
                "TERMS",
                0,
                null,
                Map.of("version", version, "contentLength", req.contentMd().length()));
        return new PublishedTerms(version, req.contentMd(), now);
    }

    private Settings toSettings(Map<String, String> rows) {
        String domains = rows.getOrDefault(DOMAINS, "");
        return new Settings(
                parseCapacity(rows.get(CAPACITY)),
                domains.isEmpty() ? List.of() : List.of(domains.split(",")),
                new Social(rows.get(REDDIT), rows.get(FACEBOOK), rows.get(INSTAGRAM), rows.get(X)));
    }

    private static int parseCapacity(String v) {
        if (isCorruptCapacity(v)) {
            log.warn("Stored setting {} is invalid, using the default", CAPACITY);
            return DEFAULT_CAPACITY;
        }
        return v == null ? DEFAULT_CAPACITY : Integer.parseInt(v);
    }

    /** True when a stored value exists but is not an integer in 1..50. */
    private static boolean isCorruptCapacity(String v) {
        if (v == null) {
            return false;
        }
        try {
            int n = Integer.parseInt(v);
            return n < 1 || n > 50;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private void putOrDelete(String key, String value, Instant now) {
        if (value == null) {
            repo.delete(key);
        } else {
            repo.upsert(key, value, now);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Map<String, Object> fields(Settings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defaultCapacity", s.defaultCapacity());
        m.put("paymentAllowedDomains", s.paymentAllowedDomains());
        m.put("reddit", s.social().reddit());
        m.put("facebook", s.social().facebook());
        m.put("instagram", s.social().instagram());
        m.put("x", s.social().x());
        return m;
    }
}
