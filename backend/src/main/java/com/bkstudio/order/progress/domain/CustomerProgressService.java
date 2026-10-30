package com.bkstudio.order.progress.domain;

import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import com.bkstudio.order.customer.domain.CustomerOrderService;
import com.bkstudio.order.progress.api.ProgressDtos.ProgressView;
import com.bkstudio.order.progress.api.ProgressDtos.StageView;
import com.bkstudio.order.progress.domain.ProgressReader.Row;
import com.bkstudio.order.state.OrderStatus;
import com.bkstudio.shared.web.ApiException;
import com.bkstudio.shared.web.ErrorCode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The six customer-facing stages of a made-to-order order. Stages 1, 2 and 6 are derived from the order status;
 * 3 to 5 come from order_progress. Unknown token, bad token shape and non made-to-order all give the same 404.
 */
@Service
@Transactional(readOnly = true)
public class CustomerProgressService {

    private record Def(String code, String label, String explanation) {}

    // Fixed customer-facing text; the optional admin note is added per stored stage.
    private static final List<Def> STAGES = List.of(
            new Def("AWAITING_APPROVAL", "Awaiting approval", "We have your order and are waiting for your deposit."),
            new Def(
                    "DEPOSIT_RECEIVED",
                    "Deposit received",
                    "Your deposit is confirmed and your place in the queue is secured."),
            new Def("RAW_PRINT", "Raw print", "Raw 3D print, before cleanup and preparation."),
            new Def("PREP", "Preparation", "Cleanup, sanding and priming, before painting."),
            new Def("PAINT", "Painting", "Hand painting, before the final checks."),
            new Def("FINISHED", "Finished", "Your figure is finished; the remaining balance is due before it ships."));

    private static final Set<OrderStatus> DEPOSIT_DONE = EnumSet.of(
            OrderStatus.QUEUED,
            OrderStatus.IN_PRODUCTION,
            OrderStatus.AWAITING_BALANCE,
            OrderStatus.PAID_IN_FULL,
            OrderStatus.SHIPPED,
            OrderStatus.COMPLETED,
            OrderStatus.CANCELLED_DEPOSIT_KEPT,
            OrderStatus.CANCELLED_REFUNDED,
            OrderStatus.FORFEITED);

    private static final Set<OrderStatus> FINISHED_DONE = EnumSet.of(
            OrderStatus.AWAITING_BALANCE,
            OrderStatus.PAID_IN_FULL,
            OrderStatus.SHIPPED,
            OrderStatus.COMPLETED,
            OrderStatus.FORFEITED);

    private final JdbcTemplate jdbc;
    private final CustomerOrderService customerOrders;
    private final ProgressReader reader;

    CustomerProgressService(JdbcTemplate jdbc, CustomerOrderService customerOrders, ProgressReader reader) {
        this.jdbc = jdbc;
        this.customerOrders = customerOrders;
        this.reader = reader;
    }

    public ProgressView view(String token) {
        long id = customerOrders.idByToken(token);
        List<String[]> head = jdbc.query(
                "SELECT fulfillment_type, status FROM customer_order WHERE id = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)},
                id);
        if (head.isEmpty() || !FulfillmentType.MADE_TO_ORDER.name().equals(head.get(0)[0])) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Order not found.");
        }
        return build(OrderStatus.valueOf(head.get(0)[1]), reader.load(id));
    }

    /** Package-private so tests can check the derivation without a database. */
    static ProgressView build(OrderStatus status, Map<String, Row> stored) {
        boolean depositDone = DEPOSIT_DONE.contains(status);
        boolean finished = FINISHED_DONE.contains(status);
        // Exactly one stage can be CURRENT, and only while the order is moving (not closed, not past production).
        int current = switch (status) {
            case AWAITING_PAYMENT, AWAITING_DEPOSIT -> 0;
            case QUEUED -> 1;
            case IN_PRODUCTION -> {
                int first = -1;
                for (int i = 2; i <= 4 && first < 0; i++) {
                    if (!stored.containsKey(STAGES.get(i).code())) {
                        first = i;
                    }
                }
                yield first; // -1 when all three are approved: waiting for mark-finished
            }
            default -> -1;
        };
        List<StageView> out = new ArrayList<>();
        for (int i = 0; i < STAGES.size(); i++) {
            Def d = STAGES.get(i);
            Row row = stored.get(d.code());
            boolean done = switch (i) {
                case 0 -> depositDone;
                case 1 -> depositDone && status != OrderStatus.QUEUED;
                case 5 -> finished;
                // A finished figure has passed every stage, approved or not.
                default -> row != null || finished;
            };
            String state = done ? "DONE" : i == current ? "CURRENT" : "PENDING";
            out.add(new StageView(
                    d.code(),
                    d.label(),
                    d.explanation(),
                    state,
                    row == null ? null : row.approvedAt(),
                    row == null ? null : row.note(),
                    row == null ? null : row.images()));
        }
        return new ProgressView(out);
    }
}
