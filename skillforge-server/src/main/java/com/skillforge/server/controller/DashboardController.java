package com.skillforge.server.controller;

import com.skillforge.server.dto.AgentUsageDto;
import com.skillforge.server.dto.DailyUsageDto;
import com.skillforge.server.dto.DashboardOverview;
import com.skillforge.server.dto.ModelUsageDto;
import com.skillforge.server.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @GetMapping("/usage/daily")
    public ResponseEntity<List<DailyUsageDto>> getDailyUsage(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(dashboardService.getDailyUsage(days));
    }

    @GetMapping("/usage/by-model")
    public ResponseEntity<List<ModelUsageDto>> getUsageByModel() {
        return ResponseEntity.ok(dashboardService.getUsageByModel());
    }

    @GetMapping("/usage/by-agent")
    public ResponseEntity<List<AgentUsageDto>> getUsageByAgent() {
        return ResponseEntity.ok(dashboardService.getUsageByAgent());
    }
}
