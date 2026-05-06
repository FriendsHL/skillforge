package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.service.EvalScenarioVersionService;
import com.skillforge.server.service.TraceScenarioImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EvalController scenario versioning")
class EvalControllerScenarioVersionTest {

    @Mock private com.skillforge.server.eval.EvalOrchestrator evalOrchestrator;
    @Mock private com.skillforge.server.repository.EvalTaskRepository evalTaskRepository;
    @Mock private com.skillforge.server.repository.EvalSessionRepository evalSessionRepository;
    @Mock private ExecutorService executorService;
    @Mock private com.skillforge.server.eval.scenario.ScenarioLoader scenarioLoader;
    @Mock private com.skillforge.server.repository.EvalScenarioDraftRepository evalScenarioDraftRepository;
    @Mock private com.skillforge.server.eval.scenario.BaseScenarioService baseScenarioService;
    @Mock private EvalScenarioVersionService evalScenarioVersionService;
    @Mock private TraceScenarioImportService traceScenarioImportService;

    private EvalController controller;

    @BeforeEach
    void setUp() {
        controller = new EvalController(
                evalOrchestrator,
                evalTaskRepository,
                evalSessionRepository,
                new ObjectMapper(),
                executorService,
                scenarioLoader,
                evalScenarioDraftRepository,
                baseScenarioService,
                evalScenarioVersionService,
                traceScenarioImportService
        );
    }

    @Test
    @DisplayName("GET /api/eval/scenarios with agentId returns latest versions only")
    void listScenarios_returnsLatestVersionsOnly() {
        EvalScenarioEntity latest = scenario("scn-v2", "7", 2, "scn-v1", "Alpha", Instant.parse("2026-05-06T02:00:00Z"));
        when(evalScenarioVersionService.listLatestScenarios("7")).thenReturn(List.of(latest));

        ResponseEntity<List<Map<String, Object>>> response = controller.listScenarios("7");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0)).containsEntry("id", "scn-v2");
        assertThat(response.getBody().get(0)).containsEntry("version", 2);
        assertThat(response.getBody().get(0)).containsEntry("parentScenarioId", "scn-v1");
    }

    @Test
    @DisplayName("GET /api/eval/scenarios/{id}/versions returns version history")
    void listScenarioVersions_returnsVersionHistory() {
        EvalScenarioEntity latest = scenario("scn-v2", "7", 2, "scn-v1", "Alpha", Instant.parse("2026-05-06T02:00:00Z"));
        EvalScenarioEntity root = scenario("scn-v1", "7", 1, null, "Alpha", Instant.parse("2026-05-06T01:00:00Z"));
        when(evalScenarioVersionService.listVersions("scn-v2")).thenReturn(List.of(latest, root));

        ResponseEntity<List<Map<String, Object>>> response = controller.listScenarioVersions("scn-v2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(m -> m.get("id"))
                .containsExactly("scn-v2", "scn-v1");
    }

    @Test
    @DisplayName("POST /api/eval/scenarios/{id}/version creates next version")
    void createScenarioVersion_returnsCreatedVersion() {
        EvalScenarioEntity created = scenario("scn-v3", "7", 3, "scn-v2", "Alpha", Instant.parse("2026-05-06T03:00:00Z"));
        when(evalScenarioVersionService.createVersion("scn-v2", Map.of("oracleExpected", "new expected")))
                .thenReturn(created);

        ResponseEntity<Map<String, Object>> response = controller.createScenarioVersion(
                "scn-v2",
                Map.of("oracleExpected", "new expected")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("id", "scn-v3");
        assertThat(response.getBody()).containsEntry("version", 3);
        assertThat(response.getBody()).containsEntry("parentScenarioId", "scn-v2");
    }

    private static EvalScenarioEntity scenario(String id,
                                               String agentId,
                                               int version,
                                               String parentScenarioId,
                                               String name,
                                               Instant createdAt) {
        EvalScenarioEntity entity = new EvalScenarioEntity();
        entity.setId(id);
        entity.setAgentId(agentId);
        entity.setVersion(version);
        entity.setParentScenarioId(parentScenarioId);
        entity.setName(name);
        entity.setTask("task");
        entity.setOracleType("llm_judge");
        entity.setStatus("active");
        entity.setCreatedAt(createdAt);
        return entity;
    }
}
