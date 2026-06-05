package com.skillforge.server.evolve;

import com.skillforge.server.bootstrap.SystemAgentNames;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.evolve.AgentBundleAdoptionService.AdoptResult;
import com.skillforge.server.evolve.dto.ActivateScenarioResponse;
import com.skillforge.server.evolve.dto.AdoptBundleRequest;
import com.skillforge.server.evolve.dto.CandidateBundle;
import com.skillforge.server.evolve.dto.EvolveRunDetailDto;
import com.skillforge.server.evolve.dto.EvolveRunSummaryDto;
import com.skillforge.server.evolve.dto.HarvestedScenarioDto;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.workflow.WorkflowRunnerService;
import com.skillforge.workflow.exception.WorkflowAlreadyRunningException;
import com.skillforge.workflow.exception.WorkflowNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C0) — on-demand trigger entry for the
 * agent-driven auto-evolving loop.
 *
 * <p>{@code POST /api/evolve/agents/{agentId}/run} creates a
 * {@link FlywheelRunEntity} with {@code loop_kind=evolve} for the target agent,
 * spawns a system session bound to the TOP-LEVEL {@code evolve-orchestrator}
 * agent (V131 seed), and kicks the orchestrator loop with
 * {@link ChatService#chatAsync}. The orchestrator then drives
 * report→candidate→A/B→gate→record itself (see its system prompt).
 *
 * <p>Mirrors {@code FlywheelController.runLoop} +
 * {@code AttributionDispatcherService.dispatchOne} (system session + chatAsync).
 * The orchestrator runs TOP-LEVEL (this C0 session) — NOT as a workflow
 * sub-agent — so its A/B fan-out via {@code TriggerAbEval} stays 2 layers deep
 * (no 3-layer recursion trap; architect R1).
 *
 * <p>Auth: V1 single-tenant dogfood pattern — goes through the same Bearer-token
 * AuthInterceptor on all {@code /api/**} routes (matches FlywheelController).
 */
@RestController
@RequestMapping("/api/evolve")
public class EvolveController {

    private static final Logger log = LoggerFactory.getLogger(EvolveController.class);

    /** SYSTEM user marker — mirrors {@code AttributionDispatcherService.SYSTEM_USER_ID}. */
    static final long SYSTEM_USER_ID = 0L;

    /** Default loop iteration ceiling threaded into the kickoff prompt. */
    static final int DEFAULT_MAX_ITER = 10;
    static final int MIN_MAX_ITER = 1;
    static final int MAX_MAX_ITER = 50;

    /** reportId is embedded into the LLM kickoff prompt — restrict to id-safe chars. */
    static final java.util.regex.Pattern REPORT_ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    /**
     * OPT-REPORT-V1 reused window for the EVOLVE RUN row (the evolve run reuses the
     * FlywheelRun time window columns; the orchestrator itself scopes the report via
     * the opt-report workflow, so this is a defensive default for the row). NOTE:
     * this governs the {@code /run} evolve-run window ONLY — it is unrelated to the
     * harvest endpoint window (see {@link #HARVEST_DEFAULT_WINDOW_DAYS}).
     */
    static final int DEFAULT_EVOLVE_RUN_WINDOW_DAYS = 7;

    /**
     * Default lookback window (days) for the bad-case harvest endpoint when the FE
     * omits {@code windowDays}. Mirrors {@link com.skillforge.server.evolve.BadCaseClusterService}'s
     * clamp range default — keep this in sync with the {@code @RequestParam}
     * literal below (annotation defaults must be compile-time literals).
     */
    static final int HARVEST_DEFAULT_WINDOW_DAYS = 30;

    private final AgentRepository agentRepository;
    private final FlywheelRunService flywheelRunService;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final EvolveReadService evolveReadService;
    private final AgentBundleAdoptionService adoptionService;
    private final HarvestedScenarioService harvestedScenarioService;
    private final WorkflowRunnerService workflowRunnerService;

    /** AUTOEVOLVE-CLOSE-LOOP P1 — the deterministic evolve-loop workflow name. */
    static final String EVOLVE_LOOP_WORKFLOW = "evolve-loop";

    public EvolveController(AgentRepository agentRepository,
                            FlywheelRunService flywheelRunService,
                            SessionService sessionService,
                            ChatService chatService,
                            EvolveReadService evolveReadService,
                            AgentBundleAdoptionService adoptionService,
                            HarvestedScenarioService harvestedScenarioService,
                            WorkflowRunnerService workflowRunnerService) {
        this.agentRepository = agentRepository;
        this.flywheelRunService = flywheelRunService;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.evolveReadService = evolveReadService;
        this.adoptionService = adoptionService;
        this.harvestedScenarioService = harvestedScenarioService;
        this.workflowRunnerService = workflowRunnerService;
    }

    /**
     * Trigger an auto-evolving run for the target agent.
     *
     * @param agentId  target agent (the agent being evolved); 404 if missing.
     * @param maxIter  optional iteration ceiling threaded into the orchestrator
     *                 prompt (clamped to [{@value #MIN_MAX_ITER},
     *                 {@value #MAX_MAX_ITER}], default {@value #DEFAULT_MAX_ITER}).
     *                 NOTE: this is a soft prompt hint — the server-side hard cap
     *                 is FR-C7's per-evolve-run A/B budget on TriggerAbEval.
     */
    @PostMapping("/agents/{agentId}/run")
    public ResponseEntity<?> run(@PathVariable("agentId") Long agentId,
                                 @RequestParam(value = "maxIter", required = false) Integer maxIterRaw,
                                 @RequestParam(value = "reportId", required = false) String reportIdRaw,
                                 @RequestParam(value = "engine", required = false) String engineRaw) {
        if (agentId == null || agentId <= 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "agentId must be a positive long; got " + agentId);
        }
        // AUTOEVOLVE-CLOSE-LOOP P1 — gray-rollout switch. Default stays "orchestrator"
        // (the legacy top-level agent loop) so existing behaviour is unchanged; pass
        // engine=workflow to drive the new deterministic evolve-loop.workflow.js. Both
        // produce a loop_kind=evolve FlywheelRun, so the read API / dashboards are
        // identical regardless of engine. The orchestrator path is NOT retired in P1
        // (instant rollback). Reject any other value.
        String engine = (engineRaw == null || engineRaw.isBlank()) ? "orchestrator" : engineRaw.trim();
        if (!"orchestrator".equals(engine) && !"workflow".equals(engine)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "engine must be 'orchestrator' or 'workflow'; got " + engine);
        }
        int maxIter = clampMaxIter(maxIterRaw);
        // Optional pre-existing completed opt-report to drive the loop from. When
        // present the orchestrator reads it via GetOptReport and SKIPS the live
        // RunWorkflow('opt-report') step (focused-loop path). When absent the
        // orchestrator runs opt-report itself (full-chain path).
        //
        // SECURITY: reportId is embedded verbatim into the orchestrator kickoff
        // prompt, so it must be format-validated (FlywheelRun ids are UUIDs) to
        // block prompt-injection via crafted punctuation/instructions. Reject
        // anything outside [A-Za-z0-9-], length 1..64, with a 400.
        String reportId = (reportIdRaw == null || reportIdRaw.isBlank()) ? null : reportIdRaw.trim();
        if (reportId != null && !REPORT_ID_PATTERN.matcher(reportId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reportId must match " + REPORT_ID_PATTERN.pattern() + "; got: " + reportId);
        }

        // Pre-check: the target agent (being evolved) exists. Like
        // FlywheelController, we don't gate on agent_type — operators may evolve
        // a system agent during dogfood.
        AgentEntity targetAgent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Agent not found: id=" + agentId));

        // HIGH-1 fix (note): per-agent in-flight guard — reject with 409 if this
        // agent already has an active (running / pending) evolve run. Two concurrent
        // evolve loops on the same agent would race on candidates / A/B runs. The
        // guard is applied INSIDE each engine branch (not before) so the legacy
        // orchestrator path keeps its original "orchestrator-missing 503 before the
        // active-run guard" ordering.

        // AUTOEVOLVE-CLOSE-LOOP P1 — new deterministic engine path.
        if ("workflow".equals(engine)) {
            if (flywheelRunService.hasActiveEvolveRun(agentId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "agent " + agentId + " already has an active evolve run; "
                                + "wait for it to complete (or fail) before triggering another");
            }
            return runViaWorkflow(agentId, targetAgent, maxIter, reportId);
        }

        // ── Legacy orchestrator engine (default) ──
        // The orchestrator agent is seeded by V131. A missing row indicates a
        // partially-rolled-out instance — fail fast with 503 rather than firing
        // chatAsync into a void (matches FlywheelController's system-agent guard).
        AgentEntity orchestratorAgent = agentRepository.findFirstByName(SystemAgentNames.EVOLVE_ORCHESTRATOR)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "evolve-orchestrator system agent not seeded; check V131 migration"));

        if (flywheelRunService.hasActiveEvolveRun(agentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "agent " + agentId + " already has an active evolve run; "
                            + "wait for it to complete (or fail) before triggering another");
        }

        // STEP 1: create the evolve-run row (loop_kind=evolve) for the TARGET
        // agent. REUSE FlywheelRun — no new table. Each iteration becomes a
        // step_kind=evolve_iteration row (RecordIteration).
        Map<String, Object> inputJson = new LinkedHashMap<>();
        inputJson.put("targetAgentId", agentId);
        inputJson.put("maxIter", maxIter);
        inputJson.put("orchestratorAgentId", orchestratorAgent.getId());
        if (reportId != null) {
            inputJson.put("reportId", reportId);
        }
        FlywheelRunEntity run = flywheelRunService.startRun(
                FlywheelRunEntity.LOOP_KIND_EVOLVE,
                FlywheelRunEntity.TRIGGER_SOURCE_API,
                inputJson,
                agentId,
                DEFAULT_EVOLVE_RUN_WINDOW_DAYS);

        // STEP 2: spawn the orchestrator system session + kick the loop. The
        // kickoff prompt threads targetAgentId + evolveRunId (+ maxIter) — the
        // orchestrator's system prompt parses these.
        SessionEntity orchestratorSession =
                sessionService.createSession(SYSTEM_USER_ID, orchestratorAgent.getId());
        // attachGeneratorSession transitions the run pending→running and records
        // the orchestrator session id (mirrors OptReportService's pattern).
        flywheelRunService.attachGeneratorSession(run.getId(), orchestratorSession.getId());

        String reportClause = reportId != null
                ? "已提供现成报告 reportId=" + reportId + "（已 completed）：直接 GetOptReport(reportId=" + reportId
                        + ", expectedAgentId=" + agentId + ") 读 issue，跳过 RunWorkflow。"
                : "先 RunWorkflow opt-report 拿归因 report（runId 即 reportId），再 GetOptReport 读 issue。";
        String kickoffPrompt = String.format(
                "targetAgentId=%d evolveRunId=%s maxIter=%d。请按你的系统提示驱动自动进化 loop：%s"
                        + "再对每个 issue 迭代（GenerateCandidate（带 reportId+issueId）→ "
                        + "TriggerAbEval（带 evolveRunId=%s）→ GetAbResult 轮询 → 科学判断是否保留 → "
                        + "RecordIteration），最多 %d 轮，末尾汇总 kept 候选交人定夺，不要直接 promote。",
                agentId, run.getId(), maxIter, reportClause, run.getId(), maxIter);
        chatService.chatAsync(orchestratorSession.getId(), kickoffPrompt, SYSTEM_USER_ID);

        log.info("[Evolve] evolve run started: evolveRunId={} targetAgentId={} orchestratorSessionId={} maxIter={} reportId={}",
                run.getId(), agentId, orchestratorSession.getId(), maxIter, reportId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("evolveRunId", run.getId());
        body.put("sessionId", orchestratorSession.getId());
        body.put("agentId", agentId);
        body.put("agentName", targetAgent.getName());
        body.put("maxIter", maxIter);
        body.put("status", "running");
        // 202 ACCEPTED — the loop is async; the session is queued and the
        // orchestrator will drive it in the background (mirrors FlywheelController
        // .runLoop's accepted() pattern).
        return ResponseEntity.accepted().body(body);
    }

    /**
     * AUTOEVOLVE-CLOSE-LOOP P1 — drive the deterministic {@code evolve-loop}
     * workflow. Creates a {@code loop_kind=evolve} {@link FlywheelRunEntity} via
     * {@link WorkflowRunnerService#startRun(String, java.util.Map, Long, String)}
     * (spike-verified R1: the engine is keyed on runId, not loop_kind, so the
     * evolve read API / {@code hasActiveEvolveRun} / completion listener all work
     * on this run exactly like the orchestrator one).
     *
     * <p>The run is attributed to the TARGET agent (args.agentId) so it shows under
     * the agent's evolve trajectory; the workflow JS reads {@code targetAgentId}.
     * {@code autoApprove=true} routes any inner opt-report sub-flow past its human
     * gate (the human gate is the END-of-loop adopt step).
     */
    private ResponseEntity<?> runViaWorkflow(Long agentId, AgentEntity targetAgent,
                                             int maxIter, String reportId) {
        java.util.Map<String, Object> wfArgs = new LinkedHashMap<>();
        // agentId → run attribution (WorkflowRunnerService.extractAgentId);
        // targetAgentId → consumed by the workflow JS.
        wfArgs.put("agentId", agentId);
        wfArgs.put("targetAgentId", agentId);
        wfArgs.put("maxIter", maxIter);
        // P1 ALWAYS autoApprove=true (design §7 R3): the evolve-loop's humanApprove
        // branch (pause→resume mid-polling) is untested and deferred to P2. The human
        // gate stays the END-of-loop adopt step (POST /runs/{id}/adopt). Do NOT expose
        // an autoApprove=false path here until resume-mid-polling is verified.
        wfArgs.put("autoApprove", true);
        if (reportId != null) {
            wfArgs.put("reportId", reportId);
        }

        String runId;
        try {
            runId = workflowRunnerService.startRun(
                    EVOLVE_LOOP_WORKFLOW, wfArgs, SYSTEM_USER_ID, FlywheelRunEntity.LOOP_KIND_EVOLVE);
        } catch (WorkflowAlreadyRunningException e) {
            // The evolve-loop workflow holds a per-NAME lock, so only one evolve-loop
            // run can be in flight globally at a time (P1 single-tenant dogfood).
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "an evolve-loop workflow run is already in flight; wait for it to finish");
        } catch (WorkflowNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "evolve-loop workflow not registered (check classpath:workflows/evolve-loop.workflow.js)");
        }

        log.info("[Evolve] evolve-loop workflow run started: evolveRunId={} targetAgentId={} maxIter={} reportId={}",
                runId, agentId, maxIter, reportId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("evolveRunId", runId);
        body.put("agentId", agentId);
        body.put("agentName", targetAgent.getName());
        body.put("maxIter", maxIter);
        body.put("engine", "workflow");
        body.put("status", "running");
        return ResponseEntity.accepted().body(body);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Module D — read API (FR-D1 / FR-D3 / FR-D4)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * FR-D4 — list evolve runs for a given agent, newest-first.
     *
     * <p>Returns an {@code {items: [...]} } envelope (footgun #6b: FE must NOT
     * treat the response as a bare array).
     *
     * <p>Response shape:
     * <pre>
     * {
     *   "items": [
     *     {
     *       "evolveRunId":    "...",
     *       "status":         "completed",
     *       "createdAt":      "2026-05-31T10:00:00Z",
     *       "updatedAt":      "2026-05-31T12:00:00Z",
     *       "iterationCount": 5,
     *       "finalDelta":     2.4
     *     },
     *     ...
     *   ]
     * }
     * </pre>
     *
     * @param agentId target agent id; 400 if &lt;= 0.
     * @param limit   max runs to return (default 20, clamped to [1, 100]).
     */
    @GetMapping("/agents/{agentId}/runs")
    public ResponseEntity<?> listRuns(
            @PathVariable("agentId") Long agentId,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        if (agentId == null || agentId <= 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "agentId must be a positive long; got " + agentId);
        }
        List<EvolveRunSummaryDto> items = evolveReadService.listRunsForAgent(agentId, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    /**
     * FR-D3 — full detail for a single evolve run, including all recorded
     * iterations (trajectory).
     *
     * <p>Returns a single JSON object (NOT enveloped). 404 if the run is not
     * found or its {@code loop_kind} is not {@code evolve}.
     *
     * <p>Response shape:
     * <pre>
     * {
     *   "evolveRunId":  "...",
     *   "agentId":      7,
     *   "agentName":    "my-agent",
     *   "status":       "completed",
     *   "createdAt":    "2026-05-31T10:00:00Z",
     *   "updatedAt":    "2026-05-31T12:30:00Z",
     *   "iterations": [
     *     {
     *       "iteration":      1,
     *       "surface":        "prompt",
     *       "changeDesc":     "Tightened the system prompt greeting.",
     *       "candidateId":    "cand-abc",
     *       "baselineScore":  72.5,
     *       "candidateScore": 74.9,
     *       "delta":          2.4,
     *       "kept":           true,
     *       "abRunId":        "ab-xyz",
     *       "createdAt":      "2026-05-31T10:05:00Z"
     *     }
     *   ]
     * }
     * </pre>
     *
     * @param evolveRunId the run UUID
     */
    @GetMapping("/runs/{evolveRunId}")
    public ResponseEntity<?> getRunDetail(
            @PathVariable("evolveRunId") String evolveRunId) {
        if (evolveRunId == null || evolveRunId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "evolveRunId is required");
        }
        EvolveRunDetailDto detail = evolveReadService.getRunDetail(evolveRunId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Evolve run not found or not an evolve run: " + evolveRunId));
        return ResponseEntity.ok(detail);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AUTOEVOLVE-CLOSE-LOOP P1 — human adoption of a winning candidate bundle
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Adopt a winning candidate bundle from an evolve run onto the run's target
     * agent. The three surfaces (prompt / behavior_rule / skill) are adopted in
     * independent transactions — a failure on one does not roll back another
     * (AC-4). Returns the bare {@link AdoptResult} (NOT enveloped).
     *
     * <p>Validation chain (fail-fast):
     * <ol>
     *   <li>{@code userId} null or the SYSTEM user (0) → 400 (the system user
     *       must not push config through the human-adopt path).</li>
     *   <li>{@code evolveRunId} blank → 400.</li>
     *   <li>run not found OR not an {@code evolve} run → 404.</li>
     *   <li>request body absent or all pointers null → 400 (nothing to adopt).</li>
     *   <li>each non-null pointer must originate from a <em>kept</em> iteration's
     *       candidate bundle of THIS run → else 400 (privilege-escalation guard:
     *       a caller can't adopt an arbitrary cross-run/cross-agent version).</li>
     *   <li>{@link AgentBundleAdoptionService#adopt} ownership mismatch → 400.</li>
     * </ol>
     *
     * @param evolveRunId the evolve run the bundle belongs to
     * @param userId      operator id (must be a non-system user)
     * @param body        the bundle pointers to adopt
     */
    @PostMapping("/runs/{evolveRunId}/adopt")
    public ResponseEntity<?> adopt(
            @PathVariable("evolveRunId") String evolveRunId,
            @RequestParam("userId") Long userId,
            @RequestBody(required = false) AdoptBundleRequest body) {
        // 1) system / missing user guard
        if (userId == null || userId == SYSTEM_USER_ID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "userId must be a non-system user to adopt a bundle");
        }
        // 2) run id
        if (evolveRunId == null || evolveRunId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "evolveRunId is required");
        }
        // 3) run lookup — getRunDetail returns empty for missing OR non-evolve
        EvolveRunDetailDto detail = evolveReadService.getRunDetail(evolveRunId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Evolve run not found or not an evolve run: " + evolveRunId));

        // 4) at least one pointer
        String promptVersionId = body == null ? null : nullIfBlank(body.promptVersionId());
        String behaviorRuleVersionId = body == null ? null : nullIfBlank(body.behaviorRuleVersionId());
        String skillDraftId = body == null ? null : nullIfBlank(body.skillDraftId());
        if (promptVersionId == null && behaviorRuleVersionId == null && skillDraftId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "at least one of promptVersionId / behaviorRuleVersionId / skillDraftId is required");
        }

        // 5) bundle source guard — every non-null pointer must come from a kept
        // iteration's bundle of THIS run.
        List<CandidateBundle> keptBundles = evolveReadService.listKeptCandidateBundles(evolveRunId);
        if (!matchesKeptBundle(keptBundles, promptVersionId, behaviorRuleVersionId, skillDraftId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "the requested pointers do not match any kept iteration's candidate bundle "
                            + "for evolve run " + evolveRunId);
        }

        // 6) the target agent is the run's agent
        String agentId = String.valueOf(detail.agentId());

        // 7) adopt — ownership mismatch surfaces as 400
        try {
            AdoptResult result = adoptionService.adopt(
                    new CandidateBundle(promptVersionId, behaviorRuleVersionId, skillDraftId),
                    agentId, userId);
            log.info("[Evolve] adopt completed: evolveRunId={} agentId={} userId={} anyFailed={}",
                    evolveRunId, agentId, userId, result.anyFailed());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // AUTOEVOLVE-CLOSE-LOOP BC-M2b — harvested bad-case scenarios (activate flow)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Human-gated activation of a harvested (session-derived) draft scenario into
     * its agent's mixed eval dataset. Returns the bare {@link ActivateScenarioResponse}
     * (NOT enveloped, mirrors adopt / run-detail).
     *
     * <p><b>Iron-Law human gate:</b> {@code userId} null or the SYSTEM user (0) is
     * rejected with 400 — the orchestrator (which acts as the system user) can
     * never activate a draft; activation is a human decision.
     *
     * @param scenarioId the harvested draft scenario id
     * @param userId     operator id (must be a non-system user)
     */
    @PostMapping("/scenarios/{scenarioId}/activate")
    public ResponseEntity<?> activateScenario(
            @PathVariable("scenarioId") String scenarioId,
            @RequestParam("userId") Long userId) {
        if (userId == null || userId == SYSTEM_USER_ID) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "userId must be a non-system user to activate a harvested scenario");
        }
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scenarioId is required");
        }
        try {
            ActivateScenarioResponse resp = harvestedScenarioService.activate(scenarioId, userId);
            log.info("[Evolve] harvested scenario activated: scenarioId={} userId={} datasetVersionId={}",
                    scenarioId, userId, resp.datasetVersionId());
            return ResponseEntity.ok(resp);
        } catch (HarvestedScenarioService.ScenarioNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            // wrong lifecycle status (not draft) → 409
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            // wrong source_type / missing agentId / blank id → 400
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * List an agent's harvested (session-derived) scenarios in a given lifecycle
     * status. Returns an {@code {items: [...]}} envelope (footgun #6b).
     *
     * @param agentId    target agent id (&gt; 0)
     * @param status     lifecycle status filter ({@code draft} | {@code active}); default {@code draft}
     * @param sourceType optional; when present must be {@code session_derived} (the only supported value)
     */
    @GetMapping("/agents/{agentId}/scenarios")
    public ResponseEntity<?> listHarvestedScenarios(
            @PathVariable("agentId") Long agentId,
            @RequestParam(value = "status", required = false, defaultValue = "draft") String status,
            @RequestParam(value = "sourceType", required = false) String sourceType) {
        if (agentId == null || agentId <= 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "agentId must be a positive long; got " + agentId);
        }
        if (!"draft".equals(status) && !"active".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be 'draft' or 'active'; got " + status);
        }
        if (sourceType != null && !sourceType.isBlank()
                && !"session_derived".equals(sourceType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sourceType must be 'session_derived' (the only harvested source); got " + sourceType);
        }
        List<HarvestedScenarioDto> items =
                harvestedScenarioService.listHarvestedScenarios(String.valueOf(agentId), status);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    /**
     * Cluster the agent's recent failed tool calls and rebuild one DRAFT bad-case
     * scenario per harvestable cluster (deterministic replay — no LLM, no remedy).
     * Returns an {@code {items: [draftIds], count}} envelope. The drafts still
     * require human activation before they enter any A/B run.
     *
     * @param agentId    target agent id (&gt; 0)
     * @param windowDays lookback window for failure clustering (default 30)
     */
    @PostMapping("/agents/{agentId}/harvest-bad-cases")
    public ResponseEntity<?> harvestBadCases(
            @PathVariable("agentId") Long agentId,
            // defaultValue must be a literal; keep it == HARVEST_DEFAULT_WINDOW_DAYS (30).
            @RequestParam(value = "windowDays", required = false, defaultValue = "30") int windowDays) {
        if (agentId == null || agentId <= 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "agentId must be a positive long; got " + agentId);
        }
        List<String> created = harvestedScenarioService.harvestBadCases(agentId, windowDays);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", created);
        body.put("count", created.size());
        log.info("[Evolve] harvest-bad-cases agentId={} windowDays={} created={}",
                agentId, windowDays, created.size());
        return ResponseEntity.ok(body);
    }

    /**
     * AUTOEVOLVE-CLOSE-LOOP P1 — lightweight skill-draft read for the adopt card
     * diff (the FE needs the draft's promptHint / triggers / requiredTools to
     * render the skill surface). Kept here (not in SkillDraftController) to avoid
     * widening that controller's scope; returns a minimal projection. 404 when
     * the draft does not exist.
     */
    @GetMapping("/skill-drafts/{draftId}")
    public ResponseEntity<?> getSkillDraft(@PathVariable("draftId") String draftId) {
        if (draftId == null || draftId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "draftId is required");
        }
        // Delegates to the read service (design review W2 — controller no longer
        // touches SkillDraftRepository directly; the read runs in a readOnly tx).
        Map<String, Object> body = evolveReadService.getSkillDraftView(draftId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Skill draft not found: " + draftId));
        return ResponseEntity.ok(body);
    }

    /**
     * True iff some kept bundle matches ALL the non-null request pointers (each
     * non-null pointer equals that field of the SAME kept bundle). A null
     * request pointer is a wildcard. Empty {@code keptBundles} → never matches.
     */
    private static boolean matchesKeptBundle(List<CandidateBundle> keptBundles,
                                             String promptVersionId,
                                             String behaviorRuleVersionId,
                                             String skillDraftId) {
        for (CandidateBundle b : keptBundles) {
            boolean promptOk = promptVersionId == null || promptVersionId.equals(b.promptVersionId());
            boolean ruleOk = behaviorRuleVersionId == null
                    || behaviorRuleVersionId.equals(b.behaviorRuleVersionId());
            boolean skillOk = skillDraftId == null || skillDraftId.equals(b.skillDraftId());
            if (promptOk && ruleOk && skillOk) {
                return true;
            }
        }
        return false;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static int clampMaxIter(Integer raw) {
        if (raw == null) return DEFAULT_MAX_ITER;
        if (raw < MIN_MAX_ITER) return MIN_MAX_ITER;
        return Math.min(raw, MAX_MAX_ITER);
    }
}
