package com.bkstudio.order.admin.domain;

import com.bkstudio.order.admin.api.AdminOrderDtos.ActionResult;
import com.bkstudio.order.admin.api.AdminOrderDtos.Customer;
import com.bkstudio.order.admin.api.AdminOrderDtos.Detail;
import com.bkstudio.order.admin.api.AdminOrderDtos.Item;
import com.bkstudio.order.admin.api.AdminOrderDtos.ListItem;
import com.bkstudio.order.admin.api.AdminOrderDtos.NoteView;
import com.bkstudio.order.admin.api.AdminOrderDtos.OrderPage;
import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import com.bkstudio.order.checkout.infra.CheckoutRepository;
import com.bkstudio.order.state.OrderAction;
import com.bkstudio.order.state.OrderActor;
import com.bkstudio.order.state.OrderStateMachine;
import com.bkstudio.order.state.OrderStateMachine.Details;
import com.bkstudio.order.state.OrderStatus;
import com.bkstudio.order.state.OrderTransitions;
import com.bkstudio.payment.domain.PaymentRequestService;
import com.bkstudio.queue.domain.QueueService;
import com.bkstudio.shared.audit.AuditLogWriter;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin order list/detail and the shop-side actions (WORKFLOWS §1-§3). Status changes go through
 * {@link OrderStateMachine}; the actor is always ADMIN and the version always comes from the client.
 */
@Service
@Transactional
public class AdminOrderService {

    static final int PAGE_SIZE = 20;
    static final int MAX_PAGE = 10_000;

    private static final Set<OrderStatus> REFUND_STATUSES =
            Set.of(OrderStatus.QUEUED, OrderStatus.IN_PRODUCTION, OrderStatus.PAID_IN_FULL);

    private record OrderHead(FulfillmentType type, OrderStatus status) {}

    private record StockItem(long id, long variantId, int quantity) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OrderStateMachine machine;
    private final PaymentRequestService payments;
    private final QueueService queue;
    private final CheckoutRepository checkoutRepo;
    private final AuditLogWriter audit;

