package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.OptReportEntity;
import com.skillforge.server.optreport.OptReportService;
import com.skillforge.server.repository.OptReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WriteOptReportTool")
class WriteOptReportToolTest {

    @Mock private OptReportRepository reportRepository;
    @Mock private OptReportService reportService;

    private ObjectMapper objectMapper;
    private WriteOptReportTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new WriteOptReportTool(reportRepository, reportService, objectMapper);
    }

    @Test
    @DisplayName("happy path: status running → completed + WS broadcast fired")
    void happyPath_writesAndBroadcasts() throws Exception {
        OptReportEntity report = new OptReportEntity();
        report.setId("rep-1");
        report.setAgentId(7L);
        report.setStatus(OptReportEntity.STATUS_RUNNING);
        when(reportRepository.findById("rep-1")).thenReturn(Optional.of(report));

        Map<String, Object> input = new HashMap<>();
        input.put("reportId", "rep-1");
        input.put("contentMd", "# hello report\n\nbody");
        input.put("summaryJson", Map.of("totalSessions", 12, "successRate", 0.5));

        SkillResult result = tool.execute(input, new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<OptReportEntity> savedCaptor = ArgumentCaptor.forClass(OptReportEntity.class);
        verify(reportRepository).save(savedCaptor.capture());
        OptReportEntity saved = savedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OptReportEntity.STATUS_COMPLETED);
        assertThat(saved.getContentMd()).isEqualTo("# hello report\n\nbody");
        assertThat(saved.getSummaryJson()).contains("\"totalSessions\":12");

        verify(reportService).onReportCompleted("rep-1");
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("ok").asBoolean()).isTrue();
        assertThat(root.path("reportId").asText()).isEqualTo("rep-1");
        assertThat(root.path("contentMdLength").asInt()).isGreaterThan(0);
    }

    @Test
    @DisplayName("status pending is also writable (pre-running race window)")
    void pendingStatus_isAlsoWritable() throws Exception {
        OptReportEntity report = new OptReportEntity();
        report.setId("rep-2");
        report.setStatus(OptReportEntity.STATUS_PENDING);
        when(reportRepository.findById("rep-2")).thenReturn(Optional.of(report));

        Map<String, Object> input = Map.of("reportId", "rep-2", "contentMd", "## ok");
        SkillResult r = tool.execute(input, new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("validation: missing reportId / contentMd → VALIDATION error")
    void validation_missingFields() {
        SkillResult missingId = tool.execute(Map.of("contentMd", "x"),
                new SkillContext(null, null, 0L));
        assertThat(missingId.isSuccess()).isFalse();
        assertThat(missingId.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);

        SkillResult missingMd = tool.execute(Map.of("reportId", "rep-1"),
                new SkillContext(null, null, 0L));
        assertThat(missingMd.isSuccess()).isFalse();
        assertThat(missingMd.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    @Test
    @DisplayName("not-found reportId → error (no save, no broadcast)")
    void notFoundReport_errors() {
        when(reportRepository.findById("nope")).thenReturn(Optional.empty());

        SkillResult r = tool.execute(Map.of("reportId", "nope", "contentMd", "x"),
                new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getError()).contains("not found");
        verify(reportRepository, never()).save(any());
        verify(reportService, never()).onReportCompleted(anyString());
    }

    @Test
    @DisplayName("already-completed report → VALIDATION error (no re-publish)")
    void alreadyCompleted_rejects() {
        OptReportEntity report = new OptReportEntity();
        report.setId("rep-1");
        report.setStatus(OptReportEntity.STATUS_COMPLETED);
        when(reportRepository.findById("rep-1")).thenReturn(Optional.of(report));

        SkillResult r = tool.execute(Map.of("reportId", "rep-1", "contentMd", "x"),
                new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        verify(reportRepository, never()).save(any());
    }
}
