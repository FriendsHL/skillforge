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
}
