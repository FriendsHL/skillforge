package com.skillforge.server.optreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.bootstrap.SystemAgentNames;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * OPT-REPORT-V1: orchestration for the "Generate Report" flywheel route.
 *
 * <p>OPT-LOOP-FRAMEWORK Sprint 1 (V124, 2026-05-28): public surface preserved
 * ({@code startReport} / {@code onReportCompleted}), but the underlying row
 * persistence is delegated to {@link FlywheelRunService} so all flywheel
 * orchestrators land on the same {@code t_flywheel_run} table. OPT-REPORT
 * callers see no API drift: REST endpoints still return the original DTO
 * shape, and the {@code opt_report_completed} WS event keeps firing for
 * dashboard back-compat (in addition to the new generic
 * {@code flywheel_run_status_changed} event emitted by
 * {@link FlywheelRunService}).
 *
 * <p>{@link #startReport(Long, int)} is the controller-facing entrypoint:
 * <ol>
 *   <li>compute the window ({@code now-windowDays → now}),</li>
 *   <li>insert a {@link FlywheelRunEntity} row with
 *       {@code loop_kind='opt_report'} + {@code trigger_source='user_manual'}
 *       + {@code input_json={agentId, windowDays}} via
 *       {@link FlywheelRunService#startRun},</li>
 *   <li>look up the seeded {@link SystemAgentNames#REPORT_GENERATOR}
 *       system agent + create its session,</li>
 *   <li>fire {@code ChatService.chatAsync} with a kickoff prompt carrying
 *       the agentId / reportId / windowDays,</li>
 *   <li>attach the spawned session id and transition to {@code running}
 *       via {@link FlywheelRunService#attachGeneratorSession}.</li>
 * </ol>
 *
 * <p>{@link #onReportCompleted(String)} is invoked by
 * {@link com.skillforge.server.tool.optreport.WriteOptReportTool} after a
 * successful {@code FlywheelRunService.markCompleted} call — it broadcasts
 * the OPT-REPORT-V1 specific {@code opt_report_completed} WS event so the
 * dashboard Reports tab can show a toast without waiting for the operator to
 * refresh.
 *
 * <p>SYSTEM_USER_ID = 0 matches {@code FlywheelController.SYSTEM_USER_ID}
 * and the rest of the system-agent fleet (cron-owned sessions).
 */
@Service
public class OptReportService {

    private static final Logger log = LoggerFactory.getLogger(OptReportService.class);

    /** SYSTEM user marker — mirrors FlywheelController.SYSTEM_USER_ID. */
    public static final long SYSTEM_USER_ID = 0L;

    private final FlywheelRunRepository runRepository;
    private final FlywheelRunService flywheelRunService;
    private final AgentRepository agentRepository;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final UserWebSocketHandler userWebSocketHandler;
    private final ObjectMapper objectMapper;

    public OptReportService(FlywheelRunRepository runRepository,
                            FlywheelRunService flywheelRunService,
                            AgentRepository agentRepository,
                            SessionService sessionService,
                            ChatService chatService,
                            UserWebSocketHandler userWebSocketHandler,
                            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.flywheelRunService = flywheelRunService;
        this.agentRepository = agentRepository;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.userWebSocketHandler = userWebSocketHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * Insert a pending {@link FlywheelRunEntity} row, spawn the
     * {@code report-generator} session, and kick off {@link ChatService#chatAsync}
     * with the agentId / reportId / windowDays payload. Returns the persisted
     * entity (with {@code status=running} and {@code generatorSessionId} populated).
     *
     * <p><b>Not @Transactional</b> by design: each {@code save} commits in its
     * own tx, and {@code sessionService.createSession} runs its own
     * {@code @Transactional}. If the whole method were wrapped in
     * {@code @Transactional}, the session row would not commit before
     * {@code chatAsync} fires, and the async runLoop thread would hit
     * "Session not found" (witnessed via the V97 first dogfood trigger).
     * Mirrors {@code FlywheelController.runLoop}'s working non-transactional
     * pattern.
     *
     * @throws IllegalArgumentException if the target agent does not exist
     * @throws IllegalStateException if {@code report-generator} is not seeded
     */
    public FlywheelRunEntity startReport(Long agentId, int windowDays) {
        if (agentId == null || agentId <= 0L) {
            throw new IllegalArgumentException("agentId must be a positive long");
        }
        // Pre-check target agent exists — fail fast so the controller can
        // surface a clean 404 instead of letting a downstream FK error escape.
        AgentEntity targetAgent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Target agent not found: id=" + agentId));

        AgentEntity generator = agentRepository.findFirstByName(SystemAgentNames.REPORT_GENERATOR)
                .orElseThrow(() -> new IllegalStateException(
                        "report-generator system agent not seeded; check V97 migration"));

        Map<String, Object> inputJson = new LinkedHashMap<>();
        inputJson.put("agentId", agentId);
        inputJson.put("windowDays", windowDays);

        FlywheelRunEntity run = flywheelRunService.startRun(
                FlywheelRunEntity.LOOP_KIND_OPT_REPORT,
                FlywheelRunEntity.TRIGGER_SOURCE_USER_MANUAL,
                inputJson,
                agentId,
                windowDays);
        String reportId = run.getId();

        SessionEntity generatorSession = sessionService.createSession(SYSTEM_USER_ID, generator.getId());

        String prompt = String.format(
                "请为 agentId=%d 生成最近 %d 天的优化报告。reportId=%s。windowDays=%d。",
                agentId, windowDays, reportId, windowDays);
        chatService.chatAsync(generatorSession.getId(), prompt, SYSTEM_USER_ID);

        FlywheelRunEntity updated = flywheelRunService.attachGeneratorSession(
                reportId, generatorSession.getId());

        log.info("OptReportService.startReport: reportId={} agentId={} agentName={} windowDays={} generatorSessionId={}",
                reportId, agentId, targetAgent.getName(), windowDays, generatorSession.getId());
        return updated;
    }

    /**
     * Broadcast an {@code opt_report_completed} WS event. Called by
     * {@link com.skillforge.server.tool.optreport.WriteOptReportTool} after a
     * successful DB write. Lookups the freshly-saved row so the payload
     * reflects the persisted state (including the {@code summary_json}
     * highlight when present).
     *
     * <p>This is the OPT-REPORT-V1-specific WS event (dashboard Reports tab
     * subscriber). The generic {@code flywheel_run_status_changed} event is
     * already emitted by {@link FlywheelRunService#markCompleted} for the
     * new "All Flywheel Runs" dashboard page.
     */
    public void onReportCompleted(String reportId) {
        if (reportId == null || reportId.isBlank()) return;
        Optional<FlywheelRunEntity> opt = runRepository.findById(reportId);
        if (opt.isEmpty()) {
            log.warn("OptReportService.onReportCompleted: reportId={} not found, skipping WS broadcast", reportId);
            return;
        }
        FlywheelRunEntity report = opt.get();
        String agentName = agentRepository.findById(report.getAgentId())
                .map(AgentEntity::getName)
                .orElse(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "opt_report_completed");
        payload.put("reportId", reportId);
        payload.put("agentId", report.getAgentId());
        payload.put("agentName", agentName);
        payload.put("status", report.getStatus());
        payload.put("summaryHighlight", extractSummaryHighlight(report.getSummaryJson()));
        Instant completedAt = report.getUpdatedAt();
        payload.put("completedAt", completedAt == null ? null : completedAt.toString());

        try {
            userWebSocketHandler.broadcastAll(payload);
        } catch (RuntimeException e) {
            log.warn("OptReportService.onReportCompleted: WS broadcast failed for reportId={}: {}",
                    reportId, e.getMessage());
        }
    }

    /**
     * Pluck a one-liner from {@code summary_json} for the toast. V1 looks at
     * a "summary" or "highlight" field if the agent supplied one; otherwise
     * synthesizes a basic stat from {@code successRate} / {@code totalSessions}
     * if both are present. Returns null when nothing usable is found —
     * dashboard renders a generic "Report ready" toast in that case.
     */
    private String extractSummaryHighlight(String summaryJson) {
        if (summaryJson == null || summaryJson.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(summaryJson);
            for (String key : new String[]{"summary", "highlight", "headline"}) {
                JsonNode v = root.get(key);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    return v.asText();
                }
            }
            JsonNode total = root.get("totalSessions");
            JsonNode rate = root.get("successRate");
            if (total != null && rate != null && total.canConvertToInt() && rate.isNumber()) {
                return String.format("Session: %d, Success Rate: %.0f%%",
                        total.asInt(), rate.asDouble() * 100.0);
            }
        } catch (Exception e) {
            log.debug("OptReportService.extractSummaryHighlight parse failed (treated as no-highlight): {}",
                    e.getMessage());
        }
        return null;
    }
}
