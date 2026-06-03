package com.skillforge.server.improve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalDatasetVersionEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.eval.EvalEngineFactory;
import com.skillforge.server.eval.EvalJudgeOutput;
import com.skillforge.server.eval.EvalJudgeTool;
import com.skillforge.server.eval.ScenarioRunResult;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.eval.scenario.ScenarioLoader;
import com.skillforge.server.repository.EvalDatasetVersionRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.EvalDatasetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class AbEvalPipeline {

    private static final Logger log = LoggerFactory.getLogger(AbEvalPipeline.class);

    /**
     * BUG-1 (winner-carry-forward): per-scenario baseline status when the baseline
     * side is skipped (a cached rate was supplied). The accompanying oracleScore is a
     * placeholder, NOT a measurement — the run-level {@code baselinePassRate} carries
     * the real (cached) value. Any consumer reading per-scenario baseline results must
     * treat this status as "not measured this run" and ignore the 0.0 score.
     */
    static final String BASELINE_CACHED_STATUS = "CACHED";

    /**
     * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1 (§7 B1) — composite-score pass
     * threshold. Mirrors {@code EvalJudgeTool}'s {@code output.setPass(compositeScore >= 40.0)}
     * so the whole-agent A/B channel counts passes IDENTICALLY to the prompt path
     * (which counts {@code judge.isPass()}). {@link AbScenarioResult.RunResult#oracleScore()}
     * stores the FINAL (post-meta-judge) composite score, so
     * {@code oracleScore >= PASS_COMPOSITE_THRESHOLD} reproduces {@code isPass()}
     * exactly for non-error scenarios — the numeric-parity guarantee AC-1 asserts.
     */
    public static final double PASS_COMPOSITE_THRESHOLD = 40.0;

    /**
     * Shared pass predicate over a per-scenario {@link AbScenarioResult.RunResult}
     * (§7 B1). A side "passes" iff it did not error/timeout/skip AND its composite
     * score crosses {@link #PASS_COMPOSITE_THRESHOLD}. Used by
     * {@code AgentEvolveAbEvalService} to aggregate the whole-agent pass-rate so the
     * count matches the prompt path's {@code judge.isPass()} counting.
     */
    public static boolean isPass(AbScenarioResult.RunResult run) {
        if (run == null) {
            return false;
        }
        String status = run.status();
        if (status == null || "ERROR".equals(status) || "TIMEOUT".equals(status)
                || BASELINE_CACHED_STATUS.equals(status)) {
            return false;
        }
        return run.oracleScore() >= PASS_COMPOSITE_THRESHOLD;
    }

    /**
     * EVAL-429-ROBUSTNESS D1 — a per-scenario status counts as "measured" (a real
     * quality signal that belongs in the A/B pass-rate denominator) iff it is NOT an
     * infrastructure / not-measured-this-run status. Excluded:
     * <ul>
     *   <li>{@code ERROR} / {@code TIMEOUT} — infra/runtime failure (network, rate
     *       limit, loop budget) → NOT a quality verdict, must be摘出分母.</li>
     *   <li>{@link #BASELINE_CACHED_STATUS} — winner-carry-forward sentinel: the
     *       baseline side was never run THIS round (the run-level cached rate carries
     *       its value), so the per-scenario baseline is not measured.</li>
     *   <li>{@code null} — no status → treated as not-measured.</li>
     * </ul>
     * {@code PASS} / {@code FAIL} / {@code VETO} / {@code PENDING_JUDGE} are all
     * measured: {@code VETO} is a genuine quality failure (D1: 质量失败留), kept in the
     * denominator.
     */
    public static boolean isMeasured(String status) {
        if (status == null) {
            return false;
        }
        return !("ERROR".equals(status) || "TIMEOUT".equals(status)
                || BASELINE_CACHED_STATUS.equals(status));
    }

    /**
     * EVAL-429-ROBUSTNESS D2 — pairwise-measured predicate: a scenario contributes to
     * the (candidate, baseline) pass-rate comparison ONLY when BOTH sides were
     * measured this round. If either side hit ERROR/TIMEOUT (or the baseline is the
     * CACHED sentinel), the scenario is dropped from BOTH rates so they are always
     * computed over the same set of scenarios and the delta is strictly comparable.
     */
    public static boolean pairwiseMeasured(String candidateStatus, String baselineStatus) {
        return isMeasured(candidateStatus) && isMeasured(baselineStatus);
    }

    private final ScenarioLoader scenarioLoader;
    private final SandboxSkillRegistryFactory sandboxFactory;
    private final EvalEngineFactory evalEngineFactory;
    private final EvalJudgeTool evalJudgeTool;
    private final PromptAbRunRepository promptAbRunRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final ChatEventBroadcaster broadcaster;
    private final ExecutorService loopExecutor;
    private final long scenarioTimeoutMs;
    /**
     * Max A/B scenarios run concurrently (each spawns an inner engine.run, so the
     * abEvalLoopExecutor pool must hold ~2× this + buffer — kept in sync in
     * AbEvalExecutorConfig). Configurable: a fresh A/B with no cached baseline is
     * dominated by scenario wall-clock, so more parallelism = faster A/B when the
     * provider's rate limit allows (429s are retried). Default 3 (now configurable; was a hardcoded 3). Higher hurts on a rate-limited provider — more parallel calls = more 429..
     */
    private final int scenarioConcurrency;
    /**
     * Cap on how many eval scenarios an A/B run executes (0 = all). The full
     * dataset (~49 scenarios × baseline+candidate × multi-turn) makes one A/B take
     * ~10-15 min, which is impractical for an iterative evolve loop AND multiplies
     * provider load (→ 429s). Capping to a representative sample (first N, stable
     * order so baseline/candidate and successive iterations compare like-for-like)
     * gives a directional pass-rate signal in a fraction of the time/calls. The
     * human reviews the trajectory before adopting, so sampling is an acceptable
     * speed/cost trade-off. Configurable via
     * {@code skillforge.flywheel.ab-eval.max-scenarios}; default 0 (unchanged).
     */
    private final int maxScenarios;
    // EVAL-DATASET-LAYER V1: nullable so the existing unit test ctor
    // (AbEvalPipelineAttributionBaselineTest) doesn't have to wire these — the
    // new dataset-version path that uses them is exercised by a separate test.
    // In production, Spring DI wires them.
    private final EvalDatasetService evalDatasetService;
    private final EvalDatasetVersionRepository evalDatasetVersionRepository;

    public AbEvalPipeline(ScenarioLoader scenarioLoader,
                           SandboxSkillRegistryFactory sandboxFactory,
                           EvalEngineFactory evalEngineFactory,
                           EvalJudgeTool evalJudgeTool,
                           PromptAbRunRepository promptAbRunRepository,
                           PromptVersionRepository promptVersionRepository,
                           AgentService agentService,
                           ObjectMapper objectMapper,
                           ChatEventBroadcaster broadcaster,
                           @Qualifier("abEvalLoopExecutor") ExecutorService loopExecutor,
                           @Value("${skillforge.flywheel.ab-eval.scenario-timeout-ms:120000}") long scenarioTimeoutMs) {
        this(scenarioLoader, sandboxFactory, evalEngineFactory, evalJudgeTool,
             promptAbRunRepository, promptVersionRepository, agentService, objectMapper,
             broadcaster, loopExecutor, scenarioTimeoutMs, null, null, 3, 0);
    }

    /** Sample at most {@link #maxScenarios} scenarios (stable first-N) when the cap
     *  is set; baseline + candidate + successive iterations then compare on the same
     *  subset. {@code maxScenarios<=0} or a smaller list = run all. */
    private List<EvalScenarioEntity> capScenarios(List<EvalScenarioEntity> scenarios) {
        if (maxScenarios <= 0 || scenarios == null || scenarios.size() <= maxScenarios) {
            return scenarios;
        }
        log.info("A/B scenario cap: running {} of {} scenarios (skillforge.flywheel.ab-eval.max-scenarios)",
                maxScenarios, scenarios.size());
        return scenarios.subList(0, maxScenarios);
    }

    /**
     * EVAL-DATASET-LAYER V1: full constructor used by Spring DI in production
     * (autowires the dataset-version dependencies). The narrower ctor above
     * is preserved so existing tests (Mockito @Mock-only) compile unchanged.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AbEvalPipeline(ScenarioLoader scenarioLoader,
                           SandboxSkillRegistryFactory sandboxFactory,
                           EvalEngineFactory evalEngineFactory,
                           EvalJudgeTool evalJudgeTool,
                           PromptAbRunRepository promptAbRunRepository,
                           PromptVersionRepository promptVersionRepository,
                           AgentService agentService,
                           ObjectMapper objectMapper,
                           ChatEventBroadcaster broadcaster,
                           @Qualifier("abEvalLoopExecutor") ExecutorService loopExecutor,
                           @Value("${skillforge.flywheel.ab-eval.scenario-timeout-ms:120000}") long scenarioTimeoutMs,
                           EvalDatasetService evalDatasetService,
                           EvalDatasetVersionRepository evalDatasetVersionRepository,
                           @Value("${skillforge.flywheel.ab-eval.scenario-concurrency:3}") int scenarioConcurrency,
                           @Value("${skillforge.flywheel.ab-eval.max-scenarios:0}") int maxScenarios) {
        this.scenarioConcurrency = Math.max(1, scenarioConcurrency);
        this.maxScenarios = Math.max(0, maxScenarios);
        this.scenarioLoader = scenarioLoader;
        this.sandboxFactory = sandboxFactory;
        this.evalEngineFactory = evalEngineFactory;
        this.evalJudgeTool = evalJudgeTool;
        this.promptAbRunRepository = promptAbRunRepository;
        this.promptVersionRepository = promptVersionRepository;
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
        this.loopExecutor = loopExecutor;
        this.scenarioTimeoutMs = scenarioTimeoutMs;
        this.evalDatasetService = evalDatasetService;
        this.evalDatasetVersionRepository = evalDatasetVersionRepository;
    }

    public void run(PromptAbRunEntity abRun, PromptVersionEntity candidate,
                    EvalTaskEntity baselineRun, AgentEntity originalAgent) {
        // 1. Mark as RUNNING
        abRun.setStatus("RUNNING");
        abRun.setStartedAt(Instant.now());
        promptAbRunRepository.save(abRun);

        // 2. Load held-out scenarios
        List<EvalScenario> heldOutScenarios = scenarioLoader.loadAll().stream()
                .filter(s -> "held_out".equals(s.getSplit()))
                .toList();

        if (heldOutScenarios.isEmpty()) {
            log.warn("No held_out scenarios found, using all scenarios for AB eval");
            heldOutScenarios = scenarioLoader.loadAll();
        }

        // 3. Compute held-out baseline rate from baselineRun's scenarioResultsJson.
        // EVAL-429-ROBUSTNESS Fix4: nullable — null when no held-out scenario was measured.
        Double heldOutBaselineRate = computeHeldOutBaselineRate(baselineRun, heldOutScenarios);
        abRun.setBaselinePassRate(heldOutBaselineRate);

        // 4. Construct candidate AgentDefinition with candidate prompt
        AgentDefinition candidateDef = agentService.toAgentDefinition(originalAgent);
        candidateDef.setSystemPrompt(candidate.getContent());

        // 5. Run each held-out scenario with candidate prompt
        List<AbScenarioResult> scenarioResults = new ArrayList<>();
        int passed = 0;
        // EVAL-429-ROBUSTNESS: only pairwise-measured scenarios (candidate run AND the
        // historical baseline both non-infra) count toward the denominator; ERROR/TIMEOUT
        // on either side is excluded and logged (D2 + D4).
        int measuredCount = 0;
        List<String> infraExcluded = new ArrayList<>();

        for (EvalScenario scenario : heldOutScenarios) {
            log.info("AB eval scenario: {} ({})", scenario.getId(), scenario.getName());
            // EVAL-V2 M2 R5: PromptImprover AB pipeline does not yet support
            // multi-turn cases — falls back to single-turn (uses scenario.task).
            // Log a warning so dataset curators know the case isn't fully exercised
            // here; M2 keeps the AB pipeline working but doesn't crash on multi-turn.
            if (scenario.isMultiTurn()) {
                log.warn("AB eval skipping multi-turn execution for scenario {}; falling back to single-turn (task only). "
                        + "PromptImprover does not yet support multi-turn — R5 follow-up.",
                        scenario.getId());
            }
            try {
                ScenarioRunResult runResult = runSingleScenario(abRun.getId(), scenario, candidateDef);
                EvalJudgeOutput judgeOutput = evalJudgeTool.judge(scenario, runResult);

                // Look up baseline status for this scenario
                String baselineStatus = lookupBaselineStatus(baselineRun, scenario.getId());
                double baselineOracleScore = lookupBaselineOracleScore(baselineRun, scenario.getId());

                scenarioResults.add(new AbScenarioResult(
                        scenario.getId(), scenario.getName(),
                        new AbScenarioResult.RunResult(baselineStatus, baselineOracleScore),
                        new AbScenarioResult.RunResult(runResult.getStatus(), judgeOutput.getCompositeScore())));

                // EVAL-429-ROBUSTNESS D2: count toward the denominator only when both
                // the candidate run AND the historical baseline for this scenario were
                // measured (non ERROR/TIMEOUT). Pass counting stays on judgeOutput.isPass()
                // (unchanged) so a measured candidate scores identically to before.
                if (pairwiseMeasured(runResult.getStatus(), baselineStatus)) {
                    measuredCount++;
                    if (judgeOutput.isPass()) {
                        passed++;
                    }
                } else {
                    infraExcluded.add(scenario.getId());
                }

                // Broadcast ab_scenario_finished
                if (broadcaster != null && abRun.getTriggeredByUserId() != null) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("type", "ab_scenario_finished");
                    event.put("abRunId", abRun.getId());
                    event.put("scenarioId", scenario.getId());
                    event.put("candidateStatus", runResult.getStatus());
                    event.put("baselineStatus", baselineStatus);
                    event.put("candidateScore", judgeOutput.getCompositeScore());
                    broadcaster.userEvent(abRun.getTriggeredByUserId(), event);
                }
            } catch (Exception e) {
                log.error("AB eval scenario {} failed: {}", scenario.getId(), e.getMessage());
                String baselineStatus = lookupBaselineStatus(baselineRun, scenario.getId());
                double baselineScore = lookupBaselineOracleScore(baselineRun, scenario.getId());
                scenarioResults.add(new AbScenarioResult(
                        scenario.getId(), scenario.getName(),
                        new AbScenarioResult.RunResult(baselineStatus, baselineScore),
                        new AbScenarioResult.RunResult("ERROR", 0.0)));
                // Candidate errored (infra) → pairwise=false → excluded from denominator (D2/D4).
                infraExcluded.add(scenario.getId());
            }
        }

        // 6. Compute candidate pass rate and delta over the pairwise-measured set only.
        // EVAL-429-ROBUSTNESS D3: measured=0 → not-measured sentinel (null), NOT 0% — the
        // gate must treat it as "couldn't measure / don't promote", not a full failure.
        if (!infraExcluded.isEmpty()) {
            log.info("AB eval excluded {} infra/not-measured scenario(s) from denominator: {}",
                    infraExcluded.size(), infraExcluded);
        }
        Double candidatePassRate = measuredCount == 0
                ? null
                : (double) passed / measuredCount * 100;
        Double delta = (candidatePassRate == null || heldOutBaselineRate == null)
                ? null
                : candidatePassRate - heldOutBaselineRate;

        // 7. Serialize per-scenario results
        try {
            abRun.setAbScenarioResultsJson(objectMapper.writeValueAsString(scenarioResults));
        } catch (Exception e) {
            log.warn("Failed to serialize AB scenario results", e);
        }

        // 8. Update abRun and candidate version
        abRun.setStatus("COMPLETED");
        abRun.setCandidatePassRate(candidatePassRate);
        abRun.setDeltaPassRate(delta);
        abRun.setCompletedAt(Instant.now());
        promptAbRunRepository.save(abRun);

        candidate.setDeltaPassRate(delta);
        candidate.setBaselinePassRate(heldOutBaselineRate);
        candidate.setAbRunId(abRun.getId());
        promptVersionRepository.save(candidate);

        log.info("AB eval completed: abRunId={}, candidateRate={}, baselineRate={}, delta={}",
                abRun.getId(), candidatePassRate, heldOutBaselineRate, delta);
    }

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.4c sub-task 1 (Ratify #7-A) — attribution-
     * baseline overload. Runs the supplied scenarios against both the
     * {@code baselineVersion} prompt (= agent's current active prompt) and the
     * {@code candidate} prompt fresh (no historical {@link EvalTaskEntity}
     * lookup; attribution path has no prior eval run). Mutates {@code abRun}
     * with baselinePassRate / candidatePassRate / deltaPassRate / status +
     * persists via {@link #promptAbRunRepository}.
     *
     * <p>Additive — existing {@code run(abRun, candidate, baselineRun, agent)}
     * is unchanged.
     *
     * <p>Reuses the private {@link #runSingleScenario(String, EvalScenario, AgentDefinition)}
     * helper for per-scenario execution. Each scenario is run twice (baseline
     * + candidate) using distinct abRun sandbox ids so the SandboxFactory
     * doesn't collide. Per-scenario errors are recorded as ERROR status and
     * do not abort the batch.
     */
    /**
     * EVAL-DATASET-LAYER V1 (★ r4 D2 fix ★) — legacy overload kept for the
     * attribution-derived (ephemeral) path. Marked
     * {@link Deprecated} {@code forRemoval = true}: V2 will remove this
     * once attribution callers migrate to the dataset-version path
     * ({@link #run(PromptAbRunEntity, PromptVersionEntity, PromptVersionEntity, AgentEntity, String)}).
     *
     * <p>A {@link Logger#warn} is emitted on every invocation so the
     * migration path is visible to operators.
     */
    @Deprecated(forRemoval = true, since = "EVAL-DATASET-LAYER V1")
    public void run(PromptAbRunEntity abRun,
                    PromptVersionEntity candidate,
                    PromptVersionEntity baselineVersion,
                    AgentEntity agent,
                    List<EvalScenarioEntity> scenarios) {
        run(abRun, candidate, baselineVersion, agent, scenarios, (Double) null);
    }

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL BUG-1 — legacy-scenarios overload with an optional
     * {@code cachedBaselineRate}: when non-null the run is CANDIDATE-ONLY and reuses
     * that rate as the baseline pass-rate (winner-carry-forward; no baseline re-eval).
     */
    public void run(PromptAbRunEntity abRun,
                    PromptVersionEntity candidate,
                    PromptVersionEntity baselineVersion,
                    AgentEntity agent,
                    List<EvalScenarioEntity> scenarios,
                    Double cachedBaselineRate) {
        log.warn("AbEvalPipeline.run(scenarios) legacy overload invoked — V2 will remove this; "
                + "migrate to run(abRun, candidate, baseline, agent, datasetVersionId). abRunId={}",
                abRun.getId());
        runWithScenarios(abRun, candidate, baselineVersion, agent, capScenarios(scenarios), null, cachedBaselineRate);
    }

    /**
     * EVAL-DATASET-LAYER V1: new overload that takes an immutable dataset
     * version reference. Behavioural parity with the legacy
     * {@link #run(PromptAbRunEntity, PromptVersionEntity, PromptVersionEntity, AgentEntity, List)}
     * for per-scenario execution, plus:
     * <ul>
     *   <li>{@code abRun.datasetVersionId} is set so the run row is forever
     *       linked back to the immutable snapshot.</li>
     *   <li>The version's {@code actualBaselinePassRate} is back-written
     *       (moving-average if multiple runs) — ★ r4 D1 fix ★.</li>
     * </ul>
     */
    public void run(PromptAbRunEntity abRun,
                    PromptVersionEntity candidate,
                    PromptVersionEntity baselineVersion,
                    AgentEntity agent,
                    String datasetVersionId) {
        run(abRun, candidate, baselineVersion, agent, datasetVersionId, (Double) null);
    }

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL BUG-1 — dataset-version overload with an optional
     * {@code cachedBaselineRate} (winner-carry-forward; candidate-only + reuse rate).
     */
    public void run(PromptAbRunEntity abRun,
                    PromptVersionEntity candidate,
                    PromptVersionEntity baselineVersion,
                    AgentEntity agent,
                    String datasetVersionId,
                    Double cachedBaselineRate) {
        if (datasetVersionId == null || datasetVersionId.isBlank()) {
            throw new IllegalArgumentException("datasetVersionId required for this overload");
        }
        if (evalDatasetService == null || evalDatasetVersionRepository == null) {
            throw new IllegalStateException(
                    "AbEvalPipeline was built without EvalDatasetService / EvalDatasetVersionRepository "
                            + "— dataset-version path unavailable. Wire via the Spring DI constructor.");
        }
        List<EvalScenarioEntity> scenarios =
                evalDatasetService.getScenariosForVersion(datasetVersionId);
        abRun.setDatasetVersionId(datasetVersionId);
        runWithScenarios(abRun, candidate, baselineVersion, agent, capScenarios(scenarios),
                datasetVersionId, cachedBaselineRate);
    }

    /**
     * Internal helper: the shared body for both the legacy List<EvalScenarioEntity>
     * overload and the new datasetVersionId overload. Mutates {@code abRun}
     * and persists; if {@code datasetVersionId} is non-null, also back-writes
     * the version's actualBaselinePassRate after the run completes.
     */
    private void runWithScenarios(PromptAbRunEntity abRun,
                                   PromptVersionEntity candidate,
                                   PromptVersionEntity baselineVersion,
                                   AgentEntity agent,
                                   List<EvalScenarioEntity> scenarios,
                                   String datasetVersionId,
                                   Double cachedBaselineRate) {
        // AUTOEVOLVE-AGENT-FLYWHEEL BUG-1 (winner-carry-forward): when the caller
        // already knows the baseline's pass-rate (the current-best in a greedy
        // evolve hill-climb), run CANDIDATE-ONLY and reuse that rate — never
        // re-measure the baseline. This (a) halves the work and (b) removes the
        // re-eval noise that made an unchanged baseline score wildly differently
        // each iteration (the bug). null = legacy behaviour (measure both fresh).
        final boolean skipBaseline = cachedBaselineRate != null;
        abRun.setStatus("RUNNING");
        abRun.setStartedAt(Instant.now());
        promptAbRunRepository.save(abRun);

        if (scenarios == null || scenarios.isEmpty()) {
            abRun.setStatus("FAILED");
            abRun.setFailureReason("No scenarios supplied for attribution-baseline A/B run");
            abRun.setCompletedAt(Instant.now());
            promptAbRunRepository.save(abRun);
            log.warn("Attribution A/B run {} aborted: no scenarios", abRun.getId());
            return;
        }

        AgentDefinition baselineDef = agentService.toAgentDefinition(agent);
        baselineDef.setSystemPrompt(baselineVersion.getContent());
        AgentDefinition candidateDef = agentService.toAgentDefinition(agent);
        candidateDef.setSystemPrompt(candidate.getContent());

        // ★ 2026-05-24 V1 r3 perf fix: 并行 batch scenarios (fan-out via loopExecutor,
        // cap=8 pool max). 之前 serial 49 scenarios × 2 side = 40-90 min；并行后
        // 受 LLM provider QPS 限制实际约 ~8-15 min（5x 加速）。
        //
        // 每 scenario 内部 baseline + candidate 仍 serial（共用 sandbox naming +
        // judge 顺序便于 debug；并行内嵌的 ROI 小）。Scenario 之间无依赖可安全并行。
        //
        // ScenarioRunResult / EvalJudgeOutput 都是 fresh 对象，无共享 mutation。
        // EVAL-429-ROBUSTNESS: pass-counting + measured-filtering moved to a single-
        // threaded post-process over the collected results (below) so the
        // pairwise-measured denominator is computed from the final per-scenario
        // statuses — no thread-safe counters needed.
        List<CompletableFuture<AbScenarioResult>> futures = new ArrayList<>(scenarios.size());

        // ★ 2026-05-24 V1 r3.1 fix RejectedExecutionException:
        // loopExecutor cap = core 4 / max 8 / queue 20 = total 28 capacity. 一次
        // submit 49 scenarios 撑爆 + 内部 runSingleScenario.submit(engine.run) 跟
        // 自己抢 pool → cascading reject (Event 122 第 6 次 retry 暴露)。
        // Semaphore cap=3 限并发：3 outer + 3 inner = 6 ≤ 8 pool max，留 2 buffer。
        final Semaphore concurrency = new Semaphore(scenarioConcurrency);

        for (EvalScenarioEntity entity : scenarios) {
            EvalScenario scenario = toEvalScenario(entity);
            // acquire 在主线程 block — 控制 submit 进 loopExecutor 的 task 数永远
            // ≤3 concurrent (其余 in-flight 完成 release semaphore 才放下一个 submit)
            try {
                concurrency.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while throttling A/B scenario submit", ie);
            }
            CompletableFuture<AbScenarioResult> fut = CompletableFuture.supplyAsync(() -> {
                try {
                    // BUG-1: skip the baseline side entirely when a cached rate is
                    // supplied (candidate-only winner-carry-forward).
                    AbScenarioResult.RunResult baselineResult;
                    if (skipBaseline) {
                        baselineResult = new AbScenarioResult.RunResult(BASELINE_CACHED_STATUS, 0.0);
                    } else {
                        ScenarioRunResult baselineRun = runSingleScenario(
                                abRun.getId() + "-baseline", scenario, baselineDef);
                        // Option B: explain=true → judge also emits a per-case rationale.
                        EvalJudgeOutput baselineJudge = evalJudgeTool.judge(scenario, baselineRun, true);
                        // Reflection: keep the judge's per-scenario reasoning so the
                        // evolve-editor can see WHY this case scored as it did.
                        baselineResult = new AbScenarioResult.RunResult(
                                baselineRun.getStatus(), baselineJudge.getCompositeScore(),
                                baselineJudge.getMetaJudgeRationale());
                    }
                    ScenarioRunResult candidateRun = runSingleScenario(
                            abRun.getId() + "-candidate", scenario, candidateDef);
                    // Option B: explain=true → judge also emits a per-case rationale.
                    EvalJudgeOutput candidateJudge = evalJudgeTool.judge(scenario, candidateRun, true);

                    return new AbScenarioResult(
                            scenario.getId(), scenario.getName(),
                            baselineResult,
                            new AbScenarioResult.RunResult(candidateRun.getStatus(),
                                    candidateJudge.getCompositeScore(),
                                    candidateJudge.getMetaJudgeRationale()));
                } catch (Exception e) {
                    log.error("Attribution A/B scenario {} failed: {}", scenario.getId(), e.getMessage(), e);
                    return new AbScenarioResult(
                            scenario.getId(), scenario.getName(),
                            new AbScenarioResult.RunResult("ERROR", 0.0),
                            new AbScenarioResult.RunResult("ERROR", 0.0));
                } finally {
                    concurrency.release();   // r3.1 fix: 释放并发槽
                }
            }, loopExecutor);
            futures.add(fut);
        }

        // Wait all done (no scenario-level timeout — runSingleScenario 内已有 scenarioTimeoutMs cap)
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Collect results in original scenario order (futures preserve order).
        List<AbScenarioResult> results = new ArrayList<>(futures.size());
        for (CompletableFuture<AbScenarioResult> f : futures) {
            results.add(f.join());
        }

        // EVAL-429-ROBUSTNESS D2/D3/D4: post-process the collected per-scenario results
        // to compute pass-rates over the MEASURED set only.
        //  - skipBaseline (winner-carry-forward): the baseline side is the CACHED
        //    sentinel (never run this round), so "measured" = candidate-side measured;
        //    the run-level baseline rate stays the supplied cachedBaselineRate.
        //  - fresh: "measured" = pairwise (BOTH candidate and baseline non-infra) so
        //    both rates are over the SAME scenarios and the delta is strictly comparable.
        // measured==0 → null sentinel (D3): the gate must treat it as "not measured",
        // NOT a 0% rate. ERROR/TIMEOUT scenarios are logged (D4).
        int measuredPairs = 0;
        int baselinePassed = 0;
        int candidatePassed = 0;
        List<String> infraExcluded = new ArrayList<>();
        for (AbScenarioResult r : results) {
            boolean measured = skipBaseline
                    ? isMeasured(r.candidate().status())
                    : pairwiseMeasured(r.candidate().status(), r.baseline().status());
            if (measured) {
                measuredPairs++;
                if (isPass(r.candidate())) candidatePassed++;
                if (!skipBaseline && isPass(r.baseline())) baselinePassed++;
            } else {
                infraExcluded.add(r.scenarioId());
            }
        }
        if (!infraExcluded.isEmpty()) {
            log.info("Attribution A/B run {} excluded {} infra/not-measured scenario(s) from "
                            + "denominator (skipBaseline={}): {}",
                    abRun.getId(), infraExcluded.size(), skipBaseline, infraExcluded);
        }

        // BUG-1: when the baseline was skipped (winner-carry-forward), use the supplied
        // cached rate instead of the (un-measured) per-scenario count.
        Double baselinePassRate = skipBaseline
                ? cachedBaselineRate
                : (measuredPairs == 0 ? null : (double) baselinePassed / measuredPairs * 100);
        Double candidatePassRate = measuredPairs == 0
                ? null
                : (double) candidatePassed / measuredPairs * 100;
        Double delta = (candidatePassRate == null || baselinePassRate == null)
                ? null
                : candidatePassRate - baselinePassRate;

        abRun.setBaselinePassRate(baselinePassRate);
        abRun.setCandidatePassRate(candidatePassRate);
        abRun.setDeltaPassRate(delta);
        abRun.setStatus("COMPLETED");
        abRun.setCompletedAt(Instant.now());
        try {
            abRun.setAbScenarioResultsJson(objectMapper.writeValueAsString(results));
        } catch (Exception e) {
            log.warn("Failed to serialize attribution A/B scenario results: {}", e.getMessage());
        }
        promptAbRunRepository.save(abRun);

        // Mirror baseline write-back from the existing run() — keeps the
        // candidate row coherent with the abRun outcome.
        candidate.setBaselinePassRate(baselinePassRate);
        candidate.setDeltaPassRate(delta);
        candidate.setAbRunId(abRun.getId());
        promptVersionRepository.save(candidate);

        // ★ r4 D1 fix ★: back-write the version's actualBaselinePassRate so
        // the FE can prefer this over the static heuristic estimate. Multiple
        // runs against the same version use a simple moving average — over
        // many runs this converges to the true mean. Stored as fraction
        // [0.0, 1.0] to match the composition_stats.expected_baseline_pass_rate
        // field. baselinePassRate above is in 0-100 percentage form.
        // BUG-1: never back-write when the baseline was skipped — baselinePassRate is
        // then the carried current-best score (winner-carry-forward), not a fresh
        // measurement of THIS dataset's baseline, so averaging it in would pollute
        // the dataset's actualBaselinePassRate estimate.
        // EVAL-429-ROBUSTNESS: skip the back-write when the baseline rate is null
        // (no scenario was measured this round) — there's no fresh measurement to fold in.
        if (!skipBaseline && baselinePassRate != null
                && datasetVersionId != null && evalDatasetVersionRepository != null) {
            final double measuredBaselineRate = baselinePassRate;
            try {
                evalDatasetVersionRepository.findById(datasetVersionId).ifPresent(v -> {
                    double newFraction = measuredBaselineRate / 100.0;
                    Double prior = v.getActualBaselinePassRate();
                    double updated = (prior == null) ? newFraction : (prior + newFraction) / 2.0;
                    v.setActualBaselinePassRate(updated);
                    evalDatasetVersionRepository.save(v);
                });
            } catch (Exception e) {
                // Best-effort: a back-write failure shouldn't fail the A/B run.
                log.warn("Failed to back-write actualBaselinePassRate for "
                        + "datasetVersionId={}: {}", datasetVersionId, e.getMessage());
            }
        }

        log.info("Attribution A/B run {} COMPLETED: baseline={} candidate={} delta={} datasetVersionId={}",
                abRun.getId(), baselinePassRate, candidatePassRate, delta, datasetVersionId);
    }

    /**
     * BEHAVIOR-RULE-AB-EVAL V1: explicit-defs overload. Runs every scenario
     * twice (baseline def + candidate def) and returns merged per-scenario
     * results. Reuses {@link #runSingleScenario} + {@link #sandboxFactory}.
     *
     * <p><b>Persistence contract</b> (r1-FIX, java-design WARN SRP):
     * this overload is <b>pure compute</b> — it does NOT touch any
     * {@code t_*_ab_run} row. The caller
     * ({@code BehaviorRuleAbEvalService.runAsync}) owns ALL persistence —
     * status transitions, delta calculation, JSON serialization. Existing
     * {@link #run(PromptAbRunEntity, PromptVersionEntity, EvalTaskEntity, AgentEntity)} /
     * {@link #run(PromptAbRunEntity, PromptVersionEntity, PromptVersionEntity, AgentEntity, String)}
     * overloads DO persist via {@link #promptAbRunRepository}; divergent contract
     * is intentional but cumulative SRP pressure on this class means V2
     * should extract an {@code AbScenarioRunner} helper (TODO below).
     *
     * <p>r1-FIX (java-design WARN abstraction): returns plain
     * {@code List<AbScenarioResult>} not a {@code DualDefResult} wrapper —
     * the single-field record had no upside. V2 may upgrade to a record when
     * token-usage fields land.
     *
     * @param abRunId       abRun id used as sandbox-naming prefix (suffixed
     *                      with {@code ":b"} / {@code ":c"} for the two sides)
     * @param scenarios     scenarios to run; runs both sides for each
     * @param baselineDef   agent definition for the baseline side (without
     *                      candidate rule injected)
     * @param candidateDef  agent definition for the candidate side (with
     *                      candidate rule injected)
     * @return per-scenario results in original order; on per-scenario error
     *         entry has status="ERROR" on both sides (does not abort batch)
     */
    public List<AbScenarioResult> runWithExplicitDefs(
            String abRunId,
            List<EvalScenarioEntity> scenarios,
            AgentDefinition baselineDef,
            AgentDefinition candidateDef) {
        // TODO(V2): extract AbScenarioRunner to relieve SRP pressure on
        //           AbEvalPipeline if a 5th eval orchestrator surface lands.
        if (scenarios == null || scenarios.isEmpty()) {
            return List.of();
        }
        // Apply same eval-overrides (force temperature=0.0, strip execution_mode)
        // as production run() — keeps A/B comparison deterministic.
        AgentDefinition bEval = copyWithoutEvalOverrides(baselineDef);
        AgentDefinition cEval = copyWithoutEvalOverrides(candidateDef);

        // Mirror the prompt-surface fan-out: cap=3 Semaphore + loopExecutor
        // (4 core / 8 max). 3 outer × 2 inner = 6 ≤ 8 with 2 buffer.
        final Semaphore concurrency = new Semaphore(scenarioConcurrency);
        List<CompletableFuture<AbScenarioResult>> futures = new ArrayList<>(scenarios.size());

        for (EvalScenarioEntity entity : scenarios) {
            EvalScenario scenario = toEvalScenario(entity);
            try {
                concurrency.acquire();
            } catch (InterruptedException ie) {
                // Restore interrupt flag and abort: throwing a checked-into-runtime
                // exception lets caller decide failure semantics (mark abRun FAILED).
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while throttling A/B scenario submit (abRunId=" + abRunId + ")", ie);
            }
            CompletableFuture<AbScenarioResult> fut = CompletableFuture.supplyAsync(() -> {
                try {
                    ScenarioRunResult bRun = runSingleScenario(abRunId + ":b", scenario, bEval);
                    EvalJudgeOutput bJudge = evalJudgeTool.judge(scenario, bRun);
                    ScenarioRunResult cRun = runSingleScenario(abRunId + ":c", scenario, cEval);
                    EvalJudgeOutput cJudge = evalJudgeTool.judge(scenario, cRun);
                    return new AbScenarioResult(
                            scenario.getId(), scenario.getName(),
                            new AbScenarioResult.RunResult(bRun.getStatus(), bJudge.getCompositeScore()),
                            new AbScenarioResult.RunResult(cRun.getStatus(), cJudge.getCompositeScore()));
                } catch (Exception e) {
                    log.error("BehaviorRule A/B scenario {} failed: {}",
                            scenario.getId(), e.getMessage(), e);
                    return new AbScenarioResult(
                            scenario.getId(), scenario.getName(),
                            new AbScenarioResult.RunResult("ERROR", 0.0),
                            new AbScenarioResult.RunResult("ERROR", 0.0));
                } finally {
                    concurrency.release();
                }
            }, loopExecutor);
            futures.add(fut);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<AbScenarioResult> out = new ArrayList<>(futures.size());
        for (CompletableFuture<AbScenarioResult> f : futures) {
            out.add(f.join());
        }
        return out;
    }

    /**
     * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1 (§2.2 / §7 B1 / W2) — def-based A/B
     * sibling of {@link #runWithExplicitDefs}, adding the candidate-only
     * winner-carry-forward skip the prompt path has. Runs every scenario against
     * {@code candidateDef} (and, unless a cached rate is supplied, against
     * {@code baselineDef}), returning merged per-scenario results.
     *
     * <p><b>Why a NEW method, not a refactor of {@link #runWithExplicitDefs}</b>
     * (§7 B1): the behavior_rule path that calls {@code runWithExplicitDefs} uses
     * {@code explain=false} + an {@code oracleScore>=0.5} caller predicate; folding
     * that into a shared method would silently change its byte behaviour. This
     * sibling takes an explicit {@code explain} flag and judges with
     * {@code judge(scenario, run, explain)} so the agent caller (explain=true) is
     * byte-identical to the prompt path's {@code judge(scenario, run, true)}.
     *
     * <p><b>Persistence contract</b>: pure compute — does NOT touch any
     * {@code t_*_ab_run} row (same SRP boundary as {@code runWithExplicitDefs}).
     * The caller ({@code AgentEvolveAbEvalService}) owns ALL persistence and
     * aggregates the pass-rate via {@link #isPass(AbScenarioResult.RunResult)}.
     *
     * <p>Returns {@code List<AbScenarioResult>} from day one (§7 W2) so Phase 2's
     * target/general partition can group per-scenario without changing this
     * signature.
     *
     * @param abRunId            sandbox-naming prefix (suffixed {@code ":b"} / {@code ":c"})
     * @param scenarios          scenarios to run (both sides unless skipped)
     * @param baselineDef        baseline-side agent definition (ignored when {@code cachedBaselineRate != null})
     * @param candidateDef       candidate-side agent definition
     * @param cachedBaselineRate non-null → CANDIDATE-ONLY; baseline side filled with
     *                           the {@link #BASELINE_CACHED_STATUS} sentinel (the
     *                           run-level rate carries the cached value, set by the caller)
     * @param explain            judge explain flag; agent caller passes {@code true} (§7 B1)
     * @return per-scenario results in original order; per-scenario error → status="ERROR" both sides
     */
    public List<AbScenarioResult> runWithDefs(
            String abRunId,
            List<EvalScenarioEntity> scenarios,
            AgentDefinition baselineDef,
            AgentDefinition candidateDef,
            Double cachedBaselineRate,
            boolean explain) {
        // Backward-compatible 6-arg overload — no extra (skill) defs to inject.
        return runWithDefs(abRunId, scenarios, baselineDef, candidateDef,
                cachedBaselineRate, explain, List.of());
    }

    /**
     * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 4 (§10 #5) — overload that injects
     * in-memory candidate {@code extraSkills} into the eval sandbox. The skills
     * are registered ONLY on the CANDIDATE side ({@code :c}); the baseline side
     * ({@code :b}) runs with the plain sandbox. This matches "baseline = agent
     * without the candidate skill" — and is correct under hill-climb because the
     * baseline arm only actually runs in the fresh round (no cached rate) where
     * best = the original agent (no skill); carry-forward rounds skip the baseline
     * arm and the carried-forward skill lives on the candidate side.
     *
     * @param extraSkills in-memory candidate skill defs (empty → identical to the
     *                    6-arg overload; registered on the candidate side only)
     */
    public List<AbScenarioResult> runWithDefs(
            String abRunId,
            List<EvalScenarioEntity> scenarios,
            AgentDefinition baselineDef,
            AgentDefinition candidateDef,
            Double cachedBaselineRate,
            boolean explain,
            List<SkillDefinition> extraSkills) {
        if (scenarios == null || scenarios.isEmpty()) {
            return List.of();
        }
        final List<SkillDefinition> candidateExtraSkills =
                extraSkills == null ? List.of() : extraSkills;
        final boolean skipBaseline = cachedBaselineRate != null;
        // Same eval-overrides (temperature=0.0, strip execution_mode) as the prompt
        // / behavior_rule paths — keeps the A/B comparison deterministic.
        AgentDefinition bEval = copyWithoutEvalOverrides(baselineDef);
        AgentDefinition cEval = copyWithoutEvalOverrides(candidateDef);

        final Semaphore concurrency = new Semaphore(scenarioConcurrency);
        List<CompletableFuture<AbScenarioResult>> futures = new ArrayList<>(scenarios.size());

        for (EvalScenarioEntity entity : scenarios) {
            EvalScenario scenario = toEvalScenario(entity);
            try {
                concurrency.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while throttling A/B scenario submit (abRunId=" + abRunId + ")", ie);
            }
            CompletableFuture<AbScenarioResult> fut = CompletableFuture.supplyAsync(() -> {
                try {
                    AbScenarioResult.RunResult baselineResult;
                    if (skipBaseline) {
                        // Candidate-only winner-carry-forward — never re-measure the
                        // baseline (same sentinel as runWithScenarios BUG-1 path).
                        baselineResult = new AbScenarioResult.RunResult(BASELINE_CACHED_STATUS, 0.0);
                    } else {
                        ScenarioRunResult bRun = runSingleScenario(abRunId + ":b", scenario, bEval);
                        EvalJudgeOutput bJudge = evalJudgeTool.judge(scenario, bRun, explain);
                        baselineResult = new AbScenarioResult.RunResult(
                                bRun.getStatus(), bJudge.getCompositeScore(), bJudge.getMetaJudgeRationale());
                    }
                    ScenarioRunResult cRun = runSingleScenario(
                            abRunId + ":c", scenario, cEval, candidateExtraSkills);
                    EvalJudgeOutput cJudge = evalJudgeTool.judge(scenario, cRun, explain);
                    return new AbScenarioResult(
                            scenario.getId(), scenario.getName(),
                            baselineResult,
                            new AbScenarioResult.RunResult(cRun.getStatus(),
                                    cJudge.getCompositeScore(), cJudge.getMetaJudgeRationale()));
                } catch (Exception e) {
                    log.error("Agent A/B scenario {} failed: {}", scenario.getId(), e.getMessage(), e);
                    return new AbScenarioResult(
                            scenario.getId(), scenario.getName(),
                            new AbScenarioResult.RunResult("ERROR", 0.0),
                            new AbScenarioResult.RunResult("ERROR", 0.0));
                } finally {
                    concurrency.release();
                }
            }, loopExecutor);
            futures.add(fut);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<AbScenarioResult> out = new ArrayList<>(futures.size());
        for (CompletableFuture<AbScenarioResult> f : futures) {
            out.add(f.join());
        }
        return out;
    }

    /**
     * Phase 1.4c helper — minimal {@link EvalScenarioEntity} → {@link EvalScenario}
     * adapter for the attribution path. Mirrors {@code ScenarioLoader.toEvalScenario}
     * (private) without coupling to its multi-turn JSON parsing (attribution
     * ephemerals are single-turn by construction).
     */
    private EvalScenario toEvalScenario(EvalScenarioEntity entity) {
        EvalScenario scenario = new EvalScenario();
        scenario.setId(entity.getId());
        scenario.setName(entity.getName());
        scenario.setDescription(entity.getDescription());
        scenario.setCategory(entity.getCategory());
        scenario.setSplit(entity.getSplit());
        scenario.setTask(entity.getTask());
        EvalScenario.ScenarioOracle oracle = new EvalScenario.ScenarioOracle();
        oracle.setType(entity.getOracleType());
        oracle.setExpected(entity.getOracleExpected());
        scenario.setOracle(oracle);
        return scenario;
    }

    private ScenarioRunResult runSingleScenario(String abRunId, EvalScenario scenario,
                                                 AgentDefinition candidateDef) {
        return runSingleScenario(abRunId, scenario, candidateDef, List.of());
    }

    /**
     * Phase 4 (§10 #5) overload — builds the sandbox registry WITH the supplied
     * in-memory {@code extraSkills} so the agent loop can resolve the bundle's
     * skill by name. An empty list reproduces the plain-sandbox path byte-for-byte
     * (so the prompt/behavior_rule callers stay unchanged).
     */
    private ScenarioRunResult runSingleScenario(String abRunId, EvalScenario scenario,
                                                 AgentDefinition candidateDef,
                                                 List<SkillDefinition> extraSkills) {
        try {
            SkillRegistry sandboxRegistry = (extraSkills == null || extraSkills.isEmpty())
                    ? sandboxFactory.buildSandboxRegistry(abRunId, scenario.getId())
                    : sandboxFactory.buildSandboxRegistryWithSkills(abRunId, scenario.getId(), extraSkills);
            AgentLoopEngine engine = evalEngineFactory.buildEvalEngine(sandboxRegistry);

            String evalSessionId = UUID.randomUUID().toString();
            LoopContext ctx = new LoopContext();
            // ★ 2026-05-24 V1 r3 fix: eval 跟 production 同 budget — 优先用 agent
            // config 的 max_loops (Main Assistant 配 50)，让 attribution-curator 改的
            // prompt 在 eval 反映真实 production 表现。之前 hardcoded scenario.getMaxLoops()
            // 默认 10 → research task 多步 LLM 调用撞顶 TIMEOUT (Event 122 dogfood
            // 暴露：rule #6 "总结再分析" 让 4 个 web research scenarios 全 TIMEOUT
            // 因 candidate 10 loops 不够，但 production 50 loops 完全能跑完)。
            int agentMaxLoops = readAgentMaxLoops(candidateDef, scenario.getMaxLoops());
            ctx.setMaxLoops(agentMaxLoops);
            ctx.setExecutionMode("auto");
            ctx.setMaxLlmStreamTimeoutMs(20_000L);

            // Strip eval-sensitive config (max_loops 现在保留，让 evalDef agent 的
            // config 跟 ctx 一致；execution_mode 仍 strip 防 'ask' 卡住 eval)
            AgentDefinition evalDef = copyWithoutEvalOverrides(candidateDef);

            Path sandboxRoot = sandboxFactory.getSandboxRoot(abRunId, scenario.getId());
            String task = scenario.getTask().replace("/tmp/eval/", sandboxRoot.toString() + "/");

            // Write fixture files
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

            // Run with configurable timeout (default 120s — old 30s killed most
            // realistic LLM multi-turn agent runs at 7/12 scenarios for Event 122
            // V108 verify). Override via skillforge.flywheel.ab-eval.scenario-timeout-ms.
            Future<LoopResult> future = loopExecutor.submit(
                    () -> engine.run(evalDef, task, null, evalSessionId, null, ctx));

            long startMs = System.currentTimeMillis();
            LoopResult loopResult;
            try {
                loopResult = future.get(scenarioTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return ScenarioRunResult.timeout(scenario.getId(),
                        scenarioTimeoutMs + "ms AB eval timeout");
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
            log.error("AB eval single scenario failed: {}", scenario.getId(), e);
            ScenarioRunResult result = ScenarioRunResult.error(scenario.getId(), e.getMessage());
            return result;
        } finally {
            sandboxFactory.cleanupSandbox(abRunId, scenario.getId());
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
            // ★ 2026-05-24 V1 r3 fix: 不再 remove max_loops — eval 跟 production 同
            // budget，让 attribution-curator 改的 prompt 在 eval 反映真实表现。
            // (历史: 删 max_loops 让 scenario.getMaxLoops()=10 接管，强行降级 budget
            //  让 eval 评的是"严苛环境表现"而非 production 真实表现)
            configCopy.remove("execution_mode");  // 仍 strip 防 'ask' 卡 eval
            // ★ 2026-05-24 V1 r3.2 fix: eval 强制 temperature=0.0 让 A/B 可重复对比
            // (Event 122 第 5 vs 7 次 retry 同 prompt 同 dataset，baseline 从 44.9%
            // 变到 34.7% — 因 agent config temperature=0.7 + parallel batch 同时跑
            // LLM 撞 randomness/rate limit，5 个 scenario PASS→FAIL drift)。
            // eval 是 deterministic A/B 比较，random 是 noise；production 用户
            // chat 仍按 agent.config 原 temperature。
            configCopy.put("temperature", 0.0);
            copy.setConfig(configCopy);
        }
        return copy;
    }

    /**
     * ★ 2026-05-24 V1 r3 fix: 读 agent.config.max_loops，回退到 scenario 默认。
     * Priority: agent.config.max_loops &gt; scenarioFallback &gt; built-in 10.
     */
    private int readAgentMaxLoops(AgentDefinition agentDef, int scenarioFallback) {
        if (agentDef.getConfig() != null) {
            Object v = agentDef.getConfig().get("max_loops");
            if (v instanceof Number n) return n.intValue();
            if (v instanceof String s) {
                try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) {}
            }
        }
        return scenarioFallback > 0 ? scenarioFallback : 10;
    }

    /**
     * EVAL-429-ROBUSTNESS Fix4 — held-out baseline rate over MEASURED historical
     * scenarios only. ERROR/TIMEOUT rows in the baseline eval task are dropped from
     * both numerator and denominator (D1/D2). Returns {@code null} when no held-out
     * scenario was measured (D3 not-measured sentinel) so the gate doesn't read it as
     * a 0% baseline. Legacy rows with no {@code status} field are conservatively
     * treated as measured (so old datasets keep their historical rate).
     */
    private Double computeHeldOutBaselineRate(EvalTaskEntity baselineRun, List<EvalScenario> heldOutScenarios) {
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
                    .filter(AbEvalPipeline::isHistoricScenarioMeasured)
                    .filter(r -> Boolean.TRUE.equals(r.get("pass")))
                    .count();

            long heldOutTotal = results.stream()
                    .filter(r -> heldOutIds.contains(r.get("scenarioId")))
                    .filter(AbEvalPipeline::isHistoricScenarioMeasured)
                    .count();

            return heldOutTotal == 0 ? null : (double) heldOutPassed / heldOutTotal * 100;
        } catch (Exception e) {
            log.warn("Failed to parse baseline scenario results, using overall pass rate", e);
            return baselineRun.getOverallPassRate();
        }
    }

    /**
     * EVAL-429-ROBUSTNESS Fix4 — a historical (persisted) baseline scenario row counts
     * as measured unless its {@code status} field is ERROR/TIMEOUT. A missing status
     * (legacy rows) is conservatively kept as measured.
     */
    private static boolean isHistoricScenarioMeasured(Map<String, Object> row) {
        Object status = row.get("status");
        if (status == null) {
            return true;
        }
        String s = String.valueOf(status);
        return !("ERROR".equals(s) || "TIMEOUT".equals(s));
    }

    private String lookupBaselineStatus(EvalTaskEntity baselineRun, String scenarioId) {
        return lookupBaselineField(baselineRun, scenarioId, "status", "UNKNOWN");
    }

    private double lookupBaselineOracleScore(EvalTaskEntity baselineRun, String scenarioId) {
        String scoreStr = lookupBaselineField(baselineRun, scenarioId, "compositeScore", "0");
        try {
            return Double.parseDouble(scoreStr);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    @SuppressWarnings("unchecked")
    private String lookupBaselineField(EvalTaskEntity baselineRun, String scenarioId,
                                        String field, String defaultValue) {
        if (baselineRun.getScenarioResultsJson() == null) return defaultValue;
        try {
            List<Map<String, Object>> results = objectMapper.readValue(
                    baselineRun.getScenarioResultsJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
            return results.stream()
                    .filter(r -> scenarioId.equals(r.get("scenarioId")))
                    .findFirst()
                    .map(r -> String.valueOf(r.getOrDefault(field, defaultValue)))
                    .orElse(defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
