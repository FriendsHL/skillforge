package com.skillforge.server.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.EvalTaskItemEntity;
import com.skillforge.server.eval.attribution.FailureAttribution;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.eval.scenario.ScenarioLoader;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.EvalTaskItemRepository;
import com.skillforge.server.repository.EvalTaskRepository;
import com.skillforge.server.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EvalOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(EvalOrchestrator.class);

    private final ScenarioLoader scenarioLoader;
    private final ScenarioRunnerTool scenarioRunner;
    private final EvalJudgeTool evalJudge;
    private final EvalTaskRepository evalRunRepository;
    private final EvalTaskItemRepository evalTaskItemRepository;
    private final CollabRunRepository collabRunRepository;
    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final ChatEventBroadcaster broadcaster;
    private final LlmTraceRepository llmTraceRepository;
    private final BigDecimal globalCostThresholdUsd;

    public EvalOrchestrator(ScenarioLoader scenarioLoader,
                            ScenarioRunnerTool scenarioRunner,
                            EvalJudgeTool evalJudge,
                            EvalTaskRepository evalRunRepository,
                            EvalTaskItemRepository evalTaskItemRepository,
                            CollabRunRepository collabRunRepository,
                            AgentService agentService,
                            ObjectMapper objectMapper,
                            ChatEventBroadcaster broadcaster,
                            LlmTraceRepository llmTraceRepository,
                            @Value("${skillforge.eval.score.global-cost-threshold-usd:0.01}")
                            String globalCostThresholdUsd) {
        this.scenarioLoader = scenarioLoader;
        this.scenarioRunner = scenarioRunner;
        this.evalJudge = evalJudge;
        this.evalRunRepository = evalRunRepository;
        this.evalTaskItemRepository = evalTaskItemRepository;
        this.collabRunRepository = collabRunRepository;
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
        this.llmTraceRepository = llmTraceRepository;
        this.globalCostThresholdUsd = new BigDecimal(globalCostThresholdUsd);
    }

    public void runEval(String agentDefinitionId, Long userId, String evalRunId) {
        log.info("Starting eval run: evalRunId={}, agentId={}", evalRunId, agentDefinitionId);

        // 1. Goodhart rate limit: no active/completed eval in last 30 minutes for this agent.
        // Only count RUNNING or COMPLETED tasks — FAILED ghost tasks must not extend the window indefinitely.
        List<EvalTaskEntity> recentRuns = evalRunRepository.findByAgentDefinitionIdAndStatusInAndStartedAtAfter(
                agentDefinitionId, List.of("RUNNING", "COMPLETED"), Instant.now().minusSeconds(30 * 60));
        if (!recentRuns.isEmpty()) {
            log.warn("Eval rate limited: agent {} already ran eval within 30 minutes", agentDefinitionId);
            // Do NOT create a FAILED entity here — that would itself become a blocker on the next check.
            // Just return without persisting.
            return;
        }

        // 2. Load scenarios
        List<EvalScenario> scenarios = scenarioLoader.loadAll();
        if (scenarios.isEmpty()) {
            log.error("No eval scenarios found");
            EvalTaskEntity run = createEvalTask(evalRunId, agentDefinitionId, userId);
            run.setStatus("FAILED");
            run.setErrorMessage("No eval scenarios found on classpath");
            run.setCompletedAt(Instant.now());
            evalRunRepository.save(run);
            return;
        }

        // 3. Create EvalTaskEntity (still using legacy "run" naming on the
        //    in-method var to keep diff small; persisted entity is on t_eval_task).
        EvalTaskEntity evalRun = createEvalTask(evalRunId, agentDefinitionId, userId);
        evalRun.setTotalScenarios(scenarios.size());
        evalRun.setScenarioCount(scenarios.size());
        evalRunRepository.save(evalRun);

        // 4. Create CollabRunEntity for eval
        CollabRunEntity collabRun = new CollabRunEntity();
        collabRun.setCollabRunId(UUID.randomUUID().toString());
        collabRun.setStatus("RUNNING");
        collabRun.setRunType("eval");
        collabRun.setCreatedAt(Instant.now());
        collabRunRepository.save(collabRun);
        evalRun.setCollabRunId(collabRun.getCollabRunId());
        evalRunRepository.save(evalRun);

        try {
            // Resolve AgentDefinition
            Long agentIdLong = Long.parseLong(agentDefinitionId);
            AgentDefinition agentDef = agentService.toAgentDefinition(
                    agentService.getAgent(agentIdLong));

            // 5. Run each scenario sequentially (v1.0)
            List<Map<String, Object>> scenarioResults = new ArrayList<>();
            int passed = 0, failed = 0, timeouts = 0, vetos = 0;
            double totalOracleScore = 0;
            Map<FailureAttribution, Integer> attrHistogram = new HashMap<>();

            // EVAL-V2 M1: progress streaming throttle. When totalCount > 50, push
            // events on roughly every 5% boundary instead of per-case to avoid WS
            // spam at scale. The final case always pushes (i == totalCount-1).
            int totalCount = scenarios.size();
            int progressStride = computeProgressStride(totalCount);

            for (int i = 0; i < totalCount; i++) {
                EvalScenario scenario = scenarios.get(i);
                log.info("Running scenario: {} ({})", scenario.getId(), scenario.getName());

                boolean shouldPushProgress = shouldPushProgressEvent(i, totalCount, progressStride);

                // EVAL-V2 M1: push case_running BEFORE we run the scenario so the UI
                // can show "current case" while the scenario executes.
                if (shouldPushProgress && broadcaster != null && userId != null) {
                    Map<String, Object> evt = new LinkedHashMap<>();
                    evt.put("type", "eval_progress");
                    evt.put("event", "case_running");
                    evt.put("evalRunId", evalRunId);
                    evt.put("scenarioId", scenario.getId());
                    evt.put("scenarioName", scenario.getName());
                    evt.put("passedCount", passed);
                    evt.put("totalCount", totalCount);
                    evt.put("currentIndex", i);
                    broadcaster.userEvent(userId, evt);
                }

                Instant scenarioStartedAt = Instant.now();

                // EVAL-V2 M2: branch to multi-turn execution + multi-turn judge
                // when the scenario carries a non-empty conversation_turns spec.
                // Single-turn (NULL/empty) scenarios stay on the legacy path
                // verbatim — preserves backward compat for the entire pipeline.
                ScenarioRunResult runResult;
                EvalJudgeOutput judgeOutput;
                if (scenario.isMultiTurn()) {
                    MultiTurnTranscript transcript = new MultiTurnTranscript();
                    runResult = scenarioRunner.runScenarioMultiTurn(
                            evalRunId, scenario, agentDef, agentIdLong, userId, transcript);
                    EvalJudgeMultiTurnOutput mt = evalJudge.judgeMultiTurnConversation(
                            scenario, runResult, transcript);
                    // Project multi-turn output → legacy EvalJudgeOutput shape so
                    // the existing scenarioResults JSON / aggregation paths work
                    // without forking. compositeScore is the single source of truth
                    // for pass/fail and aggregation; outcomeScore = overallScore,
                    // efficiencyScore is left at 0 for multi-turn (efficiency
                    // semantics for multi-turn would need its own design — out of
                    // M2 scope; the per-turn detail surfaces via metaJudgeRationale).
                    judgeOutput = new EvalJudgeOutput();
                    judgeOutput.setOutcomeScore(mt.getOverallScore());
                    judgeOutput.setEfficiencyScore(0.0);
                    judgeOutput.setCompositeScore(mt.getCompositeScore());
                    judgeOutput.setPass(mt.isPass());
                    judgeOutput.setAttribution(mt.getAttribution());
                    judgeOutput.setMetaJudgeRationale(mt.getRationale());
                } else {
                    runResult = scenarioRunner.runScenario(
                            evalRunId, scenario, agentDef, agentIdLong, userId);
                    judgeOutput = evalJudge.judge(scenario, runResult);
                }

                BigDecimal observedCostUsd = aggregateTraceTreeCost(runResult.getRootTraceId());
                runResult.setCostUsd(observedCostUsd);
                EvalScoreFormula.Result scoreResult = EvalScoreFormula.calculate(
                        judgeOutput.getOutcomeScore(),
                        judgeOutput.getEfficiencyScore(),
                        runResult.getExecutionTimeMs(),
                        scenario.getPerformanceThresholdMs(),
                        observedCostUsd,
                        globalCostThresholdUsd,
                        runResult.getLoopCount(),
                        runResult.getToolCallCount()
                );
                judgeOutput.setCompositeScore(scoreResult.compositeScore());
                judgeOutput.setPass(scoreResult.compositeScore() >= EvalScoreFormula.PASS_THRESHOLD);

                // Promote final scenario status from PENDING_JUDGE → judge verdict
                // PASS/FAIL so the t_eval_task_item.status reflects the eventual
                // judge result, not the loop runner's interim PENDING_JUDGE.
                if ("PENDING_JUDGE".equals(runResult.getStatus())) {
                    runResult.setStatus(judgeOutput.isPass() ? "PASS" : "FAIL");
                }

                // Collect results (legacy jsonb shape — kept during dual-write
                // transition; the new t_eval_task_item row is the canonical source).
                Map<String, Object> scenarioResult = new LinkedHashMap<>();
                scenarioResult.put("scenarioId", scenario.getId());
                scenarioResult.put("name", scenario.getName());
                scenarioResult.put("category", scenario.getCategory());
                scenarioResult.put("split", scenario.getSplit());
                scenarioResult.put("status", runResult.getStatus());
                scenarioResult.put("outcomeScore", judgeOutput.getOutcomeScore());
                scenarioResult.put("efficiencyScore", judgeOutput.getEfficiencyScore());
                scenarioResult.put("qualityScore", scoreResult.qualityScore());
                scenarioResult.put("latencyScore", scoreResult.latencyScore());
                scenarioResult.put("costScore", scoreResult.costScore());
                scenarioResult.put("compositeScore", judgeOutput.getCompositeScore());
                scenarioResult.put("costUsd", observedCostUsd);
                scenarioResult.put("scoreFormulaVersion", scoreResult.formulaVersion());
                scenarioResult.put("scoreBreakdownJson", scoreResult.breakdownJson());
                scenarioResult.put("pass", judgeOutput.isPass());
                scenarioResult.put("attribution", judgeOutput.getAttribution().name());
                scenarioResult.put("executionTimeMs", runResult.getExecutionTimeMs());
                scenarioResult.put("loopCount", runResult.getLoopCount());
                scenarioResult.put("inputTokens", runResult.getInputTokens());
                scenarioResult.put("outputTokens", runResult.getOutputTokens());
                if (runResult.getErrorMessage() != null) {
                    scenarioResult.put("errorMessage", runResult.getErrorMessage());
                }
                scenarioResult.put("agentFinalOutput", runResult.getAgentFinalOutput());
                scenarioResult.put("task", scenario.getTask());
                if (scenario.getOracle() != null) {
                    scenarioResult.put("oracleType", scenario.getOracle().getType());
                    if (scenario.getOracle().getExpected() != null) {
                        scenarioResult.put("oracleExpected", scenario.getOracle().getExpected());
                    }
                    if (scenario.getOracle().getExpectedList() != null) {
                        scenarioResult.put("oracleExpectedList", scenario.getOracle().getExpectedList());
                    }
                }
                if (judgeOutput.getMetaJudgeRationale() != null) {
                    scenarioResult.put("judgeRationale", judgeOutput.getMetaJudgeRationale());
                }
                scenarioResults.add(scenarioResult);

                // EVAL-V2 M3a (b2): dual-write per-case row to t_eval_task_item.
                // Wrapped in try/catch so a row-level write failure doesn't lose
                // the whole task — the legacy jsonb still captures it.
                try {
                    EvalTaskItemEntity item = new EvalTaskItemEntity();
                    item.setTaskId(evalRunId);
                    item.setScenarioId(scenario.getId());
                    item.setScenarioSource(scenario.getSource());
                    item.setSessionId(runResult.getSessionId());
                    item.setRootTraceId(runResult.getRootTraceId());
                    item.setCompositeScore(BigDecimal.valueOf(judgeOutput.getCompositeScore())
                            .setScale(2, RoundingMode.HALF_UP));
                    item.setQualityScore(toScaledDecimal(scoreResult.qualityScore()));
                    item.setEfficiencyScore(toScaledDecimal(scoreResult.efficiencyScore()));
                    item.setLatencyScore(toScaledDecimal(scoreResult.latencyScore()));
                    item.setCostScore(toScaledDecimal(scoreResult.costScore()));
                    item.setCostUsd(observedCostUsd != null
                            ? observedCostUsd.setScale(6, RoundingMode.HALF_UP) : null);
                    item.setScoreFormulaVersion(scoreResult.formulaVersion());
                    item.setScoreBreakdownJson(scoreResult.breakdownJson());
                    item.setStatus(runResult.getStatus());
                    item.setLoopCount(runResult.getLoopCount());
                    item.setToolCallCount(runResult.getToolCallCount());
                    item.setLatencyMs(runResult.getExecutionTimeMs());
                    item.setAttribution(judgeOutput.getAttribution() != null
                            ? judgeOutput.getAttribution().name() : null);
                    item.setJudgeRationale(judgeOutput.getMetaJudgeRationale());
                    item.setAgentFinalOutput(runResult.getAgentFinalOutput());
                    item.setStartedAt(scenarioStartedAt);
                    item.setCompletedAt(Instant.now());
                    item.touchCreatedAtIfMissing();
                    evalTaskItemRepository.save(item);
                } catch (Exception itemSaveEx) {
                    // EVAL-V2 M3a (b2): tolerate item-row write failure — the legacy
                    // jsonb still captures the case, and the orchestrator continues.
                    // Logged at WARN so flaky DB doesn't silently lose data.
                    log.warn("Failed to persist EvalTaskItemEntity for task={} scenario={}: {}",
                            evalRunId, scenario.getId(), itemSaveEx.getMessage(), itemSaveEx);
                }

                totalOracleScore += judgeOutput.getCompositeScore();

                switch (runResult.getStatus()) {
                    case "PASS" -> passed++;
                    case "FAIL" -> failed++;
                    case "TIMEOUT" -> timeouts++;
                    case "VETO" -> vetos++;
                    default -> failed++;
                }

                attrHistogram.merge(judgeOutput.getAttribution(), 1, Integer::sum);

                // Broadcast eval_scenario_finished (legacy, retained for callers
                // that already subscribe to per-case finishes).
                if (broadcaster != null && userId != null) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("type", "eval_scenario_finished");
                    event.put("evalRunId", evalRunId);
                    event.put("scenarioId", runResult.getScenarioId());
                    event.put("status", runResult.getStatus());
                    event.put("oracleScore", judgeOutput.getCompositeScore());
                    event.put("executionTimeMs", runResult.getExecutionTimeMs());
                    event.put("loopCount", runResult.getLoopCount());
                    broadcaster.userEvent(userId, event);
                }

                // EVAL-V2 M1: push case_passed / case_failed for progress UI
                // (separate type=eval_progress channel from legacy event above).
                if (shouldPushProgress && broadcaster != null && userId != null) {
                    Map<String, Object> evt = new LinkedHashMap<>();
                    evt.put("type", "eval_progress");
                    evt.put("event", judgeOutput.isPass() ? "case_passed" : "case_failed");
                    evt.put("evalRunId", evalRunId);
                    evt.put("scenarioId", scenario.getId());
                    evt.put("scenarioName", scenario.getName());
                    evt.put("score", judgeOutput.getCompositeScore());
                    evt.put("attribution", judgeOutput.getAttribution().name());
                    evt.put("passedCount", passed);
                    evt.put("totalCount", totalCount);
                    evt.put("currentIndex", i);
                    broadcaster.userEvent(userId, evt);
                }
            }

            // 6. Aggregate stats
            double passRate = scenarios.isEmpty() ? 0 : (double) passed / scenarios.size() * 100;
            double avgOracleScore = scenarios.isEmpty() ? 0 : totalOracleScore / scenarios.size();

            // Find primary attribution (most common)
            FailureAttribution primaryAttr = attrHistogram.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(FailureAttribution.NONE);

            // 7. Generate improvement suggestions
            List<Map<String, Object>> suggestions = generateSuggestions(attrHistogram);

            // 8. Goodhart delta monitoring
            int consecutiveDecline = computeConsecutiveDeclineCount(agentDefinitionId, passRate);

            // 9. Persist EvalTaskEntity (final aggregates)
            evalRun.setStatus("COMPLETED");
            evalRun.setOverallPassRate(passRate);
            evalRun.setAvgOracleScore(avgOracleScore);
            evalRun.setPassCount(passed);
            evalRun.setFailedScenarios(failed);
            evalRun.setTimeoutScenarios(timeouts);
            evalRun.setVetoScenarios(vetos);
            evalRun.setPrimaryAttribution(primaryAttr);
            evalRun.setAttrSkillMissing(attrHistogram.getOrDefault(FailureAttribution.SKILL_MISSING, 0));
            evalRun.setAttrSkillExecFailure(attrHistogram.getOrDefault(FailureAttribution.SKILL_EXECUTION_FAILURE, 0));
            evalRun.setAttrPromptQuality(attrHistogram.getOrDefault(FailureAttribution.PROMPT_QUALITY, 0));
            evalRun.setAttrContextOverflow(attrHistogram.getOrDefault(FailureAttribution.CONTEXT_OVERFLOW, 0));
            evalRun.setAttrPerformance(attrHistogram.getOrDefault(FailureAttribution.PERFORMANCE, 0));
            evalRun.setAttrMemoryInterference(attrHistogram.getOrDefault(FailureAttribution.MEMORY_INTERFERENCE, 0));
            evalRun.setAttrMemoryMissing(attrHistogram.getOrDefault(FailureAttribution.MEMORY_MISSING, 0));
            evalRun.setConsecutiveDeclineCount(consecutiveDecline);
            evalRun.setCompletedAt(Instant.now());

            // EVAL-V2 M3a (b2) new aggregates
            // failCount = anything that didn't pass (failed + timeouts + vetos + errors).
            evalRun.setFailCount(scenarios.size() - passed);
            // composite_avg = mean of per-case composite score, scale=2.
            evalRun.setCompositeAvg(BigDecimal.valueOf(avgOracleScore)
                    .setScale(2, RoundingMode.HALF_UP));

            try {
                evalRun.setScenarioResultsJson(objectMapper.writeValueAsString(scenarioResults));
                evalRun.setImprovementSuggestionsJson(objectMapper.writeValueAsString(suggestions));
            } catch (Exception e) {
                log.warn("Failed to serialize eval results to JSON", e);
            }
            evalRunRepository.save(evalRun);

            // 10. Update CollabRunEntity
            collabRun.setStatus("COMPLETED");
            collabRun.setCompletedAt(Instant.now());
            collabRunRepository.save(collabRun);

            // 11. Broadcast eval_run_completed (legacy event)
            if (broadcaster != null && userId != null) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", "eval_run_completed");
                event.put("evalRunId", evalRunId);
                event.put("status", "COMPLETED");
                event.put("passRate", passRate);
                event.put("avgOracleScore", avgOracleScore);
                event.put("totalScenarios", scenarios.size());
                event.put("passed", passed);
                event.put("failed", failed);
                broadcaster.userEvent(userId, event);
            }

            // EVAL-V2 M1: progress channel completion event (mirrors legacy event
            // but with type=eval_progress, so the FE progress handler doesn't
            // have to subscribe to two payload shapes).
            if (broadcaster != null && userId != null) {
                Map<String, Object> evt = new LinkedHashMap<>();
                evt.put("type", "eval_progress");
                evt.put("event", "eval_run_completed");
                evt.put("evalRunId", evalRunId);
                evt.put("passedCount", passed);
                evt.put("failedCount", failed);
                evt.put("totalCount", scenarios.size());
                broadcaster.userEvent(userId, evt);
            }

            log.info("Eval run completed: evalRunId={}, passRate={}%, avgScore={}", evalRunId,
                    String.format("%.1f", passRate), String.format("%.1f", avgOracleScore));

        } catch (Exception e) {
            log.error("Eval run failed: evalRunId={}", evalRunId, e);
            evalRun.setStatus("FAILED");
            evalRun.setErrorMessage(e.getMessage());
            evalRun.setCompletedAt(Instant.now());
            evalRunRepository.save(evalRun);

            collabRun.setStatus("COMPLETED");
            collabRun.setCompletedAt(Instant.now());
            collabRunRepository.save(collabRun);
        }
    }

    private EvalTaskEntity createEvalTask(String evalRunId, String agentDefinitionId, Long userId) {
        EvalTaskEntity run = new EvalTaskEntity();
        run.setId(evalRunId);
        run.setAgentDefinitionId(agentDefinitionId);
        run.setStatus("RUNNING");
        run.setTriggeredByUserId(userId);
        return evalRunRepository.save(run);
    }

    private List<Map<String, Object>> generateSuggestions(Map<FailureAttribution, Integer> histogram) {
        List<Map<String, Object>> suggestions = new ArrayList<>();

        Map<FailureAttribution, String> suggestionMap = Map.of(
                FailureAttribution.PROMPT_QUALITY, "Review task descriptions for ambiguity and add clearer output format instructions",
                FailureAttribution.SKILL_EXECUTION_FAILURE, "Check skill input validation and error handling",
                FailureAttribution.SKILL_MISSING, "Review if required tools are registered in the skill registry",
                FailureAttribution.CONTEXT_OVERFLOW, "Consider reducing max_loops or breaking tasks into smaller steps",
                FailureAttribution.PERFORMANCE, "Optimize tool execution speed or increase performance thresholds",
                FailureAttribution.MEMORY_INTERFERENCE, "Inspect recalled memories for stale or conflicting context",
                FailureAttribution.MEMORY_MISSING, "Add or refresh memories needed by these scenarios"
        );

        histogram.entrySet().stream()
                .filter(e -> e.getKey() != FailureAttribution.NONE && e.getKey() != FailureAttribution.VETO_EXCEPTION)
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(3)
                .forEach(entry -> {
                    Map<String, Object> suggestion = new LinkedHashMap<>();
                    suggestion.put("category", entry.getKey().name());
                    suggestion.put("count", entry.getValue());
                    suggestion.put("suggestion", suggestionMap.getOrDefault(entry.getKey(), "Review failing scenarios"));
                    suggestions.add(suggestion);
                });

        return suggestions;
    }

    /**
     * EVAL-V2 M1: progress event stride. Up to 50 cases pushes per case
     * (stride=1); above that, pushes on every {@code totalCount/20} cases
     * (~5% increments) so the WS channel doesn't get hammered on big eval
     * runs. {@code Math.max(1, …)} guards against div-rounding to 0 (only
     * possible if {@code totalCount < 20}, which the >50 gate rules out,
     * but the guard is cheap insurance).
     *
     * <p>Package-private + static so {@code EvalOrchestratorThrottleTest}
     * can lock the math without spinning up Spring.
     */
    static int computeProgressStride(int totalCount) {
        return totalCount > 50 ? Math.max(1, totalCount / 20) : 1;
    }

    /**
     * EVAL-V2 M1: returns whether index {@code i} (0-based) of an eval batch
     * should emit a progress WS event. Always emits on the last case so the
     * "100% complete" frame doesn't get swallowed by the stride.
     */
    static boolean shouldPushProgressEvent(int i, int totalCount, int stride) {
        if (totalCount <= 0) return false;
        if (stride <= 0) return false;
        return (i % stride == 0) || (i == totalCount - 1);
    }

    private BigDecimal aggregateTraceTreeCost(String rootTraceId) {
        if (rootTraceId == null || rootTraceId.isBlank()) {
            return null;
        }
        List<LlmTraceEntity> traces = llmTraceRepository.findByRootTraceIdOrderByStartedAtAsc(rootTraceId);
        if (traces.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        boolean observed = false;
        for (LlmTraceEntity trace : traces) {
            if (trace.getTotalCostUsd() == null) {
                continue;
            }
            total = total.add(trace.getTotalCostUsd());
            observed = true;
        }
        return observed ? total : null;
    }

    private static BigDecimal toScaledDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private int computeConsecutiveDeclineCount(String agentDefinitionId, double currentPassRate) {
        var previousRun = evalRunRepository.findTopByAgentDefinitionIdAndStatusOrderByStartedAtDesc(
                agentDefinitionId, "COMPLETED");
        if (previousRun.isEmpty()) return 0;

        EvalTaskEntity prev = previousRun.get();
        if (currentPassRate < prev.getOverallPassRate() - 5.0) {
            int count = prev.getConsecutiveDeclineCount() + 1;
            if (count >= 3) {
                log.warn("GOODHART ALERT: {} consecutive pass rate declines for agent {}",
                        count, agentDefinitionId);
            }
            return count;
        }
        return 0;
    }
}
