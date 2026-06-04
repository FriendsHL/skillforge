package com.skillforge.server.evolve;

import com.skillforge.server.entity.EvalDatasetEntity;
import com.skillforge.server.entity.EvalDatasetVersionEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.evolve.dto.ActivateScenarioResponse;
import com.skillforge.server.evolve.dto.HarvestedScenarioDto;
import com.skillforge.server.repository.EvalDatasetRepository;
import com.skillforge.server.repository.EvalDatasetVersionRepository;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.service.EvalDatasetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BC-M2b: {@link HarvestedScenarioService} — activate flow (Option A′ agent-scoped
 * mixed dataset), guards, list, and harvest orchestration.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HarvestedScenarioService")
class HarvestedScenarioServiceTest {

    @Mock private EvalScenarioDraftRepository scenarioRepository;
    @Mock private EvalDatasetService evalDatasetService;
    @Mock private EvalDatasetRepository datasetRepository;
    @Mock private EvalDatasetVersionRepository versionRepository;
    @Mock private BadCaseClusterService clusterService;
    @Mock private BadCaseHarvestService harvestService;

    private HarvestedScenarioService service;

    private static final Long USER = 42L;

    @BeforeEach
    void setUp() {
        service = new HarvestedScenarioService(scenarioRepository, evalDatasetService,
                datasetRepository, versionRepository, clusterService, harvestService);
    }

    private EvalScenarioEntity draft(String id, String agentId) {
        EvalScenarioEntity s = new EvalScenarioEntity();
        s.setId(id);
        s.setAgentId(agentId);
        s.setName("badcase-" + id);
        s.setStatus("draft");
        s.setSourceType(EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED);
        return s;
    }

    private EvalScenarioEntity scenario(String id) {
        EvalScenarioEntity s = new EvalScenarioEntity();
        s.setId(id);
        return s;
    }

    private EvalDatasetVersionEntity version(String id, int number) {
        EvalDatasetVersionEntity v = new EvalDatasetVersionEntity();
        v.setId(id);
        v.setVersionNumber(number);
        return v;
    }

