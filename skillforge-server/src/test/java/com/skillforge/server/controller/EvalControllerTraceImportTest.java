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
}
