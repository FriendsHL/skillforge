package com.skillforge.server.eval;

import com.skillforge.server.eval.attribution.FailureAttribution;

import java.util.ArrayList;
import java.util.List;

/**
 * EVAL-V2 M2: judge output for multi-turn cases.
 *
 * <p>Distinguished from {@link EvalJudgeOutput} (single-turn) so the multi-turn
 * scoring shape (per-turn + overall + composite) doesn't pollute the single-turn
 * surface. The {@code compositeScore} is the value that flows back into the
 * standard eval pipeline (pass/fail + EvalRunEntity aggregation), while the
 * {@code perTurnScores} and {@code overallScore} are surfaced for diagnostics.
 */
public class EvalJudgeMultiTurnOutput {

    private double compositeScore;
    private double overallScore;
    private final List<PerTurnScore> perTurnScores = new ArrayList<>();
    private FailureAttribution attribution;
    private String rationale;
    private boolean pass;

    public double getCompositeScore() { return compositeScore; }
    public void setCompositeScore(double compositeScore) { this.compositeScore = compositeScore; }

    public double getOverallScore() { return overallScore; }
    public void setOverallScore(double overallScore) { this.overallScore = overallScore; }

    public List<PerTurnScore> getPerTurnScores() { return perTurnScores; }

    public FailureAttribution getAttribution() { return attribution; }
    public void setAttribution(FailureAttribution attribution) { this.attribution = attribution; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }

    public boolean isPass() { return pass; }
    public void setPass(boolean pass) { this.pass = pass; }

    public static class PerTurnScore {
        private int turnIndex;
        private double score;
        private String comment;

        public PerTurnScore() {}

        public PerTurnScore(int turnIndex, double score, String comment) {
            this.turnIndex = turnIndex;
            this.score = score;
            this.comment = comment;
        }

        public int getTurnIndex() { return turnIndex; }
        public void setTurnIndex(int turnIndex) { this.turnIndex = turnIndex; }

        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }

        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }
}
