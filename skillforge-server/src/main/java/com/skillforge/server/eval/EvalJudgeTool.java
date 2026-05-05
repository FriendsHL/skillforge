package com.skillforge.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Value;
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
    private final ObjectMapper objectMapper;
    private final String defaultProviderName;
    /**
     * EVAL-V2 M2: composite weight for multi-turn judge — weight given to the
     * "overall outcome" score; the remainder ({@code 1 - overallWeight}) goes
     * to the average per-turn score. Default 0.7 per tech-design §1.4.
     * Externalized via {@code skillforge.eval.judge.multi-turn-weight} so we
     * can tune without code changes.
     */
    private final double multiTurnOverallWeight;

    public EvalJudgeTool(LlmProviderFactory llmProviderFactory,
                          AttributionEngine attributionEngine,
                          ObjectMapper objectMapper,
                          LlmProperties llmProperties,
                          @Value("${skillforge.eval.judge.multi-turn-weight:0.7}") double multiTurnOverallWeight) {
        this.llmProviderFactory = llmProviderFactory;
        this.attributionEngine = attributionEngine;
        this.objectMapper = objectMapper;
        this.defaultProviderName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
        // Clamp to [0,1] — a misconfigured value should not silently break math.
        this.multiTurnOverallWeight = Math.max(0.0, Math.min(1.0, multiTurnOverallWeight));
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

    /**
     * EVAL-V2 M2: judge a multi-turn conversation. Builds a transcript prompt
     * (per-turn process scoring + overall outcome scoring) and asks the LLM to
     * return JSON. Failures (no provider / malformed JSON / score out of range)
     * degrade to a 0-score, NONE attribution — caller's pass threshold (40)
     * still applies. The runResult is updated with status PASS|FAIL and
     * {@code oracleScore} so the existing histogram/aggregation paths work
     * unchanged.
     *
     * @param scenario   spec
     * @param runResult  aggregated multi-turn run signals
     * @param transcript user/assistant transcript with placeholders replaced
     */
    public EvalJudgeMultiTurnOutput judgeMultiTurnConversation(EvalScenario scenario,
                                                                ScenarioRunResult runResult,
                                                                MultiTurnTranscript transcript) {
        EvalJudgeMultiTurnOutput out = new EvalJudgeMultiTurnOutput();

        // ERROR/TIMEOUT: short-circuit to 0 score + signal-driven attribution
        // matching single-turn judge() behaviour (callers depend on this).
        if ("ERROR".equals(runResult.getStatus()) || "TIMEOUT".equals(runResult.getStatus())) {
            out.setCompositeScore(0.0);
            out.setOverallScore(0.0);
            out.setPass(false);
            EvalSignals signals = EvalSignals.builder()
                    .engineThrewException(runResult.isEngineThrewException())
                    .taskCompletionOraclePass(false)
                    .hitLoopLimit("TIMEOUT".equals(runResult.getStatus()))
                    .skillExecutionFailed(runResult.isSkillExecutionFailed())
                    .memorySkillCalled(runResult.isMemorySkillCalled())
                    .memoryResultEmpty(runResult.isMemoryResultEmpty())
                    .build();
            out.setAttribution(attributionEngine.compute(signals));
            runResult.setOracleScore(0.0);
            runResult.setStatus(runResult.isEngineThrewException() ? "VETO" : runResult.getStatus());
            return out;
        }

        String expected = scenario.getOracle() != null ? scenario.getOracle().getExpected() : null;
        String transcriptText = transcript != null ? transcript.render() : "(no transcript)";

        try {
            LlmProvider provider = llmProviderFactory.getProvider(defaultProviderName);
            if (provider == null) {
                log.warn("No LLM provider available for multi-turn judge, returning 0");
                out.setCompositeScore(0.0);
                out.setOverallScore(0.0);
                out.setAttribution(FailureAttribution.NONE);
                out.setRationale("No LLM provider available");
                runResult.setOracleScore(0.0);
                runResult.setStatus("FAIL");
                return out;
            }

            String systemPrompt = """
                    You are evaluating a multi-turn conversation between a user and an AI agent.
                    Score per-turn process (Was each assistant response reasonable, informative, non-repetitive?) \
                    and overall outcome (Did the conversation as a whole resolve the user's request and meet the expected behavior?).
                    Respond with ONLY a JSON object — no markdown fences, no commentary — matching this shape:
                    {
                      "perTurnScores": [{"turnIndex": <int, 0-based among assistant turns>, "score": <0-100>, "comment": "<short>"}],
                      "overallScore": <0-100>,
                      "attribution": "<one of: PROMPT_QUALITY|CONTEXT_OVERFLOW|SKILL_MISSING|SKILL_EXECUTION_FAILURE|PERFORMANCE|MEMORY_INTERFERENCE|MEMORY_MISSING|NONE>",
                      "rationale": "<concise reasoning, may reference specific turn indices>"
                    }
                    """;

            String userMsg = String.format("""
                    Conversation:
                    ---
                    %s
                    ---

                    Expected behavior:
                    %s

                    Scenario name: %s
                    Scenario task summary: %s
                    """,
                    transcriptText,
                    expected != null ? expected : "(none provided)",
                    scenario.getName() != null ? scenario.getName() : scenario.getId(),
                    scenario.getTask() != null ? scenario.getTask() : "(none)");

            LlmRequest request = new LlmRequest();
            request.setSystemPrompt(systemPrompt);
            List<Message> messages = new ArrayList<>();
            messages.add(Message.user(userMsg));
            request.setMessages(messages);
            request.setMaxTokens(1024);
            request.setTemperature(0.0);

            LlmResponse response = provider.chat(request);
            String content = response.getContent();
            if (content == null || content.isBlank()) {
                log.warn("Multi-turn judge returned empty content for scenario {}", scenario.getId());
                out.setCompositeScore(0.0);
                out.setOverallScore(0.0);
                out.setAttribution(FailureAttribution.NONE);
                out.setRationale("Empty judge response");
                runResult.setOracleScore(0.0);
                runResult.setStatus("FAIL");
                return out;
            }

            // Strip optional ```json fences before parsing.
            String cleaned = content.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceFirst("```(?:json)?\\s*", "");
                cleaned = cleaned.replaceFirst("\\s*```$", "");
            }

            JsonNode root;
            try {
                root = objectMapper.readTree(cleaned);
            } catch (Exception parseEx) {
                log.warn("Multi-turn judge returned non-JSON for scenario {}: {}",
                        scenario.getId(), parseEx.getMessage());
                out.setCompositeScore(0.0);
                out.setOverallScore(0.0);
                out.setAttribution(FailureAttribution.NONE);
                out.setRationale("Judge output was not valid JSON: " + truncate(content, 200));
                runResult.setOracleScore(0.0);
                runResult.setStatus("FAIL");
                return out;
            }

            double overallScore = clamp(root.path("overallScore").asDouble(0.0));
            JsonNode perTurnNode = root.path("perTurnScores");
            double perTurnSum = 0.0;
            int perTurnCount = 0;
            if (perTurnNode.isArray()) {
                for (JsonNode turnNode : perTurnNode) {
                    double s = clamp(turnNode.path("score").asDouble(0.0));
                    int idx = turnNode.path("turnIndex").asInt(perTurnCount);
                    String comment = turnNode.path("comment").asText("");
                    out.getPerTurnScores().add(
                            new EvalJudgeMultiTurnOutput.PerTurnScore(idx, s, comment));
                    perTurnSum += s;
                    perTurnCount++;
                }
            }
            double perTurnAvg = perTurnCount > 0 ? perTurnSum / perTurnCount : overallScore;

            double composite = multiTurnOverallWeight * overallScore
                    + (1.0 - multiTurnOverallWeight) * perTurnAvg;
            composite = clamp(composite);

            out.setOverallScore(overallScore);
            out.setCompositeScore(composite);
            out.setRationale(root.path("rationale").asText(""));

            FailureAttribution attribution = parseAttribution(root.path("attribution").asText("NONE"));
            // If the judge omits attribution but the run signals point to a known
            // failure mode, fall back to attribution engine for consistency with
            // single-turn behaviour. attribution is non-NONE → trust judge.
            if (attribution == FailureAttribution.NONE && composite < 60.0) {
                EvalSignals signals = EvalSignals.builder()
                        .engineThrewException(runResult.isEngineThrewException())
                        .taskCompletionOraclePass(composite >= 60.0)
                        .skillExecutionFailed(runResult.isSkillExecutionFailed())
                        .skillOutputWasMalformed(runResult.isSkillOutputWasMalformed())
                        .hitLoopLimit(runResult.getLoopCount() >= scenario.getMaxLoops())
                        .nearPassOracle(composite >= 40 && composite < 60)
                        .outputFormatCorrect(composite > 0)
                        .slowExecution(runResult.getExecutionTimeMs() > scenario.getPerformanceThresholdMs())
                        .memorySkillCalled(runResult.isMemorySkillCalled())
                        .memoryResultEmpty(runResult.isMemoryResultEmpty())
                        .build();
                attribution = attributionEngine.compute(signals);
            }
            out.setAttribution(attribution);

            boolean pass = composite >= 40.0;
            out.setPass(pass);
            runResult.setOracleScore(composite);
            if (pass) {
                runResult.setStatus("PASS");
            } else if (runResult.isEngineThrewException()) {
                runResult.setStatus("VETO");
            } else {
                runResult.setStatus("FAIL");
            }
            return out;
        } catch (Exception e) {
            log.warn("Multi-turn judge failed for scenario {}: {}", scenario.getId(), e.getMessage());
            out.setCompositeScore(0.0);
            out.setOverallScore(0.0);
            out.setAttribution(FailureAttribution.NONE);
            out.setRationale("Judge call failed: " + e.getMessage());
            runResult.setOracleScore(0.0);
            runResult.setStatus("FAIL");
            return out;
        }
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(100.0, v));
    }

    private static FailureAttribution parseAttribution(String raw) {
        if (raw == null || raw.isBlank()) return FailureAttribution.NONE;
        String norm = raw.trim().toUpperCase(java.util.Locale.ROOT);
        // Helpful aliases — multi-turn judge prompt may receive freer-form
        // attribution names from older prompt iterations or external prompts.
        if ("TOOL_FAILURE".equals(norm) || "TOOL_ERROR".equals(norm)) {
            return FailureAttribution.SKILL_EXECUTION_FAILURE;
        }
        try {
            return FailureAttribution.valueOf(norm);
        } catch (IllegalArgumentException e) {
            return FailureAttribution.NONE;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
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
