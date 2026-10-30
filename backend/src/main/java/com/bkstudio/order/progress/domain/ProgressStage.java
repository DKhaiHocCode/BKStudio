package com.bkstudio.order.progress.domain;

import com.bkstudio.shared.web.ApiException;
import java.util.Optional;

/** The three admin-approved production stages, in order. The other customer-facing stages are derived. */
public enum ProgressStage {
    RAW_PRINT,
    PREP,
    PAINT;

    public Optional<ProgressStage> previous() {
        return ordinal() == 0 ? Optional.empty() : Optional.of(values()[ordinal() - 1]);
    }

    /** Anything else (including a customer-only stage name) is a 400. */
    public static ProgressStage parse(String raw) {
        for (ProgressStage s : values()) {
            if (s.name().equals(raw)) {
                return s;
            }
        }
        throw ApiException.invalidField("stage", "stage must be one of RAW_PRINT, PREP, PAINT.");
    }
}
