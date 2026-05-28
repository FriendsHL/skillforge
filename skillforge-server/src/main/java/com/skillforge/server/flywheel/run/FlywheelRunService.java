package com.skillforge.server.flywheel.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * OPT-LOOP-FRAMEWORK Sprint 1: CRUD + simple state machine for
 * {@link FlywheelRunEntity}. OPT-REPORT-V1 keeps its public {@code OptReportService}
 * surface (back-compat) but internally delegates the row writes here, so any
 * future loop orchestrator (memory-curation, attribution, ...) can land on the
 * same persistence + WS-broadcast pipeline.
 *
 * <p>State machine (matches OPT-REPORT-V1 lifecycle):
 * <pre>
 *   pending → running → completed
 *                    └→ error
 * </pre>
 *
 * <p>{@link #transitionStatus} fires a {@code flywheel_run_status_changed}
 * WebSocket event on every state change so the dashboard "All Flywheel Runs"
 * page can update live. OPT-REPORT keeps its own {@code opt_report_completed}
 * broadcast in addition (W6 dual-event back-compat).
 */
@Service
public class FlywheelRunService {

    private static final Logger log = LoggerFactory.getLogger(FlywheelRunService.class);

    /**
     * Status transitions accepted by {@link #transitionStatus}. We rely on
     * Service-layer validation rather than a DB CHECK because the same row
     * can legally go {@code pending → running → completed} or skip running
     * (e.g. when a worker fails before kickoff).
     */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            FlywheelRunEntity.STATUS_PENDING, Set.of(
                    FlywheelRunEntity.STATUS_RUNNING,
                    FlywheelRunEntity.STATUS_COMPLETED,
                    FlywheelRunEntity.STATUS_ERROR),
            FlywheelRunEntity.STATUS_RUNNING, Set.of(
                    FlywheelRunEntity.STATUS_COMPLETED,
                    FlywheelRunEntity.STATUS_ERROR)
    );

    private final FlywheelRunRepository runRepository;
    private final UserWebSocketHandler userWebSocketHandler;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FlywheelRunService(FlywheelRunRepository runRepository,
                              UserWebSocketHandler userWebSocketHandler,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.runRepository = runRepository;
        this.userWebSocketHandler = userWebSocketHandler;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Insert a {@link FlywheelRunEntity} row in {@code status=pending}. Caller
     * (e.g. {@code OptReportService.startReport}) is responsible for spawning
     * any orchestrator session afterwards and calling
     * {@link #attachGeneratorSession} once the session id is known.
     *
     * <p><b>Not @Transactional</b> by design: each repository {@code save}
     * commits in its own short tx so the row is visible to the async
     * orchestrator runLoop thread before kickoff (mirrors the original
     * OPT-REPORT-V1 pattern documented on {@code OptReportService.startReport}).
     *
     * @param loopKind       one of {@link FlywheelRunEntity}{@code .LOOP_KIND_*}
     * @param triggerSource  one of {@link FlywheelRunEntity}{@code .TRIGGER_SOURCE_*}
     * @param inputJson      free-schema map; serialized to JSONB
     * @param agentId        target agent id
     * @param windowDays     historical OPT-REPORT-V1 window (>= 1); future
     *                       loop_kinds without a time window may pass any
     *                       positive int — only the JSONB carries the canonical
     *                       value
     */
    public FlywheelRunEntity startRun(String loopKind,
                                      String triggerSource,
                                      Map<String, Object> inputJson,
                                      Long agentId,
                                      int windowDays) {
        if (loopKind == null || loopKind.isBlank()) {
            throw new IllegalArgumentException("loopKind is required");
        }
        if (triggerSource == null || triggerSource.isBlank()) {
            throw new IllegalArgumentException("triggerSource is required");
        }
        if (agentId == null || agentId <= 0L) {
            throw new IllegalArgumentException("agentId must be a positive long");
        }
        if (windowDays < 1) {
            throw new IllegalArgumentException("windowDays must be >= 1");
        }

        Instant now = clock.instant();
        Instant windowStart = now.minus(windowDays, ChronoUnit.DAYS);

        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId(UUID.randomUUID().toString());
        run.setAgentId(agentId);
        run.setWindowStart(windowStart);
        run.setWindowEnd(now);
        run.setStatus(FlywheelRunEntity.STATUS_PENDING);
        run.setLoopKind(loopKind);
        run.setTriggerSource(triggerSource);
        run.setInputJson(serializeInput(inputJson));
        runRepository.save(run);

        log.info("FlywheelRunService.startRun: runId={} loopKind={} triggerSource={} agentId={} windowDays={}",
                run.getId(), loopKind, triggerSource, agentId, windowDays);
        return run;
    }

    /**
     * Attach the spawned orchestrator session id and transition the run to
     * {@code running}. Fires the {@code flywheel_run_status_changed} WS event.
     *
     * <p><b>State machine guard</b>: only allowed when the current status is
     * {@code pending}. Calling on a {@code running} / {@code completed} /
     * {@code error} row throws {@link IllegalStateException} — silently
     * rewinding a terminal state to {@code running} would mask either a
     * double-spawn bug or a race against {@link #markCompleted} /
     * {@link #markError}. (Sprint 1 r1 review W1 fix.)
     */
    @Transactional
    public FlywheelRunEntity attachGeneratorSession(String runId, String generatorSessionId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        FlywheelRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("FlywheelRun not found: " + runId));
        String oldStatus = run.getStatus();
        if (!FlywheelRunEntity.STATUS_PENDING.equals(oldStatus)) {
            throw new IllegalStateException(
                    "attachGeneratorSession requires status=pending; got '" + oldStatus
                            + "' (runId=" + runId + ")");
        }
        run.setGeneratorSessionId(generatorSessionId);
        run.setStatus(FlywheelRunEntity.STATUS_RUNNING);
        runRepository.save(run);
        broadcastStatusChanged(run, oldStatus, FlywheelRunEntity.STATUS_RUNNING, null);
        return run;
    }

    /**
     * Mark the run as completed with optional summary payload (for the OPT-REPORT
     * caller this is content_md + summary_json). Validates the transition and
     * fires a WS broadcast.
     */
    @Transactional
    public FlywheelRunEntity markCompleted(String runId, String contentMd, String summaryJson) {
        FlywheelRunEntity run = requireWritableRun(runId);
        String oldStatus = run.getStatus();
        if (contentMd != null) run.setContentMd(contentMd);
        if (summaryJson != null) run.setSummaryJson(summaryJson);
        run.setStatus(FlywheelRunEntity.STATUS_COMPLETED);
        runRepository.save(run);
        broadcastStatusChanged(run, oldStatus, FlywheelRunEntity.STATUS_COMPLETED, null);
        return run;
    }

    /**
     * Mark the run as errored with an explicit reason. Validates the transition
     * and fires a WS broadcast.
     */
    @Transactional
    public FlywheelRunEntity markError(String runId, String errorReason) {
        FlywheelRunEntity run = requireWritableRun(runId);
        String oldStatus = run.getStatus();
        run.setErrorReason(errorReason);
        run.setStatus(FlywheelRunEntity.STATUS_ERROR);
        runRepository.save(run);
        broadcastStatusChanged(run, oldStatus, FlywheelRunEntity.STATUS_ERROR, errorReason);
        return run;
    }

    /**
     * Generic state-machine helper exposed for tests / future callers that
     * need to drive arbitrary {@code pending→running→completed/error}
     * transitions without going through {@link #markCompleted} /
     * {@link #markError}.
     */
    @Transactional
    public FlywheelRunEntity transitionStatus(String runId, String newStatus, String errorReason) {
        FlywheelRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("FlywheelRun not found: " + runId));
        String oldStatus = run.getStatus();
        if (oldStatus.equals(newStatus)) return run;
        Set<String> allowed = ALLOWED_TRANSITIONS.get(oldStatus);
        if (allowed == null || !allowed.contains(newStatus)) {
            throw new IllegalStateException(
                    "Disallowed transition: " + oldStatus + " → " + newStatus
                            + " (runId=" + runId + ")");
        }
        run.setStatus(newStatus);
        if (FlywheelRunEntity.STATUS_ERROR.equals(newStatus) && errorReason != null) {
            run.setErrorReason(errorReason);
        }
        runRepository.save(run);
        broadcastStatusChanged(run, oldStatus, newStatus, errorReason);
        return run;
    }

    public Optional<FlywheelRunEntity> findById(String runId) {
        return runRepository.findById(runId);
    }

    private FlywheelRunEntity requireWritableRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        FlywheelRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("FlywheelRun not found: " + runId));
        String status = run.getStatus();
        if (!FlywheelRunEntity.STATUS_PENDING.equals(status)
                && !FlywheelRunEntity.STATUS_RUNNING.equals(status)) {
            throw new IllegalStateException(
                    "FlywheelRun " + runId + " not writable (status=" + status + ")");
        }
        return run;
    }

    private String serializeInput(Map<String, Object> inputJson) {
        if (inputJson == null || inputJson.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(inputJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("inputJson could not be serialized: " + e.getMessage(), e);
        }
    }

    /**
     * Best-effort WS broadcast. Swallows runtime errors so a dropped connection
     * never masks a successful DB write (operator can still see the row by
     * polling).
     */
    private void broadcastStatusChanged(FlywheelRunEntity run,
                                        String oldStatus,
                                        String newStatus,
                                        String errorReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "flywheel_run_status_changed");
        payload.put("runId", run.getId());
        payload.put("loopKind", run.getLoopKind());
        payload.put("agentId", run.getAgentId());
        payload.put("oldStatus", oldStatus);
        payload.put("newStatus", newStatus);
        Instant updatedAt = run.getUpdatedAt();
        payload.put("timestamp", updatedAt == null ? clock.instant().toString() : updatedAt.toString());
        if (errorReason != null) {
            payload.put("errorReason", errorReason);
        }
        try {
            userWebSocketHandler.broadcastAll(payload);
        } catch (RuntimeException e) {
            log.warn("FlywheelRunService.broadcastStatusChanged: WS broadcast failed for runId={} ({}→{}): {}",
                    run.getId(), oldStatus, newStatus, e.getMessage());
        }
    }
}
