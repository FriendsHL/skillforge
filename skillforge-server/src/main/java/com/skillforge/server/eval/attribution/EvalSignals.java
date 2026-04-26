package com.skillforge.server.eval.attribution;

public class EvalSignals {

    private boolean engineThrewException;
    private boolean taskCompletionOraclePass;
    private boolean skillExecutionFailed;
    private boolean skillOutputWasMalformed;
    private boolean hitLoopLimit;
    private boolean nearPassOracle;
    private boolean outputFormatCorrect;
    private boolean slowExecution;
    private boolean memorySkillCalled;
    private boolean memoryResultEmpty;

    public EvalSignals() {
    }

    public EvalSignals(boolean engineThrewException, boolean taskCompletionOraclePass,
                       boolean skillExecutionFailed, boolean skillOutputWasMalformed,
                       boolean hitLoopLimit, boolean nearPassOracle,
                       boolean outputFormatCorrect, boolean slowExecution) {
        this(engineThrewException, taskCompletionOraclePass, skillExecutionFailed,
                skillOutputWasMalformed, hitLoopLimit, nearPassOracle,
                outputFormatCorrect, slowExecution, false, false);
    }

    public EvalSignals(boolean engineThrewException, boolean taskCompletionOraclePass,
                       boolean skillExecutionFailed, boolean skillOutputWasMalformed,
                       boolean hitLoopLimit, boolean nearPassOracle,
                       boolean outputFormatCorrect, boolean slowExecution,
                       boolean memorySkillCalled, boolean memoryResultEmpty) {
        this.engineThrewException = engineThrewException;
        this.taskCompletionOraclePass = taskCompletionOraclePass;
        this.skillExecutionFailed = skillExecutionFailed;
        this.skillOutputWasMalformed = skillOutputWasMalformed;
        this.hitLoopLimit = hitLoopLimit;
        this.nearPassOracle = nearPassOracle;
        this.outputFormatCorrect = outputFormatCorrect;
        this.slowExecution = slowExecution;
        this.memorySkillCalled = memorySkillCalled;
        this.memoryResultEmpty = memoryResultEmpty;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEngineThrewException() { return engineThrewException; }
    public void setEngineThrewException(boolean engineThrewException) { this.engineThrewException = engineThrewException; }

    public boolean isTaskCompletionOraclePass() { return taskCompletionOraclePass; }
    public void setTaskCompletionOraclePass(boolean taskCompletionOraclePass) { this.taskCompletionOraclePass = taskCompletionOraclePass; }

    public boolean isSkillExecutionFailed() { return skillExecutionFailed; }
    public void setSkillExecutionFailed(boolean skillExecutionFailed) { this.skillExecutionFailed = skillExecutionFailed; }

    public boolean isSkillOutputWasMalformed() { return skillOutputWasMalformed; }
    public void setSkillOutputWasMalformed(boolean skillOutputWasMalformed) { this.skillOutputWasMalformed = skillOutputWasMalformed; }

    public boolean isHitLoopLimit() { return hitLoopLimit; }
    public void setHitLoopLimit(boolean hitLoopLimit) { this.hitLoopLimit = hitLoopLimit; }

    public boolean isNearPassOracle() { return nearPassOracle; }
    public void setNearPassOracle(boolean nearPassOracle) { this.nearPassOracle = nearPassOracle; }

    public boolean isOutputFormatCorrect() { return outputFormatCorrect; }
    public void setOutputFormatCorrect(boolean outputFormatCorrect) { this.outputFormatCorrect = outputFormatCorrect; }

    public boolean isSlowExecution() { return slowExecution; }
    public void setSlowExecution(boolean slowExecution) { this.slowExecution = slowExecution; }

    public boolean isMemorySkillCalled() { return memorySkillCalled; }
    public void setMemorySkillCalled(boolean memorySkillCalled) { this.memorySkillCalled = memorySkillCalled; }

    public boolean isMemoryResultEmpty() { return memoryResultEmpty; }
    public void setMemoryResultEmpty(boolean memoryResultEmpty) { this.memoryResultEmpty = memoryResultEmpty; }

    public static class Builder {
        private boolean engineThrewException;
        private boolean taskCompletionOraclePass;
        private boolean skillExecutionFailed;
        private boolean skillOutputWasMalformed;
        private boolean hitLoopLimit;
        private boolean nearPassOracle;
        private boolean outputFormatCorrect = true;
        private boolean slowExecution;
        private boolean memorySkillCalled;
        private boolean memoryResultEmpty;

        public Builder engineThrewException(boolean v) { this.engineThrewException = v; return this; }
        public Builder taskCompletionOraclePass(boolean v) { this.taskCompletionOraclePass = v; return this; }
        public Builder skillExecutionFailed(boolean v) { this.skillExecutionFailed = v; return this; }
        public Builder skillOutputWasMalformed(boolean v) { this.skillOutputWasMalformed = v; return this; }
        public Builder hitLoopLimit(boolean v) { this.hitLoopLimit = v; return this; }
        public Builder nearPassOracle(boolean v) { this.nearPassOracle = v; return this; }
        public Builder outputFormatCorrect(boolean v) { this.outputFormatCorrect = v; return this; }
        public Builder slowExecution(boolean v) { this.slowExecution = v; return this; }
        public Builder memorySkillCalled(boolean v) { this.memorySkillCalled = v; return this; }
        public Builder memoryResultEmpty(boolean v) { this.memoryResultEmpty = v; return this; }

        public EvalSignals build() {
            return new EvalSignals(engineThrewException, taskCompletionOraclePass,
                    skillExecutionFailed, skillOutputWasMalformed,
                    hitLoopLimit, nearPassOracle, outputFormatCorrect, slowExecution,
                    memorySkillCalled, memoryResultEmpty);
        }
    }
}
