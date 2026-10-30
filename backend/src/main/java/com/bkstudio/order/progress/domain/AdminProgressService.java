package com.bkstudio.order.progress.domain;

import com.bkstudio.catalog.admin.domain.AdminImageService;
import com.bkstudio.catalog.image.StoredImage;
import com.bkstudio.notification.domain.OutboxWriter;
import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import com.bkstudio.order.progress.api.ProgressDtos.Approved;
import com.bkstudio.order.progress.domain.ProgressReader.Row;
import com.bkstudio.order.state.OrderStatus;
import com.bkstudio.shared.audit.AuditLogWriter;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admin approval of a production stage (RAW_PRINT, PREP, PAINT) of a made-to-order order in IN_PRODUCTION.
 * Deliberately NOT transactional as a whole: photo processing runs outside the DB transaction (like
 * {@link AdminImageService}); only the short insert runs in a {@link TransactionTemplate}. This path locks only
 * customer_order (never payment_request) and does not touch customer_order.version: approving a stage is not a
 * state transition, so a stale admin tab is not forced to reload.
 */
@Service
public class AdminProgressService {

    static final int MAX_FILES = 5;
    static final int MAX_NOTE = 500;

    private static final Logger log = LoggerFactory.getLogger(AdminProgressService.class);

    /** Opens one uploaded file lazily, so a request rejected by the pre-checks never touches the temp files. */
    @FunctionalInterface
    public interface Upload {
        InputStream open() throws IOException;
    }

    private record Head(String type, String status) {}

    private record Locked(String code, String email, String type, String status) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AdminImageService images;
    private final OutboxWriter outbox;
    private final AuditLogWriter audit;
    private final ProgressReader reader;

    AdminProgressService(
            JdbcTemplate jdbc,
            PlatformTransactionManager txManager,
            Clock clock,
            AdminImageService images,
            OutboxWriter outbox,
            AuditLogWriter audit,
            ProgressReader reader) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.images = images;
        this.outbox = outbox;
        this.audit = audit;
        this.reader = reader;
    }

    public Approved approve(long orderId, long adminId, String stageRaw, String noteRaw, List<Upload> files) {
        ProgressStage stage = ProgressStage.parse(stageRaw);
        String note = note(noteRaw);
        List<Upload> uploads = files == null ? List.of() : files;
        if (uploads.size() > MAX_FILES) {
            throw ApiException.invalidField("files", "At most " + MAX_FILES + " photos per stage.");
        }
        Head head = head(orderId);
        Map<String, Row> approved = reader.load(orderId);
        if (approved.containsKey(stage.name())) {
            return replay(orderId, stage); // replay stays valid after the order left IN_PRODUCTION
        }
        requireInProduction(head.type(), head.status());
        requirePrevious(
                stage, approved.containsKey(stage.previous().map(Enum::name).orElse("")));

        List<StoredImage> stored = new ArrayList<>();
        try {
            for (Upload u : uploads) {
                try {
                    stored.add(images.storeProgress(u.open()));
                } catch (IOException e) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unreadable upload.");
                }
            }
            Boolean inserted = tx.execute(status -> insert(orderId, adminId, stage, note, stored));
            if (!Boolean.TRUE.equals(inserted)) {
                // Lost a race to another approval of the same stage: its photos win, ours are not referenced.
                deleteAll(stored);
                return replay(orderId, stage);
            }
        } catch (RuntimeException e) {
            // shortcut: if the commit outcome is unknown (connection lost after COMMIT) or an Error escapes, photos
            // may be orphaned or deleted although referenced; accept until it matters.
            deleteAll(stored);
            throw e;
        }
        return view(orderId, stage, false);
    }

    /** One short transaction. Returns false when the stage row already existed. */
    private boolean insert(long orderId, long adminId, ProgressStage stage, String note, List<StoredImage> stored) {
        // Row lock on customer_order only: serialises concurrent approvals and status changes of this order.
        List<Locked> rows = jdbc.query(
                "SELECT public_code, customer_email, fulfillment_type, status FROM customer_order"
                        + " WHERE id = ? FOR UPDATE",
                (rs, i) -> new Locked(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                orderId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        Locked o = rows.get(0);
        requireInProduction(o.type(), o.status());
        if (stage.previous().isPresent()) {
            Integer prev = jdbc.queryForObject(
                    "SELECT count(*) FROM order_progress WHERE order_id = ? AND stage = ?",
                    Integer.class,
                    orderId,
                    stage.previous().get().name());
            requirePrevious(stage, prev != null && prev > 0);
        }
        Instant now = clock.instant();
        int n = jdbc.update(
                "INSERT INTO order_progress (order_id, stage, note, approved_at, approved_by) VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT (order_id, stage) DO NOTHING",
                orderId,
                stage.name(),
                note,
                Timestamp.from(now),
                adminId);
        if (n == 0) {
            return false;
        }
        for (int i = 0; i < stored.size(); i++) {
            StoredImage s = stored.get(i);
            jdbc.update(
                    "INSERT INTO order_progress_image (order_id, stage, storage_key, width, height, sort_order,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    orderId,
                    stage.name(),
                    s.storageKey(),
                    s.width(),
                    s.height(),
                    i,
                    Timestamp.from(now));
        }
        // Audit never holds the note text, only its length.
        audit.admin(
                "PROGRESS_APPROVED",
                "ORDER",
                orderId,
                null,
                Map.of("stage", stage.name(), "imageCount", stored.size(), "noteLength", note.length()));
        outbox.enqueue(
                "order:" + orderId + ":progress:" + stage.name(),
                o.email().trim(),
                "ORDER_PROGRESS",
                Map.of("publicCode", o.code(), "stage", stage.name()));
        return true;
    }

    private Approved replay(long orderId, ProgressStage stage) {
        return view(orderId, stage, true);
    }

    private Approved view(long orderId, ProgressStage stage, boolean replay) {
        Row r = reader.load(orderId).get(stage.name());
        return new Approved(stage.name(), true, r.approvedAt(), r.note(), r.images(), replay);
    }

    private Head head(long orderId) {
        List<Head> rows = jdbc.query(
                "SELECT fulfillment_type, status FROM customer_order WHERE id = ?",
                (rs, i) -> new Head(rs.getString(1), rs.getString(2)),
                orderId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.get(0);
    }

    private static void requireInProduction(String type, String status) {
        if (!FulfillmentType.MADE_TO_ORDER.name().equals(type)
                || !OrderStatus.IN_PRODUCTION.name().equals(status)) {
            throw new ApiException(
                    ErrorCode.INVALID_TRANSITION, "Progress can only be approved for orders in production.");
        }
    }

    private static void requirePrevious(ProgressStage stage, boolean previousApproved) {
        if (stage.previous().isPresent() && !previousApproved) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "The previous stage must be approved first.");
        }
    }

    private static String note(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.length() > MAX_NOTE || t.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')) {
            throw ApiException.invalidField(
                    "note", "note must be at most " + MAX_NOTE + " characters without control characters.");
        }
        return t;
    }

    private void deleteAll(List<StoredImage> stored) {
        for (StoredImage s : stored) {
            images.deleteFiles(s.storageKey()); // best effort; logs a WARN without PII
        }
        if (!stored.isEmpty()) {
            log.warn("Progress photos discarded count={}", stored.size());
        }
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Order not found.");
    }
}
