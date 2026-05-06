package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.dto.EvalAnalysisCreateRequest;
import com.skillforge.server.entity.EvalAnalysisSessionEntity;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.EvalTaskItemEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.eval.EvalOrchestrator;
import com.skillforge.server.repository.EvalTaskItemRepository;
import com.skillforge.server.repository.EvalTaskRepository;
import com.skillforge.server.service.EvalAnalysisSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EVAL-V2 M3a (b2): contract test for {@link EvalTaskController} happy paths
 * (5 endpoints) + the 400/404 boundary cases that protect FE callers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EvalTaskController")
class EvalTaskControllerTest {

    @Mock private EvalOrchestrator evalOrchestrator;
    @Mock private EvalTaskRepository evalTaskRepository;
    @Mock private EvalTaskItemRepository evalTaskItemRepository;
    @Mock private ExecutorService evalOrchestratorExecutor;
    @Mock private EvalAnalysisSessionService evalAnalysisSessionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EvalTaskController controller;

    @BeforeEach
    void setUp() {
        controller = new EvalTaskController(
                evalOrchestrator, evalTaskRepository, evalTaskItemRepository,
                evalOrchestratorExecutor, objectMapper, evalAnalysisSessionService);
        when(evalTaskRepository.save(any(EvalTaskEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("POST /api/eval/tasks: missing agentId → 400")
    void create_missingAgentId_400() {
        ResponseEntity<Map<String, Object>> resp = controller.createTask(Map.of());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).extractingByKey("error").asString().contains("agentId");
    }

    @Test
    @DisplayName("POST /api/eval/tasks: non-numeric agentId → 400")
    void create_nonNumericAgentId_400() {
        Map<String, Object> body = Map.of("agentId", "not-a-number");
        ResponseEntity<Map<String, Object>> resp = controller.createTask(body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/eval/tasks: valid agentId → 202 + persists PENDING + dispatches")
    void create_valid_acceptedAndDispatches() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", "123");
        body.put("userId", 7);
        body.put("datasetFilter", Map.of("split", "held_out"));

        ResponseEntity<Map<String, Object>> resp = controller.createTask(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).containsKey("taskId");
        assertThat(resp.getBody()).extractingByKey("status").isEqualTo("PENDING");

        ArgumentCaptor<EvalTaskEntity> cap = ArgumentCaptor.forClass(EvalTaskEntity.class);
        verify(evalTaskRepository).save(cap.capture());
        EvalTaskEntity saved = cap.getValue();
        assertThat(saved.getAgentDefinitionId()).isEqualTo("123");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getTriggeredByUserId()).isEqualTo(7L);
        assertThat(saved.getDatasetFilter()).contains("\"split\"").contains("\"held_out\"");

        verify(evalOrchestratorExecutor, times(1)).submit(any(Runnable.class));
    }

    @Test
    @DisplayName("GET /api/eval/tasks: lists tasks newest-first when no filter")
    void list_noFilter_returnsAllSortedDesc() {
        EvalTaskEntity older = makeTask("t-older", "10", "COMPLETED", Instant.now().minusSeconds(60));
        EvalTaskEntity newer = makeTask("t-newer", "10", "RUNNING", Instant.now());
        when(evalTaskRepository.findAll()).thenReturn(new ArrayList<>(List.of(older, newer)));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listTasks(null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(2);
        assertThat(resp.getBody().get(0).get("id")).isEqualTo("t-newer");
        assertThat(resp.getBody().get(1).get("id")).isEqualTo("t-older");
    }

    @Test
    @DisplayName("GET /api/eval/tasks?agentId=10&status=COMPLETED: routes to filtered repo method")
    void list_withFilters_callsFilteredQuery() {
        when(evalTaskRepository.findByAgentDefinitionIdAndStatusOrderByStartedAtDesc("10", "COMPLETED"))
                .thenReturn(List.of(makeTask("t-1", "10", "COMPLETED", Instant.now())));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listTasks("10", "COMPLETED");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        verify(evalTaskRepository).findByAgentDefinitionIdAndStatusOrderByStartedAtDesc("10", "COMPLETED");
    }

    @Test
    @DisplayName("GET /api/eval/tasks/{id}: 404 when missing")
    void getOne_missing_404() {
        when(evalTaskRepository.findById("nope")).thenReturn(Optional.empty());
        ResponseEntity<Map<String, Object>> resp = controller.getTask("nope");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /api/eval/tasks/{id}: 200 with detail + itemCount")
    void getOne_present_returnsDetail() {
        EvalTaskEntity task = makeTask("t-1", "10", "COMPLETED", Instant.now());
        task.setDatasetFilter("{\"split\":\"held_out\"}");
        task.setAttributionSummary("foo");
        task.setImprovementSuggestion("bar");
        when(evalTaskRepository.findById("t-1")).thenReturn(Optional.of(task));
        when(evalTaskItemRepository.countByTaskId("t-1")).thenReturn(3L);

        ResponseEntity<Map<String, Object>> resp = controller.getTask("t-1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("id")).isEqualTo("t-1");
        assertThat(resp.getBody().get("itemCount")).isEqualTo(3L);
        assertThat(resp.getBody().get("datasetFilter")).isEqualTo("{\"split\":\"held_out\"}");
        assertThat(resp.getBody().get("attributionSummary")).isEqualTo("foo");
        assertThat(resp.getBody().get("improvementSuggestion")).isEqualTo("bar");
    }

    @Test
    @DisplayName("GET /api/eval/tasks/{id}/items: 404 when task missing")
    void listItems_missingTask_404() {
        when(evalTaskRepository.existsById("nope")).thenReturn(false);
        ResponseEntity<List<Map<String, Object>>> resp = controller.listItems("nope");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /api/eval/tasks/{id}/items: 200 with serialized rows")
    void listItems_present_returnsRows() {
        when(evalTaskRepository.existsById("t-1")).thenReturn(true);
        when(evalTaskItemRepository.findByTaskIdOrderByCreatedAtAsc("t-1"))
                .thenReturn(List.of(makeItem(1L, "t-1", "scenario-A", "PASS")));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listItems("t-1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).get("scenarioId")).isEqualTo("scenario-A");
        assertThat(resp.getBody().get(0).get("status")).isEqualTo("PASS");
    }

    @Test
    @DisplayName("GET /api/eval/tasks/{id}/items/{itemId}: returns the item when task id matches")
    void getItem_taskMatch_returnsItem() {
        EvalTaskItemEntity item = makeItem(99L, "t-1", "scenario-A", "PASS");
        when(evalTaskItemRepository.findById(99L)).thenReturn(Optional.of(item));

        ResponseEntity<Map<String, Object>> resp = controller.getItem("t-1", 99L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("id")).isEqualTo(99L);
    }

    @Test
    @DisplayName("POST /api/eval/tasks/compare: returns aligned rows for shared scenarios")
    void compareTasks_returnsIntersectionRows() {
        EvalTaskEntity left = makeTask("t-1", "10", "COMPLETED", Instant.now());
        left.setCompositeAvg(new BigDecimal("81.00"));
        left.setPassCount(2);
        left.setFailCount(1);
        EvalTaskEntity right = makeTask("t-2", "10", "COMPLETED", Instant.now().minusSeconds(30));
        right.setCompositeAvg(new BigDecimal("67.00"));
        right.setPassCount(1);
        right.setFailCount(2);
        when(evalTaskRepository.findById("t-1")).thenReturn(Optional.of(left));
        when(evalTaskRepository.findById("t-2")).thenReturn(Optional.of(right));

        EvalTaskItemEntity leftShared = makeItem(1L, "t-1", "scenario-A", "PASS");
        leftShared.setAgentFinalOutput("left output");
        EvalTaskItemEntity leftOnly = makeItem(2L, "t-1", "scenario-B", "FAIL");
        EvalTaskItemEntity rightShared = makeItem(3L, "t-2", "scenario-A", "FAIL");
        rightShared.setCompositeScore(new BigDecimal("61.00"));
        rightShared.setAgentFinalOutput("right output");
        EvalTaskItemEntity rightOnly = makeItem(4L, "t-2", "scenario-C", "PASS");
        when(evalTaskItemRepository.findByTaskIdOrderByCreatedAtAsc("t-1"))
                .thenReturn(List.of(leftShared, leftOnly));
        when(evalTaskItemRepository.findByTaskIdOrderByCreatedAtAsc("t-2"))
                .thenReturn(List.of(rightShared, rightOnly));

        ResponseEntity<Map<String, Object>> resp = controller.compareTasks("t-1,t-2");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("scenarioCount", 1);
        assertThat(resp.getBody()).containsEntry("taskCount", 2);
        assertThat((List<?>) resp.getBody().get("tasks")).hasSize(2);
        assertThat((List<?>) resp.getBody().get("rows")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) ((List<?>) resp.getBody().get("rows")).get(0);
        assertThat(row).containsEntry("scenarioId", "scenario-A");
        assertThat(row).containsEntry("outputDiffers", true);
        assertThat(row).containsEntry("scoreDelta", 14.0);
    }

    @Test
    @DisplayName("POST /api/eval/tasks/compare: 400 when fewer than 2 task ids")
    void compareTasks_requiresTwoIds() {
        ResponseEntity<Map<String, Object>> resp = controller.compareTasks("t-1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("error", "compare requires exactly 2 task ids");
    }

    @Test
    @DisplayName("GET /api/eval/tasks/{id}/analysis-sessions returns linked analysis sessions")
    void listTaskAnalysisSessions_returnsRows() {
        when(evalTaskRepository.existsById("t-1")).thenReturn(true);
        SessionEntity session = new SessionEntity();
        session.setId("analysis-1");
        EvalAnalysisSessionEntity link = new EvalAnalysisSessionEntity();
        link.setAnalysisType(EvalAnalysisSessionEntity.TYPE_RUN_CASE);
        link.setTaskId("t-1");
        link.setTaskItemId(99L);
        link.setScenarioId("scenario-A");
        when(evalAnalysisSessionService.listTaskAnalysisSessions("t-1", 7L))
                .thenReturn(List.of(new EvalAnalysisSessionService.TaskAnalysisSessionView(link, session)));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listTaskAnalysisSessions("t-1", 7L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0)).containsEntry("sessionId", "analysis-1");
        assertThat(resp.getBody().get(0)).containsEntry("analysisType", "run_case");
        assertThat(resp.getBody().get(0)).containsEntry("itemId", 99L);
    }

    @Test
    @DisplayName("GET /api/eval/tasks/{id}/items/{itemId}: 404 when item belongs to a different task")
    void getItem_taskMismatch_404() {
        EvalTaskItemEntity item = makeItem(99L, "t-OTHER", "scenario-A", "PASS");
        when(evalTaskItemRepository.findById(99L)).thenReturn(Optional.of(item));

        ResponseEntity<Map<String, Object>> resp = controller.getItem("t-1", 99L);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /api/eval/tasks/{id}/analyze: 404 when task missing")
    void analyzeTask_missing_404() {
        when(evalTaskRepository.findById("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.analyzeTask("missing", new EvalAnalysisCreateRequest(7L, 42L));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /api/eval/tasks/{id}/analyze: creates run_overall analysis session")
    void analyzeTask_createsSession() {
        EvalTaskEntity task = makeTask("t-1", "10", "COMPLETED", Instant.now());
        when(evalTaskRepository.findById("t-1")).thenReturn(Optional.of(task));
        SessionEntity session = new SessionEntity();
        session.setId("analysis-1");
        when(evalAnalysisSessionService.createTaskOverallAnalysisSession(7L, 42L, "t-1")).thenReturn(session);

        ResponseEntity<?> resp = controller.analyzeTask("t-1", new EvalAnalysisCreateRequest(7L, 42L));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasFieldOrPropertyWithValue("sessionId", "analysis-1");
        assertThat(resp.getBody()).hasFieldOrPropertyWithValue("analysisType", "run_overall");
    }

    @Test
    @DisplayName("POST /api/eval/tasks/{id}/items/{itemId}/analyze: creates run_case analysis session")
    void analyzeItem_createsSession() {
        EvalTaskEntity task = makeTask("t-1", "10", "COMPLETED", Instant.now());
        EvalTaskItemEntity item = makeItem(99L, "t-1", "scenario-A", "FAIL");
        when(evalTaskRepository.findById("t-1")).thenReturn(Optional.of(task));
        when(evalTaskItemRepository.findById(99L)).thenReturn(Optional.of(item));
        SessionEntity session = new SessionEntity();
        session.setId("analysis-2");
        when(evalAnalysisSessionService.createTaskItemAnalysisSession(7L, 42L, "t-1", 99L, "scenario-A"))
                .thenReturn(session);

        ResponseEntity<?> resp = controller.analyzeItem("t-1", 99L, new EvalAnalysisCreateRequest(7L, 42L));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasFieldOrPropertyWithValue("sessionId", "analysis-2");
        assertThat(resp.getBody()).hasFieldOrPropertyWithValue("analysisType", "run_case");
        assertThat(resp.getBody()).hasFieldOrPropertyWithValue("scenarioId", "scenario-A");
    }

    private static EvalTaskEntity makeTask(String id, String agentId, String status, Instant startedAt) {
        EvalTaskEntity t = new EvalTaskEntity();
        t.setId(id);
        t.setAgentDefinitionId(agentId);
        t.setStatus(status);
        t.setStartedAt(startedAt);
        return t;
    }

    private static EvalTaskItemEntity makeItem(Long id, String taskId, String scenarioId, String status) {
        EvalTaskItemEntity i = new EvalTaskItemEntity();
        i.setId(id);
        i.setTaskId(taskId);
        i.setScenarioId(scenarioId);
        i.setStatus(status);
        i.setCompositeScore(new BigDecimal("75.00"));
        i.setLoopCount(2);
        i.setToolCallCount(3);
        i.setLatencyMs(1000L);
        i.setCreatedAt(Instant.now());
        return i;
    }
}
