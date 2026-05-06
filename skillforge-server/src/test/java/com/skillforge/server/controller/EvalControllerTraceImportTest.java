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
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EvalController trace import")
class EvalControllerTraceImportTest {

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
    @DisplayName("POST /api/eval/scenarios/from-trace imports a dataset scenario")
    void createScenarioFromTrace_returnsCreatedScenario() {
        EvalScenarioEntity created = new EvalScenarioEntity();
        created.setId("scn-trace-1");
        created.setAgentId("7");
        created.setName("Imported from trace");
        created.setTask("Fix the flaky eval");
        created.setOracleType("llm_judge");
        created.setOracleExpected("Expected answer");
        created.setStatus("active");
        created.setCategory("trace_import");
        created.setSplit("held_out");
        created.setSourceSessionId("sess-1");
        created.setExtractionRationale("Imported from trace root-1");
        created.setVersion(1);
        created.setCreatedAt(Instant.parse("2026-05-06T08:00:00Z"));

        when(traceScenarioImportService.importFromTrace(Map.of(
                "rootTraceId", "root-1",
                "name", "Imported from trace"
        ))).thenReturn(created);

        ResponseEntity<Map<String, Object>> response = controller.createScenarioFromTrace(Map.of(
                "rootTraceId", "root-1",
                "name", "Imported from trace"
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("id", "scn-trace-1");
        assertThat(response.getBody()).containsEntry("agentId", "7");
        assertThat(response.getBody()).containsEntry("category", "trace_import");
        assertThat(response.getBody()).containsEntry("sourceSessionId", "sess-1");
    }

    @Test
    @DisplayName("POST /api/eval/scenarios/from-trace returns 404 when root trace is missing")
    void createScenarioFromTrace_returnsNotFoundWhenTraceMissing() {
        when(traceScenarioImportService.importFromTrace(Map.of("rootTraceId", "missing-trace")))
                .thenThrow(new NoSuchElementException("Trace not found: missing-trace"));

        ResponseEntity<Map<String, Object>> response = controller.createScenarioFromTrace(
                Map.of("rootTraceId", "missing-trace")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "Trace not found: missing-trace");
    }

    @Test
    @DisplayName("GET /api/eval/traces/suggestions returns trace import candidates")
    void suggestTraceImportCandidates_returnsCandidates() {
        TraceScenarioImportService.TraceImportCandidate candidate =
                new TraceScenarioImportService.TraceImportCandidate(
                        "trace-1",
                        "trace-1",
                        "sess-1",
                        "7",
                        "Debug Agent",
                        "Debug failing tool",
                        "error",
                        2400,
                        3,
                        1,
                        List.of("agent_error", "tool_failure", "high_token"),
                        "2026-05-06T08:00:00Z"
                );
        when(traceScenarioImportService.suggestImportCandidates(Map.of(
                "minTokens", "2000",
                "limit", "20"
        ))).thenReturn(List.of(candidate));

        ResponseEntity<List<Map<String, Object>>> response =
                controller.suggestTraceImportCandidates("2000", null, null, null, "20");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0)).containsEntry("rootTraceId", "trace-1");
        assertThat(response.getBody().get(0)).containsEntry("preview", "Debug failing tool");
        assertThat(response.getBody().get(0)).containsEntry("reasonCodes", List.of("agent_error", "tool_failure", "high_token"));
    }

    @Test
    @DisplayName("POST /api/eval/scenarios/batch-import creates reviewable trace drafts")
    void batchImportTraceDrafts_returnsCreatedDrafts() {
        EvalScenarioEntity draft = new EvalScenarioEntity();
        draft.setId("draft-1");
        draft.setAgentId("7");
        draft.setName("Debug failing tool");
        draft.setTask("Debug failing tool");
        draft.setOracleType("llm_judge");
        draft.setStatus("draft");
        draft.setCategory("trace_import");
        draft.setSplit("held_out");
        draft.setSourceSessionId("sess-1");
        draft.setExtractionRationale("Candidate imported from trace trace-1");
        draft.setVersion(1);

        when(traceScenarioImportService.createDraftsFromTraces(Map.of("rootTraceIds", List.of("trace-1"))))
                .thenReturn(List.of(draft));

        ResponseEntity<Map<String, Object>> response = controller.batchImportTraceDrafts(
                Map.of("rootTraceIds", List.of("trace-1"))
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("count", 1);
        assertThat((List<?>) response.getBody().get("scenarios")).hasSize(1);
    }
}
