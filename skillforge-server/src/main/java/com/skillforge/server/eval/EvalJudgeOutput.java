package com.skillforge.server.eval;

import com.skillforge.server.eval.attribution.FailureAttribution;

public class EvalJudgeOutput {

    private double outcomeScore;
    private double efficiencyScore;
    private double compositeScore;
    private boolean pass;
    private FailureAttribution attribution;
    private String metaJudgeRationale;

    public EvalJudgeOutput() {
    }

    public double getOutcomeScore() { return outcomeScore; }
    public void setOutcomeScore(double outcomeScore) { this.outcomeScore = outcomeScore; }

    public double getEfficiencyScore() { return efficiencyScore; }
    public void setEfficiencyScore(double efficiencyScore) { this.efficiencyScore = efficiencyScore; }

    public double getCompositeScore() { return compositeScore; }
    public void setCompositeScore(double compositeScore) { this.compositeScore = compositeScore; }

    public boolean isPass() { return pass; }
    public void setPass(boolean pass) { this.pass = pass; }

    public FailureAttribution getAttribution() { return attribution; }
    public void setAttribution(FailureAttribution attribution) { this.attribution = attribution; }

    public String getMetaJudgeRationale() { return metaJudgeRationale; }
    public void setMetaJudgeRationale(String metaJudgeRationale) { this.metaJudgeRationale = metaJudgeRationale; }
}
