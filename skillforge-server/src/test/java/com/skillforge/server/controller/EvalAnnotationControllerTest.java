package com.skillforge.server.controller;

import com.skillforge.server.entity.EvalAnnotationEntity;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.EvalTaskItemEntity;
import com.skillforge.server.repository.EvalAnnotationRepository;
import com.skillforge.server.repository.EvalTaskItemRepository;
import com.skillforge.server.repository.EvalTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EvalAnnotationController")
class EvalAnnotationControllerTest {

    @Mock
    private EvalAnnotationRepository evalAnnotationRepository;
    @Mock
    private EvalTaskItemRepository evalTaskItemRepository;
    @Mock
    private EvalTaskRepository evalTaskRepository;

    private EvalAnnotationController controller;

    @BeforeEach
    void setUp() {
        controller = new EvalAnnotationController(evalAnnotationRepository, evalTaskItemRepository, evalTaskRepository);
    }

    @Test
    @DisplayName("POST /api/eval/annotations creates pending annotation from task item")
    void createAnnotation_createsPendingRow() {
        EvalTaskItemEntity item = new EvalTaskItemEntity();
        item.setId(11L);
        item.setTaskId("task-1");
        item.setScenarioId("scenario-A");
        item.setCompositeScore(new BigDecimal("72.50"));
        item.setStatus("FAIL");
        when(evalTaskItemRepository.findById(11L)).thenReturn(Optional.of(item));
        when(evalAnnotationRepository.save(any(EvalAnnotationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> resp = controller.createAnnotation(Map.of(
                "taskItemId", 11L,
                "annotatorId", 7L,
                "correctedScore", "90",
                "correctedExpected", "Should mention retry policy."
        ));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).hasFieldOrPropertyWithValue("taskItemId", 11L);
    }

    @Test
    @DisplayName("GET /api/eval/annotations returns mapped queue rows")
    void listAnnotations_returnsMappedRows() {
        EvalAnnotationEntity entity = new EvalAnnotationEntity();
        entity.setId(3L);
        entity.setTaskItemId(11L);
        entity.setAnnotatorId(7L);
        entity.setOriginalScore(new BigDecimal("72.50"));
        entity.setCorrectedScore(new BigDecimal("90.00"));
        entity.setCorrectedExpected("retry");
        entity.setStatus(EvalAnnotationEntity.STATUS_PENDING);
        entity.setCreatedAt(Instant.now());
        when(evalAnnotationRepository.findByStatusOrderByCreatedAtDesc(EvalAnnotationEntity.STATUS_PENDING))
                .thenReturn(List.of(entity));
        EvalTaskItemEntity item = new EvalTaskItemEntity();
        item.setId(11L);
        item.setTaskId("task-1");
        item.setScenarioId("scenario-A");
        item.setStatus("FAIL");
        when(evalTaskItemRepository.findById(11L)).thenReturn(Optional.of(item));
        EvalTaskEntity task = new EvalTaskEntity();
        task.setId("task-1");
        task.setAgentDefinitionId("42");
        task.setStatus("COMPLETED");
        when(evalTaskRepository.findById("task-1")).thenReturn(Optional.of(task));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listAnnotations(EvalAnnotationEntity.STATUS_PENDING);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0)).containsEntry("scenarioId", "scenario-A");
        assertThat(resp.getBody().get(0)).containsEntry("taskId", "task-1");
    }

    @Test
    @DisplayName("PATCH /api/eval/annotations/{id} marks annotation applied")
    void updateAnnotation_marksApplied() {
        EvalAnnotationEntity entity = new EvalAnnotationEntity();
        entity.setId(3L);
        entity.setTaskItemId(11L);
        entity.setStatus(EvalAnnotationEntity.STATUS_PENDING);
        when(evalAnnotationRepository.findById(3L)).thenReturn(Optional.of(entity));
        when(evalTaskItemRepository.findById(11L)).thenReturn(Optional.empty());
        when(evalAnnotationRepository.save(any(EvalAnnotationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> resp = controller.updateAnnotation(3L, Map.of("status", "applied"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getStatus()).isEqualTo(EvalAnnotationEntity.STATUS_APPLIED);
        assertThat(entity.getAppliedAt()).isNotNull();
    }
}