    @Test
    @DisplayName("activate (first time): seeds agent mixed dataset from benchmark + appends harvested case")
    void activate_firstTime_seedsAgentDataset() {
        EvalScenarioEntity sc = draft("bad-1", "7");
        when(scenarioRepository.findById("bad-1")).thenReturn(Optional.of(sc));
        // No agent-specific mixed dataset yet (targeted query returns none).
        when(datasetRepository.findByAgentId("7")).thenReturn(List.of());
        // Current resolved default = global benchmark version with 2 members.
        when(evalDatasetService.findDefaultVersionIdForAgent("7")).thenReturn("gv-1");
        when(evalDatasetService.getScenariosForVersion("gv-1"))
                .thenReturn(List.of(scenario("b1"), scenario("b2")));
        EvalDatasetEntity agentDataset = new EvalDatasetEntity();
        agentDataset.setId("ds-7"); agentDataset.setAgentId("7"); agentDataset.setName("7-mixed");
        when(evalDatasetService.createDataset(any())).thenReturn(agentDataset);
        when(evalDatasetService.publishVersion(eq("ds-7"), anyList(), eq(USER)))
                .thenReturn(version("v-1", 1));
        when(scenarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActivateScenarioResponse resp = service.activate("bad-1", USER);

        // KEYSTONE: the seeded dataset must be agent-specific (agent_id=7) AND its
        // name must contain "mixed" — those are exactly what
        // EvalDatasetService.findDefaultVersionIdForAgent prefers (agent-specific
        // *mixed* wins, MAX version), so the published version auto-becomes agent
        // 7's resolved default. Without both, resolveRoleSplit would never see the
        // harvested case as a member.
        ArgumentCaptor<EvalDatasetService.CreateDatasetRequest> req =
                ArgumentCaptor.forClass(EvalDatasetService.CreateDatasetRequest.class);
        verify(evalDatasetService).createDataset(req.capture());
        assertThat(req.getValue().agentId()).isEqualTo("7");
        assertThat(req.getValue().name().toLowerCase()).contains("mixed");
        assertThat(req.getValue().ownerId()).isEqualTo(USER);

        ArgumentCaptor<List<String>> members = ArgumentCaptor.forClass(List.class);
        verify(evalDatasetService).publishVersion(eq("ds-7"), members.capture(), eq(USER));
        assertThat(members.getValue()).containsExactly("b1", "b2", "bad-1");

        assertThat(resp.scenarioId()).isEqualTo("bad-1");
        assertThat(resp.status()).isEqualTo("active");
        assertThat(resp.agentId()).isEqualTo("7");
        assertThat(resp.datasetVersionId()).isEqualTo("v-1");
        assertThat(resp.datasetVersionNumber()).isEqualTo(1);
        assertThat(resp.datasetScenarioCount()).isEqualTo(3);
        assertThat(resp.reviewedAt()).isNotNull();
        assertThat(sc.getStatus()).isEqualTo("active");
        assertThat(sc.getReviewedAt()).isNotNull();
    }

    @Test
    @DisplayName("activate (reuse): publishes a new version on the existing agent mixed dataset")
    void activate_reuseAgentDataset_publishesNewVersion() {
        EvalScenarioEntity sc = draft("bad-2", "7");
        when(scenarioRepository.findById("bad-2")).thenReturn(Optional.of(sc));
        EvalDatasetEntity agentDataset = new EvalDatasetEntity();
        agentDataset.setId("ds-7"); agentDataset.setAgentId("7"); agentDataset.setName("7-mixed");
        when(datasetRepository.findByAgentId("7")).thenReturn(List.of(agentDataset));
        when(versionRepository.findMaxVersionNumber("ds-7")).thenReturn(Optional.of(2));
        when(versionRepository.findByDatasetIdAndVersionNumber("ds-7", 2))
                .thenReturn(Optional.of(version("v-2", 2)));
        when(evalDatasetService.getScenariosForVersion("v-2"))
                .thenReturn(List.of(scenario("b1"), scenario("b2"), scenario("prevBad")));
        when(evalDatasetService.publishVersion(eq("ds-7"), anyList(), eq(USER)))
                .thenReturn(version("v-3", 3));
        when(scenarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ActivateScenarioResponse resp = service.activate("bad-2", USER);

        ArgumentCaptor<List<String>> members = ArgumentCaptor.forClass(List.class);
        verify(evalDatasetService).publishVersion(eq("ds-7"), members.capture(), eq(USER));
        assertThat(members.getValue()).containsExactly("b1", "b2", "prevBad", "bad-2");
        // existing dataset reused — no new dataset created
        verify(evalDatasetService, never()).createDataset(any());
        assertThat(resp.datasetVersionNumber()).isEqualTo(3);
        assertThat(resp.datasetScenarioCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("activate: not found → ScenarioNotFoundException")
    void activate_notFound_throws() {
        when(scenarioRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activate("nope", USER))
                .isInstanceOf(HarvestedScenarioService.ScenarioNotFoundException.class);
    }

    @Test
    @DisplayName("activate: already active (not draft) → IllegalStateException (409)")
    void activate_notDraft_throws() {
        EvalScenarioEntity sc = draft("bad-3", "7");
        sc.setStatus("active");
        when(scenarioRepository.findById("bad-3")).thenReturn(Optional.of(sc));
        assertThatThrownBy(() -> service.activate("bad-3", USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a draft");
        verify(evalDatasetService, never()).publishVersion(any(), any(), any());
    }

    @Test
    @DisplayName("activate: wrong source_type → IllegalArgumentException (400)")
    void activate_wrongSourceType_throws() {
        EvalScenarioEntity sc = draft("bad-4", "7");
        sc.setSourceType(EvalScenarioEntity.SOURCE_TYPE_BENCHMARK);
        when(scenarioRepository.findById("bad-4")).thenReturn(Optional.of(sc));
        assertThatThrownBy(() -> service.activate("bad-4", USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session_derived");
    }

    @Test
    @DisplayName("activate: missing agentId → IllegalArgumentException (400)")
    void activate_missingAgentId_throws() {
        EvalScenarioEntity sc = draft("bad-5", null);
        when(scenarioRepository.findById("bad-5")).thenReturn(Optional.of(sc));
        assertThatThrownBy(() -> service.activate("bad-5", USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
    }

    @Test
    @DisplayName("listHarvestedScenarios: maps rows to DTOs, restricted to session_derived")
    void list_mapsRows() {
        EvalScenarioEntity sc = draft("bad-6", "7");
        sc.setSourceRef("session:s-1");
        when(scenarioRepository.findByAgentIdAndStatusAndSourceType(
                "7", "draft", EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED))
                .thenReturn(List.of(sc));

        List<HarvestedScenarioDto> dtos = service.listHarvestedScenarios("7", "draft");

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).id()).isEqualTo("bad-6");
        assertThat(dtos.get(0).sourceRef()).isEqualTo("session:s-1");
    }

    @Test
    @DisplayName("harvestBadCases: one draft per harvestable cluster; unsupported clusters skipped")
    void harvest_collectsCreatedDraftIds() {
        when(clusterService.representativeSpans(7L, 30)).thenReturn(List.of(
                new BadCaseClusterService.RepresentativeSpan("s1", "span1", "Edit", 3),
                new BadCaseClusterService.RepresentativeSpan("s2", "span2", "Bash", 1)));
        when(harvestService.harvestToolFailureCase("s1", "span1"))
                .thenReturn(Optional.of(scenario("d1")));
        // unsupported tool cluster → harvest returns empty → skipped
        when(harvestService.harvestToolFailureCase("s2", "span2")).thenReturn(Optional.empty());

        List<String> created = service.harvestBadCases(7L, 30);

        assertThat(created).containsExactly("d1");
    }
}
