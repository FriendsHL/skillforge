package com.skillforge.server.eval;

import com.skillforge.core.engine.ToolCallRecord;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ScenarioRunResult {

    private static final Set<String> MEMORY_TOOL_NAMES = Set.of("Memory", "memory_search", "memory_detail");

    private String scenarioId;
    private String status;    // PASS | FAIL | VETO | TIMEOUT | ERROR
    private double oracleScore;
    private long executionTimeMs;
    private int loopCount;
    private long inputTokens;
    private long outputTokens;
    private String agentFinalOutput;
    private boolean skillExecutionFailed;
    private boolean skillOutputWasMalformed;
    private boolean memorySkillCalled;
    private boolean memoryResultEmpty;
    private boolean engineThrewException;
    private String errorMessage;

    /**
     * EVAL-V2 M3a (b2): real {@code t_session.id} (origin='eval') the scenario
     * ran on. Replaces the legacy synthetic "eval_<UUID>" id so OBS trace
     * drill-down can follow {@code t_eval_task_item.session_id → t_session.id}.
     */
    private String sessionId;

    /**
     * EVAL-V2 M3a (b2): root trace id captured at scenario completion. Lets
     * the OBS spans drawer pre-filter to "this case's spans" without
     * re-deriving from session_id.
     */
    private String rootTraceId;

    /**
     * EVAL-V2 M3a (b2): tool call count derived from the engine's loop result
     * tool_calls list size (mirrors what {@link #applyToolCallSignals(java.util.List)}
     * iterates over).
     */
    private int toolCallCount;

    public ScenarioRunResult() {
    }

    public static ScenarioRunResult pass(String scenarioId) {
        ScenarioRunResult r = new ScenarioRunResult();
        r.scenarioId = scenarioId;
        r.status = "PASS";
        return r;
    }

    public static ScenarioRunResult fail(String scenarioId) {
        ScenarioRunResult r = new ScenarioRunResult();
        r.scenarioId = scenarioId;
        r.status = "FAIL";
        return r;
    }

    public static ScenarioRunResult timeout(String scenarioId, String errorMessage) {
        ScenarioRunResult r = new ScenarioRunResult();
        r.scenarioId = scenarioId;
        r.status = "TIMEOUT";
        r.errorMessage = errorMessage;
        return r;
    }

    public static ScenarioRunResult error(String scenarioId, String errorMessage) {
        ScenarioRunResult r = new ScenarioRunResult();
        r.scenarioId = scenarioId;
        r.status = "ERROR";
        r.errorMessage = errorMessage;
        r.engineThrewException = true;
        return r;
    }

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getOracleScore() { return oracleScore; }
    public void setOracleScore(double oracleScore) { this.oracleScore = oracleScore; }

    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }

    public int getLoopCount() { return loopCount; }
    public void setLoopCount(int loopCount) { this.loopCount = loopCount; }

    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }

    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }

    public String getAgentFinalOutput() { return agentFinalOutput; }
    public void setAgentFinalOutput(String agentFinalOutput) { this.agentFinalOutput = agentFinalOutput; }

    public boolean isSkillExecutionFailed() { return skillExecutionFailed; }
    public void setSkillExecutionFailed(boolean skillExecutionFailed) { this.skillExecutionFailed = skillExecutionFailed; }

    public boolean isSkillOutputWasMalformed() { return skillOutputWasMalformed; }
    public void setSkillOutputWasMalformed(boolean skillOutputWasMalformed) { this.skillOutputWasMalformed = skillOutputWasMalformed; }

    public boolean isMemorySkillCalled() { return memorySkillCalled; }
    public void setMemorySkillCalled(boolean memorySkillCalled) { this.memorySkillCalled = memorySkillCalled; }

    public boolean isMemoryResultEmpty() { return memoryResultEmpty; }
    public void setMemoryResultEmpty(boolean memoryResultEmpty) { this.memoryResultEmpty = memoryResultEmpty; }

    public boolean isEngineThrewException() { return engineThrewException; }
    public void setEngineThrewException(boolean engineThrewException) { this.engineThrewException = engineThrewException; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getRootTraceId() { return rootTraceId; }
    public void setRootTraceId(String rootTraceId) { this.rootTraceId = rootTraceId; }

    public int getToolCallCount() { return toolCallCount; }
    public void setToolCallCount(int toolCallCount) { this.toolCallCount = toolCallCount; }

    public void applyToolCallSignals(List<ToolCallRecord> toolCalls) {
        if (toolCalls == null) return;
        toolCallCount = toolCalls.size();
        for (ToolCallRecord record : toolCalls) {
            if (record == null) continue;
            if (!record.isSuccess()) {
                skillExecutionFailed = true;
            }
            if (isMemoryTool(record.getSkillName())) {
                memorySkillCalled = true;
                if (isEmptyMemoryResult(record.getOutput())) {
                    memoryResultEmpty = true;
                }
            }
        }
    }

    private static boolean isMemoryTool(String skillName) {
        return skillName != null && MEMORY_TOOL_NAMES.contains(skillName);
    }

    private static boolean isEmptyMemoryResult(String output) {
        if (output == null || output.isBlank()) return true;
        String normalized = output.toLowerCase(Locale.ROOT);
        return normalized.contains("no memories found") || normalized.contains("memory not found");
    }
}
