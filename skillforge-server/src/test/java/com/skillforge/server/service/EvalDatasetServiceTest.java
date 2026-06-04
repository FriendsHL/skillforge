package com.skillforge.server.service;

import com.skillforge.server.entity.EvalDatasetEntity;
import com.skillforge.server.entity.EvalDatasetVersionEntity;
import com.skillforge.server.entity.EvalDatasetVersionScenarioEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.repository.EvalDatasetRepository;
import com.skillforge.server.repository.EvalDatasetVersionRepository;
import com.skillforge.server.repository.EvalDatasetVersionScenarioRepository;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EVAL-DATASET-LAYER V1: unit tests for {@link EvalDatasetService}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>★ r4 W4 fix ★ — publishVersion(emptyScenarioIds) → IllegalArgumentException</li>
 *   <li>publishVersion happy path — version_number auto-increment + hash computation</li>
 *   <li>computeCompositionStats — source_type/purpose breakdown</li>
 *   <li>assessHealth — composition policy warnings (UC-4)</li>
 *   <li>computeCompositionHash — deterministic SHA256 regardless of input order</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvalDatasetService")
class EvalDatasetServiceTest {

    @Mock private EvalDatasetRepository datasetRepository;
    @Mock private EvalDatasetVersionRepository versionRepository;
    @Mock private EvalDatasetVersionScenarioRepository bridgeRepository;
    @Mock private EvalScenarioDraftRepository scenarioRepository;

    private EvalDatasetService service;

    @BeforeEach
    void setUp() {
        service = new EvalDatasetService(datasetRepository, versionRepository,
                bridgeRepository, scenarioRepository, new StaticSourceTypeHeuristic());
    }

