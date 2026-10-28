package com.bkstudio.shared.settings.api;

import com.bkstudio.shared.settings.api.SettingsDtos.PublishTerms;
import com.bkstudio.shared.settings.api.SettingsDtos.PublishedTerms;
import com.bkstudio.shared.settings.api.SettingsDtos.Settings;
import com.bkstudio.shared.settings.api.SettingsDtos.Social;
import com.bkstudio.shared.settings.api.SettingsDtos.Terms;
import com.bkstudio.shared.settings.domain.SettingsService;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public site-info/terms plus admin settings/terms. /api/admin/** is already ROLE_ADMIN + CSRF (SecurityConfig). */
@RestController
public class SettingsController {

    private static final CacheControl PUBLIC_60S =
            CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/api/site-info")
    public ResponseEntity<Social> siteInfo() {
        return ResponseEntity.ok().cacheControl(PUBLIC_60S).body(settings.siteInfo());
    }

    @GetMapping("/api/terms/current")
    public ResponseEntity<Terms> currentTerms() {
        return ResponseEntity.ok().cacheControl(PUBLIC_60S).body(settings.currentTerms());
    }

    @GetMapping("/api/admin/settings")
    public Settings get() {
        return settings.get();
    }

    @PutMapping("/api/admin/settings")
    public Settings update(@Valid @RequestBody Settings req) {
        return settings.update(req);
    }

    @PostMapping("/api/admin/terms")
    @ResponseStatus(HttpStatus.CREATED)
    public PublishedTerms publish(@Valid @RequestBody PublishTerms req) {
        return settings.publishTerms(req);
    }
}
