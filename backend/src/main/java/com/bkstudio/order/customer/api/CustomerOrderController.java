package com.bkstudio.order.customer.api;

import com.bkstudio.order.customer.api.CustomerOrderDtos.CancelRequest;
import com.bkstudio.order.customer.api.CustomerOrderDtos.LookupAccepted;
import com.bkstudio.order.customer.api.CustomerOrderDtos.LookupRequest;
import com.bkstudio.order.customer.api.CustomerOrderDtos.OrderView;
import com.bkstudio.order.customer.domain.CustomerOrderLimiters;
import com.bkstudio.order.customer.domain.CustomerOrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Public order API: permitAll in SecurityConfig, POSTs still need CSRF. The URL token is the credential. */
@RestController
public class CustomerOrderController {

    static final String ACCEPTED = "If the order code and email match, we have sent a new link to that email.";

    private final CustomerOrderService orders;
    private final CustomerOrderLimiters limiters;

    public CustomerOrderController(CustomerOrderService orders, CustomerOrderLimiters limiters) {
        this.orders = orders;
        this.limiters = limiters;
    }

    @GetMapping("/api/orders/{token}")
    public ResponseEntity<OrderView> get(@PathVariable String token, HttpServletRequest request) {
        limiters.checkAccess(request.getRemoteAddr());
        return sensitive(orders.get(token));
    }

    @PostMapping("/api/orders/{token}/cancel")
    public ResponseEntity<OrderView> cancel(
            @PathVariable String token, @RequestBody(required = false) CancelRequest body, HttpServletRequest request) {
        limiters.checkAccess(request.getRemoteAddr());
        boolean ack = body != null && Boolean.TRUE.equals(body.acknowledgeDepositLost());
        return sensitive(orders.cancel(token, ack));
    }

    @PostMapping("/api/orders/lookup")
    public ResponseEntity<LookupAccepted> lookup(@Valid @RequestBody LookupRequest req, HttpServletRequest request) {
        // A limited IP gets the same 202 and costs no DB work.
        if (limiters.allowLookup(request.getRemoteAddr())) {
            orders.lookup(req.code(), req.email());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new LookupAccepted(ACCEPTED));
    }

    /** The URL is the secret: keep it out of caches and out of Referer headers. */
    private static ResponseEntity<OrderView> sensitive(OrderView view) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(view);
    }
}
