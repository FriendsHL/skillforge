package com.skillforge.server.controller;

import com.skillforge.server.dto.EvalAnalysisCreateRequest;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.EvalAnalysisSessionService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EvalAnalysisController")
class EvalAnalysisControllerTest {

    @Mock
    private EvalAnalysisSessionService evalAnalysisSessionService;

    @Test
    @DisplayName("POST /api/eval/scenarios/{id}/analyze creates scenario_history analysis session")
    void analyzeScenario_createsSession() {
        EvalAnalysisController controller = new EvalAnalysisController(evalAnalysisSessionService);
        SessionEntity session = new SessionEntity();
        session.setId("analysis-1");
        when(evalAnalysisSessionService.createScenarioHistoryAnalysisSession(7L, 42L, "scenario-a"))
                .thenReturn(session);

        ResponseEntity<?> resp = controller.analyzeScenario("scenario-a", new EvalAnalysisCreateRequest(7L, 42L));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasFieldOrPropertyWithValue("sessionId", "analysis-1");
        assertThat(resp.getBody()).hasFieldOrPropertyWithValue("analysisType", "scenario_history");
        assertThat(resp.getBody()).hasFieldOrPropertyWithValue("scenarioId", "scenario-a");
    }

    @Test
    @DisplayName("GET /api/eval/scenarios/{id}/analysis-sessions returns mapped sessions")
    void listScenarioSessions_returnsMappedRows() {
        EvalAnalysisController controller = new EvalAnalysisController(evalAnalysisSessionService);
        SessionEntity session = new SessionEntity();
        session.setId("analysis-1");
        session.setAgentId(42L);
        session.setTitle("Analysis");
        session.setRuntimeStatus("idle");
        session.setMessageCount(5);
        session.setCreatedAt(java.time.LocalDateTime.parse("2026-05-06T00:00:00"));
        session.setUpdatedAt(java.time.LocalDateTime.parse("2026-05-06T08:00:00"));
        when(evalAnalysisSessionService.listScenarioAnalysisSessions("scenario-a", 7L))
                .thenReturn(List.of(session));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listScenarioAnalysisSessions("scenario-a", 7L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0)).containsEntry("id", "analysis-1");
        assertThat(resp.getBody().get(0)).containsEntry("agentId", 42L);
    }
}
