package com.bkstudio.order.progress.api;

import com.bkstudio.order.customer.domain.CustomerOrderLimiters;
import com.bkstudio.order.progress.api.ProgressDtos.ProgressView;
import com.bkstudio.order.progress.domain.CustomerProgressService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public progress of a made-to-order order. Permit-all in SecurityConfig; the URL token is the credential. */
@RestController
public class CustomerProgressController {

    private final CustomerProgressService progress;
    private final CustomerOrderLimiters limiters;

    public CustomerProgressController(CustomerProgressService progress, CustomerOrderLimiters limiters) {
        this.progress = progress;
        this.limiters = limiters;
    }

    @GetMapping("/api/orders/{token}/progress")
    public ResponseEntity<ProgressView> get(@PathVariable String token, HttpServletRequest request) {
        limiters.checkAccess(request.getRemoteAddr());
        // The URL is the secret: keep it out of caches and out of Referer headers.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(progress.view(token));
    }
}
