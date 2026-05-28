package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.optreport.OptReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WriteOptReportTool (post-V124 — delegates to FlywheelRunService)")
class WriteOptReportToolTest {

    @Mock private FlywheelRunRepository reportRepository;
    @Mock private FlywheelRunService flywheelRunService;
    @Mock private OptReportService reportService;

    private ObjectMapper objectMapper;
    private WriteOptReportTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new WriteOptReportTool(reportRepository, flywheelRunService, reportService, objectMapper);
    }

    @Test
    @DisplayName("happy path: delegates to FlywheelRunService.markCompleted + fires opt_report_completed WS broadcast")
    void happyPath_writesAndBroadcasts() throws Exception {
        FlywheelRunEntity report = newReport("rep-1", FlywheelRunEntity.STATUS_RUNNING);
        when(reportRepository.findById("rep-1")).thenReturn(Optional.of(report));

        Map<String, Object> input = new HashMap<>();
        input.put("reportId", "rep-1");
        input.put("contentMd", "# hello report\n\nbody");
        input.put("summaryJson", Map.of("totalSessions", 12, "successRate", 0.5));

        SkillResult result = tool.execute(input, new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();

        // Service is called with contentMd verbatim and summaryJson serialised
        // through the tool's ObjectMapper. We assert on the summary substring
        // rather than the full string so JSON key order differences don't fail.
        verify(flywheelRunService).markCompleted(eq("rep-1"),
                eq("# hello report\n\nbody"),
                contains("\"totalSessions\":12"));

        verify(reportService).onReportCompleted("rep-1");
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("ok").asBoolean()).isTrue();
        assertThat(root.path("reportId").asText()).isEqualTo("rep-1");
        assertThat(root.path("contentMdLength").asInt()).isGreaterThan(0);
    }

    @Test
    @DisplayName("status pending is also writable (pre-running race window)")
    void pendingStatus_isAlsoWritable() throws Exception {
        FlywheelRunEntity report = newReport("rep-2", FlywheelRunEntity.STATUS_PENDING);
        when(reportRepository.findById("rep-2")).thenReturn(Optional.of(report));

        Map<String, Object> input = Map.of("reportId", "rep-2", "contentMd", "## ok");
        SkillResult r = tool.execute(input, new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isTrue();
        verify(flywheelRunService).markCompleted(eq("rep-2"), eq("## ok"), eq(null));
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
    @DisplayName("not-found reportId → error (no service call, no broadcast)")
    void notFoundReport_errors() {
        when(reportRepository.findById("nope")).thenReturn(Optional.empty());

        SkillResult r = tool.execute(Map.of("reportId", "nope", "contentMd", "x"),
                new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getError()).contains("not found");
        verify(flywheelRunService, never()).markCompleted(anyString(), anyString(), anyString());
        verify(reportService, never()).onReportCompleted(anyString());
    }

    @Test
    @DisplayName("already-completed report → VALIDATION error (no re-publish)")
    void alreadyCompleted_rejects() {
        FlywheelRunEntity report = newReport("rep-1", FlywheelRunEntity.STATUS_COMPLETED);
        when(reportRepository.findById("rep-1")).thenReturn(Optional.of(report));

        SkillResult r = tool.execute(Map.of("reportId", "rep-1", "contentMd", "x"),
                new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        verify(flywheelRunService, never()).markCompleted(anyString(), anyString(), anyString());
    }

    private static FlywheelRunEntity newReport(String id, String status) {
        FlywheelRunEntity report = new FlywheelRunEntity();
        report.setId(id);
        report.setAgentId(7L);
        report.setStatus(status);
        report.setLoopKind(FlywheelRunEntity.LOOP_KIND_OPT_REPORT);
        report.setTriggerSource(FlywheelRunEntity.TRIGGER_SOURCE_USER_MANUAL);
        return report;
    }
}
