package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.service.EvalDatasetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * BC-M2b: {@link ListActiveHarvestedScenariosTool} returns only ACTIVE
 * session-derived scenarios; empty result yields {@code []} (never null).
 */
@ExtendWith(MockitoExtension.class)
class ListActiveHarvestedScenariosToolTest {

    @Mock private EvalScenarioDraftRepository scenarioRepository;
    @Mock private EvalDatasetService evalDatasetService;

    private ListActiveHarvestedScenariosTool tool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tool = new ListActiveHarvestedScenariosTool(scenarioRepository, evalDatasetService, objectMapper);
    }

    private EvalScenarioEntity active(String id, String ref) {
        EvalScenarioEntity s = new EvalScenarioEntity();
        s.setId(id);
        s.setName("badcase-" + id);
        s.setStatus("active");
        s.setSourceType(EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED);
        s.setSourceRef(ref);
        return s;
    }

    private EvalScenarioEntity activeWithDetail(String id, String oracle, String task, String rationale) {
        EvalScenarioEntity s = active(id, "session:" + id);
        s.setOracleExpected(oracle);
        s.setTask(task);
        s.setExtractionRationale(rationale);
        return s;
    }

    @Test
    @DisplayName("returns active session_derived ids that ARE members of the resolved default version")
    void returnsActiveMemberIds() throws Exception {
        when(scenarioRepository.findByAgentIdAndStatusAndSourceType(
                "7", "active", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED))
                .thenReturn(List.of(active("a1", "session:s1"), active("a2", "session:s2")));
        // Both are members of the agent's resolved default version.
        when(evalDatasetService.findDefaultVersionIdForAgent("7")).thenReturn("v-1");
        when(scenarioRepository.findAllByDatasetVersionId("v-1"))
                .thenReturn(List.of(active("a1", "session:s1"), active("a2", "session:s2")));

        SkillResult r = tool.execute(Map.of("agentId", "7"), null);

        assertThat(r.isSuccess()).isTrue();
        var node = objectMapper.readTree(r.getOutput());
        assertThat(node.path("agentId").asText()).isEqualTo("7");
        assertThat(node.path("count").asInt()).isEqualTo(2);
        assertThat(node.path("scenarioIds").toString()).isEqualTo("[\"a1\",\"a2\"]");
        assertThat(node.path("items").get(0).path("sourceRef").asText()).isEqualTo("session:s1");
    }

    @Test
    @DisplayName("design W2: an active scenario that is NOT a dataset-version member is filtered out")
    void filtersOutNonMember() throws Exception {
        // a1 is a member; a2 was flipped active by a side path but never added to a
        // dataset version → must NOT be returned as a target.
        when(scenarioRepository.findByAgentIdAndStatusAndSourceType(
                "7", "active", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED))
                .thenReturn(List.of(active("a1", "session:s1"), active("a2", "session:s2")));
        when(evalDatasetService.findDefaultVersionIdForAgent("7")).thenReturn("v-1");
        when(scenarioRepository.findAllByDatasetVersionId("v-1"))
                .thenReturn(List.of(active("a1", "session:s1")));   // only a1 is a member

        SkillResult r = tool.execute(Map.of("agentId", "7"), null);

        var node = objectMapper.readTree(r.getOutput());
        assertThat(node.path("count").asInt()).isEqualTo(1);
        assertThat(node.path("scenarioIds").toString()).isEqualTo("[\"a1\"]");
    }

    @Test
    @DisplayName("no resolvable default version → nothing qualifies as a target")
    void noDefaultVersion_empty() throws Exception {
        when(scenarioRepository.findByAgentIdAndStatusAndSourceType(
                "7", "active", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED))
                .thenReturn(List.of(active("a1", "session:s1")));
        when(evalDatasetService.findDefaultVersionIdForAgent("7")).thenReturn(null);

        SkillResult r = tool.execute(Map.of("agentId", "7"), null);

        var node = objectMapper.readTree(r.getOutput());
        assertThat(node.path("count").asInt()).isZero();
        assertThat(node.path("scenarioIds").size()).isZero();
    }

    @Test
    @DisplayName("no active scenarios → scenarioIds is [] (not null), count 0")
    void emptyYieldsEmptyArray() throws Exception {
        when(scenarioRepository.findByAgentIdAndStatusAndSourceType(
                "7", "active", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED))
                .thenReturn(List.of());

        SkillResult r = tool.execute(Map.of("agentId", "7"), null);

        assertThat(r.isSuccess()).isTrue();
        var node = objectMapper.readTree(r.getOutput());
        assertThat(node.path("count").asInt()).isZero();
        assertThat(node.path("scenarioIds").isArray()).isTrue();
        assertThat(node.path("scenarioIds").size()).isZero();
    }

    @Test
    @DisplayName("missing agentId → validation error")
    void missingAgentId_validationError() {
        SkillResult r = tool.execute(Map.of(), null);
        assertThat(r.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("tool is read-only")
    void readOnly() {
        assertThat(tool.isReadOnly()).isTrue();
        assertThat(tool.getName()).isEqualTo("ListActiveHarvestedScenarios");
    }

    // ── EVOLVE-CANDIDATE-GROUNDING (FR2 / INV-2): capped failureDetails projection ──

    @Test
    @DisplayName("FR2: failureDetails projects errorSignature + taskSummary + extractionRationale (no fixtures/full task)")
    void failureDetailsProjection() throws Exception {
        EvalScenarioEntity s = activeWithDetail("a1",
                "{\"tool\":\"FileWrite\",\"errorSignature\":\"ENOENT path\",\"filePath\":\"x.txt\",\"rounds\":3}",
                "Fix the broken file write\nwith a long body that should not be shipped",
                "harvested because FileWrite kept failing");
        when(scenarioRepository.findByAgentIdAndStatusAndSourceType(
                "7", "active", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED))
                .thenReturn(List.of(s));
        when(evalDatasetService.findDefaultVersionIdForAgent("7")).thenReturn("v-1");
        when(scenarioRepository.findAllByDatasetVersionId("v-1")).thenReturn(List.of(s));

        SkillResult r = tool.execute(Map.of("agentId", "7"), null);

        var node = objectMapper.readTree(r.getOutput());
        var fd = node.path("failureDetails");
        assertThat(fd.isArray()).isTrue();
        assertThat(fd.size()).isEqualTo(1);
        var d0 = fd.get(0);
        assertThat(d0.path("id").asText()).isEqualTo("a1");
        assertThat(d0.path("errorSignature").asText()).isEqualTo("ENOENT path");
        // taskSummary = first line only (no second line / full body)
        assertThat(d0.path("taskSummary").asText()).isEqualTo("Fix the broken file write");
        assertThat(d0.path("extractionRationale").asText()).isEqualTo("harvested because FileWrite kept failing");
        // excludes the full task body + any fixture content
        assertThat(r.getOutput()).doesNotContain("with a long body");
    }

    @Test
    @DisplayName("INV-2: failureDetails is capped at MAX_FAILURE_DETAILS even with many active members")
    void failureDetailsCappedByCount() throws Exception {
        int n = ListActiveHarvestedScenariosTool.MAX_FAILURE_DETAILS + 5;
        java.util.List<EvalScenarioEntity> many = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            many.add(activeWithDetail("id-" + i,
                    "{\"errorSignature\":\"sig-" + i + "\"}", "task-" + i, "rat-" + i));
        }
        when(scenarioRepository.findByAgentIdAndStatusAndSourceType(
                "7", "active", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED))
                .thenReturn(many);
        when(evalDatasetService.findDefaultVersionIdForAgent("7")).thenReturn("v-1");
        when(scenarioRepository.findAllByDatasetVersionId("v-1")).thenReturn(many);

        SkillResult r = tool.execute(Map.of("agentId", "7"), null);

        var node = objectMapper.readTree(r.getOutput());
        // scenarioIds / items stay COMPLETE (A/B targeting path); only the prompt-bound
        // failureDetails projection is capped.
        assertThat(node.path("count").asInt()).isEqualTo(n);
        assertThat(node.path("scenarioIds").size()).isEqualTo(n);
        assertThat(node.path("failureDetails").size())
                .isEqualTo(ListActiveHarvestedScenariosTool.MAX_FAILURE_DETAILS);
    }

    @Test
    @DisplayName("INV-2: taskSummary is truncated to TASK_SUMMARY_MAX_CHARS")
    void taskSummaryTruncated() throws Exception {
        String longTask = "x".repeat(ListActiveHarvestedScenariosTool.TASK_SUMMARY_MAX_CHARS + 100);
        EvalScenarioEntity s = activeWithDetail("a1",
                "{\"errorSignature\":\"sig\"}", longTask, "rat");
        when(scenarioRepository.findByAgentIdAndStatusAndSourceType(
                "7", "active", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED))
                .thenReturn(List.of(s));
        when(evalDatasetService.findDefaultVersionIdForAgent("7")).thenReturn("v-1");
        when(scenarioRepository.findAllByDatasetVersionId("v-1")).thenReturn(List.of(s));

        SkillResult r = tool.execute(Map.of("agentId", "7"), null);

        var node = objectMapper.readTree(r.getOutput());
        String summary = node.path("failureDetails").get(0).path("taskSummary").asText();
        assertThat(summary.length()).isEqualTo(ListActiveHarvestedScenariosTool.TASK_SUMMARY_MAX_CHARS);
    }

    @Test
    @DisplayName("INV-2: errorSignature + extractionRationale capped to DETAIL_FIELD_MAX_CHARS")
    void detailFieldsCapped() throws Exception {
        int cap = ListActiveHarvestedScenariosTool.DETAIL_FIELD_MAX_CHARS;
        String longSig = "s".repeat(cap + 100);
        String longRat = "r".repeat(cap + 100);
        EvalScenarioEntity s = activeWithDetail("a1",
                "{\"errorSignature\":\"" + longSig + "\"}", "task", longRat);
        when(scenarioRepository.findByAgentIdAndStatusAndSourceType(
                "7", "active", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED))
                .thenReturn(List.of(s));
        when(evalDatasetService.findDefaultVersionIdForAgent("7")).thenReturn("v-1");
        when(scenarioRepository.findAllByDatasetVersionId("v-1")).thenReturn(List.of(s));

        SkillResult r = tool.execute(Map.of("agentId", "7"), null);

        var d = objectMapper.readTree(r.getOutput()).path("failureDetails").get(0);
        assertThat(d.path("errorSignature").asText().length()).isEqualTo(cap);
        assertThat(d.path("extractionRationale").asText().length()).isEqualTo(cap);
    }

    @Test
    @DisplayName("INV-4: null extractionRationale → serialized as JSON null, no crash")
    void nullRationaleGraceful() throws Exception {
        EvalScenarioEntity s = activeWithDetail("a1",
                "{\"errorSignature\":\"sig\"}", "task", null);
        when(scenarioRepository.findByAgentIdAndStatusAndSourceType(
                "7", "active", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED))
                .thenReturn(List.of(s));
        when(evalDatasetService.findDefaultVersionIdForAgent("7")).thenReturn("v-1");
        when(scenarioRepository.findAllByDatasetVersionId("v-1")).thenReturn(List.of(s));

        SkillResult r = tool.execute(Map.of("agentId", "7"), null);

        var d = objectMapper.readTree(r.getOutput()).path("failureDetails").get(0);
        assertThat(d.path("extractionRationale").isNull()).isTrue();
    }

    @Test
    @DisplayName("INV-4: empty harvest → failureDetails is [] (graceful, never null)")
    void emptyHarvestYieldsEmptyFailureDetails() throws Exception {
        when(scenarioRepository.findByAgentIdAndStatusAndSourceType(
                "7", "active", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED))
                .thenReturn(List.of());

        SkillResult r = tool.execute(Map.of("agentId", "7"), null);

        var node = objectMapper.readTree(r.getOutput());
        assertThat(node.path("failureDetails").isArray()).isTrue();
        assertThat(node.path("failureDetails").size()).isZero();
    }

    @Test
    @DisplayName("INV-4: unparseable / null oracle → errorSignature null, no crash")
    void unparseableOracleGracefulNull() throws Exception {
        EvalScenarioEntity bad = activeWithDetail("a1", "not-json", "task", "rat");
        EvalScenarioEntity noOracle = activeWithDetail("a2", null, "task2", "rat2");
        when(scenarioRepository.findByAgentIdAndStatusAndSourceType(
                "7", "active", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED))
                .thenReturn(List.of(bad, noOracle));
        when(evalDatasetService.findDefaultVersionIdForAgent("7")).thenReturn("v-1");
        when(scenarioRepository.findAllByDatasetVersionId("v-1")).thenReturn(List.of(bad, noOracle));

        SkillResult r = tool.execute(Map.of("agentId", "7"), null);

        assertThat(r.isSuccess()).isTrue();
        var node = objectMapper.readTree(r.getOutput());
        assertThat(node.path("failureDetails").get(0).path("errorSignature").isNull()).isTrue();
        assertThat(node.path("failureDetails").get(1).path("errorSignature").isNull()).isTrue();
    }
}
