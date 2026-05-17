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
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    public AbEvalPipeline(ScenarioLoader scenarioLoader,
                           SandboxSkillRegistryFactory sandboxFactory,
                           EvalEngineFactory evalEngineFactory,
                           EvalJudgeTool evalJudgeTool,
                           PromptAbRunRepository promptAbRunRepository,
                           PromptVersionRepository promptVersionRepository,
                           AgentService agentService,
                           ObjectMapper objectMapper,
                           ChatEventBroadcaster broadcaster,
                           @Qualifier("abEvalLoopExecutor") ExecutorService loopExecutor) {
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
    public void run(PromptAbRunEntity abRun,
                    PromptVersionEntity candidate,
                    PromptVersionEntity baselineVersion,
                    AgentEntity agent,
                    List<EvalScenarioEntity> scenarios) {
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

        List<AbScenarioResult> results = new ArrayList<>();
        int baselinePassed = 0;
        int candidatePassed = 0;
        for (EvalScenarioEntity entity : scenarios) {
            EvalScenario scenario = toEvalScenario(entity);
            try {
                // Distinct sandbox ids per side prevent SandboxFactory collision
                // when baseline + candidate write fixture files to the same path.
                ScenarioRunResult baselineRun = runSingleScenario(
                        abRun.getId() + "-baseline", scenario, baselineDef);
                EvalJudgeOutput baselineJudge = evalJudgeTool.judge(scenario, baselineRun);
                ScenarioRunResult candidateRun = runSingleScenario(
                        abRun.getId() + "-candidate", scenario, candidateDef);
                EvalJudgeOutput candidateJudge = evalJudgeTool.judge(scenario, candidateRun);

                if (baselineJudge.isPass()) baselinePassed++;
                if (candidateJudge.isPass()) candidatePassed++;

                results.add(new AbScenarioResult(
                        scenario.getId(), scenario.getName(),
                        new AbScenarioResult.RunResult(baselineRun.getStatus(),
                                baselineJudge.getCompositeScore()),
                        new AbScenarioResult.RunResult(candidateRun.getStatus(),
                                candidateJudge.getCompositeScore())));
            } catch (Exception e) {
                log.error("Attribution A/B scenario {} failed: {}", scenario.getId(), e.getMessage(), e);
                results.add(new AbScenarioResult(
                        scenario.getId(), scenario.getName(),
                        new AbScenarioResult.RunResult("ERROR", 0.0),
                        new AbScenarioResult.RunResult("ERROR", 0.0)));
            }
        }

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

        log.info("Attribution A/B run {} COMPLETED: baseline={} candidate={} delta={}",
                abRun.getId(), baselinePassRate, candidatePassRate, delta);
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

            // Run with 30s timeout, no retry
            Future<LoopResult> future = loopExecutor.submit(
                    () -> engine.run(evalDef, task, null, evalSessionId, null, ctx));

            long startMs = System.currentTimeMillis();
            LoopResult loopResult;
            try {
                loopResult = future.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return ScenarioRunResult.timeout(scenario.getId(), "30s AB eval timeout");
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
