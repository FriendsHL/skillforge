package com.skillforge.server.eval;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.eval.attribution.AttributionEngine;
import com.skillforge.server.eval.attribution.EvalSignals;
import com.skillforge.server.eval.attribution.FailureAttribution;
import com.skillforge.server.eval.scenario.EvalScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EvalJudgeTool {

    private static final Logger log = LoggerFactory.getLogger(EvalJudgeTool.class);

    private final LlmProviderFactory llmProviderFactory;
    private final AttributionEngine attributionEngine;
    private final String defaultProviderName;

    public EvalJudgeTool(LlmProviderFactory llmProviderFactory,
                          AttributionEngine attributionEngine,
                          LlmProperties llmProperties) {
        this.llmProviderFactory = llmProviderFactory;
        this.attributionEngine = attributionEngine;
        this.defaultProviderName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
    }

    public EvalJudgeOutput judge(EvalScenario scenario, ScenarioRunResult runResult) {
        EvalJudgeOutput output = new EvalJudgeOutput();

        // Handle ERROR/TIMEOUT results
        if ("ERROR".equals(runResult.getStatus()) || "TIMEOUT".equals(runResult.getStatus())) {
            output.setOutcomeScore(0.0);
            output.setEfficiencyScore(0.0);
            output.setCompositeScore(0.0);
            output.setPass(false);

            EvalSignals signals = EvalSignals.builder()
                    .engineThrewException(runResult.isEngineThrewException())
                    .taskCompletionOraclePass(false)
                    .hitLoopLimit("TIMEOUT".equals(runResult.getStatus()))
                    .skillExecutionFailed(runResult.isSkillExecutionFailed())
                    .memorySkillCalled(runResult.isMemorySkillCalled())
                    .memoryResultEmpty(runResult.isMemoryResultEmpty())
                    .build();
            output.setAttribution(attributionEngine.compute(signals));
            return output;
        }

        // 1. Compute outcome score via oracle
        double outcomeScore = computeOracleScore(scenario, runResult);
        output.setOutcomeScore(outcomeScore);

        // 2. Compute efficiency score
        double efficiencyScore = computeEfficiencyScore(scenario, runResult);
        output.setEfficiencyScore(efficiencyScore);

        // 3. Composite score
        double compositeScore = 0.7 * outcomeScore + 0.3 * efficiencyScore;
        output.setCompositeScore(compositeScore);

        // 4. Meta-Judge for fuzzy zone [30, 55]
        if (compositeScore >= 30.0 && compositeScore <= 55.0) {
            try {
                double metaScore = callMetaJudge(scenario, runResult, compositeScore);
                output.setMetaJudgeRationale("Meta-judge adjusted score from " + compositeScore + " to " + metaScore);
                compositeScore = metaScore;
                output.setCompositeScore(compositeScore);
            } catch (Exception e) {
                log.warn("Meta-judge failed, using original score: {}", e.getMessage());
            }
        }

        // 5. Pass/fail
        output.setPass(compositeScore >= 40.0);

        // 6. Build signals and compute attribution
        // nearPassOracle: [40, 60) exclusive upper — 60 itself is the oracle-pass threshold (s1)
        boolean nearPass = outcomeScore >= 40 && outcomeScore < 60;
        EvalSignals signals = EvalSignals.builder()
                .engineThrewException(runResult.isEngineThrewException())
                // taskCompletionOraclePass uses oracle threshold (60), NOT composite pass (40)
                .taskCompletionOraclePass(outcomeScore >= 60.0)
                .skillExecutionFailed(runResult.isSkillExecutionFailed())
                .skillOutputWasMalformed(runResult.isSkillOutputWasMalformed())
                .hitLoopLimit(runResult.getLoopCount() >= scenario.getMaxLoops())
                .nearPassOracle(nearPass)
                .outputFormatCorrect(outcomeScore > 0)
                .slowExecution(runResult.getExecutionTimeMs() > scenario.getPerformanceThresholdMs())
                .memorySkillCalled(runResult.isMemorySkillCalled())
                .memoryResultEmpty(runResult.isMemoryResultEmpty())
                .build();
        output.setAttribution(attributionEngine.compute(signals));

        // Update run result status based on judge
        if (output.isPass()) {
            runResult.setStatus("PASS");
        } else if (runResult.isEngineThrewException()) {
            runResult.setStatus("VETO");
        } else {
            runResult.setStatus("FAIL");
        }
        runResult.setOracleScore(compositeScore);

        return output;
    }

    private double computeOracleScore(EvalScenario scenario, ScenarioRunResult runResult) {
        String agentOutput = runResult.getAgentFinalOutput();
        if (agentOutput == null) agentOutput = "";
        String trimmedOutput = agentOutput.trim();

        EvalScenario.ScenarioOracle oracle = scenario.getOracle();
        if (oracle == null) return 0.0;

        return switch (oracle.getType()) {
            case "exact_match" -> {
                String expected = oracle.getExpected() != null ? oracle.getExpected().trim() : "";
                yield trimmedOutput.equals(expected) ? 100.0 : 0.0;
            }
            case "contains" -> {
                List<String> expectedList = oracle.getExpectedList();
                if (expectedList != null && !expectedList.isEmpty()) {
                    long matched = expectedList.stream()
                            .filter(exp -> trimmedOutput.contains(exp))
                            .count();
                    yield (double) matched / expectedList.size() * 100.0;
                } else if (oracle.getExpected() != null) {
                    yield trimmedOutput.contains(oracle.getExpected()) ? 100.0 : 0.0;
                }
                yield 0.0;
            }
            case "regex" -> {
                try {
                    Pattern pattern = Pattern.compile(oracle.getExpected(), Pattern.DOTALL);
                    Matcher matcher = pattern.matcher(trimmedOutput);
                    yield matcher.find() ? 100.0 : 0.0;
                } catch (Exception e) {
                    log.warn("Invalid regex in oracle: {}", oracle.getExpected());
                    yield 0.0;
                }
            }
            case "llm_judge" -> callLlmJudge(oracle.getExpected(), trimmedOutput);
            default -> {
                log.warn("Unknown oracle type: {}", oracle.getType());
                yield 0.0;
            }
        };
    }

    private double computeEfficiencyScore(EvalScenario scenario, ScenarioRunResult runResult) {
        int loopCount = runResult.getLoopCount();
        int maxLoops = scenario.getMaxLoops();
        double halfMax = maxLoops * 0.5;

        double loopScore;
        if (loopCount <= halfMax) {
            loopScore = 100.0;
        } else if (loopCount >= maxLoops) {
            loopScore = 0.0;
        } else {
            // Linear interpolation between halfMax (100) and maxLoops (0)
            loopScore = 100.0 * (maxLoops - loopCount) / (maxLoops - halfMax);
        }

        // Penalize for slow execution
        if (runResult.getExecutionTimeMs() > scenario.getPerformanceThresholdMs()) {
            double timeRatio = (double) runResult.getExecutionTimeMs() / scenario.getPerformanceThresholdMs();
            loopScore *= Math.max(0.0, 1.0 - (timeRatio - 1.0) * 0.5);
        }

        return Math.max(0.0, Math.min(100.0, loopScore));
    }

    private double callLlmJudge(String criteria, String agentOutput) {
        try {
            LlmProvider provider = llmProviderFactory.getProvider(defaultProviderName);
            if (provider == null) {
                log.warn("No LLM provider available for judge, returning 0");
                return 0.0;
            }

            LlmRequest request = new LlmRequest();
            request.setSystemPrompt("You are an eval judge. Score the agent's output from 0 to 100 based on the criteria. "
                    + "Respond with ONLY an integer between 0 and 100, nothing else.");

            List<Message> messages = new ArrayList<>();
            messages.add(Message.user("Criteria: " + criteria + "\n\nAgent output: " + agentOutput
                    + "\n\nScore (0-100):"));
            request.setMessages(messages);
            request.setMaxTokens(16);
            request.setTemperature(0.0);

            LlmResponse response = provider.chat(request);
            String text = response.getContent();
            if (text != null) {
                Matcher m = Pattern.compile("\\d+").matcher(text.trim());
                if (m.find()) {
                    int score = Integer.parseInt(m.group());
                    return Math.max(0, Math.min(100, score));
                }
            }
            return 0.0;
        } catch (Exception e) {
            log.warn("LLM judge call failed: {}", e.getMessage());
            return 0.0;
        }
    }

    private double callMetaJudge(EvalScenario scenario, ScenarioRunResult runResult, double currentScore) {
        LlmProvider provider = llmProviderFactory.getProvider(defaultProviderName);
        if (provider == null) return currentScore;

        LlmRequest request = new LlmRequest();
        request.setSystemPrompt("You are a meta-judge reviewing an eval result in the fuzzy zone. "
                + "The composite score is " + String.format("%.1f", currentScore) + " (pass threshold = 40). "
                + "Decide whether this should pass or fail. Respond with ONLY an integer 0-100.");

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("Scenario: " + scenario.getName() + "\nTask: " + scenario.getTask()
                + "\nAgent output: " + (runResult.getAgentFinalOutput() != null
                ? runResult.getAgentFinalOutput().substring(0, Math.min(500, runResult.getAgentFinalOutput().length()))
                : "(empty)")
                + "\nLoops used: " + runResult.getLoopCount() + "/" + scenario.getMaxLoops()
                + "\nScore (0-100):"));
        request.setMessages(messages);
        request.setMaxTokens(16);
        request.setTemperature(0.0);

        LlmResponse response = provider.chat(request);
        String text = response.getContent();
        if (text != null) {
            Matcher m = Pattern.compile("\\d+").matcher(text.trim());
            if (m.find()) {
                int score = Integer.parseInt(m.group());
                return Math.max(0, Math.min(100, score));
            }
        }
        return currentScore;
    }
}
