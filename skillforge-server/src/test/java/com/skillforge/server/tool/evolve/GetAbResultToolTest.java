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
        // small block timeout/interval so the RUNNING test doesn't wait 90s
        tool = new GetAbResultTool(promptAbRunRepository, skillAbRunRepository,
                behaviorRuleAbRunRepository, agentEvolveAbRunRepository, objectMapper, 80L, 20L);
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
        assertThat(out).contains("\"wouldPromote\":true");   // 20 >= 15
        assertThat(out).contains("\"scenarioId\":\"s1\"");   // perScenario passthrough
    }

    @Test
    @DisplayName("prompt COMPLETED with sub-threshold delta → wouldPromote false")
    void promptCompleted_subThreshold_wouldNotPromote() {
        PromptAbRunEntity e = new PromptAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setDeltaPassRate(5.0);
        when(promptAbRunRepository.findById("ab-2")).thenReturn(Optional.of(e));

        SkillResult result = run("prompt", "ab-2", "42");

        assertThat(result.getOutput()).contains("\"wouldPromote\":false");
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
        assertThat(out).contains("\"wouldPromote\":true");   // 20>=15 && 50>=40
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
    @DisplayName("agent COMPLETED → scores + advisory wouldPromote (positive delta)")
    void agentCompleted_returnsScores() {
        com.skillforge.server.entity.AgentEvolveAbRunEntity e =
                new com.skillforge.server.entity.AgentEvolveAbRunEntity();
        e.setAgentId("42");
        e.setStatus("COMPLETED");
        e.setBaselinePassRate(50.0);
        e.setCandidatePassRate(66.0);
        e.setDeltaPassRate(16.0);
        when(agentEvolveAbRunRepository.findById("ae-1")).thenReturn(Optional.of(e));

        SkillResult result = run("agent", "ae-1", "42");

        String out = result.getOutput();
        assertThat(out).contains("\"baselineScore\":50.0");
        assertThat(out).contains("\"candidateScore\":66.0");
        assertThat(out).contains("\"deltaPassRate\":16.0");
        assertThat(out).contains("\"wouldPromote\":true");   // advisory: delta > 0
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
