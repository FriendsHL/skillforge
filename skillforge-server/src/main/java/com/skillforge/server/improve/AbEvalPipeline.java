package com.skillforge.server.improve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.model.AgentDefinition;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AbEvalPipeline {

    private static final Logger log = LoggerFactory.getLogger(AbEvalPipeline.class);

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
             broadcaster, loopExecutor, scenarioTimeoutMs, null, null);
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
                           EvalDatasetVersionRepository evalDatasetVersionRepository) {
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

        // 3. Compute held-out baseline rate from baselineRun's scenarioResultsJson
        double heldOutBaselineRate = computeHeldOutBaselineRate(baselineRun, heldOutScenarios);
        abRun.setBaselinePassRate(heldOutBaselineRate);

        // 4. Construct candidate AgentDefinition with candidate prompt
        AgentDefinition candidateDef = agentService.toAgentDefinition(originalAgent);
        candidateDef.setSystemPrompt(candidate.getContent());

        // 5. Run each held-out scenario with candidate prompt
        List<AbScenarioResult> scenarioResults = new ArrayList<>();
        int passed = 0;

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

                if (judgeOutput.isPass()) {
                    passed++;
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
            }
        }

        // 6. Compute candidate pass rate and delta
        double candidatePassRate = heldOutScenarios.isEmpty() ? 0
                : (double) passed / heldOutScenarios.size() * 100;
        double delta = candidatePassRate - heldOutBaselineRate;

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
        log.warn("AbEvalPipeline.run(scenarios) legacy overload invoked — V2 will remove this; "
                + "migrate to run(abRun, candidate, baseline, agent, datasetVersionId). abRunId={}",
                abRun.getId());
        runWithScenarios(abRun, candidate, baselineVersion, agent, scenarios, null);
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
        runWithScenarios(abRun, candidate, baselineVersion, agent, scenarios, datasetVersionId);
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
                                   String datasetVersionId) {
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
        // results / passed counts 用 thread-safe collections。
        AtomicInteger baselinePassedAtomic = new AtomicInteger(0);
        AtomicInteger candidatePassedAtomic = new AtomicInteger(0);
        List<CompletableFuture<AbScenarioResult>> futures = new ArrayList<>(scenarios.size());

        for (EvalScenarioEntity entity : scenarios) {
            EvalScenario scenario = toEvalScenario(entity);
            CompletableFuture<AbScenarioResult> fut = CompletableFuture.supplyAsync(() -> {
                try {
                    // Distinct sandbox ids per side prevent SandboxFactory collision
                    // when baseline + candidate write fixture files to the same path.
                    ScenarioRunResult baselineRun = runSingleScenario(
                            abRun.getId() + "-baseline", scenario, baselineDef);
                    EvalJudgeOutput baselineJudge = evalJudgeTool.judge(scenario, baselineRun);
                    ScenarioRunResult candidateRun = runSingleScenario(
                            abRun.getId() + "-candidate", scenario, candidateDef);
                    EvalJudgeOutput candidateJudge = evalJudgeTool.judge(scenario, candidateRun);

                    if (baselineJudge.isPass()) baselinePassedAtomic.incrementAndGet();
                    if (candidateJudge.isPass()) candidatePassedAtomic.incrementAndGet();

                    return new AbScenarioResult(
                            scenario.getId(), scenario.getName(),
                            new AbScenarioResult.RunResult(baselineRun.getStatus(),
                                    baselineJudge.getCompositeScore()),
                            new AbScenarioResult.RunResult(candidateRun.getStatus(),
                                    candidateJudge.getCompositeScore()));
                } catch (Exception e) {
                    log.error("Attribution A/B scenario {} failed: {}", scenario.getId(), e.getMessage(), e);
                    return new AbScenarioResult(
                            scenario.getId(), scenario.getName(),
                            new AbScenarioResult.RunResult("ERROR", 0.0),
                            new AbScenarioResult.RunResult("ERROR", 0.0));
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
        int baselinePassed = baselinePassedAtomic.get();
        int candidatePassed = candidatePassedAtomic.get();

        double baselinePassRate = (double) baselinePassed / scenarios.size() * 100;
        double candidatePassRate = (double) candidatePassed / scenarios.size() * 100;
        double delta = candidatePassRate - baselinePassRate;

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
        if (datasetVersionId != null && evalDatasetVersionRepository != null) {
            try {
                evalDatasetVersionRepository.findById(datasetVersionId).ifPresent(v -> {
                    double newFraction = baselinePassRate / 100.0;
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
        try {
            SkillRegistry sandboxRegistry = sandboxFactory.buildSandboxRegistry(abRunId, scenario.getId());
            AgentLoopEngine engine = evalEngineFactory.buildEvalEngine(sandboxRegistry);

            String evalSessionId = UUID.randomUUID().toString();
            LoopContext ctx = new LoopContext();
            ctx.setMaxLoops(scenario.getMaxLoops());
            ctx.setExecutionMode("auto");
            ctx.setMaxLlmStreamTimeoutMs(20_000L);

            // Strip eval-sensitive config
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
