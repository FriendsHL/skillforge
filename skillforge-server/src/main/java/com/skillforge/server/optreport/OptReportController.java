package com.skillforge.server.optreport;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.OptReportEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.OptReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OPT-REPORT-V1 — REST surface for the "Generate Report" flywheel route.
 *
 * <p>Three endpoints:
 * <ul>
 *   <li>{@code POST /api/flywheel/agents/{agentId}/generate-report?windowDays=7}
 *       — fire-and-forget trigger; returns 202 with the freshly-created
 *       {@code reportId} so the FE can poll / listen for the
 *       {@code opt_report_completed} WS event.</li>
 *   <li>{@code GET /api/flywheel/agents/{agentId}/reports?limit=20}
 *       — recent reports for the target agent, newest first.</li>
 *   <li>{@code GET /api/flywheel/reports/{reportId}} — single report with
 *       full content_md + summary_json.</li>
 * </ul>
 *
 * <p>Auth: V1 single-tenant dogfood pattern — goes through the same
 * Bearer-token AuthInterceptor as every other {@code /api/**} route.
 */
@RestController
@RequestMapping("/api/flywheel")
public class OptReportController {

    private static final Logger log = LoggerFactory.getLogger(OptReportController.class);

    static final int DEFAULT_WINDOW_DAYS = 14;  // V1.1: wider window to exercise SubAgent fan-out (batchSize=5)
    static final int MIN_WINDOW_DAYS = 1;
    static final int MAX_WINDOW_DAYS = 30;
    static final int DEFAULT_LIMIT = 20;
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 100;

    private final OptReportService reportService;
    private final OptReportRepository reportRepository;
    private final AgentRepository agentRepository;

    public OptReportController(OptReportService reportService,
                               OptReportRepository reportRepository,
                               AgentRepository agentRepository) {
        this.reportService = reportService;
        this.reportRepository = reportRepository;
        this.agentRepository = agentRepository;
    }

    /**
     * Trigger a new report generation. Returns 202 immediately — the
     * dashboard polls / listens to WS to detect completion.
     */
    @PostMapping("/agents/{agentId}/generate-report")
    public ResponseEntity<?> generateReport(
            @PathVariable("agentId") Long agentId,
            @RequestParam(value = "windowDays", required = false) Integer windowDaysRaw) {
        if (agentId == null || agentId <= 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "agentId must be a positive long; got " + agentId);
        }
        int windowDays = clampWindowDays(windowDaysRaw);

        AgentEntity targetAgent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Agent not found: id=" + agentId));

        OptReportEntity report;
        try {
            report = reportService.startReport(agentId, windowDays);
        } catch (IllegalArgumentException iae) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, iae.getMessage(), iae);
        } catch (IllegalStateException ise) {
            // report-generator not seeded — operator hasn't run V97 migration.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ise.getMessage(), ise);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportId", report.getId());
        body.put("agentId", report.getAgentId());
        body.put("agentName", targetAgent.getName());
        body.put("windowStart", toIso(report.getWindowStart()));
        body.put("windowEnd", toIso(report.getWindowEnd()));
        body.put("status", report.getStatus());
        body.put("note", "report-generator session launched; the dashboard will "
                + "receive an opt_report_completed WS event once the report is ready "
                + "(typically 1-2 min for dogfood data volumes).");
        return ResponseEntity.accepted().body(body);
    }

    @GetMapping("/agents/{agentId}/reports")
    public ResponseEntity<?> listReports(
            @PathVariable("agentId") Long agentId,
            @RequestParam(value = "limit", required = false) Integer limitRaw) {
        if (agentId == null || agentId <= 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "agentId must be a positive long; got " + agentId);
        }
        int limit = clampLimit(limitRaw);
        List<OptReportEntity> rows = reportRepository.findByAgentIdOrderByCreatedAtDesc(
                agentId, PageRequest.of(0, limit));
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (OptReportEntity r : rows) {
            items.add(toSummaryDto(r));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("limit", limit);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/reports/{reportId}")
    public ResponseEntity<?> getReport(@PathVariable("reportId") String reportId) {
        if (reportId == null || reportId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reportId is required");
        }
        OptReportEntity r = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Report not found: id=" + reportId));
        Map<String, Object> body = toFullDto(r);
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> toSummaryDto(OptReportEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportId", r.getId());
        m.put("agentId", r.getAgentId());
        m.put("windowStart", toIso(r.getWindowStart()));
        m.put("windowEnd", toIso(r.getWindowEnd()));
        m.put("status", r.getStatus());
        m.put("createdAt", toIso(r.getCreatedAt()));
        m.put("updatedAt", toIso(r.getUpdatedAt()));
        return m;
    }

    private Map<String, Object> toFullDto(OptReportEntity r) {
        Map<String, Object> m = toSummaryDto(r);
        m.put("contentMd", r.getContentMd());
        m.put("summaryJson", r.getSummaryJson());
        m.put("errorReason", r.getErrorReason());
        m.put("generatorSessionId", r.getGeneratorSessionId());
        return m;
    }

    private static String toIso(Instant i) {
        return i == null ? null : i.toString();
    }

    private static int clampWindowDays(Integer raw) {
        if (raw == null) return DEFAULT_WINDOW_DAYS;
        if (raw < MIN_WINDOW_DAYS) return MIN_WINDOW_DAYS;
        return Math.min(raw, MAX_WINDOW_DAYS);
    }

    private static int clampLimit(Integer raw) {
        if (raw == null) return DEFAULT_LIMIT;
        if (raw < MIN_LIMIT) return MIN_LIMIT;
        return Math.min(raw, MAX_LIMIT);
    }
}
