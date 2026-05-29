package com.skillforge.workflow;

import com.skillforge.workflow.dto.AutoEvolvingDtos.OverviewResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AUTOEVOLVING V1 Sprint 4 (Task C) — read-only overview surface for the
 * {@code /autoevolving} dashboard page.
 *
 * <ul>
 *   <li>{@code GET /api/autoevolving/overview?weekDays=7&reportLimit=8} —
 *       KPI strip + cross-agent recent OPT-REPORTs + recent failed-run
 *       anomalies, in one round-trip.</li>
 * </ul>
 *
 * <p><b>Auth (Q5):</b> single-tenant dogfood — the same Bearer-token
 * {@code AuthInterceptor} as every {@code /api/**} route guards this; no role
 * gating in V1. {@code userId} defaults to {@code SYSTEM_USER_ID=0L} and is
 * accepted for forward-compat / FE-contract parity with the other flywheel
 * controllers — the V1 aggregates (memory pending count, workflow counts) are
 * global, so it does not yet scope the result.
 *
 * <p><b>Envelope (footgun #6b):</b> returns a single {@link OverviewResponse}
 * object, never a bare array.
 */
@RestController
@RequestMapping("/api/autoevolving")
public class AutoEvolvingController {

    static final int DEFAULT_WEEK_DAYS = 7;
    static final int MIN_WEEK_DAYS = 1;
    static final int MAX_WEEK_DAYS = 90;

    static final int DEFAULT_REPORT_LIMIT = 8;
    static final int MIN_REPORT_LIMIT = 1;
    static final int MAX_REPORT_LIMIT = 50;

    private final AutoEvolvingOverviewService overviewService;

    public AutoEvolvingController(AutoEvolvingOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/overview")
    public ResponseEntity<OverviewResponse> overview(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "weekDays", required = false) Integer weekDaysRaw,
            @RequestParam(value = "reportLimit", required = false) Integer reportLimitRaw) {
        // userId is reserved for V2 multi-tenant scoping (see class javadoc); the V1
        // aggregates are global so it is intentionally not read here. Accepted on the
        // signature so the FE contract (?userId=) stays stable across the V1→V2 seam.
        int weekDays = clamp(weekDaysRaw, DEFAULT_WEEK_DAYS, MIN_WEEK_DAYS, MAX_WEEK_DAYS);
        int reportLimit = clamp(reportLimitRaw, DEFAULT_REPORT_LIMIT, MIN_REPORT_LIMIT, MAX_REPORT_LIMIT);
        return ResponseEntity.ok(overviewService.buildOverview(weekDays, reportLimit));
    }

    private static int clamp(Integer raw, int def, int min, int max) {
        if (raw == null) return def;
        if (raw < min) return min;
        return Math.min(raw, max);
    }
}
