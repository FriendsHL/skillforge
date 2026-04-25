package com.skillforge.server.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.CollabRunEntity;
import com.skillforge.server.entity.EvalRunEntity;
import com.skillforge.server.eval.attribution.FailureAttribution;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.eval.scenario.ScenarioLoader;
import com.skillforge.server.repository.CollabRunRepository;
import com.skillforge.server.repository.EvalRunRepository;
import com.skillforge.server.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private final EvalRunRepository evalRunRepository;
    private final CollabRunRepository collabRunRepository;
    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final ChatEventBroadcaster broadcaster;

    public EvalOrchestrator(ScenarioLoader scenarioLoader,
                            ScenarioRunnerTool scenarioRunner,
                            EvalJudgeTool evalJudge,
                            EvalRunRepository evalRunRepository,
                            CollabRunRepository collabRunRepository,
                            AgentService agentService,
                            ObjectMapper objectMapper,
                            ChatEventBroadcaster broadcaster) {
        this.scenarioLoader = scenarioLoader;
        this.scenarioRunner = scenarioRunner;
        this.evalJudge = evalJudge;
        this.evalRunRepository = evalRunRepository;
        this.collabRunRepository = collabRunRepository;
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
    }

    public void runEval(String agentDefinitionId, Long userId, String evalRunId) {
        log.info("Starting eval run: evalRunId={}, agentId={}", evalRunId, agentDefinitionId);

        // 1. Goodhart rate limit: no active/completed eval in last 30 minutes for this agent.
        // Only count RUNNING or COMPLETED runs — FAILED ghost runs must not extend the window indefinitely.
        List<EvalRunEntity> recentRuns = evalRunRepository.findByAgentDefinitionIdAndStatusInAndStartedAtAfter(
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
            EvalRunEntity run = createEvalRun(evalRunId, agentDefinitionId, userId);
            run.setStatus("FAILED");
            run.setErrorMessage("No eval scenarios found on classpath");
            run.setCompletedAt(Instant.now());
            evalRunRepository.save(run);
            return;
        }

        // 3. Create EvalRunEntity
        EvalRunEntity evalRun = createEvalRun(evalRunId, agentDefinitionId, userId);
        evalRun.setTotalScenarios(scenarios.size());
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
            AgentDefinition agentDef = agentService.toAgentDefinition(
                    agentService.getAgent(Long.parseLong(agentDefinitionId)));

            // 5. Run each scenario sequentially (v1.0)
            List<Map<String, Object>> scenarioResults = new ArrayList<>();
            int passed = 0, failed = 0, timeouts = 0, vetos = 0;
            double totalOracleScore = 0;
            Map<FailureAttribution, Integer> attrHistogram = new HashMap<>();

            for (EvalScenario scenario : scenarios) {
                log.info("Running scenario: {} ({})", scenario.getId(), scenario.getName());

                ScenarioRunResult runResult = scenarioRunner.runScenario(
                        evalRunId, scenario, agentDef, userId);
                EvalJudgeOutput judgeOutput = evalJudge.judge(scenario, runResult);

                // Collect results
                Map<String, Object> scenarioResult = new LinkedHashMap<>();
                scenarioResult.put("scenarioId", scenario.getId());
                scenarioResult.put("name", scenario.getName());
                scenarioResult.put("category", scenario.getCategory());
                scenarioResult.put("split", scenario.getSplit());
                scenarioResult.put("status", runResult.getStatus());
                scenarioResult.put("outcomeScore", judgeOutput.getOutcomeScore());
                scenarioResult.put("efficiencyScore", judgeOutput.getEfficiencyScore());
                scenarioResult.put("compositeScore", judgeOutput.getCompositeScore());
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

                totalOracleScore += judgeOutput.getCompositeScore();

                switch (runResult.getStatus()) {
                    case "PASS" -> passed++;
                    case "FAIL" -> failed++;
                    case "TIMEOUT" -> timeouts++;
                    case "VETO" -> vetos++;
                    default -> failed++;
                }

                attrHistogram.merge(judgeOutput.getAttribution(), 1, Integer::sum);

                // Broadcast eval_scenario_finished
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

            // 9. Persist EvalRunEntity
            evalRun.setStatus("COMPLETED");
            evalRun.setOverallPassRate(passRate);
            evalRun.setAvgOracleScore(avgOracleScore);
            evalRun.setPassedScenarios(passed);
            evalRun.setFailedScenarios(failed);
            evalRun.setTimeoutScenarios(timeouts);
            evalRun.setVetoScenarios(vetos);
            evalRun.setPrimaryAttribution(primaryAttr);
            evalRun.setAttrSkillMissing(attrHistogram.getOrDefault(FailureAttribution.SKILL_MISSING, 0));
            evalRun.setAttrSkillExecFailure(attrHistogram.getOrDefault(FailureAttribution.SKILL_EXECUTION_FAILURE, 0));
            evalRun.setAttrPromptQuality(attrHistogram.getOrDefault(FailureAttribution.PROMPT_QUALITY, 0));
            evalRun.setAttrContextOverflow(attrHistogram.getOrDefault(FailureAttribution.CONTEXT_OVERFLOW, 0));
            evalRun.setAttrPerformance(attrHistogram.getOrDefault(FailureAttribution.PERFORMANCE, 0));
            evalRun.setConsecutiveDeclineCount(consecutiveDecline);
            evalRun.setCompletedAt(Instant.now());

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

            // 11. Broadcast eval_run_completed
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

    private EvalRunEntity createEvalRun(String evalRunId, String agentDefinitionId, Long userId) {
        EvalRunEntity run = new EvalRunEntity();
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
                FailureAttribution.PERFORMANCE, "Optimize tool execution speed or increase performance thresholds"
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

    private int computeConsecutiveDeclineCount(String agentDefinitionId, double currentPassRate) {
        var previousRun = evalRunRepository.findTopByAgentDefinitionIdAndStatusOrderByStartedAtDesc(
                agentDefinitionId, "COMPLETED");
        if (previousRun.isEmpty()) return 0;

        EvalRunEntity prev = previousRun.get();
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