    AdminOrderService(
            JdbcTemplate jdbc,
            Clock clock,
            OrderStateMachine machine,
            PaymentRequestService payments,
            QueueService queue,
            CheckoutRepository checkoutRepo,
            AuditLogWriter audit) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.machine = machine;
        this.payments = payments;
        this.queue = queue;
        this.checkoutRepo = checkoutRepo;
        this.audit = audit;
    }

    // ---- reads ----

    @Transactional(readOnly = true)
    public OrderPage list(OrderStatus status, String q, int page) {
        if (page < 0 || page > MAX_PAGE) {
            throw ApiException.invalidField("page", "page must be between 0 and " + MAX_PAGE + ".");
        }
        String code = q == null ? "" : q.trim();
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE true");
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (!code.isEmpty()) {
            where.append(" AND lower(public_code) = lower(?)");
            args.add(code);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM customer_order" + where, Long.class, args.toArray());
        args.add(PAGE_SIZE);
        args.add((long) page * PAGE_SIZE);
        List<ListItem> items = jdbc.query(
                "SELECT id, public_code, status, fulfillment_type, subtotal_cents, shipping_cents, version, created_at"
                        + " FROM customer_order" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new ListItem(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getInt(5),
                        rs.getInt(6),
                        rs.getInt(7),
                        instant(rs, 8)),
                args.toArray());
        return new OrderPage(items, page, PAGE_SIZE, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public Detail get(long id) {
        List<Detail> found = jdbc.query(
                "SELECT id, public_code, status, fulfillment_type, version, subtotal_cents, shipping_cents, currency,"
                        + " customer_name, customer_email, phone, ship_line1, ship_line2, ship_city, ship_region,"
                        + " ship_postal_code, ship_country, carrier, tracking_number, cancel_reason, admin_note,"
                        + " created_at, updated_at, shipped_at, completed_at FROM customer_order WHERE id = ?",
                (rs, i) -> {
                    OrderStatus status = OrderStatus.valueOf(rs.getString(3));
                    FulfillmentType type = FulfillmentType.valueOf(rs.getString(4));
                    return new Detail(
                            rs.getLong(1),
                            rs.getString(2),
                            status.name(),
                            type.name(),
                            rs.getInt(5),
                            rs.getInt(6),
                            rs.getInt(7),
                            rs.getString(8),
                            new Customer(
                                    rs.getString(9),
                                    rs.getString(10),
                                    rs.getString(11),
                                    rs.getString(12),
                                    rs.getString(13),
                                    rs.getString(14),
                                    rs.getString(15),
                                    rs.getString(16),
                                    rs.getString(17)),
                            null,
                            null,
                            rs.getString(18),
                            rs.getString(19),
                            rs.getString(20),
                            rs.getString(21),
                            instant(rs, 22),
                            instant(rs, 23),
                            instant(rs, 24),
                            instant(rs, 25),
                            allowedActions(type, status));
                },
                id);
        if (found.isEmpty()) {
            throw notFound();
        }
        Detail d = found.get(0);
        List<Item> items = jdbc.query(
                "SELECT id, variant_id, product_name, scale, unit_price_cents, quantity FROM order_item"
                        + " WHERE order_id = ? ORDER BY id",
                (rs, i) -> new Item(
                        rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6)),
                id);
        return new Detail(
                d.id(),
                d.publicCode(),
                d.status(),
                d.fulfillmentType(),
                d.version(),
                d.subtotalCents(),
                d.shippingCents(),
                d.currency(),
                d.customer(),
                items,
                payments.listForOrder(id),
                d.carrier(),
                d.trackingNumber(),
                d.cancelReason(),
                d.adminNote(),
                d.createdAt(),
                d.updatedAt(),
                d.shippedAt(),
                d.completedAt(),
                d.allowedActions());
    }

    static List<String> allowedActions(FulfillmentType type, OrderStatus status) {
        List<String> out = new ArrayList<>();
        if (can(type, status, OrderAction.START_PRODUCTION)) {
            out.add("start-production");
        }
        if (can(type, status, OrderAction.COMPLETE_PRODUCTION)) {
            out.add("mark-finished");
        }
        if (can(type, status, OrderAction.SHIP)) {
            out.add("ship");
        }
        if (can(type, status, OrderAction.COMPLETE)) {
            out.add("complete");
        }
        if (can(type, status, OrderAction.CANCEL) || can(type, status, OrderAction.CANCEL_REFUND)) {
            out.add("cancel-by-shop");
        }
        if (can(type, status, OrderAction.CONFIRM_DEPOSIT)
                || can(type, status, OrderAction.CONFIRM_PAYMENT)
                || can(type, status, OrderAction.CONFIRM_BALANCE)) {
            out.add("create-payment-request");
        }
        return out;
    }

    private static boolean can(FulfillmentType type, OrderStatus status, OrderAction action) {
        return OrderTransitions.next(type, status, action).isPresent();
    }

    // ---- actions ----

    public ActionResult startProduction(long id, int version) {
        return act(id, OrderAction.START_PRODUCTION, version, new Details(null, null));
    }

    /** Does not create the BALANCE payment request: the admin creates it through the payment-request endpoint. */
    public ActionResult markFinished(long id, int version) {
        return act(id, OrderAction.COMPLETE_PRODUCTION, version, new Details(null, null));
    }

    public ActionResult ship(long id, int version, String carrierRaw, String trackingRaw) {
        String carrier = text("carrier", carrierRaw, 50);
        String tracking = text("trackingNumber", trackingRaw, 100);
        return act(id, OrderAction.SHIP, version, new Details(tracking, carrier, null));
    }

    public ActionResult complete(long id, int version) {
        return act(id, OrderAction.COMPLETE, version, new Details(null, null));
    }

    private ActionResult act(long id, OrderAction action, int version, Details details) {
        OrderStatus next = machine.transition(id, action, version, OrderActor.ADMIN, details);
        return new ActionResult(id, next.name(), version + 1);
    }

    public ActionResult cancelByShop(long id, int version, String reasonRaw, String refundRaw) {
        String reason = text("reason", reasonRaw, 500);
        OrderHead head = head(id);
        // Made-to-order PAID_IN_FULL has no refund transition: CANCEL lets the state machine answer 409.
        boolean refund = REFUND_STATUSES.contains(head.status())
                && !(head.type() == FulfillmentType.MADE_TO_ORDER && head.status() == OrderStatus.PAID_IN_FULL);
        String refundRef = refund ? text("refundRef", refundRaw, 100) : null;

        // Lock order: payment_request first, then customer_order (same as PaymentRequestService.confirm).
        int cancelledRequests = payments.cancelPendingForOrder(id);
        OrderStatus next = machine.transition(
                id,
                refund ? OrderAction.CANCEL_REFUND : OrderAction.CANCEL,
                version,
                OrderActor.ADMIN,
                new Details(null, null, reason));
        releaseReservations(id, head.type());

        Map<String, Object> after = new HashMap<>();
        after.put("status", next.name());
        after.put("reason", reason);
        after.put("cancelledPaymentRequests", cancelledRequests);
        if (refundRef != null) {
            after.put("refundRef", refundRef); // audit only, never in a public DTO
        }
        audit.admin(
                "ORDER_CANCELLED_BY_SHOP",
                "ORDER",
                id,
                Map.of("status", head.status().name()),
                after);
        return new ActionResult(id, next.name(), version + 1);
    }

    /** Gives back what checkout took: stock (IN_STOCK) or quota (MADE_TO_ORDER) plus any production slots. */
    private void releaseReservations(long orderId, FulfillmentType type) {
        Instant now = clock.instant();
        List<StockItem> items = jdbc.query(
                "SELECT id, variant_id, quantity FROM order_item WHERE order_id = ? ORDER BY variant_id, id",
                (rs, i) -> new StockItem(rs.getLong(1), rs.getLong(2), rs.getInt(3)),
                orderId);
        for (StockItem item : items) {
            if (type == FulfillmentType.IN_STOCK) {
                checkoutRepo.restoreStock(item.variantId(), item.quantity(), now);
            } else {
                queue.release(item.id()); // no entries before the deposit is confirmed: a no-op then
                checkoutRepo.restoreQuota(item.variantId(), item.quantity(), now);
            }
        }
    }

    public NoteView updateNote(long id, String noteRaw) {
        String note = noteRaw == null ? "" : noteRaw.trim();
        if (note.length() > 2000 || note.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')) {
            throw ApiException.invalidField(
                    "adminNote", "adminNote must be at most 2000 characters without control characters.");
        }
        String value = note.isEmpty() ? null : note;
        int updated = jdbc.update(
                "UPDATE customer_order SET admin_note = ?, updated_at = ? WHERE id = ?",
                value,
                Timestamp.from(clock.instant()),
                id);
        if (updated == 0) {
            throw notFound();
        }
        audit.admin("ORDER_NOTE_UPDATED", "ORDER", id, null, value == null ? Map.of() : Map.of("adminNote", value));
        return new NoteView(id, value);
    }

    // ---- helpers ----

    private OrderHead head(long id) {
        List<OrderHead> rows = jdbc.query(
                "SELECT fulfillment_type, status FROM customer_order WHERE id = ?",
                (rs, i) ->
                        new OrderHead(FulfillmentType.valueOf(rs.getString(1)), OrderStatus.valueOf(rs.getString(2))),
                id);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.get(0);
    }

    private static String text(String field, String raw, int max) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty() || t.length() > max || t.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField(
                    field, field + " must be 1 to " + max + " characters without control characters.");
        }
        return t;
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Order not found.");
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
