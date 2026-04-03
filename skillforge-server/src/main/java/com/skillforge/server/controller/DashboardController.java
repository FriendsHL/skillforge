package com.skillforge.server.controller;

import com.skillforge.server.dto.DashboardOverview;
import com.skillforge.server.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/overview")
    public ResponseEntity<DashboardOverview> getOverview() {
        DashboardOverview overview = dashboardService.getOverview();
        return ResponseEntity.ok(overview);
    }
}
