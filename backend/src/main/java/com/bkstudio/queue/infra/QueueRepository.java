package com.bkstudio.queue.infra;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain-SQL access to production_month and queue_entry. No transaction of its own: the service owns it. */
@Repository
public class QueueRepository {

    public record MonthRow(LocalDate month, int capacity, int allocated) {}

    public record EntryRow(long id, long orderItemId, int unitIndex, String status, Instant queuedAt) {}

    private final JdbcTemplate jdbc;

    QueueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates every missing month in [from, to] with the default capacity; existing rows are untouched. */
    public void ensureMonths(LocalDate from, LocalDate to, int defaultCapacity) {
        jdbc.update(
                "INSERT INTO production_month (month, capacity)"
                        + " SELECT g::date, ? FROM generate_series(?::timestamp, ?::timestamp, interval '1 month') g"
                        + " ON CONFLICT (month) DO NOTHING",
                defaultCapacity,
                Date.valueOf(from),
                Date.valueOf(to));
    }

    /**
     * Locks and returns the earliest month in [from, to] with a free slot. SKIP LOCKED: rows locked by another
     * transaction are passed over instead of waited for, so concurrent allocators never block each other.
     */
    public Optional<LocalDate> lockEarliestFree(LocalDate from, LocalDate to) {
        return jdbc
                .query(
                        "SELECT month FROM production_month WHERE month BETWEEN ? AND ? AND allocated < capacity"
                                + " ORDER BY month LIMIT 1 FOR UPDATE SKIP LOCKED",
                        (rs, i) -> rs.getObject(1, LocalDate.class),
                        Date.valueOf(from),
                        Date.valueOf(to))
                .stream()
                .findFirst();
    }

