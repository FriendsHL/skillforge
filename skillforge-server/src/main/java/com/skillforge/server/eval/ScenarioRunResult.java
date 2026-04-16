package com.skillforge.server.eval;

public class ScenarioRunResult {

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
    private boolean engineThrewException;
    private String errorMessage;

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

    public boolean isEngineThrewException() { return engineThrewException; }
    public void setEngineThrewException(boolean engineThrewException) { this.engineThrewException = engineThrewException; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
