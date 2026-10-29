package com.bkstudio.order.admin.domain;

import com.bkstudio.order.checkout.api.CheckoutDtos.FulfillmentType;
import com.bkstudio.order.checkout.infra.CheckoutRepository;
import com.bkstudio.queue.domain.QueueService;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Gives back what checkout reserved for an order; shared by the shop cancel and the payment-expiry job. */
@Component
public class OrderReservations {

    private record Line(long id, long variantId, int quantity) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final QueueService queue;
    private final CheckoutRepository checkoutRepo;

    OrderReservations(JdbcTemplate jdbc, Clock clock, QueueService queue, CheckoutRepository checkoutRepo) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.queue = queue;
        this.checkoutRepo = checkoutRepo;
    }

    /**
     * Production slots are always released. Stock (IN_STOCK) and quota (MADE_TO_ORDER) are restored only when
     * {@code restore} is true (cancel before payment); after a refund the admin adjusts them by hand.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(long orderId, FulfillmentType type, boolean restore) {
        var now = clock.instant();
        List<Line> items = jdbc.query(
                "SELECT id, variant_id, quantity FROM order_item WHERE order_id = ? ORDER BY variant_id, id",
                (rs, i) -> new Line(rs.getLong(1), rs.getLong(2), rs.getInt(3)),
                orderId);
        for (Line item : items) {
            if (type == FulfillmentType.IN_STOCK) {
                if (restore) {
                    checkoutRepo.restoreStock(item.variantId(), item.quantity(), now);
                }
            } else {
                queue.release(item.id()); // no entries before the deposit is confirmed: a no-op then
                if (restore) {
                    checkoutRepo.restoreQuota(item.variantId(), item.quantity(), now);
                }
            }
        }
    }
}