    /** True when some month in range still has a free slot (locked or not); used to decide to retry. */
    public boolean anyFree(LocalDate from, LocalDate to) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM production_month WHERE month BETWEEN ? AND ? AND allocated < capacity)",
                Boolean.class,
                Date.valueOf(from),
                Date.valueOf(to)));
    }

    /**
     * Like {@link #lockEarliestFree} but waits for a locked row instead of skipping it. Bounded by a 2s lock_timeout
     * (lock_timeout resets afterwards); a timeout (55P03) or detected deadlock (40P01) surfaces as PessimisticLockingFailureException.
     */
    public Optional<LocalDate> lockEarliestFreeBlocking(LocalDate from, LocalDate to) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        Optional<LocalDate> month = jdbc
                .query(
                        "SELECT month FROM production_month WHERE month BETWEEN ? AND ? AND allocated < capacity"
                                + " ORDER BY month LIMIT 1 FOR UPDATE",
                        (rs, i) -> rs.getObject(1, LocalDate.class),
                        Date.valueOf(from),
                        Date.valueOf(to))
                .stream()
                .findFirst();
        jdbc.execute("SET LOCAL lock_timeout = DEFAULT");
        return month;
    }

    /** Returns the number of rows updated: 1, or 0 when the month is already full. */
    public int incrementAllocated(LocalDate month) {
        return jdbc.update(
                "UPDATE production_month SET allocated = allocated + 1 WHERE month = ? AND allocated < capacity",
                Date.valueOf(month));
    }

    public void insertEntry(long orderItemId, int unitIndex, LocalDate month, Instant queuedAt) {
        jdbc.update(
                "INSERT INTO queue_entry (order_item_id, unit_index, production_month, queued_at, status)"
                        + " VALUES (?, ?, ?, ?, 'QUEUED')",
                orderItemId,
                unitIndex,
                Date.valueOf(month),
                OffsetDateTime.ofInstant(queuedAt, ZoneOffset.UTC));
    }

    /** Cancels the open entries of an item and returns the month of each cancelled entry (one element per entry). */
    public List<LocalDate> cancelOpenEntries(long orderItemId) {
        return jdbc.query(
                "UPDATE queue_entry SET status = 'CANCELLED' WHERE order_item_id = ?"
                        + " AND status IN ('QUEUED','IN_PRODUCTION') RETURNING production_month",
                (rs, i) -> rs.getObject(1, LocalDate.class),
                orderItemId);
    }

    /** Frees slots, clamped at 0. Returns the allocated count before the update (empty if the month has no row). */
    public Optional<Integer> decrementAllocated(LocalDate month, int by) {
        return jdbc
                .query(
                        "UPDATE production_month pm SET allocated = GREATEST(o.allocated - ?, 0)"
                                + " FROM (SELECT month, allocated FROM production_month WHERE month = ? FOR UPDATE) o"
                                + " WHERE pm.month = o.month RETURNING o.allocated",
                        (rs, i) -> rs.getInt(1),
                        by,
                        Date.valueOf(month))
                .stream()
                .findFirst();
    }

    /** Earliest month in [from, to] with at least qty free slots; a month without a row counts as default-capacity free. */
    public Optional<LocalDate> earliestWithFree(LocalDate from, LocalDate to, int qty, int defaultCapacity) {
        return jdbc
                .query(
                        "SELECT g::date FROM generate_series(?::timestamp, ?::timestamp, interval '1 month') g"
                                + " LEFT JOIN production_month pm ON pm.month = g::date"
                                + " WHERE COALESCE(pm.capacity, ?) - COALESCE(pm.allocated, 0) >= ?"
                                + " ORDER BY g LIMIT 1",
                        (rs, i) -> rs.getObject(1, LocalDate.class),
                        Date.valueOf(from),
                        Date.valueOf(to),
                        defaultCapacity,
                        qty)
                .stream()
                .findFirst();
    }

    /** Existing rows plus virtual default rows for months without one. */
    public List<MonthRow> listRange(LocalDate from, LocalDate to, int defaultCapacity) {
        return jdbc.query(
                "SELECT g::date, COALESCE(pm.capacity, ?), COALESCE(pm.allocated, 0)"
                        + " FROM generate_series(?::timestamp, ?::timestamp, interval '1 month') g"
                        + " LEFT JOIN production_month pm ON pm.month = g::date ORDER BY g",
                (rs, i) -> new MonthRow(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getInt(3)),
                defaultCapacity,
                Date.valueOf(from),
                Date.valueOf(to));
    }

    public Optional<MonthRow> find(LocalDate month) {
        return jdbc
                .query(
                        "SELECT month, capacity, allocated FROM production_month WHERE month = ?",
                        (rs, i) -> new MonthRow(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getInt(3)),
                        Date.valueOf(month))
                .stream()
                .findFirst();
    }

    public void insertMonthIfAbsent(LocalDate month, int capacity) {
        jdbc.update(
                "INSERT INTO production_month (month, capacity) VALUES (?, ?) ON CONFLICT (month) DO NOTHING",
                Date.valueOf(month),
                capacity);
    }

    /**
     * Atomic: locks the row, sets capacity only when allocated <= newCapacity, returns the previous capacity.
     * Empty = refused (allocated is higher); the CHECK constraint is the last line of defence.
     */
    public Optional<Integer> setCapacity(LocalDate month, int newCapacity) {
        return jdbc
                .query(
                        "UPDATE production_month pm SET capacity = ?"
                                + " FROM (SELECT month, capacity AS old FROM production_month WHERE month = ?"
                                + " FOR UPDATE) o WHERE pm.month = o.month AND pm.allocated <= ? RETURNING o.old",
                        (rs, i) -> rs.getInt(1),
                        newCapacity,
                        Date.valueOf(month),
                        newCapacity)
                .stream()
                .findFirst();
    }

    public List<EntryRow> entries(LocalDate month) {
        return jdbc.query(
                "SELECT id, order_item_id, unit_index, status, queued_at FROM queue_entry"
                        + " WHERE production_month = ? ORDER BY queued_at, id",
                (rs, i) -> new EntryRow(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getInt(3),
                        rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class).toInstant()),
                Date.valueOf(month));
    }
}
