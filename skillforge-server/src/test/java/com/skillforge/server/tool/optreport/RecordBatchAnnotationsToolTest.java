package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.flywheel.run.FlywheelRunStepRepository;
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
@DisplayName("RecordBatchAnnotationsTool (post-V124 rename)")
class RecordBatchAnnotationsToolTest {

    @Mock private FlywheelRunStepRepository batchRepository;

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
        FlywheelRunStepEntity batch = newBatch("batch-1");
        batch.setErrorReason("stale prior error");
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));

        SkillResult r = tool.execute(Map.of(
                        "batchId", "batch-1",
                        "annotationsWrittenCount", 7),
                new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isTrue();

        ArgumentCaptor<FlywheelRunStepEntity> saved = ArgumentCaptor.forClass(FlywheelRunStepEntity.class);
        verify(batchRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(FlywheelRunStepEntity.STATUS_COMPLETED);
        assertThat(saved.getValue().getStepOutputCount()).isEqualTo(7);
        assertThat(saved.getValue().getErrorReason()).isNull();

        JsonNode root = objectMapper.readTree(r.getOutput());
        assertThat(root.path("ok").asBoolean()).isTrue();
        assertThat(root.path("status").asText()).isEqualTo("completed");
    }

    @Test
    @DisplayName("error status: errorReason persisted")
    void errorStatus_persistsReason() {
        FlywheelRunStepEntity batch = newBatch("batch-2");
        when(batchRepository.findById("batch-2")).thenReturn(Optional.of(batch));

        SkillResult r = tool.execute(Map.of(
                        "batchId", "batch-2",
                        "annotationsWrittenCount", 0,
                        "status", "error",
                        "errorReason", "All 5 sessions had 0 traces"),
                new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isTrue();

        ArgumentCaptor<FlywheelRunStepEntity> saved = ArgumentCaptor.forClass(FlywheelRunStepEntity.class);
        verify(batchRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(FlywheelRunStepEntity.STATUS_ERROR);
        assertThat(saved.getValue().getStepOutputCount()).isZero();
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
        when(batchRepository.save(any(FlywheelRunStepEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SkillResult r = tool.execute(
                Map.of(
                        "batchId", "new-batch",
                        "reportId", "rep-x",
                        "sessionIds", java.util.List.of("s1", "s2", "s3"),
                        "annotationsWrittenCount", 7),
                new SkillContext(null, "subagent-session-99", 0L));

        assertThat(r.isSuccess()).isTrue();
        ArgumentCaptor<FlywheelRunStepEntity> captor =
                ArgumentCaptor.forClass(FlywheelRunStepEntity.class);
        verify(batchRepository).save(captor.capture());
        FlywheelRunStepEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("new-batch");
        // V124 column renamed: report_id → run_id
        assertThat(saved.getRunId()).isEqualTo("rep-x");
        assertThat(saved.getStepInputJson()).contains("s1").contains("s2").contains("s3");
        assertThat(saved.getStatus()).isEqualTo(FlywheelRunStepEntity.STATUS_COMPLETED);
        assertThat(saved.getStepOutputCount()).isEqualTo(7);
        assertThat(saved.getSubAgentSessionId()).isEqualTo("subagent-session-99");
    }

    @Test
    @DisplayName("robustness: an over-length (non-UUID) batchId is normalized to a ≤36-char id so it can't overflow varchar(36) and abort the opt-report")
    void overlengthBatchId_normalizedToFitColumn() {
        // A weaker driving model emitted a descriptive id instead of a 36-char UUID;
        // t_flywheel_run_step.id is varchar(36), so the raw value used to crash the
        // whole opt-report with "value too long for varchar(36)".
        String longBatchId = "batch-session-annotator-run-001-for-agent-3-overlength";
        assertThat(longBatchId.length()).isGreaterThan(36);
        String derivedId = java.util.UUID.nameUUIDFromBytes(
                longBatchId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();

        when(batchRepository.findById(derivedId)).thenReturn(Optional.empty());
        when(batchRepository.save(any(FlywheelRunStepEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SkillResult r = tool.execute(
                Map.of(
                        "batchId", longBatchId,
                        "reportId", "rep-x",
                        "sessionIds", java.util.List.of("s1"),
                        "annotationsWrittenCount", 3),
                new SkillContext(null, "subagent-session-1", 0L));

        assertThat(r.isSuccess()).isTrue();
        ArgumentCaptor<FlywheelRunStepEntity> captor =
                ArgumentCaptor.forClass(FlywheelRunStepEntity.class);
        verify(batchRepository).save(captor.capture());
        String savedId = captor.getValue().getId();
        // Persisted under a stable derived id that FITS varchar(36) — never the raw value.
        assertThat(savedId).isEqualTo(derivedId);
        assertThat(savedId).isNotEqualTo(longBatchId);
        assertThat(savedId.length()).isLessThanOrEqualTo(36);
    }

    private static FlywheelRunStepEntity newBatch(String id) {
        FlywheelRunStepEntity b = new FlywheelRunStepEntity();
        b.setId(id);
        b.setRunId("rep-x");
        b.setStepInputJson("[\"s1\",\"s2\"]");
        b.setStatus(FlywheelRunStepEntity.STATUS_PENDING);
        return b;
    }
}
