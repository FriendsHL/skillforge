package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.repository.BehaviorRuleAbRunRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.SkillAbRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module B — {@link GetAbResultTool}: terminal vs
 * running, per-surface mapping, prompt per-scenario passthrough, advisory
 * wouldPromote, and ownership validation (cross-agent reads rejected).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetAbResultTool")
class GetAbResultToolTest {

    @Mock private PromptAbRunRepository promptAbRunRepository;
    @Mock private SkillAbRunRepository skillAbRunRepository;
    @Mock private BehaviorRuleAbRunRepository behaviorRuleAbRunRepository;
    @Mock private com.skillforge.server.repository.AgentEvolveAbRunRepository agentEvolveAbRunRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GetAbResultTool tool;

    @BeforeEach
    void setUp() {
        // small block timeout/interval so the RUNNING test doesn't wait 90s.
        // F5: thresholds from the properties defaults (prompt/skill delta 5,
        // agent 0/−3, minMeasuredN 10, anchor 5).
        tool = new GetAbResultTool(promptAbRunRepository, skillAbRunRepository,
                behaviorRuleAbRunRepository, agentEvolveAbRunRepository, objectMapper,
                new com.skillforge.server.config.EvolveThresholdProperties(), 80L, 20L);
    }

    /** Helper: build input map with all required fields. */
    private SkillResult run(String surface, String abRunId, String targetAgentId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", surface);
        input.put("abRunId", abRunId);
        input.put("targetAgentId", targetAgentId);
        return tool.execute(input, new SkillContext("/tmp", "sess", 7L));
    }

    // ───────────────────────────── prompt ─────────────────────────────

