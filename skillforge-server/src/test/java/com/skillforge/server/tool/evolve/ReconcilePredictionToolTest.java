package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.AgentEvolveAbRunEntity;
import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.improve.AbScenarioResult.RunResult;
import com.skillforge.server.repository.AgentEvolveAbRunRepository;
import com.skillforge.server.tool.evolve.ReconcilePredictionTool.ScenarioOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * BC-M2b (G3): {@link ReconcilePredictionTool} — the pure deterministic
 * {@code reconcile} (hits/misses/riskHits/surprises/confidence + edges) and the
 * execute() path (perScenario parse, ownership, fresh vs skip-baseline baseline).
 */
@ExtendWith(MockitoExtension.class)
class ReconcilePredictionToolTest {

    @Mock private AgentEvolveAbRunRepository abRunRepository;

    private ReconcilePredictionTool tool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tool = new ReconcilePredictionTool(abRunRepository, objectMapper);
    }

    // ── pure reconcile ────────────────────────────────────────────────────

    /** baselineMeasured, baselinePass, candidateMeasured, candidatePass. */
    private static ScenarioOutcome o(boolean bm, boolean bp, boolean cm, boolean cp) {
        return new ScenarioOutcome(bm, bp, cm, cp);
    }

    @Test
    @DisplayName("reconcile: hits / misses / confidence over evaluable flips")
    void reconcile_hitsMissesConfidence() {
        Map<String, ScenarioOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("s1", o(true, false, true, true));   // FAIL→PASS  → hit
        outcomes.put("s2", o(true, false, true, false));  // FAIL→FAIL  → miss
        outcomes.put("s3", o(true, true, true, true));    // PASS→PASS  → miss (didn't flip to pass)

        var r = ReconcilePredictionTool.reconcile(List.of("s1", "s2", "s3"), List.of(), outcomes);

        assertThat(r.hits()).containsExactly("s1");
        assertThat(r.misses()).containsExactlyInAnyOrder("s2", "s3");
        assertThat(r.confidence()).isEqualTo(1.0 / 3.0);
    }

    @Test
    @DisplayName("reconcile: riskHits = predicted regressions that actually regressed")
    void reconcile_riskHits() {
        Map<String, ScenarioOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("s4", o(true, true, true, false));   // PASS→FAIL → riskHit
        outcomes.put("s5", o(true, true, true, true));     // PASS→PASS → not a riskHit

        var r = ReconcilePredictionTool.reconcile(List.of(), List.of("s4", "s5"), outcomes);

        assertThat(r.riskHits()).containsExactly("s4");
    }

    @Test
    @DisplayName("reconcile: surprises = unpredicted flips (either direction)")
    void reconcile_surprises() {
        Map<String, ScenarioOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("s1", o(true, false, true, true));   // predicted flip
        outcomes.put("s6", o(true, false, true, true));   // unpredicted FAIL→PASS → surprise
        outcomes.put("s7", o(true, true, true, false));   // unpredicted PASS→FAIL → surprise
        outcomes.put("s8", o(true, true, true, true));     // no flip → not a surprise

        var r = ReconcilePredictionTool.reconcile(List.of("s1"), List.of(), outcomes);

        assertThat(r.surprises()).containsExactlyInAnyOrder("s6", "s7");
        assertThat(r.hits()).containsExactly("s1");
    }

    @Test
    @DisplayName("reconcile: empty flipToPass → confidence null (no divide-by-zero)")
    void reconcile_emptyFlip_nullConfidence() {
        var r = ReconcilePredictionTool.reconcile(List.of(), List.of(), Map.of());
        assertThat(r.confidence()).isNull();
        assertThat(r.hits()).isEmpty();
    }

    @Test
    @DisplayName("reconcile: predicted flip not evaluable (arm unmeasured) → excluded, confidence null")
    void reconcile_nonEvaluable_excludedFromConfidence() {
        Map<String, ScenarioOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("s1", o(false, false, true, true));  // baseline not measured → not evaluable

        var r = ReconcilePredictionTool.reconcile(List.of("s1"), List.of(), outcomes);

        assertThat(r.hits()).isEmpty();
        assertThat(r.misses()).isEmpty();
        assertThat(r.confidence()).isNull();
    }

    // ── execute() integration ─────────────────────────────────────────────

    private RunResult pass() { return new RunResult("PASS", 80.0); }
    private RunResult fail() { return new RunResult("FAIL", 10.0); }
    private RunResult cached() { return new RunResult("CACHED", 0.0); }

    private String perScenarioJson(List<AbScenarioResult> ps) throws Exception {
        return objectMapper.writeValueAsString(ps);
    }

    private AgentEvolveAbRunEntity run(String id, String agentId) {
        AgentEvolveAbRunEntity r = new AgentEvolveAbRunEntity();
        r.setId(id);
        r.setAgentId(agentId);
        return r;
    }

    private Map<String, Object> prediction(List<String> flipToPass, List<String> riskToFail) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("issueId", "issue-1");
        p.put("targetProblem", "some scenarios keep failing");
        p.put("flipToPass", flipToPass);
        p.put("riskToFail", riskToFail);
        return p;
    }

    @Test
    @DisplayName("execute (fresh round): baseline = this run's baseline side; FAIL→PASS counts as hit")
    void execute_freshRound_hit() throws Exception {
        AgentEvolveAbRunEntity r = run("ab-1", "7");
        r.setSkipBaseline(false);
        r.setAbScenarioResultsJson(perScenarioJson(List.of(
                new AbScenarioResult("s1", "s1", fail(), pass()))));   // baseline FAIL, candidate PASS
        when(abRunRepository.findById("ab-1")).thenReturn(Optional.of(r));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prediction", prediction(List.of("s1"), List.of()));
        input.put("abRunId", "ab-1");
        input.put("targetAgentId", "7");

        SkillResult res = tool.execute(input, null);

        assertThat(res.isSuccess()).isTrue();
        JsonNode node = objectMapper.readTree(res.getOutput());
        assertThat(node.path("hits").toString()).isEqualTo("[\"s1\"]");
        assertThat(node.path("confidence").asDouble()).isEqualTo(1.0);
        assertThat(node.path("issueId").asText()).isEqualTo("issue-1");
    }

    @Test
    @DisplayName("execute (skip-baseline): baseline = prior winner's CANDIDATE side (cross-round)")
    void execute_skipBaseline_usesPriorWinnerCandidate() throws Exception {
        // current run: baseline side is the CACHED sentinel; candidate passes s1.
        AgentEvolveAbRunEntity cur = run("ab-2", "7");
        cur.setSkipBaseline(true);
        cur.setPriorWinnerAbRunId("ab-prev");
        cur.setAbScenarioResultsJson(perScenarioJson(List.of(
                new AbScenarioResult("s1", "s1", cached(), pass()))));
        // prior winner: its CANDIDATE side FAILED s1 → that is the real baseline.
        AgentEvolveAbRunEntity prev = run("ab-prev", "7");
        prev.setAbScenarioResultsJson(perScenarioJson(List.of(
                new AbScenarioResult("s1", "s1", fail(), fail()))));
        when(abRunRepository.findById("ab-2")).thenReturn(Optional.of(cur));
        when(abRunRepository.findById("ab-prev")).thenReturn(Optional.of(prev));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prediction", prediction(List.of("s1"), List.of()));
        input.put("abRunId", "ab-2");
        input.put("targetAgentId", "7");

        SkillResult res = tool.execute(input, null);

        JsonNode node = objectMapper.readTree(res.getOutput());
        // prior candidate FAIL → current candidate PASS = real flip-to-pass → hit.
        assertThat(node.path("hits").toString()).isEqualTo("[\"s1\"]");
        assertThat(node.path("confidence").asDouble()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("execute: ownership mismatch → rejected, no reconciliation")
    void execute_ownershipMismatch_rejected() throws Exception {
        AgentEvolveAbRunEntity r = run("ab-3", "9");   // owned by agent 9
        when(abRunRepository.findById("ab-3")).thenReturn(Optional.of(r));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prediction", prediction(List.of("s1"), List.of()));
        input.put("abRunId", "ab-3");
        input.put("targetAgentId", "7");

        SkillResult res = tool.execute(input, null);

        JsonNode node = objectMapper.readTree(res.getOutput());
        assertThat(node.path("status").asText()).isEqualTo("rejected");
    }

    @Test
    @DisplayName("execute: run not found → error")
    void execute_runNotFound_error() {
        when(abRunRepository.findById("missing")).thenReturn(Optional.empty());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prediction", prediction(List.of("s1"), List.of()));
        input.put("abRunId", "missing");
        input.put("targetAgentId", "7");

        SkillResult res = tool.execute(input, null);
        assertThat(res.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("execute: run with no perScenario → confidence null, empty hits")
    void execute_noPerScenario_confidenceNull() throws Exception {
        AgentEvolveAbRunEntity r = run("ab-4", "7");
        r.setAbScenarioResultsJson(null);
        when(abRunRepository.findById("ab-4")).thenReturn(Optional.of(r));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prediction", prediction(List.of("s1"), List.of()));
        input.put("abRunId", "ab-4");
        input.put("targetAgentId", "7");

        SkillResult res = tool.execute(input, null);
        JsonNode node = objectMapper.readTree(res.getOutput());
        assertThat(node.path("confidence").isNull()).isTrue();
        assertThat(node.path("hits").size()).isZero();
    }

    @Test
    @DisplayName("tool is read-only with the expected name")
    void readOnly() {
        assertThat(tool.isReadOnly()).isTrue();
        assertThat(tool.getName()).isEqualTo("ReconcilePrediction");
    }
}
