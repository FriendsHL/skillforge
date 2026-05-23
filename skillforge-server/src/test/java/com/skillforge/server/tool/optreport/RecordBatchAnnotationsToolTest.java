package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.OptReportBatchEntity;
import com.skillforge.server.repository.OptReportBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecordBatchAnnotationsTool")
class RecordBatchAnnotationsToolTest {

    @Mock private OptReportBatchRepository batchRepository;

    private ObjectMapper objectMapper;
    private RecordBatchAnnotationsTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new RecordBatchAnnotationsTool(batchRepository, objectMapper);
    }

    @Test
    @DisplayName("happy path: status defaults completed, count saved, error_reason cleared")
    void happyPath_completed() throws Exception {
        OptReportBatchEntity batch = newBatch("batch-1");
        batch.setErrorReason("stale prior error");
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));

        SkillResult r = tool.execute(Map.of(
                        "batchId", "batch-1",
                        "annotationsWrittenCount", 7),
                new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isTrue();

        ArgumentCaptor<OptReportBatchEntity> saved = ArgumentCaptor.forClass(OptReportBatchEntity.class);
        verify(batchRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(OptReportBatchEntity.STATUS_COMPLETED);
        assertThat(saved.getValue().getAnnotationsWrittenCount()).isEqualTo(7);
        assertThat(saved.getValue().getErrorReason()).isNull();

        JsonNode root = objectMapper.readTree(r.getOutput());
        assertThat(root.path("ok").asBoolean()).isTrue();
        assertThat(root.path("status").asText()).isEqualTo("completed");
    }

    @Test
    @DisplayName("error status: errorReason persisted")
    void errorStatus_persistsReason() {
        OptReportBatchEntity batch = newBatch("batch-2");
        when(batchRepository.findById("batch-2")).thenReturn(Optional.of(batch));

        SkillResult r = tool.execute(Map.of(
                        "batchId", "batch-2",
                        "annotationsWrittenCount", 0,
                        "status", "error",
                        "errorReason", "All 5 sessions had 0 traces"),
                new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isTrue();

        ArgumentCaptor<OptReportBatchEntity> saved = ArgumentCaptor.forClass(OptReportBatchEntity.class);
        verify(batchRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(OptReportBatchEntity.STATUS_ERROR);
        assertThat(saved.getValue().getAnnotationsWrittenCount()).isZero();
        assertThat(saved.getValue().getErrorReason()).isEqualTo("All 5 sessions had 0 traces");
    }

    @Test
    @DisplayName("validation: missing batchId / non-int / negative count → VALIDATION error")
    void validation_invalidInputs() {
        SkillResult missing = tool.execute(Map.of("annotationsWrittenCount", 1),
                new SkillContext(null, null, 0L));
        assertThat(missing.isSuccess()).isFalse();
        assertThat(missing.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);

        SkillResult negative = tool.execute(Map.of("batchId", "b-1", "annotationsWrittenCount", -2),
                new SkillContext(null, null, 0L));
        assertThat(negative.isSuccess()).isFalse();
        assertThat(negative.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);

        SkillResult badStatus = tool.execute(Map.of("batchId", "b-1",
                        "annotationsWrittenCount", 1, "status", "unknown"),
                new SkillContext(null, null, 0L));
        assertThat(badStatus.isSuccess()).isFalse();
        assertThat(badStatus.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    @Test
    @DisplayName("upsert: batchId missing + no reportId → validation error (no save)")
    void notFoundBatch_withoutReportId_validationError() {
        when(batchRepository.findById("nope")).thenReturn(Optional.empty());
        SkillResult r = tool.execute(Map.of("batchId", "nope", "annotationsWrittenCount", 1),
                new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(r.getError()).contains("reportId is required");
        verify(batchRepository, never()).save(any());
    }

    @Test
    @DisplayName("upsert: batchId missing + reportId + sessionIds → creates new row")
    void notFoundBatch_withReportIdAndSessionIds_createsRow() {
        when(batchRepository.findById("new-batch")).thenReturn(Optional.empty());
        when(batchRepository.save(any(OptReportBatchEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SkillResult r = tool.execute(
                Map.of(
                        "batchId", "new-batch",
                        "reportId", "rep-x",
                        "sessionIds", java.util.List.of("s1", "s2", "s3"),
                        "annotationsWrittenCount", 7),
                new SkillContext(null, "subagent-session-99", 0L));

        assertThat(r.isSuccess()).isTrue();
        org.mockito.ArgumentCaptor<OptReportBatchEntity> captor =
                org.mockito.ArgumentCaptor.forClass(OptReportBatchEntity.class);
        verify(batchRepository).save(captor.capture());
        OptReportBatchEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("new-batch");
        assertThat(saved.getReportId()).isEqualTo("rep-x");
        assertThat(saved.getSessionIdsJson()).contains("s1").contains("s2").contains("s3");
        assertThat(saved.getStatus()).isEqualTo(OptReportBatchEntity.STATUS_COMPLETED);
        assertThat(saved.getAnnotationsWrittenCount()).isEqualTo(7);
        assertThat(saved.getSubAgentSessionId()).isEqualTo("subagent-session-99");
    }

    private static OptReportBatchEntity newBatch(String id) {
        OptReportBatchEntity b = new OptReportBatchEntity();
        b.setId(id);
        b.setReportId("rep-x");
        b.setSessionIdsJson("[\"s1\",\"s2\"]");
        b.setStatus(OptReportBatchEntity.STATUS_PENDING);
        return b;
    }
}
