package com.bkstudio.order.admin.api;

import com.bkstudio.order.admin.api.AdminOrderDtos.ActionResult;
import com.bkstudio.order.admin.api.AdminOrderDtos.CancelByShop;
import com.bkstudio.order.admin.api.AdminOrderDtos.Detail;
import com.bkstudio.order.admin.api.AdminOrderDtos.MarkFinished;
import com.bkstudio.order.admin.api.AdminOrderDtos.NoteRequest;
import com.bkstudio.order.admin.api.AdminOrderDtos.NoteView;
import com.bkstudio.order.admin.api.AdminOrderDtos.OrderPage;
import com.bkstudio.order.admin.api.AdminOrderDtos.Ship;
import com.bkstudio.order.admin.api.AdminOrderDtos.VersionOnly;
import com.bkstudio.order.admin.domain.AdminOrderService;
import com.bkstudio.order.state.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin order views and actions. /api/admin/** already requires ROLE_ADMIN + CSRF (SecurityConfig). */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final AdminOrderService orders;

    public AdminOrderController(AdminOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public OrderPage list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(defaultValue = "0") int page) {
        return orders.list(status, q, page);
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable long id) {
        return orders.get(id);
    }

    @PostMapping("/{id}/start-production")
    public ActionResult startProduction(@PathVariable long id, @Valid @RequestBody VersionOnly req) {
        return orders.startProduction(id, req.version());
    }

    @PostMapping("/{id}/mark-finished")
    public ActionResult markFinished(@PathVariable long id, @Valid @RequestBody MarkFinished req) {
        return orders.markFinished(id, req.version());
    }

    @PostMapping("/{id}/ship")
    public ActionResult ship(@PathVariable long id, @Valid @RequestBody Ship req) {
        return orders.ship(id, req.version(), req.carrier(), req.trackingNumber());
    }

    @PostMapping("/{id}/complete")
    public ActionResult complete(@PathVariable long id, @Valid @RequestBody VersionOnly req) {
        return orders.complete(id, req.version());
    }

    @PostMapping("/{id}/cancel-by-shop")
    public ActionResult cancelByShop(@PathVariable long id, @Valid @RequestBody CancelByShop req) {
        return orders.cancelByShop(id, req.version(), req.reason(), req.refundRef());
    }

    @PatchMapping("/{id}/note")
    public NoteView note(@PathVariable long id, @Valid @RequestBody NoteRequest req) {
        return orders.updateNote(id, req.adminNote());
    }
}
