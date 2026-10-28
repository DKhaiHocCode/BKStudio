package com.bkstudio.payment.api;

import com.bkstudio.payment.api.PaymentDtos.CancelRequest;
import com.bkstudio.payment.api.PaymentDtos.ConfirmRequest;
import com.bkstudio.payment.api.PaymentDtos.Confirmed;
import com.bkstudio.payment.api.PaymentDtos.CreatePaymentRequest;
import com.bkstudio.payment.api.PaymentDtos.PaymentRequestView;
import com.bkstudio.payment.domain.PaymentRequestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin payment requests. Everything under /api/admin/** already requires ROLE_ADMIN + CSRF (SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminPaymentController {

    private final PaymentRequestService payments;

    public AdminPaymentController(PaymentRequestService payments) {
        this.payments = payments;
    }

    @PostMapping("/orders/{id}/payment-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentRequestView create(@PathVariable long id, @Valid @RequestBody CreatePaymentRequest req) {
        return payments.create(id, req);
    }

    @PostMapping("/payment-requests/{id}/confirm")
    public Confirmed confirm(@PathVariable long id, @Valid @RequestBody ConfirmRequest req) {
        return payments.confirm(id, req.externalRef());
    }

    @PostMapping("/payment-requests/{id}/cancel")
    public PaymentRequestView cancel(@PathVariable long id, @Valid @RequestBody CancelRequest req) {
        return payments.cancel(id, req.reason());
    }
}
