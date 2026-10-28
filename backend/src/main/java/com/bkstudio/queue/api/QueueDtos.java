package com.bkstudio.queue.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** Shapes of the queue and production-month API (API_SPEC sections 2 and 5). Months are "yyyy-MM" strings. */
public final class QueueDtos {

    /** Hard upper bound for one month's capacity; the real default is a handful of units. */
    public static final int MAX_CAPACITY = 1000;

    private QueueDtos() {}

    public record Estimate(String startMonth) {}

    public record SetCapacity(
            @NotNull @Min(0) @Max(MAX_CAPACITY) Integer capacity) {}

    public record ProductionMonth(String month, int capacity, int allocated) {}

    public record QueueEntry(long id, long orderItemId, int unitIndex, String status, Instant queuedAt) {}

    public record QueueList(String month, List<QueueEntry> entries) {}
}
