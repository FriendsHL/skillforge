package com.skillforge.server.improve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.entity.SkillEvalHistoryEntity;
import com.skillforge.server.event.SkillAbCompletedEvent;
import com.skillforge.server.event.SkillAbCompletedEventPublisher;
import com.skillforge.server.eval.EvalEngineFactory;
import com.skillforge.server.eval.EvalJudgeOutput;
import com.skillforge.server.eval.EvalJudgeMultiTurnOutput;
import com.skillforge.server.eval.EvalJudgeTool;
import com.skillforge.server.eval.MultiTurnTranscript;
import com.skillforge.server.eval.EvalScoreFormula;
import com.skillforge.server.eval.ScenarioRunResult;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.eval.scenario.ScenarioLoader;
import com.skillforge.server.repository.EvalTaskRepository;
import com.skillforge.server.repository.SkillAbRunRepository;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
public class SkillAbEvalService {

    private static final Logger log = LoggerFactory.getLogger(SkillAbEvalService.class);
    private static final double PROMOTION_DELTA_THRESHOLD_PP = 15.0;
    private static final double PROMOTION_MIN_CANDIDATE_RATE_PP = 40.0;

    private final SkillRepository skillRepository;
    private final SkillAbRunRepository skillAbRunRepository;
    private final EvalTaskRepository evalRunRepository;
    private final SkillEvalHistoryRepository skillEvalHistoryRepository;
    private final AgentService agentService;
    private final ScenarioLoader scenarioLoader;
    private final SandboxSkillRegistryFactory sandboxFactory;
    private final EvalEngineFactory evalEngineFactory;
    private final EvalJudgeTool evalJudgeTool;
    private final SkillPackageLoader skillPackageLoader;
    private final ObjectMapper objectMapper;
    private final ChatEventBroadcaster broadcaster;
    private final ExecutorService coordinatorExecutor;
    private final ExecutorService loopExecutor;
    private final SkillRegistry skillRegistry;
    private final SkillAbCompletedEventPublisher abCompletedEventPublisher;

    public SkillAbEvalService(SkillRepository skillRepository,
                              SkillAbRunRepository skillAbRunRepository,
                              EvalTaskRepository evalRunRepository,
                              SkillEvalHistoryRepository skillEvalHistoryRepository,
                              AgentService agentService,
                              ScenarioLoader scenarioLoader,
                              SandboxSkillRegistryFactory sandboxFactory,
                              EvalEngineFactory evalEngineFactory,
                              EvalJudgeTool evalJudgeTool,
                              SkillPackageLoader skillPackageLoader,
                              ObjectMapper objectMapper,
                              ChatEventBroadcaster broadcaster,
                              @Qualifier("abEvalCoordinatorExecutor") ExecutorService coordinatorExecutor,
                              @Qualifier("abEvalLoopExecutor") ExecutorService loopExecutor,
                              SkillRegistry skillRegistry,
                              SkillAbCompletedEventPublisher abCompletedEventPublisher) {
        this.skillRepository = skillRepository;
        this.skillAbRunRepository = skillAbRunRepository;
        this.evalRunRepository = evalRunRepository;
        this.skillEvalHistoryRepository = skillEvalHistoryRepository;
        this.agentService = agentService;
        this.scenarioLoader = scenarioLoader;
        this.sandboxFactory = sandboxFactory;
        this.evalEngineFactory = evalEngineFactory;
        this.evalJudgeTool = evalJudgeTool;
        this.skillPackageLoader = skillPackageLoader;
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
        this.coordinatorExecutor = coordinatorExecutor;
        this.loopExecutor = loopExecutor;
        this.skillRegistry = skillRegistry;
        this.abCompletedEventPublisher = abCompletedEventPublisher;
    }

