package com.bkstudio.queue.domain;

import com.bkstudio.queue.api.QueueDtos;
import com.bkstudio.queue.api.QueueDtos.ProductionMonth;
import com.bkstudio.queue.api.QueueDtos.QueueEntry;
import com.bkstudio.queue.api.QueueDtos.QueueList;
import com.bkstudio.queue.infra.QueueRepository;
import com.bkstudio.queue.infra.QueueRepository.MonthRow;
import com.bkstudio.shared.audit.AuditLogWriter;
import com.bkstudio.shared.settings.domain.SettingsService;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Monthly made-to-order slots: allocation without overbooking, release, estimate and admin capacity. */
@Service
@Transactional
public class QueueService {

    /** Search window: current month + 23 months. */
    public static final int WINDOW_MONTHS = 24;

    public static final int MAX_QTY = 5;

    private static final Pattern YEAR_MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_PAUSE_MS = 20;

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    public record Allocation(int unitIndex, LocalDate month) {}

    private final QueueRepository repo;
    private final SettingsService settings;
    private final AuditLogWriter audit;
    private final Clock clock;

    QueueService(QueueRepository repo, SettingsService settings, AuditLogWriter audit, Clock clock) {
        this.repo = repo;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Gives each unit the earliest month with a free slot. A month row is locked FOR UPDATE SKIP LOCKED before it is
     * incremented, so two callers can never both take the last slot, and neither waits for the other: a locked month
     * is skipped and the next one tried. When every free month is locked by others the pick is retried after a short
     * pause. All-or-nothing: QUEUE_FULL rolls the whole transaction back.
     *
     * <p>Call at most once per order item; a second call collides with uq_queue_entry_item_unit (T-022 must make
     * deposit confirmation idempotent).
     */
    public List<Allocation> allocate(long orderItemId, int units) {
        if (units < 1 || units > MAX_QTY) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "units must be between 1 and " + MAX_QTY + ".");
        }
        LocalDate from = currentMonth();
        LocalDate to = from.plusMonths(WINDOW_MONTHS - 1);
        repo.ensureMonths(from, to, settings.defaultCapacity());
        Instant now = clock.instant();
        List<Allocation> out = new ArrayList<>();
        for (int unit = 1; unit <= units; unit++) {
            LocalDate month = lockFreeMonth(from, to);
            if (repo.incrementAllocated(month) != 1) {
                throw new IllegalStateException("Locked month was not incrementable: " + format(month));
            }
            repo.insertEntry(orderItemId, unit, month, now);
            out.add(new Allocation(unit, month));
        }
        return out;
    }

    private LocalDate lockFreeMonth(LocalDate from, LocalDate to) {
        for (int attempt = 1; ; attempt++) {
            Optional<LocalDate> month = repo.lockEarliestFree(from, to);
            if (month.isPresent()) {
                return month.get();
            }
            // Nothing lockable: truly full, or the free months are locked by concurrent callers.
            if (!repo.anyFree(from, to)) {
                throw queueFull();
            }
            if (attempt >= MAX_ATTEMPTS) {
                // Last resort: wait for the lock. Multi-unit callers may wait on each other (deadlock), so the
                // repo bounds the wait with lock_timeout and a timeout is reported as QUEUE_FULL.
                try {
                    return repo.lockEarliestFreeBlocking(from, to).orElseThrow(QueueService::queueFull);
                } catch (PessimisticLockingFailureException e) {
                    throw queueFull();
                }
            }
            try {
                Thread.sleep(RETRY_PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(ErrorCode.QUEUE_FULL, "Slot allocation was interrupted.");
            }
        }
    }

    private static ApiException queueFull() {
        return new ApiException(ErrorCode.QUEUE_FULL, "No production slot is available in the next 24 months.");
    }

    /** Cancels the open entries of an item and frees their slots (allocated never goes below 0). */
    public void release(long orderItemId) {
        Map<LocalDate, Integer> perMonth = new TreeMap<>(); // ascending: stable lock order
        for (LocalDate m : repo.cancelOpenEntries(orderItemId)) {
            perMonth.merge(m, 1, Integer::sum);
        }
        perMonth.forEach((month, by) -> repo.decrementAllocated(month, by)
                .filter(before -> before < by)
                .ifPresent(before ->
                        log.warn("Queue release clamped at 0: month={} allocated={} release={}", month, before, by)));
    }

    /**
     * Earliest "yyyy-MM" with qty free slots in the window; read-only, never creates rows. For qty &gt; 1 this wants ONE
     * month holding all qty free slots, while {@link #allocate} may spread units over months; display-only.
     */
    @Transactional(readOnly = true)
    public Optional<String> estimate(int qty) {
        if (qty < 1 || qty > MAX_QTY) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "qty must be between 1 and " + MAX_QTY + ".");
        }
        LocalDate from = currentMonth();
        return repo.earliestWithFree(from, from.plusMonths(WINDOW_MONTHS - 1), qty, settings.defaultCapacity())
                .map(QueueService::format);
    }

    /** Capacity may be raised or lowered, but not below what is already allocated (409 CAPACITY_BELOW_ALLOCATED). */
    public ProductionMonth adminSetCapacity(String monthText, int capacity) {
        LocalDate month = parseMonth(monthText, "month");
        if (capacity < 0 || capacity > QueueDtos.MAX_CAPACITY) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "capacity must be between 0 and " + QueueDtos.MAX_CAPACITY + ".");
        }
        if (month.isBefore(currentMonth())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "month must not be in the past.");
        }
        repo.insertMonthIfAbsent(month, settings.defaultCapacity());
        MonthRow current = repo.find(month).orElseThrow();
        if (current.capacity() == capacity) {
            return toDto(current);
        }
        int old = repo.setCapacity(month, capacity)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.CAPACITY_BELOW_ALLOCATED, "Capacity is lower than the slots already allocated."));
        if (old != capacity) {
            audit.admin(
                    "PRODUCTION_CAPACITY_UPDATED",
                    "PRODUCTION_MONTH",
                    month.getYear() * 100L + month.getMonthValue(),
                    null,
                    Map.of("month", format(month), "oldCapacity", old, "newCapacity", capacity));
        }
        return toDto(repo.find(month).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<ProductionMonth> listMonths(String fromText, String toText) {
        LocalDate from = fromText == null ? currentMonth() : parseMonth(fromText, "from");
        LocalDate to = toText == null ? from.plusMonths(11) : parseMonth(toText, "to");
        if (to.isBefore(from) || ChronoUnit.MONTHS.between(from, to) >= WINDOW_MONTHS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "to must not be before from, and the range is at most 24 months.");
        }
        return repo.listRange(from, to, settings.defaultCapacity()).stream()
                .map(QueueService::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public QueueList entries(String monthText) {
        LocalDate month = parseMonth(monthText, "month");
        List<QueueEntry> list = repo.entries(month).stream()
                .map(e -> new QueueEntry(e.id(), e.orderItemId(), e.unitIndex(), e.status(), e.queuedAt()))
                .toList();
        return new QueueList(format(month), list);
    }

    private LocalDate currentMonth() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
    }

    private static LocalDate parseMonth(String text, String field) {
        if (text == null || !YEAR_MONTH.matcher(text).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " must be formatted yyyy-MM.");
        }
        return YearMonth.parse(text).atDay(1);
    }

    private static String format(LocalDate month) {
        return YearMonth.from(month).toString();
    }

    private static ProductionMonth toDto(MonthRow r) {
        return new ProductionMonth(format(r.month()), r.capacity(), r.allocated());
    }
}
