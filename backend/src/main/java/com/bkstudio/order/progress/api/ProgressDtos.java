package com.bkstudio.order.progress.api;

import java.time.Instant;
import java.util.List;

/** Shapes of the order progress API (API_SPEC section 2 and 4). No ids, storage keys or admin data. */
public final class ProgressDtos {

    private ProgressDtos() {}

    /** One WIP photo (the 1000 px rendition). */
    public record Photo(String url, int width, int height) {}

    /** Admin response of an approval; {@code idempotentReplay} is true when the stage was already approved. */
    public record Approved(
            String stage,
            boolean approved,
            Instant approvedAt,
            String note,
            List<Photo> images,
            boolean idempotentReplay) {}

    /** {@code state} is DONE, CURRENT or PENDING. approvedAt/note/images only for RAW_PRINT, PREP and PAINT. */
    public record StageView(
            String stage,
            String label,
            String explanation,
            String state,
            Instant approvedAt,
            String note,
            List<Photo> images) {}

    public record ProgressView(List<StageView> stages) {}
}