    public SkillAbRunEntity createAndTrigger(Long parentSkillId, Long candidateSkillId,
                                              String agentId, String baselineEvalRunId,
                                              Long triggeredByUserId) {
        SkillEntity parent = skillRepository.findById(parentSkillId)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + parentSkillId));
        SkillEntity candidate = skillRepository.findById(candidateSkillId)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + candidateSkillId));

        if (candidate.getParentSkillId() == null || !candidate.getParentSkillId().equals(parentSkillId)) {
            throw new RuntimeException("Candidate skill is not a fork of parent");
        }
        if (candidate.isEnabled()) {
            throw new RuntimeException("Candidate skill is already promoted");
        }

        boolean inProgress = skillAbRunRepository
                .findByCandidateSkillIdOrderByStartedAtDesc(candidateSkillId).stream()
                .anyMatch(r -> "RUNNING".equals(r.getStatus()) || "PENDING".equals(r.getStatus()));
        if (inProgress) {
            throw new RuntimeException("An A/B run is already in progress for this candidate");
        }

        SkillAbRunEntity abRun = new SkillAbRunEntity();
        abRun.setId(UUID.randomUUID().toString());
        abRun.setParentSkillId(parentSkillId);
        abRun.setCandidateSkillId(candidateSkillId);
        abRun.setAgentId(agentId);
        abRun.setBaselineEvalRunId(baselineEvalRunId);
        abRun.setStatus("PENDING");
        abRun.setTriggeredByUserId(triggeredByUserId);
        SkillAbRunEntity saved = skillAbRunRepository.save(abRun);

        try {
            coordinatorExecutor.submit(() -> runAbTestAsync(saved.getId()));
        } catch (RejectedExecutionException e) {
            saved.setStatus("FAILED");
            saved.setFailureReason("Coordinator executor rejected task: " + e.getMessage());
            skillAbRunRepository.save(saved);
            throw new RuntimeException("Failed to schedule A/B test: executor is full or shutdown", e);
        }
        log.info("Created skill AB run: id={} parentSkillId={} candidateSkillId={}",
                saved.getId(), parentSkillId, candidateSkillId);
        return saved;
    }

    /**
     * SKILL-EVOLVE-LOOP Phase 2: run held-out scenarios against the current skill (no fork,
     * no candidate, no delta) and persist a row in {@code t_skill_eval_history}.
     *
     * <p>Synchronous on purpose — caller is either the Phase 2 manual REST endpoint
     * (FE waits for composite_score) or {@link SkillScheduledEvaluator} (cron iterates
     * skills serially with per-skill try/catch INV-2). Each scenario is timeboxed by
     * {@link #runSingleScenario}, so one slow LLM call can't hang the whole task.
     *
     * <p>{@code datasetId} is reserved for V2 multi-dataset support (PRD §V2 pipeline)
     * and currently ignored — the held_out split of {@link ScenarioLoader#loadAll()}
     * is always used. Logged at INFO when a non-null datasetId is supplied so the
     * deferral is visible in operator logs.
     *
     * @param triggeredBy {@code "manual"} (REST endpoint) or {@code "scheduled"}
     *                    (cron); enforced by DB CHECK chk_seh_triggered_by.
     */
    public SkillEvalHistoryEntity runBaselineOnly(Long skillId, String agentId, Long userId,
                                                  String datasetId, String triggeredBy) {
        if (triggeredBy == null
                || !("manual".equals(triggeredBy) || "scheduled".equals(triggeredBy))) {
            throw new IllegalArgumentException("triggeredBy must be 'manual' or 'scheduled'");
        }
        if (datasetId != null && !datasetId.isBlank()) {
            log.info("runBaselineOnly: datasetId='{}' ignored — V1 always uses scenarioLoader held_out",
                    datasetId);
        }

        SkillEntity skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + skillId));
        SkillDefinition skillDef = buildSkillDefinition(skill);

        long agentIdLong;
        try {
            agentIdLong = Long.parseLong(agentId);
        } catch (NumberFormatException e) {
            // W5 r1 fix: don't echo NumberFormatException.getMessage() (which contains the
            // raw input "For input string: \"...\"") back to clients via the controller's
            // 400 response — return a structured, input-free message instead.
            throw new IllegalArgumentException("agentId must be numeric");
        }
        AgentEntity agentEntity = agentService.getAgent(agentIdLong);
        AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);

        List<EvalScenario> scenarios = scenarioLoader.loadAll().stream()
                .filter(s -> "held_out".equals(s.getSplit()))
                .toList();
        if (scenarios.isEmpty()) {
            log.warn("No held_out scenarios found for runBaselineOnly skill={}, falling back to all", skillId);
            scenarios = scenarioLoader.loadAll();
        }

        String runId = "baseline-" + UUID.randomUUID();

        List<EvalScoreFormula.Result> scoreResults = new ArrayList<>();
        int errors = 0;

        for (EvalScenario scenario : scenarios) {
            log.info("runBaselineOnly skill={} scenario={} ({})", skillId, scenario.getId(), scenario.getName());
            try {
                ScenarioRunResult runResult = runSingleScenario(runId, scenario, agentDef, skillDef);
                EvalJudgeOutput judgeOutput = evalJudgeTool.judge(scenario, runResult);

                EvalScoreFormula.Result scoreResult = EvalScoreFormula.calculate(
                        judgeOutput.getOutcomeScore(),
                        judgeOutput.getEfficiencyScore(),
                        runResult.getExecutionTimeMs(),
                        scenario.getPerformanceThresholdMs(),
                        runResult.getCostUsd(),
                        null,
                        runResult.getLoopCount(),
                        runResult.getToolCallCount());

                scoreResults.add(scoreResult);
            } catch (Exception e) {
                errors++;
                log.warn("runBaselineOnly scenario {} failed: {}", scenario.getId(), e.getMessage());
            }
        }

        int counted = scoreResults.size();
        SkillEvalHistoryEntity history = aggregateBaselineHistory(scoreResults);
        history.setSkillId(skillId);
        history.setEvalRunId(runId);
        history.setTriggeredBy(triggeredBy);
        // createdAt set via @PrePersist on save (W4 r1).
        SkillEvalHistoryEntity saved = skillEvalHistoryRepository.save(history);

        log.info("runBaselineOnly persisted history skill={} composite={} counted={} errors={} triggeredBy={} userId={}",
                skillId, saved.getCompositeScore(), counted, errors, triggeredBy, userId);
        return saved;
    }

    /**
     * W1 r1 fix — service-layer surface for {@code GET /api/skills/{id}/eval-history}.
     * Owns both the repo query and the wire-format projection so the controller stays
     * thin (no Repository injection, no presentation logic mixed with HTTP routing).
     *
     * @param limit FE-supplied page size; clamped to {@code [1, 100]} here so the
     *              controller cannot accidentally pass through unbounded values.
     * @return rows newest-first, each as a flat map matching the FE payload shape
     *         (skillId / compositeScore / 4 dim scores / triggeredBy / createdAt).
     */
    public List<Map<String, Object>> getEvalHistoryForSkill(Long skillId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return skillEvalHistoryRepository
                .findBySkillIdOrderByCreatedAtDesc(skillId, PageRequest.of(0, safeLimit))
                .stream()
                .map(SkillAbEvalService::toEvalHistoryMap)
                .collect(Collectors.toList());
    }

    /**
     * Aggregate per-scenario {@link EvalScoreFormula.Result}s into a single
     * {@link SkillEvalHistoryEntity}. Extracted from {@code runBaselineOnly} so
     * the null-safe latency average (M4_V2) is unit-testable without mocking
     * the full scenario runner pipeline.
     *
     * <p>Contract:
     * <ul>
     *   <li>Empty input → composite=0, all dimension scores null
     *       (mirrors legacy "no scenarios succeeded" behaviour for INV-4
     *       evolve-trigger).</li>
     *   <li>{@code latencyScore == null} samples (not_measured) are excluded
     *       from the latency average; if every sample is not_measured the
     *       persisted latency average is itself null.</li>
     *   <li>Quality / efficiency / cost / composite averages always use
     *       {@code scoreResults.size()} as divisor — they are always measured.</li>
     * </ul>
     *
     * <p>Package-private (no access modifier) so the same-package test class
     * can drive it without touching the {@code @Transactional} entry point.
     */
    static SkillEvalHistoryEntity aggregateBaselineHistory(List<EvalScoreFormula.Result> scoreResults) {
        SkillEvalHistoryEntity history = new SkillEvalHistoryEntity();
        if (scoreResults == null || scoreResults.isEmpty()) {
            // No scenarios succeeded — record 0 composite, leave dim scores null.
            // INV-4 callers see a 0 score and (intentionally) trigger evolve next cron.
            history.setCompositeScore(0.0);
            return history;
        }

        double sumComposite = 0.0;
        double sumQuality = 0.0;
        double sumEfficiency = 0.0;
        double sumLatency = 0.0;
        double sumCost = 0.0;
        int latencyMeasured = 0;

        for (EvalScoreFormula.Result r : scoreResults) {
            sumComposite += r.compositeScore();
            sumQuality += r.qualityScore();
            sumEfficiency += r.efficiencyScore();
            Double latency = r.latencyScore();
            if (latency != null) {
                sumLatency += latency;
                latencyMeasured++;
            }
            sumCost += r.costScore();
        }

        double divisor = scoreResults.size();
        history.setCompositeScore(sumComposite / divisor);
        history.setQualityScore(sumQuality / divisor);
        history.setEfficiencyScore(sumEfficiency / divisor);
        history.setLatencyScore(latencyMeasured == 0 ? null : sumLatency / latencyMeasured);
        history.setCostScore(sumCost / divisor);
        return history;
    }

    private static Map<String, Object> toEvalHistoryMap(SkillEvalHistoryEntity h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("skillId", h.getSkillId());
        m.put("evalRunId", h.getEvalRunId());
        m.put("compositeScore", h.getCompositeScore());
        m.put("qualityScore", h.getQualityScore());
        m.put("efficiencyScore", h.getEfficiencyScore());
        m.put("latencyScore", h.getLatencyScore());
        m.put("costScore", h.getCostScore());
        m.put("triggeredBy", h.getTriggeredBy());
        m.put("createdAt", h.getCreatedAt());
        return m;
    }

    private void runAbTestAsync(String abRunId) {
        SkillAbRunEntity abRun = skillAbRunRepository.findById(abRunId).orElse(null);
        if (abRun == null) {
            log.warn("SkillAbRun not found for async execution: {}", abRunId);
            return;
        }
        try {
            abRun.setStatus("RUNNING");
            abRun.setStartedAt(Instant.now());
            skillAbRunRepository.save(abRun);

            List<EvalScenario> heldOutScenarios = scenarioLoader.loadAll().stream()
                    .filter(s -> "held_out".equals(s.getSplit()))
                    .toList();
            if (heldOutScenarios.isEmpty()) {
                log.warn("No held_out scenarios found, using all scenarios for skill AB eval");
                heldOutScenarios = scenarioLoader.loadAll();
            }

            double baselineRate = 0.0;
            if (abRun.getBaselineEvalRunId() != null) {
                Optional<EvalTaskEntity> baselineRun = evalRunRepository.findById(abRun.getBaselineEvalRunId());
                if (baselineRun.isPresent()) {
                    baselineRate = computeHeldOutBaselineRate(baselineRun.get(), heldOutScenarios);
                }
            }
            abRun.setBaselinePassRate(baselineRate);

            SkillEntity candidateSkill = skillRepository.findById(abRun.getCandidateSkillId())
                    .orElseThrow(() -> new RuntimeException("Candidate skill vanished: " + abRun.getCandidateSkillId()));
            SkillDefinition candidateSkillDef = buildSkillDefinition(candidateSkill);

            AgentEntity agentEntity = agentService.getAgent(Long.parseLong(abRun.getAgentId()));
            AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);

            List<AbScenarioResult> scenarioResults = new ArrayList<>();
            int passed = 0;
            for (EvalScenario scenario : heldOutScenarios) {
                log.info("Skill AB eval scenario: {} ({})", scenario.getId(), scenario.getName());
                String candidateStatus;
                double candidateScore;
                try {
                    if (scenario.isMultiTurn()) {
                        MultiTurnTranscript transcript = new MultiTurnTranscript();
                        ScenarioRunResult runResult = runMultiTurnScenario(
                                abRunId, scenario, agentDef, candidateSkillDef, transcript);
                        EvalJudgeMultiTurnOutput judgeOutput = evalJudgeTool.judgeMultiTurnConversation(
                                scenario, runResult, transcript);
                        if ("PENDING_JUDGE".equals(runResult.getStatus())) {
                            runResult.setStatus(judgeOutput.isPass() ? "PASS" : "FAIL");
                        }
                        candidateStatus = runResult.getStatus();
                        candidateScore = judgeOutput.getCompositeScore();
                        if (judgeOutput.isPass()) {
                            passed++;
                        }
                    } else {
                        ScenarioRunResult runResult = runSingleScenario(abRunId, scenario, agentDef, candidateSkillDef);
                        EvalJudgeOutput judgeOutput = evalJudgeTool.judge(scenario, runResult);
                        candidateStatus = runResult.getStatus();
                        candidateScore = judgeOutput.getCompositeScore();
                        if (judgeOutput.isPass()) {
                            passed++;
                        }
                    }
                } catch (Exception e) {
                    log.error("Skill AB eval scenario {} failed: {}", scenario.getId(), e.getMessage());
                    candidateStatus = "ERROR";
                    candidateScore = 0.0;
                }

                scenarioResults.add(new AbScenarioResult(
                        scenario.getId(), scenario.getName(),
                        new AbScenarioResult.RunResult("UNKNOWN", 0.0),
                        new AbScenarioResult.RunResult(candidateStatus, candidateScore)));

                if (broadcaster != null && abRun.getTriggeredByUserId() != null) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("type", "ab_skill_scenario_finished");
                    event.put("abRunId", abRun.getId());
                    event.put("scenarioId", scenario.getId());
                    event.put("candidateStatus", candidateStatus);
                    event.put("candidateScore", candidateScore);
                    broadcaster.userEvent(abRun.getTriggeredByUserId(), event);
                }
            }

            double candidatePassRate = heldOutScenarios.isEmpty() ? 0
                    : (double) passed / heldOutScenarios.size() * 100;
            double delta = candidatePassRate - baselineRate;

            try {
                abRun.setAbScenarioResultsJson(objectMapper.writeValueAsString(scenarioResults));
            } catch (Exception e) {
                log.warn("Failed to serialize skill AB scenario results", e);
            }

            abRun.setCandidatePassRate(candidatePassRate);
            abRun.setDeltaPassRate(delta);
            abRun.setStatus("COMPLETED");
            abRun.setCompletedAt(Instant.now());

            boolean shouldPromote = delta >= PROMOTION_DELTA_THRESHOLD_PP
                    && candidatePassRate >= PROMOTION_MIN_CANDIDATE_RATE_PP;
            if (shouldPromote) {
                promoteCandidate(candidateSkill);
                abRun.setPromoted(true);
            } else {
                abRun.setSkipReason(String.format(
                        "delta=%.2f candidateRate=%.2f (thresholds: delta>=%.1f, rate>=%.1f)",
                        delta, candidatePassRate, PROMOTION_DELTA_THRESHOLD_PP, PROMOTION_MIN_CANDIDATE_RATE_PP));
            }

            skillAbRunRepository.save(abRun);

            if (broadcaster != null && abRun.getTriggeredByUserId() != null) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", "ab_skill_run_completed");
                event.put("abRunId", abRun.getId());
                event.put("status", abRun.getStatus());
                event.put("promoted", abRun.isPromoted());
                event.put("baselinePassRate", baselineRate);
                event.put("candidatePassRate", candidatePassRate);
                event.put("deltaPassRate", delta);
                event.put("skipReason", abRun.getSkipReason());
                broadcaster.userEvent(abRun.getTriggeredByUserId(), event);
            }

            log.info("Skill AB eval completed: abRunId={}, candidateRate={}, baselineRate={}, delta={}, promoted={}",
                    abRun.getId(), candidatePassRate, baselineRate, delta, abRun.isPromoted());

            // INV-10: publish AFTER all DB writes so listeners (WS push, future hooks)
            // observe the final state. Wrapped publisher opens a TX so the
            // @TransactionalEventListener(AFTER_COMMIT) on the listener side fires.
            // Best-effort — never let a publish/listener failure poison the A/B run.
            tryPublishAbCompleted(abRun);
        } catch (Exception e) {
            log.error("Skill AB eval failed: abRunId={}", abRunId, e);
            try {
                abRun.setStatus("FAILED");
                abRun.setFailureReason(e.getMessage());
                abRun.setCompletedAt(Instant.now());
                skillAbRunRepository.save(abRun);
                if (broadcaster != null && abRun.getTriggeredByUserId() != null) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("type", "ab_skill_run_completed");
                    event.put("abRunId", abRun.getId());
                    event.put("status", "FAILED");
                    event.put("failureReason", e.getMessage());
                    broadcaster.userEvent(abRun.getTriggeredByUserId(), event);
                }
                // INV-10: still publish so SelfImproveLoop knows this candidate
                // didn't promote (won't push WS toast — INV-9 — but logs visible).
                tryPublishAbCompleted(abRun);
            } catch (Exception saveErr) {
                log.error("Failed to persist FAILED status for abRunId={}", abRunId, saveErr);
            }
        }
    }

    /**
     * Best-effort wrapper around {@link SkillAbCompletedEventPublisher#publish}
     * — looks up parent + candidate semvers, computes the score deltas the
     * listener WS payload needs, and never throws (a failed publish must not
     * mark a successful A/B run as failed).
     */
    private void tryPublishAbCompleted(SkillAbRunEntity abRun) {
        try {
            String oldVersion = null;
            String newVersion = null;
            try {
                SkillEntity parent = skillRepository.findById(abRun.getParentSkillId()).orElse(null);
                if (parent != null) oldVersion = parent.getSemver();
                SkillEntity candidate = skillRepository.findById(abRun.getCandidateSkillId()).orElse(null);
                if (candidate != null) newVersion = candidate.getSemver();
            } catch (Exception lookupErr) {
                log.warn("tryPublishAbCompleted: semver lookup failed for abRunId={}: {}",
                        abRun.getId(), lookupErr.getMessage());
            }
            double baselineScore = abRun.getBaselinePassRate() != null ? abRun.getBaselinePassRate() : 0.0;
            double candidateScore = abRun.getCandidatePassRate() != null ? abRun.getCandidatePassRate() : 0.0;
            SkillAbCompletedEvent event = new SkillAbCompletedEvent(
                    abRun.getParentSkillId(),
                    abRun.getId(),
                    abRun.isPromoted(),
                    baselineScore,
                    candidateScore,
                    oldVersion,
                    newVersion);
            abCompletedEventPublisher.publish(event);
        } catch (Exception e) {
            log.warn("Failed to publish SkillAbCompletedEvent for abRunId={}: {}",
                    abRun.getId(), e.getMessage());
        }
    }

    /**
     * SKILL-DASHBOARD-POLISH D — manual promote override. When the auto-promote
     * thresholds (delta ≥ {@value #PROMOTION_DELTA_THRESHOLD_PP}pp AND candidate
     * rate ≥ {@value #PROMOTION_MIN_CANDIDATE_RATE_PP}pp) reject a candidate but
     * the operator wants to promote anyway, this endpoint reuses the same
     * V64-safe {@link #promoteCandidate} path and stamps the abRun with
     * {@code promoted=true}.
     *
     * <p>Pre-conditions: abRun must exist and be in {@code COMPLETED} status (we
     * never manually promote a still-RUNNING run, since {@link #promoteCandidate}
     * would race with the async coordinator).
     *
     * <p>Idempotency: if the abRun already has {@code promoted=true} we throw —
     * the caller should not be trying to promote twice; if the candidate has
     * already been disabled (rolled back) this surfaces a clear error.
     *
     * <p>Side-effects: this path deliberately does NOT publish
     * {@link SkillAbCompletedEvent}. INV-9 is "auto-promote pushes a WS toast";
     * manual promote is initiated by the user themselves so a toast is duplicate UI noise.
     *
     * @param abRunId id of a {@code COMPLETED} A/B run whose candidate should be promoted
     * @param userId  caller user id (logged for audit only)
     * @return the updated {@link SkillAbRunEntity} with {@code promoted=true}
     */
    @Transactional
    public SkillAbRunEntity manualPromote(String abRunId, Long userId) {
        SkillAbRunEntity abRun = skillAbRunRepository.findById(abRunId)
                .orElseThrow(() -> new RuntimeException("A/B run not found: " + abRunId));
        if (!"COMPLETED".equals(abRun.getStatus())) {
            throw new RuntimeException("A/B run is not in COMPLETED status (current: "
                    + abRun.getStatus() + "): " + abRunId);
        }
        if (abRun.isPromoted()) {
            throw new RuntimeException("A/B run is already promoted: " + abRunId);
        }
        SkillEntity candidate = skillRepository.findById(abRun.getCandidateSkillId())
                .orElseThrow(() -> new RuntimeException(
                        "Candidate skill vanished: " + abRun.getCandidateSkillId()));
        if (candidate.isEnabled()) {
            throw new RuntimeException("Candidate skill is already enabled: "
                    + abRun.getCandidateSkillId());
        }
        // Reuse promoteCandidate — it owns the V64 partial-unique-safe ordering
        // (disable parent + flush, then enable candidate) and registry re-registration.
        promoteCandidate(candidate);

        abRun.setPromoted(true);
        // Append a marker to skipReason so the UI can distinguish manual vs auto promote
        // without adding a new column. INV: only call existing setter (no schema change).
        String prefix = abRun.getSkipReason() == null ? "" : abRun.getSkipReason() + " | ";
        abRun.setSkipReason(prefix + "manual override (userId=" + userId + ")");
        SkillAbRunEntity saved = skillAbRunRepository.save(abRun);

        log.info("Manually promoted abRun={} candidateSkillId={} parentSkillId={} by userId={}",
                abRunId, abRun.getCandidateSkillId(), abRun.getParentSkillId(), userId);
        // Note: deliberately NOT calling tryPublishAbCompleted here — the user
        // initiated this action so a "promoted!" WS toast would be duplicate UI noise
        // (INV-9: auto-promote pushes a toast; manual promote does not).
        return saved;
    }

    @Transactional
    public void promoteCandidate(SkillEntity candidateSkill) {
        SkillEntity parentSkill = skillRepository.findById(candidateSkill.getParentSkillId())
                .orElse(null);
        // V64: unique index on (owner_id, name) is partial WHERE enabled=true.
        // Disable parent FIRST, flush, then enable candidate — otherwise both
        // rows would be enabled at the same time and the partial unique
        // constraint would fire mid-transaction.
        if (parentSkill != null) {
            parentSkill.setEnabled(false);
            skillRepository.saveAndFlush(parentSkill);
        }
        candidateSkill.setEnabled(true);
        skillRepository.save(candidateSkill);
        try {
            SkillDefinition promotedDef = buildSkillDefinition(candidateSkill);
            skillRegistry.registerSkillDefinition(promotedDef);
            log.info("Promoted skill id={} (semver={}), registered in SkillRegistry",
                    candidateSkill.getId(), candidateSkill.getSemver());
        } catch (Exception e) {
            log.warn("Failed to re-register promoted skill in SkillRegistry: {}", e.getMessage());
        }
    }

    private SkillDefinition buildSkillDefinition(SkillEntity skill) {
        if (skill.getSkillPath() != null) {
            try {
                return skillPackageLoader.loadFromDirectory(Path.of(skill.getSkillPath()));
            } catch (IOException e) {
                log.warn("Failed to load skill package from {}, falling back to metadata: {}",
                        skill.getSkillPath(), e.getMessage());
            }
        }
        SkillDefinition def = new SkillDefinition();
        def.setId(String.valueOf(skill.getId()));
        def.setName(skill.getName());
        def.setDescription(skill.getDescription());
        StringBuilder prompt = new StringBuilder();
        prompt.append("# ").append(skill.getName()).append("\n\n");
        if (skill.getDescription() != null) {
            prompt.append(skill.getDescription()).append("\n\n");
        }
        if (skill.getTriggers() != null) {
            prompt.append("**Use when:** ").append(skill.getTriggers()).append("\n\n");
        }
        if (skill.getRequiredTools() != null) {
            prompt.append("**Required tools:** ").append(skill.getRequiredTools()).append("\n");
        }
        def.setPromptContent(prompt.toString());
        if (skill.getTriggers() != null) {
            def.setTriggers(Arrays.asList(skill.getTriggers().split(",")));
        }
        if (skill.getRequiredTools() != null) {
            def.setRequiredTools(Arrays.asList(skill.getRequiredTools().split(",")));
        }
        return def;
    }

    private ScenarioRunResult runSingleScenario(String abRunId, EvalScenario scenario,
                                                 AgentDefinition agentDef,
                                                 SkillDefinition candidateSkillDef) {
        try {
            SkillRegistry sandboxRegistry = sandboxFactory.buildSandboxRegistryWithSkills(
                    abRunId, scenario.getId(), List.of(candidateSkillDef));
            AgentLoopEngine engine = evalEngineFactory.buildEvalEngine(sandboxRegistry);

            String evalSessionId = UUID.randomUUID().toString();
            LoopContext ctx = new LoopContext();
            ctx.setMaxLoops(scenario.getMaxLoops());
            ctx.setExecutionMode("auto");
            ctx.setMaxLlmStreamTimeoutMs(20_000L);

            AgentDefinition evalDef = copyWithoutEvalOverrides(agentDef);

            Path sandboxRoot = sandboxFactory.getSandboxRoot(abRunId, scenario.getId());
            String task = scenario.getTask().replace("/tmp/eval/", sandboxRoot.toString() + "/");

            if (scenario.getSetup() != null && scenario.getSetup().getFiles() != null) {
                java.nio.file.Files.createDirectories(sandboxRoot);
                for (Map.Entry<String, String> entry : scenario.getSetup().getFiles().entrySet()) {
                    Path filePath = sandboxRoot.resolve(entry.getKey());
                    if (filePath.getParent() != null) {
                        java.nio.file.Files.createDirectories(filePath.getParent());
                    }
                    java.nio.file.Files.writeString(filePath, entry.getValue());
                }
            }

            Future<LoopResult> future = loopExecutor.submit(
                    () -> engine.run(evalDef, task, null, evalSessionId, null, ctx));

            long startMs = System.currentTimeMillis();
            LoopResult loopResult;
            try {
                loopResult = future.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return ScenarioRunResult.timeout(scenario.getId(), "30s skill AB eval timeout");
            }

            long executionTimeMs = System.currentTimeMillis() - startMs;
            ScenarioRunResult result = new ScenarioRunResult();
            result.setScenarioId(scenario.getId());
            result.setAgentFinalOutput(loopResult.getFinalResponse());
            result.setLoopCount(loopResult.getLoopCount());
            result.setInputTokens(loopResult.getTotalInputTokens());
            result.setOutputTokens(loopResult.getTotalOutputTokens());
            result.setExecutionTimeMs(executionTimeMs);
            result.setStatus("PENDING_JUDGE");

            result.applyToolCallSignals(loopResult.getToolCalls());
            return result;
        } catch (Exception e) {
            log.error("Skill AB eval single scenario failed: {}", scenario.getId(), e);
            return ScenarioRunResult.error(scenario.getId(), e.getMessage());
        } finally {
            sandboxFactory.cleanupSandbox(abRunId, scenario.getId());
        }
    }

    private ScenarioRunResult runMultiTurnScenario(String abRunId, EvalScenario scenario,
                                                   AgentDefinition agentDef,
                                                   SkillDefinition candidateSkillDef,
                                                   MultiTurnTranscript transcriptOut) {
        long startMs = System.currentTimeMillis();
        long budgetMs = 90_000L;
        try {
            SkillRegistry sandboxRegistry = sandboxFactory.buildSandboxRegistryWithSkills(
                    abRunId, scenario.getId(), List.of(candidateSkillDef));
            AgentLoopEngine engine = evalEngineFactory.buildEvalEngine(sandboxRegistry);

            String evalSessionId = UUID.randomUUID().toString();
            AgentDefinition evalDef = copyWithoutEvalOverrides(agentDef);
            Path sandboxRoot = sandboxFactory.getSandboxRoot(abRunId, scenario.getId());

            writeFixtureFiles(scenario, sandboxRoot);

            ScenarioRunResult agg = new ScenarioRunResult();
            agg.setScenarioId(scenario.getId());
            agg.setStatus("PENDING_JUDGE");
            agg.setSessionId(evalSessionId);

            List<Message> history = new ArrayList<>();
            String lastResponse = null;
            int totalLoops = 0;
            long totalInput = 0;
            long totalOutput = 0;
            String capturedRootTraceId = null;

            List<EvalScenario.ConversationTurn> turns = scenario.getConversationTurns();
            int userTurnIdx = 0;
            int totalUserTurns = (int) turns.stream()
                    .filter(t -> "user".equalsIgnoreCase(t.getRole()))
                    .count();

            for (EvalScenario.ConversationTurn turn : turns) {
                String role = turn.getRole();
                String content = turn.getContent();
                if (!"user".equalsIgnoreCase(role)) {
                    if (transcriptOut != null && !"assistant".equalsIgnoreCase(role)) {
                        transcriptOut.add(role, content);
                    }
                    continue;
                }

                long remaining = budgetMs - (System.currentTimeMillis() - startMs);
                if (remaining <= 5_000L) {
                    log.warn("Skill AB multi-turn scenario {} timed out at user turn {}/{}",
                            scenario.getId(), userTurnIdx + 1, totalUserTurns);
                    ScenarioRunResult timeout = ScenarioRunResult.timeout(
                            scenario.getId(), "90s budget exceeded mid-conversation");
                    timeout.setSessionId(evalSessionId);
                    timeout.setExecutionTimeMs(System.currentTimeMillis() - startMs);
                    timeout.setLoopCount(totalLoops);
                    timeout.setInputTokens(totalInput);
                    timeout.setOutputTokens(totalOutput);
                    timeout.setAgentFinalOutput(lastResponse);
                    return timeout;
                }

                LoopContext ctx = new LoopContext();
                ctx.setMaxLoops(scenario.getMaxLoops());
                ctx.setExecutionMode("auto");
                ctx.setMaxLlmStreamTimeoutMs(20_000L);

                String userMsg = content == null ? "" : content.replace("/tmp/eval/", sandboxRoot.toString() + "/");
                if (transcriptOut != null) {
                    transcriptOut.add("user", userMsg);
                }

                List<Message> historySnapshot = new ArrayList<>(history);
                Future<LoopResult> future = loopExecutor.submit(
                        () -> engine.run(evalDef, userMsg, historySnapshot, evalSessionId, null, ctx));
                LoopResult turnResult;
                try {
                    turnResult = future.get(Math.min(25_000L, remaining), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    ScenarioRunResult timeout = ScenarioRunResult.timeout(
                            scenario.getId(), "25s skill AB eval turn timeout");
                    timeout.setSessionId(evalSessionId);
                    timeout.setExecutionTimeMs(System.currentTimeMillis() - startMs);
                    timeout.setLoopCount(totalLoops);
                    timeout.setInputTokens(totalInput);
                    timeout.setOutputTokens(totalOutput);
                    timeout.setAgentFinalOutput(lastResponse);
                    return timeout;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.cancel(true);
                    ScenarioRunResult interrupted = ScenarioRunResult.error(
                            scenario.getId(), "skill AB eval interrupted");
                    interrupted.setSessionId(evalSessionId);
                    interrupted.setExecutionTimeMs(System.currentTimeMillis() - startMs);
                    interrupted.setLoopCount(totalLoops);
                    interrupted.setInputTokens(totalInput);
                    interrupted.setOutputTokens(totalOutput);
                    interrupted.setAgentFinalOutput(lastResponse);
                    return interrupted;
                }

                userTurnIdx++;
                String response = turnResult.getFinalResponse();
                lastResponse = response;
                totalLoops += turnResult.getLoopCount();
                totalInput += turnResult.getTotalInputTokens();
                totalOutput += turnResult.getTotalOutputTokens();
                agg.applyToolCallSignals(turnResult.getToolCalls());

                if (capturedRootTraceId == null && ctx.getRootTraceId() != null) {
                    capturedRootTraceId = ctx.getRootTraceId();
                }
                if (transcriptOut != null) {
                    transcriptOut.add("assistant", response == null ? "" : response);
                }

                if (turnResult.getMessages() != null) {
                    history = new ArrayList<>(turnResult.getMessages());
                } else {
                    history.add(Message.user(userMsg));
                    if (response != null) {
                        history.add(Message.assistant(response));
                    }
                }
            }

            agg.setLoopCount(totalLoops);
            agg.setInputTokens(totalInput);
            agg.setOutputTokens(totalOutput);
            agg.setAgentFinalOutput(lastResponse);
            agg.setExecutionTimeMs(System.currentTimeMillis() - startMs);
            agg.setRootTraceId(capturedRootTraceId);
            return agg;
        } catch (Exception e) {
            log.error("Skill AB eval multi-turn scenario failed: {}", scenario.getId(), e);
            ScenarioRunResult err = ScenarioRunResult.error(scenario.getId(), e.getMessage());
            err.setExecutionTimeMs(System.currentTimeMillis() - startMs);
            return err;
        } finally {
            sandboxFactory.cleanupSandbox(abRunId, scenario.getId());
        }
    }

    private void writeFixtureFiles(EvalScenario scenario, Path sandboxRoot) throws IOException {
        if (scenario.getSetup() == null || scenario.getSetup().getFiles() == null) {
            return;
        }
        java.nio.file.Files.createDirectories(sandboxRoot);
        for (Map.Entry<String, String> entry : scenario.getSetup().getFiles().entrySet()) {
            Path filePath = sandboxRoot.resolve(entry.getKey());
            if (filePath.getParent() != null) {
                java.nio.file.Files.createDirectories(filePath.getParent());
            }
            java.nio.file.Files.writeString(filePath, entry.getValue());
        }
    }

    private AgentDefinition copyWithoutEvalOverrides(AgentDefinition original) {
        AgentDefinition copy = new AgentDefinition();
        copy.setName(original.getName());
        copy.setDescription(original.getDescription());
        copy.setSystemPrompt(original.getSystemPrompt());
        copy.setModelId(original.getModelId());
        copy.setSkillIds(original.getSkillIds());
        copy.setSoulPrompt(original.getSoulPrompt());
        copy.setToolsPrompt(original.getToolsPrompt());
        if (original.getConfig() != null) {
            Map<String, Object> configCopy = new HashMap<>(original.getConfig());
            configCopy.remove("max_loops");
            configCopy.remove("execution_mode");
            copy.setConfig(configCopy);
        }
        return copy;
    }

    private double computeHeldOutBaselineRate(EvalTaskEntity baselineRun, List<EvalScenario> heldOutScenarios) {
        if (baselineRun.getScenarioResultsJson() == null) {
            return baselineRun.getOverallPassRate();
        }
        try {
            List<Map<String, Object>> results = objectMapper.readValue(
                    baselineRun.getScenarioResultsJson(),
                    new TypeReference<List<Map<String, Object>>>() {});

            List<String> heldOutIds = heldOutScenarios.stream()
                    .map(EvalScenario::getId).toList();

            long heldOutPassed = results.stream()
                    .filter(r -> heldOutIds.contains(r.get("scenarioId")))
                    .filter(r -> Boolean.TRUE.equals(r.get("pass")))
                    .count();
            long heldOutTotal = results.stream()
                    .filter(r -> heldOutIds.contains(r.get("scenarioId")))
                    .count();
            return heldOutTotal == 0 ? 0 : (double) heldOutPassed / heldOutTotal * 100;
        } catch (Exception e) {
            log.warn("Failed to parse baseline scenario results, using overall pass rate", e);
            return baselineRun.getOverallPassRate();
        }
    }

    public List<SkillAbRunEntity> getAbRunsForSkill(Long skillId) {
        List<SkillAbRunEntity> asParent = skillAbRunRepository.findByParentSkillIdOrderByStartedAtDesc(skillId);
        List<SkillAbRunEntity> asCandidate = skillAbRunRepository.findByCandidateSkillIdOrderByStartedAtDesc(skillId);
        List<SkillAbRunEntity> merged = new ArrayList<>(asParent);
        for (SkillAbRunEntity r : asCandidate) {
            if (merged.stream().noneMatch(m -> m.getId().equals(r.getId()))) {
                merged.add(r);
            }
        }
        merged.sort(Comparator.comparing(
                SkillAbRunEntity::getStartedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return merged;
    }

    public Optional<SkillAbRunEntity> getAbRun(String abRunId) {
        return skillAbRunRepository.findById(abRunId);
    }
}
