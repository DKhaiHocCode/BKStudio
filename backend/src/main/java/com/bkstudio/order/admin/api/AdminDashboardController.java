package com.bkstudio.order.admin.api;

import com.bkstudio.order.admin.api.AdminOrderDtos.Dashboard;
import com.bkstudio.order.admin.domain.AdminDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin to-do dashboard. /api/admin/** already requires ROLE_ADMIN (SecurityConfig). */
@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService dashboard;

    public AdminDashboardController(AdminDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    public Dashboard get() {
        return dashboard.get();
    }
}
