package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.MemoryProposalRepository;
import com.skillforge.workflow.dto.AutoEvolvingDtos.KpiDto;
import com.skillforge.workflow.dto.AutoEvolvingDtos.OverviewResponse;
import com.skillforge.workflow.dto.AutoEvolvingDtos.RecentAnomalyDto;
import com.skillforge.workflow.dto.AutoEvolvingDtos.RecentReportDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AUTOEVOLVING V1 Sprint 4 (Task C) — read-only aggregation behind
 * {@code GET /api/autoevolving/overview}.
 *
 * <p>Collapses three otherwise-N+1 fan-outs into one read:
 * <ul>
 *   <li>KPI counts (workflow running / completed-this-week / memory proposals
 *       pending) — three count queries;</li>
 *   <li>cross-agent recent OPT-REPORT rows (the per-agent
 *       {@code OptReportController} endpoint cannot list across agents) +
 *       batch agent-name resolution;</li>
 *   <li>recent failed workflow runs (the simplified anomaly signal — FR-6.5).</li>
 * </ul>
 *
 * <p>Everything is {@code @Transactional(readOnly = true)} — no writes. The
 * "this week" window is computed from the injected {@link Clock} (testable;
 * java.md footgun #2 — {@link Instant}-based, never {@code LocalDateTime}).
 */
@Service
public class AutoEvolvingOverviewService {

    /** Failed-run anomaly panel is a glanceable strip — cap rows independent of reportLimit. */
    static final int ANOMALY_LIMIT = 8;

    private final FlywheelRunRepository runRepository;
    private final MemoryProposalRepository memoryProposalRepository;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AutoEvolvingOverviewService(FlywheelRunRepository runRepository,
                                       MemoryProposalRepository memoryProposalRepository,
                                       AgentRepository agentRepository,
                                       ObjectMapper objectMapper,
                                       Clock clock) {
        this.runRepository = runRepository;
        this.memoryProposalRepository = memoryProposalRepository;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OverviewResponse buildOverview(int weekDays, int reportLimit) {
        Instant weekAgo = clock.instant().minus(Duration.ofDays(weekDays));

        long workflowRunning = runRepository.countByLoopKindAndStatus(
                WorkflowRunnerService.LOOP_KIND_WORKFLOW, FlywheelRunEntity.STATUS_RUNNING);
        long workflowCompletedThisWeek = runRepository.countByLoopKindAndStatusAndCreatedAtAfter(
                WorkflowRunnerService.LOOP_KIND_WORKFLOW, FlywheelRunEntity.STATUS_COMPLETED, weekAgo);
        long memoryProposalPending = memoryProposalRepository.countByStatus("proposed");
        // autoResearchPending: V1 placeholder — the auto-research signal is V2 (FR-6.2).
        KpiDto kpi = new KpiDto(workflowRunning, workflowCompletedThisWeek, memoryProposalPending, null);

        List<RecentReportDto> recentReports = buildRecentReports(reportLimit);
        List<RecentAnomalyDto> recentAnomalies = buildRecentAnomalies();

        return new OverviewResponse(kpi, recentReports, recentAnomalies);
    }

    private List<RecentReportDto> buildRecentReports(int reportLimit) {
        List<FlywheelRunEntity> rows = runRepository.findByLoopKindOrderByCreatedAtDescIdDesc(
                FlywheelRunEntity.LOOP_KIND_OPT_REPORT, PageRequest.of(0, reportLimit));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> agentNames = resolveAgentNames(rows);
        List<RecentReportDto> out = new ArrayList<>(rows.size());
        for (FlywheelRunEntity r : rows) {
            out.add(new RecentReportDto(
                    r.getId(),
                    r.getAgentId(),
                    agentNames.get(r.getAgentId()),
                    toIso(r.getWindowEnd()),
                    r.getStatus(),
                    topIssueCountOf(r.getSummaryJson())));
        }
        return out;
    }

    private List<RecentAnomalyDto> buildRecentAnomalies() {
        List<FlywheelRunEntity> rows = runRepository.findByLoopKindAndStatusOrderByCreatedAtDescIdDesc(
                WorkflowRunnerService.LOOP_KIND_WORKFLOW, FlywheelRunEntity.STATUS_ERROR,
                PageRequest.of(0, ANOMALY_LIMIT));
        List<RecentAnomalyDto> out = new ArrayList<>(rows.size());
        for (FlywheelRunEntity r : rows) {
            out.add(new RecentAnomalyDto(
                    r.getId(),
                    workflowNameOf(r.getInputJson()),
                    r.getStatus(),
                    r.getErrorReason(),
                    toIso(r.getUpdatedAt())));
        }
        return out;
    }

    /** Batch-resolve {@code agentId → name} so the report list is not an N+1. */
    private Map<Long, String> resolveAgentNames(List<FlywheelRunEntity> rows) {
        Set<Long> ids = new HashSet<>();
        for (FlywheelRunEntity r : rows) {
            if (r.getAgentId() != null) {
                ids.add(r.getAgentId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return agentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(AgentEntity::getId, AgentEntity::getName, (a, b) -> a));
    }

    /**
     * Length of {@code summary_json.topIssues}, or 0 when absent / unparseable.
     * Deliberately lenient (no schema validation, never throws) — this is a
     * glance count for the panel, not the convert-to-event gate that uses the
     * strict {@code OptReportSummaryParser}.
     */
    private int topIssueCountOf(String summaryJson) {
        if (summaryJson == null || summaryJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(summaryJson);
            JsonNode arr = root.get("topIssues");
            return (arr != null && arr.isArray()) ? arr.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** {@code input_json.workflow_name}, or null when absent / unparseable. */
    private String workflowNameOf(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(inputJson);
            JsonNode name = node.get("workflow_name");
            return name == null || name.isNull() ? null : name.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String toIso(Instant i) {
        return i == null ? null : i.toString();
    }
}
