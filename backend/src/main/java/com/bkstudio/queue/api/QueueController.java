package com.bkstudio.queue.api;

import com.bkstudio.queue.api.QueueDtos.Estimate;
import com.bkstudio.queue.api.QueueDtos.ProductionMonth;
import com.bkstudio.queue.api.QueueDtos.QueueList;
import com.bkstudio.queue.api.QueueDtos.SetCapacity;
import com.bkstudio.queue.domain.QueueService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public estimate plus admin capacity/queue views. /api/admin/** is already ROLE_ADMIN + CSRF (SecurityConfig). */
@RestController
public class QueueController {

    private static final CacheControl PUBLIC_60S =
            CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final QueueService queue;

    public QueueController(QueueService queue) {
        this.queue = queue;
    }

    @GetMapping("/api/queue/estimate")
    public ResponseEntity<Estimate> estimate(@RequestParam(defaultValue = "1") int qty) {
        return ResponseEntity.ok()
                .cacheControl(PUBLIC_60S)
                .body(new Estimate(queue.estimate(qty).orElse(null)));
    }

    @GetMapping("/api/admin/production-months")
    public List<ProductionMonth> months(
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return queue.listMonths(from, to);
    }

    @PutMapping("/api/admin/production-months/{month}")
    public ProductionMonth setCapacity(@PathVariable String month, @Valid @RequestBody SetCapacity req) {
        return queue.adminSetCapacity(month, req.capacity());
    }

    @GetMapping("/api/admin/queue")
    public QueueList entries(@RequestParam String month) {
        return queue.entries(month);
    }
}
