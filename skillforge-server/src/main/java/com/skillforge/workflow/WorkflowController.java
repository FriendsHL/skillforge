package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.workflow.dto.WorkflowDtos.ApproveRequest;
import com.skillforge.workflow.dto.WorkflowDtos.PhaseDto;
import com.skillforge.workflow.dto.WorkflowDtos.RunWorkflowRequest;
import com.skillforge.workflow.dto.WorkflowDtos.WorkflowRunSummaryDto;
import com.skillforge.workflow.dto.WorkflowDtos.WorkflowStepDto;
import com.skillforge.workflow.dto.WorkflowDtos.WorkflowSummaryDto;
import com.skillforge.workflow.exception.WorkflowAlreadyRunningException;
import com.skillforge.workflow.exception.WorkflowDefinitionChangedException;
import com.skillforge.workflow.exception.WorkflowNotFoundException;
import com.skillforge.workflow.exception.WorkflowNotPausedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVING V1 Sprint 2 (Task G) — REST surface for DSL workflows.
 *
 * <ul>
 *   <li>{@code GET  /api/workflows} — list registered definitions.</li>
 *   <li>{@code POST /api/workflows/{name}/run} — start a run (202 + runId).</li>
 *   <li>{@code GET  /api/workflows/runs} — list workflow runs (newest-first).</li>
 *   <li>{@code GET  /api/workflows/runs/{runId}} — run detail + steps.</li>
 *   <li>{@code POST /api/workflows/runs/{runId}/approve} — resolve a humanApprove
 *       gate and resume the run.</li>
 * </ul>
 *
 * <p><b>Auth (Q5):</b> single-tenant dogfood — the same Bearer-token
 * {@code AuthInterceptor} as every {@code /api/**} route guards these; there is no
 * role gating in V1 (deferred to V2 if multi-tenant admin separation is needed).
 * The run owner defaults to {@code SYSTEM_USER_ID=0L} (overridable via
 * {@code ?userId=}), matching the {@code FlywheelController} convention.
 *
 * <p><b>Envelope (footgun #6b):</b> every list endpoint returns a
 * {@code {items:[...], ...}} object, never a bare array.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    /** SYSTEM user marker — mirrors {@code FlywheelController.SYSTEM_USER_ID}. */
    static final long SYSTEM_USER_ID = 0L;

    static final int DEFAULT_LIMIT = 20;
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 100;

    private final WorkflowDefinitionRegistry registry;
    private final WorkflowRunnerService runnerService;
    private final FlywheelRunService flywheelRunService;
    private final FlywheelRunRepository runRepository;
    private final ObjectMapper objectMapper;

    public WorkflowController(WorkflowDefinitionRegistry registry,
                              WorkflowRunnerService runnerService,
                              FlywheelRunService flywheelRunService,
                              FlywheelRunRepository runRepository,
                              ObjectMapper objectMapper) {
        this.registry = registry;
        this.runnerService = runnerService;
        this.flywheelRunService = flywheelRunService;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<?> listWorkflows() {
        Collection<WorkflowDefinition> defs = registry.listAll();
        List<WorkflowSummaryDto> items = new ArrayList<>(defs.size());
        for (WorkflowDefinition def : defs) {
            List<PhaseDto> phases = new ArrayList<>(def.phases().size());
            for (WorkflowDefinition.WorkflowPhase p : def.phases()) {
                phases.add(new PhaseDto(p.title(), p.detail()));
            }
            items.add(new WorkflowSummaryDto(def.name(), def.description(), phases));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", items.size());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{name}/run")
    public ResponseEntity<?> runWorkflow(
            @PathVariable("name") String name,
            @RequestBody(required = false) RunWorkflowRequest request,
            @RequestParam(value = "userId", required = false) Long userIdRaw) {
        Long userId = userIdRaw == null ? SYSTEM_USER_ID : userIdRaw;
        Map<String, Object> args = request == null || request.args() == null
                ? Map.of() : request.args();
        String runId;
        try {
            runId = runnerService.startRun(name, args, userId);
        } catch (WorkflowNotFoundException nf) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, nf.getMessage(), nf);
        } catch (WorkflowAlreadyRunningException ar) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ar.getMessage(), ar);
        } catch (IllegalStateException ise) {
            // r1 java-W1: startRun's internal pre-conditions (e.g. the anchor agent
            // row is missing) surface as IllegalStateException. Map to 409 with a
            // sanitized message so internal detail never leaks to the client; the
            // real cause is logged server-side.
            log.warn("WorkflowController.runWorkflow: start failed for '{}': {}", name, ise.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Workflow '" + name + "' cannot be started in its current state", ise);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("name", name);
        body.put("status", FlywheelRunEntity.STATUS_RUNNING);
        return ResponseEntity.accepted().body(body);
    }

    @GetMapping("/runs")
    public ResponseEntity<?> listRuns(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limitRaw,
            @RequestParam(value = "offset", required = false) Integer offsetRaw) {
        int limit = clampLimit(limitRaw);
        int offset = offsetRaw == null || offsetRaw < 0 ? 0 : offsetRaw;
        int page = offset / limit;
        PageRequest pageable = PageRequest.of(page, limit);

        List<FlywheelRunEntity> rows;
        long total;
        if (status != null && !status.isBlank()) {
            rows = runRepository.findByLoopKindAndStatusOrderByCreatedAtDescIdDesc(
                    WorkflowRunnerService.LOOP_KIND_WORKFLOW, status, pageable);
            total = runRepository.countByLoopKindAndStatus(
                    WorkflowRunnerService.LOOP_KIND_WORKFLOW, status);
        } else {
            rows = runRepository.findByLoopKindOrderByCreatedAtDescIdDesc(
                    WorkflowRunnerService.LOOP_KIND_WORKFLOW, pageable);
            total = runRepository.countByLoopKind(WorkflowRunnerService.LOOP_KIND_WORKFLOW);
        }

        List<WorkflowRunSummaryDto> items = new ArrayList<>(rows.size());
        for (FlywheelRunEntity r : rows) {
            items.add(toRunSummary(r));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", total);
        body.put("limit", limit);
        body.put("offset", offset);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> getRun(@PathVariable("runId") String runId) {
        FlywheelRunEntity run = flywheelRunService.findById(runId)
                .filter(r -> WorkflowRunnerService.LOOP_KIND_WORKFLOW.equals(r.getLoopKind()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Workflow run not found: " + runId));

        List<FlywheelRunStepEntity> steps = flywheelRunService.listStepsByRunId(runId);
        List<WorkflowStepDto> stepDtos = new ArrayList<>(steps.size());
        for (FlywheelRunStepEntity s : steps) {
            stepDtos.add(toStepDto(s));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.getId());
        body.put("name", workflowNameOf(run));
        body.put("status", run.getStatus());
        body.put("summaryJson", run.getSummaryJson());
        body.put("errorReason", run.getErrorReason());
        body.put("createdAt", toIso(run.getCreatedAt()));
        body.put("updatedAt", toIso(run.getUpdatedAt()));
        body.put("steps", stepDtos);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/runs/{runId}/approve")
    public ResponseEntity<?> approve(
            @PathVariable("runId") String runId,
            @RequestBody ApproveRequest request,
            @RequestParam(value = "reviewerId", required = false) String reviewerId) {
        if (request == null || request.decision() == null || request.decision().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "decision is required ('approved' or 'rejected')");
        }
        String decision = request.decision().trim().toLowerCase();
        boolean approved;
        if ("approved".equals(decision)) {
            approved = true;
        } else if ("rejected".equals(decision)) {
            approved = false;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "decision must be 'approved' or 'rejected'; got '" + request.decision() + "'");
        }

        try {
            runnerService.resume(runId, approved, request.reason(), reviewerId);
        } catch (WorkflowNotFoundException nf) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, nf.getMessage(), nf);
        } catch (WorkflowNotPausedException np) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, np.getMessage(), np);
        } catch (WorkflowDefinitionChangedException dc) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, dc.getMessage(), dc);
        } catch (WorkflowAlreadyRunningException ar) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ar.getMessage(), ar);
        } catch (IllegalStateException ise) {
            // r1 java-W1: resume's internal pre-conditions (no pending gate step,
            // missing workflow_name, null anchor session) surface as
            // IllegalStateException and previously escaped as 500. Map to 409 with a
            // sanitized message; log the real cause server-side.
            log.warn("WorkflowController.approve: resume failed for run {}: {}", runId, ise.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Workflow run " + runId + " cannot be resumed in its current state", ise);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("status", FlywheelRunEntity.STATUS_RUNNING);
        body.put("decision", decision);
        return ResponseEntity.ok(body);
    }

    // ─────────────────────────────────────────────────────────────────────

    private WorkflowRunSummaryDto toRunSummary(FlywheelRunEntity r) {
        return new WorkflowRunSummaryDto(
                r.getId(),
                workflowNameOf(r),
                r.getStatus(),
                toIso(r.getCreatedAt()),
                toIso(r.getUpdatedAt()));
    }

    private WorkflowStepDto toStepDto(FlywheelRunStepEntity s) {
        return new WorkflowStepDto(
                s.getStepIndex(),
                s.getStepKind(),
                s.getStatus(),
                agentSlugOf(s.getStepInputJson()),
                toIso(s.getCreatedAt()),
                toIso(s.getUpdatedAt()));
    }

    private String workflowNameOf(FlywheelRunEntity r) {
        String input = r.getInputJson();
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(input);
            JsonNode name = node.get("workflow_name");
            return name == null || name.isNull() ? null : name.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String agentSlugOf(String stepInputJson) {
        if (stepInputJson == null || stepInputJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(stepInputJson);
            JsonNode slug = node.get("agentSlug");
            return slug == null || slug.isNull() ? null : slug.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String toIso(Instant i) {
        return i == null ? null : i.toString();
    }

    private static int clampLimit(Integer raw) {
        if (raw == null) return DEFAULT_LIMIT;
        if (raw < MIN_LIMIT) return MIN_LIMIT;
        return Math.min(raw, MAX_LIMIT);
    }
}