    @Test
    @DisplayName("prompt RUNNING → {status:running}, no scores")
    void promptRunning_returnsRunning() {
        PromptAbRunEntity e = new PromptAbRunEntity();
        e.setAgentId("42");
        e.setStatus("RUNNING");
        when(promptAbRunRepository.findById("ab-1")).thenReturn(Optional.of(e));

        SkillResult result = run("prompt", "ab-1", "42");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"status\":\"running\"");
        assertThat(result.getOutput()).doesNotContain("baselineScore");
    }

    @Test
    @DisplayName("prompt COMPLETED → scores + delta + wouldPromote + perScenario")
    void promptCompleted_returnsScores() {
        PromptAbRunEntity e = new PromptAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setBaselinePassRate(40.0);
        e.setCandidatePassRate(60.0);
        e.setDeltaPassRate(20.0);
        e.setAbScenarioResultsJson("[{\"scenarioId\":\"s1\",\"baseline\":{\"oracleScore\":0.4}}]");
        when(promptAbRunRepository.findById("ab-1")).thenReturn(Optional.of(e));

        SkillResult result = run("prompt", "ab-1", "42");

        assertThat(result.isSuccess()).isTrue();
        String out = result.getOutput();
        assertThat(out).contains("\"status\":\"COMPLETED\"");
        assertThat(out).contains("\"baselineScore\":40.0");
        assertThat(out).contains("\"candidateScore\":60.0");
        assertThat(out).contains("\"delta\":20.0");
        assertThat(out).contains("\"deltaPassRate\":20.0");
        assertThat(out).contains("\"wouldPromote\":true");   // 20 >= 5 (prompt-delta-pp)
        assertThat(out).contains("\"scenarioId\":\"s1\"");   // perScenario passthrough
    }

    @Test
    @DisplayName("prompt COMPLETED with sub-threshold delta → wouldPromote false")
    void promptCompleted_subThreshold_wouldNotPromote() {
        PromptAbRunEntity e = new PromptAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setDeltaPassRate(2.0);   // below the F5 prompt-delta-pp default (5)
        when(promptAbRunRepository.findById("ab-2")).thenReturn(Optional.of(e));

        SkillResult result = run("prompt", "ab-2", "42");

        assertThat(result.getOutput()).contains("\"wouldPromote\":false");
    }

    @Test
    @DisplayName("F5: prompt delta at the new 5pp threshold → wouldPromote true (was false at the old 15pp)")
    void promptCompleted_newThreshold_fivePpPromotes() {
        PromptAbRunEntity e = new PromptAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setDeltaPassRate(5.0);
        when(promptAbRunRepository.findById("ab-3")).thenReturn(Optional.of(e));

        SkillResult result = run("prompt", "ab-3", "42");

        assertThat(result.getOutput()).contains("\"wouldPromote\":true");
    }

    @Test
    @DisplayName("SECURITY prompt: run belongs to another agent → rejected, no scores returned")
    void promptCrossAgent_rejected() {
        PromptAbRunEntity e = new PromptAbRunEntity();
        e.setAgentId("99");          // belongs to agent 99
        e.setStatus("COMPLETED");
        e.setBaselinePassRate(40.0);
        e.setCandidatePassRate(60.0);
        e.setDeltaPassRate(20.0);
        when(promptAbRunRepository.findById("ab-x")).thenReturn(Optional.of(e));

        SkillResult result = run("prompt", "ab-x", "42");   // caller claims agent 42

        assertThat(result.isSuccess()).isTrue();
        String out = result.getOutput();
        assertThat(out).contains("\"status\":\"rejected\"");
        assertThat(out).contains("does not belong to targetAgentId");
        // No score data leaked
        assertThat(out).doesNotContain("baselineScore");
        assertThat(out).doesNotContain("candidateScore");
        assertThat(out).doesNotContain("wouldPromote");
    }

    // ───────────────────────────── skill ─────────────────────────────

    @Test
    @DisplayName("skill COMPLETED → aggregate scores + perScenarioNote (baseline aggregate-only)")
    void skillCompleted_aggregateNote() {
        SkillAbRunEntity e = new SkillAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setBaselinePassRate(30.0);
        e.setCandidatePassRate(50.0);
        e.setDeltaPassRate(20.0);
        when(skillAbRunRepository.findById("sk-1")).thenReturn(Optional.of(e));

        SkillResult result = run("skill", "sk-1", "42");

        String out = result.getOutput();
        assertThat(out).contains("\"wouldPromote\":true");   // 20>=5 && 50>=40
        assertThat(out).contains("perScenarioNote");
        assertThat(out).contains("aggregate-only");
    }

    @Test
    @DisplayName("skill COMPLETED candidate below floor → wouldPromote false")
    void skillCompleted_lowCandidate_wouldNotPromote() {
        SkillAbRunEntity e = new SkillAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setCandidatePassRate(35.0);   // below 40
        e.setDeltaPassRate(20.0);
        when(skillAbRunRepository.findById("sk-2")).thenReturn(Optional.of(e));

        SkillResult result = run("skill", "sk-2", "42");

        assertThat(result.getOutput()).contains("\"wouldPromote\":false");
    }

    @Test
    @DisplayName("SECURITY skill: run belongs to another agent → rejected, no scores returned")
    void skillCrossAgent_rejected() {
        SkillAbRunEntity e = new SkillAbRunEntity();
        e.setAgentId("99");          // belongs to agent 99
        e.setStatus("COMPLETED");
        e.setBaselinePassRate(30.0);
        e.setCandidatePassRate(55.0);
        e.setDeltaPassRate(25.0);
        when(skillAbRunRepository.findById("sk-x")).thenReturn(Optional.of(e));

        SkillResult result = run("skill", "sk-x", "42");    // caller claims agent 42

        assertThat(result.isSuccess()).isTrue();
        String out = result.getOutput();
        assertThat(out).contains("\"status\":\"rejected\"");
        assertThat(out).contains("does not belong to targetAgentId");
        assertThat(out).doesNotContain("baselineScore");
        assertThat(out).doesNotContain("wouldPromote");
    }

    // ───────────────────────────── behavior_rule ─────────────────────────────

    @Test
    @DisplayName("behavior_rule COMPLETED → dual-criteria wouldPromote + delta fields")
    void behaviorRuleCompleted_dualCriteria() {
        BehaviorRuleAbRunEntity e = new BehaviorRuleAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setBaselinePassRate(50.0);
        e.setCandidatePassRate(65.0);
        e.setDeltaPassRate(15.0);
        e.setTargetDeltaPp(15.0);       // >= 10 threshold
        e.setRegressionDeltaPp(0.0);    // >= -3 floor
        when(behaviorRuleAbRunRepository.findById("br-1")).thenReturn(Optional.of(e));

        SkillResult result = run("behavior_rule", "br-1", "42");

        String out = result.getOutput();
        assertThat(out).contains("\"wouldPromote\":true");
        assertThat(out).contains("\"targetDeltaPp\":15.0");
        assertThat(out).contains("\"regressionDeltaPp\":0.0");
    }

    @Test
    @DisplayName("behavior_rule regression below floor → wouldPromote false")
    void behaviorRuleCompleted_regressionViolation() {
        BehaviorRuleAbRunEntity e = new BehaviorRuleAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setTargetDeltaPp(15.0);
        e.setRegressionDeltaPp(-10.0);  // below -3 floor
        when(behaviorRuleAbRunRepository.findById("br-2")).thenReturn(Optional.of(e));

        SkillResult result = run("behavior_rule", "br-2", "42");

        assertThat(result.getOutput()).contains("\"wouldPromote\":false");
    }

    @Test
    @DisplayName("SECURITY behavior_rule: run belongs to another agent → rejected, no scores returned")
    void behaviorRuleCrossAgent_rejected() {
        BehaviorRuleAbRunEntity e = new BehaviorRuleAbRunEntity();
        e.setAgentId("99");          // belongs to agent 99
        e.setStatus("COMPLETED");
        e.setTargetDeltaPp(15.0);
        e.setRegressionDeltaPp(0.0);
        when(behaviorRuleAbRunRepository.findById("br-x")).thenReturn(Optional.of(e));

        SkillResult result = run("behavior_rule", "br-x", "42"); // caller claims agent 42

        assertThat(result.isSuccess()).isTrue();
        String out = result.getOutput();
        assertThat(out).contains("\"status\":\"rejected\"");
        assertThat(out).contains("does not belong to targetAgentId");
        assertThat(out).doesNotContain("baselineScore");
        assertThat(out).doesNotContain("wouldPromote");
    }

    @Test
    @DisplayName("agent COMPLETED → scores + target/regression deltas + dual-criteria wouldPromote (§8 子点②)")
    void agentCompleted_returnsScores() {
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setBaselinePassRate(50.0);
        e.setCandidatePassRate(66.0);
        e.setDeltaPassRate(16.0);
        e.setTargetDeltaPp(12.0);      // vs-best target up
        e.setRegressionDeltaPp(0.0);   // >= agent-regression-floor-pp (-3)
        e.setCandidateTargetRate(72.0);
        e.setCandidateGeneralRate(60.0);
        e.setBaselineTargetRate(60.0);
        e.setBaselineGeneralRate(60.0);
        when(agentEvolveAbRunRepository.findById("ae-1")).thenReturn(Optional.of(e));

        SkillResult result = run("agent", "ae-1", "42");

        String out = result.getOutput();
        assertThat(out).contains("\"baselineScore\":50.0");
        assertThat(out).contains("\"candidateScore\":66.0");
        assertThat(out).contains("\"deltaPassRate\":16.0");
        assertThat(out).contains("\"targetDeltaPp\":12.0");
        assertThat(out).contains("\"regressionDeltaPp\":0.0");
        // item 4 — absolute per-subset rates exposed for the vs-original anchor.
        assertThat(out).contains("\"candidateTargetRate\":72.0");
        assertThat(out).contains("\"candidateGeneralRate\":60.0");
        assertThat(out).contains("\"baselineTargetRate\":60.0");
        assertThat(out).contains("\"baselineGeneralRate\":60.0");
        assertThat(out).contains("\"wouldPromote\":true");   // target>0 AND regression>=floor
    }

    @Test
    @DisplayName("agent regression-only mode: target rates null, general rates populated (item 4)")
    void agentCompleted_regressionOnly_targetRatesNull() {
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setBaselinePassRate(55.0);
        e.setCandidatePassRate(60.0);
        e.setDeltaPassRate(5.0);
        // regression-only: no target subset → target delta + target rates null.
        e.setTargetDeltaPp(null);
        e.setRegressionDeltaPp(5.0);
        e.setCandidateTargetRate(null);
        e.setBaselineTargetRate(null);
        e.setCandidateGeneralRate(60.0);
        e.setBaselineGeneralRate(55.0);
        when(agentEvolveAbRunRepository.findById("ae-ro")).thenReturn(Optional.of(e));

        SkillResult result = run("agent", "ae-ro", "42");

        String out = result.getOutput();
        assertThat(out).contains("\"candidateTargetRate\":null");
        assertThat(out).contains("\"baselineTargetRate\":null");
        assertThat(out).contains("\"candidateGeneralRate\":60.0");
        assertThat(out).contains("\"baselineGeneralRate\":55.0");
        // regression-only advisory: general strictly improves → wouldPromote true
        assertThat(out).contains("\"wouldPromote\":true");
    }

    @Test
    @DisplayName("agent COMPLETED: regression below floor → wouldPromote false (§8 子点②)")
    void agentCompleted_regressionBelowFloor_wouldPromoteFalse() {
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setTargetDeltaPp(12.0);
        e.setRegressionDeltaPp(-10.0);   // below -3 floor
        when(agentEvolveAbRunRepository.findById("ae-2")).thenReturn(Optional.of(e));

        SkillResult result = run("agent", "ae-2", "42");

        assertThat(result.getOutput()).contains("\"wouldPromote\":false");
    }

    @Test
    @DisplayName("SECURITY agent: run belongs to another agent → rejected, no scores returned")
    void agentCrossAgent_rejected() {
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("99");          // belongs to agent 99
        e.setStatus("COMPLETED");
        e.setBaselinePassRate(50.0);
        e.setCandidatePassRate(66.0);
        e.setDeltaPassRate(16.0);
        when(agentEvolveAbRunRepository.findById("ae-x")).thenReturn(Optional.of(e));

        SkillResult result = run("agent", "ae-x", "42"); // caller claims agent 42

        assertThat(result.isSuccess()).isTrue();
        String out = result.getOutput();
        assertThat(out).contains("\"status\":\"rejected\"");
        assertThat(out).contains("does not belong to targetAgentId");
        assertThat(out).doesNotContain("baselineScore");
        assertThat(out).doesNotContain("wouldPromote");
    }

    // ───────────────── agent F3 measurement fields + F5 thresholds echo ─────────────────

    private static com.skillforge.server.improve.AbScenarioResult sc(
            String id, String subset, String baselineStatus, String candidateStatus) {
        return new com.skillforge.server.improve.AbScenarioResult(id, id,
                new com.skillforge.server.improve.AbScenarioResult.RunResult(baselineStatus, 80.0),
                new com.skillforge.server.improve.AbScenarioResult.RunResult(candidateStatus, 80.0),
                subset);
    }

    @Test
    @DisplayName("F3 agent fresh run: totalN/measuredN pairwise + per-subset counts from tags + thresholds echo")
    void agentCompleted_measurementFields_freshRun() throws Exception {
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setSkipBaseline(false);
        // 4 scenarios: t1 measured, t2 candidate ERROR (drop), g1 measured,
        // g2 baseline TIMEOUT (drop) → totalN=4 measuredN=2 target=1 general=1.
        e.setAbScenarioResultsJson(objectMapper.writeValueAsString(java.util.List.of(
                sc("t1", "target", "PASS", "PASS"),
                sc("t2", "target", "PASS", "ERROR"),
                sc("g1", "general", "FAIL", "PASS"),
                sc("g2", "general", "TIMEOUT", "PASS"))));
        when(agentEvolveAbRunRepository.findById("ae-m1")).thenReturn(Optional.of(e));

        String out = run("agent", "ae-m1", "42").getOutput();

        assertThat(out).contains("\"totalN\":4");
        assertThat(out).contains("\"measuredN\":2");
        assertThat(out).contains("\"targetMeasuredN\":1");
        assertThat(out).contains("\"generalMeasuredN\":1");
        // F5 thresholds echo: the workflow reads minMeasuredN/anchorErosionFloorPp here.
        assertThat(out).contains("\"thresholds\":{");
        assertThat(out).contains("\"minMeasuredN\":10");
        assertThat(out).contains("\"anchorErosionFloorPp\":5.0");
        assertThat(out).contains("\"agentRegressionFloorPp\":-3.0");
        assertThat(out).contains("\"promptDeltaPp\":5.0");
    }

    @Test
    @DisplayName("F3 agent skip run: measuredN is cross-round pairwise vs the prior winner's candidate side")
    void agentCompleted_measurementFields_skipRun_crossRound() throws Exception {
        com.skillforge.server.entity.AgentEvolveAbRunEntity prior =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        prior.setAgentId("42");
        prior.setStatus("COMPLETED");
        prior.setAbScenarioResultsJson(objectMapper.writeValueAsString(java.util.List.of(
                sc("s1", "general", "FAIL", "PASS"),     // prior candidate measured
                sc("s2", "general", "FAIL", "ERROR")))); // prior candidate infra → s2 drops
        when(agentEvolveAbRunRepository.findById("ae-prior")).thenReturn(Optional.of(prior));

        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setSkipBaseline(true);
        e.setPriorWinnerAbRunId("ae-prior");
        e.setAbScenarioResultsJson(objectMapper.writeValueAsString(java.util.List.of(
                sc("s1", "general", "CACHED", "PASS"),
                sc("s2", "general", "CACHED", "PASS"))));
        when(agentEvolveAbRunRepository.findById("ae-m2")).thenReturn(Optional.of(e));

        String out = run("agent", "ae-m2", "42").getOutput();

        assertThat(out).contains("\"totalN\":2");
        assertThat(out).contains("\"measuredN\":1");        // s2 dropped (prior infra)
        assertThat(out).contains("\"generalMeasuredN\":1");
    }

    @Test
    @DisplayName("F3 agent legacy row (no subset tags): per-subset counts null, overall still computed")
    void agentCompleted_measurementFields_legacyUntagged() throws Exception {
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setSkipBaseline(false);
        // Legacy JSON without subset tags (pre-F3 rows).
        e.setAbScenarioResultsJson(
                "[{\"scenarioId\":\"s1\",\"scenarioName\":\"s1\","
                        + "\"baseline\":{\"status\":\"PASS\",\"oracleScore\":80.0},"
                        + "\"candidate\":{\"status\":\"PASS\",\"oracleScore\":80.0}}]");
        when(agentEvolveAbRunRepository.findById("ae-m3")).thenReturn(Optional.of(e));

        String out = run("agent", "ae-m3", "42").getOutput();

        assertThat(out).contains("\"totalN\":1");
        assertThat(out).contains("\"measuredN\":1");
        assertThat(out).contains("\"targetMeasuredN\":null");
        assertThat(out).contains("\"generalMeasuredN\":null");
    }

    // ───────── EVOLVE-LOOP-HILLCLIMB: weightedScore + thresholds echo + perScenarioFlips ─────────

    @Test
    @DisplayName("HILLCLIMB computeWeightedScore: convex over present subsets; empty harvest degrades; both null → null")
    void computeWeightedScore_formula() {
        // both subsets present: (0.6*60 + 0.4*80) / 1.0 = 68.0
        assertThat(GetAbResultTool.computeWeightedScore(60.0, 80.0, 0.6, 0.4))
                .isEqualTo(68.0);
        // empty harvest (target null) → re-normalised to the pure general rate.
        assertThat(GetAbResultTool.computeWeightedScore(60.0, null, 0.6, 0.4))
                .isEqualTo(60.0);
        // empty general → pure harvest rate.
        assertThat(GetAbResultTool.computeWeightedScore(null, 80.0, 0.6, 0.4))
                .isEqualTo(80.0);
        // no subset measured → null (no signal).
        assertThat(GetAbResultTool.computeWeightedScore(null, null, 0.6, 0.4))
                .isNull();
    }

    @Test
    @DisplayName("HILLCLIMB agent: weightedScore + baselineWeightedScore in the response (both subsets present)")
    void agentCompleted_weightedScore() {
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setCandidateTargetRate(72.0);
        e.setCandidateGeneralRate(60.0);
        e.setBaselineTargetRate(60.0);
        e.setBaselineGeneralRate(60.0);
        when(agentEvolveAbRunRepository.findById("ae-w1")).thenReturn(Optional.of(e));

        String out = run("agent", "ae-w1", "42").getOutput();
        // candidate: 0.6*60 + 0.4*72 = 64.8 ; baseline: 0.6*60 + 0.4*60 = 60.0
        assertThat(out).contains("\"weightedScore\":64.8");
        assertThat(out).contains("\"baselineWeightedScore\":60.0");
    }

    @Test
    @DisplayName("HILLCLIMB agent: empty harvest subset → weightedScore == generalRate (degenerate)")
    void agentCompleted_weightedScore_emptyHarvestDegrades() {
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setCandidateTargetRate(null);   // empty harvest
        e.setCandidateGeneralRate(82.0);
        e.setBaselineTargetRate(null);
        e.setBaselineGeneralRate(70.0);
        when(agentEvolveAbRunRepository.findById("ae-w2")).thenReturn(Optional.of(e));

        String out = run("agent", "ae-w2", "42").getOutput();
        assertThat(out).contains("\"weightedScore\":82.0");
        assertThat(out).contains("\"baselineWeightedScore\":70.0");
    }

    @Test
    @DisplayName("HILLCLIMB agent: thresholds echo carries the hill-climb knobs (incl. null targetWeightedScore)")
    void agentCompleted_thresholdsEcho_hillclimb() {
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        when(agentEvolveAbRunRepository.findById("ae-th")).thenReturn(Optional.of(e));

        String out = run("agent", "ae-th", "42").getOutput();
        assertThat(out).contains("\"weightGeneral\":0.6");
        assertThat(out).contains("\"weightHarvest\":0.4");
        assertThat(out).contains("\"minImprovePp\":0.0");
        assertThat(out).contains("\"noImproveStreakLimit\":3");
        // null target = no target-stop; emitted verbatim as null.
        assertThat(out).contains("\"targetWeightedScore\":null");
    }

    @Test
    @DisplayName("HILLCLIMB agent: perScenarioFlips classifies regressed (pass→fail) / improved (fail→pass)")
    void agentCompleted_perScenarioFlips() throws Exception {
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setSkipBaseline(false);
        e.setAbScenarioResultsJson(objectMapper.writeValueAsString(java.util.List.of(
                flipSc("regr", "PASS", 80.0, "FAIL", 10.0),   // pass→fail = regressed
                flipSc("impr", "FAIL", 10.0, "PASS", 80.0),   // fail→pass = improved
                flipSc("same", "PASS", 80.0, "PASS", 80.0),   // no flip
                flipSc("drop", "PASS", 80.0, "ERROR", 0.0))));  // infra → not measured
        when(agentEvolveAbRunRepository.findById("ae-flip")).thenReturn(Optional.of(e));

        String out = run("agent", "ae-flip", "42").getOutput();
        com.fasterxml.jackson.databind.JsonNode flips = objectMapper.readTree(out).get("perScenarioFlips");
        assertThat(flips.get("regressedTotal").asInt()).isEqualTo(1);
        assertThat(flips.get("improvedTotal").asInt()).isEqualTo(1);
        assertThat(flips.get("regressed").get(0).get("scenarioId").asText()).isEqualTo("regr");
        assertThat(flips.get("improved").get(0).get("scenarioId").asText()).isEqualTo("impr");
    }

    @Test
    @DisplayName("HILLCLIMB agent: perScenarioFlips caps each list at 20 but reports the full total")
    void agentCompleted_perScenarioFlips_truncates() throws Exception {
        java.util.List<com.skillforge.server.improve.AbScenarioResult> scenarios = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            scenarios.add(flipSc("r" + i, "PASS", 80.0, "FAIL", 10.0));   // 25 regressions
        }
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setSkipBaseline(false);
        e.setAbScenarioResultsJson(objectMapper.writeValueAsString(scenarios));
        when(agentEvolveAbRunRepository.findById("ae-trunc")).thenReturn(Optional.of(e));

        String out = run("agent", "ae-trunc", "42").getOutput();
        com.fasterxml.jackson.databind.JsonNode flips = objectMapper.readTree(out).get("perScenarioFlips");
        assertThat(flips.get("regressed").size()).isEqualTo(20);   // capped
        assertThat(flips.get("regressedTotal").asInt()).isEqualTo(25);   // full count
    }

    @Test
    @DisplayName("HILLCLIMB agent skip run (prior winner available): flips judged vs the prior winner's candidate side")
    void agentCompleted_perScenarioFlips_skipRun_priorAvailable() throws Exception {
        // Prior winner run: its CANDIDATE side is the cross-round baseline for the skip run.
        com.skillforge.server.entity.AgentEvolveAbRunEntity prior =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        prior.setAgentId("42");
        prior.setStatus("COMPLETED");
        prior.setAbScenarioResultsJson(objectMapper.writeValueAsString(java.util.List.of(
                // prior candidate PASS (baseline-pass for "regr"); FAIL (baseline-fail for "impr").
                flipSc("regr", "FAIL", 0.0, "PASS", 80.0),
                flipSc("impr", "FAIL", 0.0, "FAIL", 10.0))));
        when(agentEvolveAbRunRepository.findById("ae-prior")).thenReturn(Optional.of(prior));

        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setSkipBaseline(true);
        e.setPriorWinnerAbRunId("ae-prior");
        e.setAbScenarioResultsJson(objectMapper.writeValueAsString(java.util.List.of(
                // baseline side is the CACHED sentinel on a skip run — the prior candidate is the real baseline.
                flipSc("regr", "CACHED", 0.0, "FAIL", 10.0),    // prior PASS → cur FAIL = regressed
                flipSc("impr", "CACHED", 0.0, "PASS", 80.0))));  // prior FAIL → cur PASS = improved
        when(agentEvolveAbRunRepository.findById("ae-skip")).thenReturn(Optional.of(e));

        String out = run("agent", "ae-skip", "42").getOutput();
        com.fasterxml.jackson.databind.JsonNode flips = objectMapper.readTree(out).get("perScenarioFlips");
        assertThat(flips.get("regressedTotal").asInt()).isEqualTo(1);
        assertThat(flips.get("improvedTotal").asInt()).isEqualTo(1);
        assertThat(flips.get("regressed").get(0).get("scenarioId").asText()).isEqualTo("regr");
        assertThat(flips.get("improved").get(0).get("scenarioId").asText()).isEqualTo("impr");
    }

    @Test
    @DisplayName("HILLCLIMB agent skip run (prior winner unavailable): legacy degrade — every measured candidate-pass = improved")
    void agentCompleted_perScenarioFlips_skipRun_priorUnavailable() throws Exception {
        // No priorWinnerAbRunId → loadPriorWinnerCandidateSide returns null → candidate-side-only
        // degrade: baselineSide is null, isPass(null)=false, so every measured candidate-pass is
        // "improved" and a candidate-fail is neither (pins the documented degrade semantics).
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setSkipBaseline(true);
        e.setPriorWinnerAbRunId(null);
        e.setAbScenarioResultsJson(objectMapper.writeValueAsString(java.util.List.of(
                flipSc("a", "CACHED", 0.0, "PASS", 80.0),   // measured pass → improved
                flipSc("c", "CACHED", 0.0, "PASS", 80.0),   // measured pass → improved
                flipSc("b", "CACHED", 0.0, "FAIL", 10.0))));  // measured fail → neither
        when(agentEvolveAbRunRepository.findById("ae-skip-degrade")).thenReturn(Optional.of(e));

        String out = run("agent", "ae-skip-degrade", "42").getOutput();
        com.fasterxml.jackson.databind.JsonNode flips = objectMapper.readTree(out).get("perScenarioFlips");
        assertThat(flips.get("improvedTotal").asInt()).isEqualTo(2);
        assertThat(flips.get("regressedTotal").asInt()).isEqualTo(0);
    }

    private static com.skillforge.server.improve.AbScenarioResult flipSc(
            String id, String baselineStatus, double baselineScore,
            String candidateStatus, double candidateScore) {
        return new com.skillforge.server.improve.AbScenarioResult(id, id,
                new com.skillforge.server.improve.AbScenarioResult.RunResult(baselineStatus, baselineScore),
                new com.skillforge.server.improve.AbScenarioResult.RunResult(candidateStatus, candidateScore),
                "general");
    }

    @Test
    @DisplayName("F3 agent row without per-scenario JSON: all measurement fields null (no crash)")
    void agentCompleted_measurementFields_noJson() {
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        when(agentEvolveAbRunRepository.findById("ae-m4")).thenReturn(Optional.of(e));

        String out = run("agent", "ae-m4", "42").getOutput();

        assertThat(out).contains("\"totalN\":null");
        assertThat(out).contains("\"measuredN\":null");
        // perScenarioFlips also degrades to null (no per-scenario JSON to classify).
        assertThat(out).contains("\"perScenarioFlips\":null");
    }

    // ───────────────────────────── validation ─────────────────────────────

    @Test
    @DisplayName("run not found → error result")
    void notFound_error() {
        when(promptAbRunRepository.findById("missing")).thenReturn(Optional.empty());

        SkillResult result = run("prompt", "missing", "42");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("not found");
    }

    @Test
    @DisplayName("missing abRunId → validation error")
    void missingAbRunId_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("targetAgentId", "42");

        SkillResult result = tool.execute(input, new SkillContext("/tmp", "sess", 7L));

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("abRunId is required");
    }

    @Test
    @DisplayName("missing targetAgentId → validation error")
    void missingTargetAgentId_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("abRunId", "ab-1");

        SkillResult result = tool.execute(input, new SkillContext("/tmp", "sess", 7L));

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("targetAgentId is required");
    }

    @Test
    @DisplayName("tool metadata: read-only")
    void metadata() {
        assertThat(tool.getName()).isEqualTo("GetAbResult");
        assertThat(tool.isReadOnly()).isTrue();
    }
}