    // ─────────────────────────────────────────────────────────────────────
    // publishVersion
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ r4 W4 fix ★ — publishVersion with empty scenarioIds throws")
    void publishVersionEmptyListThrows() {
        assertThatThrownBy(() -> service.publishVersion("ds-1", List.of(), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        // No DB writes should happen.
        verify(versionRepository, never()).save(any());
        verify(bridgeRepository, never()).save(any());
    }

    @Test
    @DisplayName("publishVersion with null scenarioIds throws")
    void publishVersionNullListThrows() {
        assertThatThrownBy(() -> service.publishVersion("ds-1", null, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("publishVersion happy path: writes version + bridge rows; hash is set")
    void publishVersionHappyPath() {
        // Arrange — existing dataset, no prior version.
        EvalDatasetEntity dataset = newDataset("ds-1");
        when(datasetRepository.findById("ds-1")).thenReturn(Optional.of(dataset));

        List<String> scenarioIds = List.of("s2", "s1", "s3"); // unordered intentionally
        when(scenarioRepository.findAllById(scenarioIds)).thenReturn(List.of(
                scenarioOf("s1", EvalScenarioEntity.SOURCE_TYPE_BENCHMARK,
                        EvalScenarioEntity.PURPOSE_BASELINE_ANCHOR),
                scenarioOf("s2", EvalScenarioEntity.SOURCE_TYPE_BENCHMARK,
                        EvalScenarioEntity.PURPOSE_BASELINE_ANCHOR),
                scenarioOf("s3", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED,
                        EvalScenarioEntity.PURPOSE_REGRESSION)));
        when(versionRepository.findMaxVersionNumber("ds-1")).thenReturn(Optional.empty());
        when(versionRepository.save(any(EvalDatasetVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Act.
        EvalDatasetVersionEntity result = service.publishVersion("ds-1", scenarioIds, 99L);

        // Assert.
        assertThat(result.getDatasetId()).isEqualTo("ds-1");
        assertThat(result.getVersionNumber()).isEqualTo(1);
        assertThat(result.getCreatedBy()).isEqualTo(99L);
        assertThat(result.getCompositionHash())
                .as("composition_hash must be 64-char SHA256 hex")
                .hasSize(64);
        Map<String, Object> stats = result.getCompositionStats();
        assertThat(stats).containsEntry("benchmark", 2)
                         .containsEntry("session_derived", 1)
                         .containsEntry("total", 3);
        // Bridge rows are persisted once per scenario id.
        verify(bridgeRepository, org.mockito.Mockito.times(3))
                .save(any(EvalDatasetVersionScenarioEntity.class));
    }

    @Test
    @DisplayName("publishVersion auto-increments version_number when prior versions exist")
    void publishVersionIncrementsVersionNumber() {
        EvalDatasetEntity dataset = newDataset("ds-1");
        when(datasetRepository.findById("ds-1")).thenReturn(Optional.of(dataset));
        when(scenarioRepository.findAllById(anyList())).thenReturn(List.of(
                scenarioOf("s1", EvalScenarioEntity.SOURCE_TYPE_BENCHMARK,
                        EvalScenarioEntity.PURPOSE_BASELINE_ANCHOR)));
        when(versionRepository.findMaxVersionNumber("ds-1")).thenReturn(Optional.of(3));
        when(versionRepository.save(any(EvalDatasetVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EvalDatasetVersionEntity result = service.publishVersion("ds-1", List.of("s1"), 1L);
        assertThat(result.getVersionNumber()).isEqualTo(4);
    }

    @Test
    @DisplayName("publishVersion with bogus scenarioId throws (fail-fast)")
    void publishVersionBogusScenarioIdThrows() {
        EvalDatasetEntity dataset = newDataset("ds-1");
        when(datasetRepository.findById("ds-1")).thenReturn(Optional.of(dataset));
        // Only one of two requested scenarios exists.
        when(scenarioRepository.findAllById(List.of("s1", "s-bogus")))
                .thenReturn(List.of(scenarioOf("s1",
                        EvalScenarioEntity.SOURCE_TYPE_BENCHMARK,
                        EvalScenarioEntity.PURPOSE_BASELINE_ANCHOR)));

        assertThatThrownBy(() -> service.publishVersion("ds-1", List.of("s1", "s-bogus"), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("s-bogus");
        verify(versionRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // computeCompositionHash — deterministic regardless of input order
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("computeCompositionHash is sorted-deterministic")
    void compositionHashSortedDeterministic() {
        String h1 = EvalDatasetService.computeCompositionHash(List.of("a", "b", "c"));
        String h2 = EvalDatasetService.computeCompositionHash(List.of("c", "b", "a"));
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("computeCompositionHash differs for different sets")
    void compositionHashDiffersForDifferentSets() {
        String h1 = EvalDatasetService.computeCompositionHash(List.of("a", "b"));
        String h2 = EvalDatasetService.computeCompositionHash(List.of("a", "b", "c"));
        assertThat(h1).isNotEqualTo(h2);
    }

    // ─────────────────────────────────────────────────────────────────────
    // assessHealth (composition policy UC-4)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("assessHealth: 100% benchmark + ≥5 scenarios → healthy")
    void healthHealthy() {
        String versionId = "v-1";
        when(bridgeRepository.findScenarioIdsByDatasetVersionId(versionId))
                .thenReturn(List.of("s1", "s2", "s3", "s4", "s5"));
        when(scenarioRepository.findAllById(List.of("s1", "s2", "s3", "s4", "s5")))
                .thenReturn(List.of(
                        scenarioOf("s1", EvalScenarioEntity.SOURCE_TYPE_BENCHMARK, EvalScenarioEntity.PURPOSE_BASELINE_ANCHOR),
                        scenarioOf("s2", EvalScenarioEntity.SOURCE_TYPE_BENCHMARK, EvalScenarioEntity.PURPOSE_BASELINE_ANCHOR),
                        scenarioOf("s3", EvalScenarioEntity.SOURCE_TYPE_BENCHMARK, EvalScenarioEntity.PURPOSE_BASELINE_ANCHOR),
                        scenarioOf("s4", EvalScenarioEntity.SOURCE_TYPE_BENCHMARK, EvalScenarioEntity.PURPOSE_BASELINE_ANCHOR),
                        scenarioOf("s5", EvalScenarioEntity.SOURCE_TYPE_BENCHMARK, EvalScenarioEntity.PURPOSE_BASELINE_ANCHOR)));

        EvalDatasetService.DatasetHealthAssessment h = service.assessHealth(versionId);
        assertThat(h.isHealthy()).isTrue();
        assertThat(h.warnings()).isEmpty();
    }

    @Test
    @DisplayName("assessHealth: 100% session_derived → warns about likely 0% baseline (UC-4)")
    void healthAllSessionDerivedWarns() {
        String versionId = "v-2";
        when(bridgeRepository.findScenarioIdsByDatasetVersionId(versionId))
                .thenReturn(List.of("s1", "s2", "s3", "s4", "s5", "s6"));
        when(scenarioRepository.findAllById(List.of("s1", "s2", "s3", "s4", "s5", "s6")))
                .thenReturn(List.of(
                        scenarioOf("s1", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED, EvalScenarioEntity.PURPOSE_REGRESSION),
                        scenarioOf("s2", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED, EvalScenarioEntity.PURPOSE_REGRESSION),
                        scenarioOf("s3", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED, EvalScenarioEntity.PURPOSE_REGRESSION),
                        scenarioOf("s4", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED, EvalScenarioEntity.PURPOSE_REGRESSION),
                        scenarioOf("s5", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED, EvalScenarioEntity.PURPOSE_REGRESSION),
                        scenarioOf("s6", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED, EvalScenarioEntity.PURPOSE_REGRESSION)));

        EvalDatasetService.DatasetHealthAssessment h = service.assessHealth(versionId);
        assertThat(h.isHealthy()).isFalse();
        assertThat(h.warnings()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(h.warnings().toString()).containsIgnoringCase("benchmark");
    }

    @Test
    @DisplayName("assessHealth: empty version → warns no signal + unhealthy")
    void healthEmptyVersion() {
        String versionId = "v-empty";
        when(bridgeRepository.findScenarioIdsByDatasetVersionId(versionId))
                .thenReturn(List.of());

        EvalDatasetService.DatasetHealthAssessment h = service.assessHealth(versionId);
        assertThat(h.isHealthy()).isFalse();
        assertThat(h.warnings().toString()).containsIgnoringCase("no scenarios");
    }

    // ─────────────────────────────────────────────────────────────────────
    // findDefaultVersionIdForAgent — resolver preference (BC-M2b 子轮2 keystone)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BC-M2b keystone: agent-specific *mixed* dataset wins over global *mixed* even when global has a HIGHER version number")
    void findDefaultVersionIdForAgent_agentSpecificMixedWinsOverGlobal() {
        // The whole harvest→activate→target chain rests on this: after activate
        // publishes a new version of the agent's <agentId>-mixed dataset, a later
        // A/B (which calls findDefaultVersionIdForAgent when no explicit
        // datasetVersionId is given) must resolve to THAT agent-specific version —
        // not the global main-assistant-mixed-v1 — even though the global dataset
        // may carry a higher version_number. This directly guards the "two
        // MAX-version mixed datasets" ambiguity design review flagged (W1).
        EvalDatasetEntity globalMixed = new EvalDatasetEntity();
        globalMixed.setId("global-mixed");
        globalMixed.setName("main-assistant-mixed-v1");
        globalMixed.setAgentId(null); // global (agent_id NULL)

        EvalDatasetEntity agentMixed = new EvalDatasetEntity();
        agentMixed.setId("agent7-mixed");
        agentMixed.setName("7-mixed");
        agentMixed.setAgentId("7"); // agent-specific

        when(datasetRepository.findAll()).thenReturn(List.of(globalMixed, agentMixed));
        // Agent dataset carries a LOWER version number than the global — it must
        // STILL win (the resolver preference is agent-specificity, not the max
        // version across datasets). Global's version lookups are deliberately NOT
        // stubbed: the resolver short-circuits on the agent dataset, so strict
        // stubbing would flag them — proving global is never consulted.
        when(versionRepository.findMaxVersionNumber("agent7-mixed")).thenReturn(Optional.of(1));
        EvalDatasetVersionEntity agentV1 = new EvalDatasetVersionEntity();
        agentV1.setId("agent7-mixed-v1");
        when(versionRepository.findByDatasetIdAndVersionNumber("agent7-mixed", 1))
                .thenReturn(Optional.of(agentV1));

        assertThat(service.findDefaultVersionIdForAgent("7")).isEqualTo("agent7-mixed-v1");
    }

    @Test
    @DisplayName("BC-M2b: with only the global *mixed* dataset (pre-activation seed state), the global version resolves")
    void findDefaultVersionIdForAgent_onlyGlobal_resolvesGlobal() {
        // Matches the real seed state before any agent has activated a harvest:
        // only main-assistant-mixed-v1 (agent_id NULL) exists, so it is the
        // fallback default for every agent.
        EvalDatasetEntity globalMixed = new EvalDatasetEntity();
        globalMixed.setId("global-mixed");
        globalMixed.setName("main-assistant-mixed-v1");
        globalMixed.setAgentId(null);
        when(datasetRepository.findAll()).thenReturn(List.of(globalMixed));
        when(versionRepository.findMaxVersionNumber("global-mixed")).thenReturn(Optional.of(1));
        EvalDatasetVersionEntity gv1 = new EvalDatasetVersionEntity();
        gv1.setId("global-mixed-v1");
        when(versionRepository.findByDatasetIdAndVersionNumber("global-mixed", 1))
                .thenReturn(Optional.of(gv1));

        assertThat(service.findDefaultVersionIdForAgent("7")).isEqualTo("global-mixed-v1");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static EvalDatasetEntity newDataset(String id) {
        EvalDatasetEntity d = new EvalDatasetEntity();
        d.setId(id);
        d.setOwnerId(1L);
        d.setName("test");
        return d;
    }

    private static EvalScenarioEntity scenarioOf(String id, String sourceType, String purpose) {
        EvalScenarioEntity s = new EvalScenarioEntity();
        s.setId(id);
        s.setSourceType(sourceType);
        s.setPurpose(purpose);
        return s;
    }
}
