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
import com.skillforge.server.entity.EvalRunEntity;
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
                    EvalRunEntity baselineRun, AgentEntity originalAgent) {
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

            if (loopResult.getToolCalls() != null) {
                loopResult.getToolCalls().stream()
                        .filter(tc -> !tc.isSuccess())
                        .findFirst()
                        .ifPresent(tc -> result.setSkillExecutionFailed(true));
            }

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

    private double computeHeldOutBaselineRate(EvalRunEntity baselineRun, List<EvalScenario> heldOutScenarios) {
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

    private String lookupBaselineStatus(EvalRunEntity baselineRun, String scenarioId) {
        return lookupBaselineField(baselineRun, scenarioId, "status", "UNKNOWN");
    }

    private double lookupBaselineOracleScore(EvalRunEntity baselineRun, String scenarioId) {
        String scoreStr = lookupBaselineField(baselineRun, scenarioId, "compositeScore", "0");
        try {
            return Double.parseDouble(scoreStr);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    @SuppressWarnings("unchecked")
    private String lookupBaselineField(EvalRunEntity baselineRun, String scenarioId,
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
