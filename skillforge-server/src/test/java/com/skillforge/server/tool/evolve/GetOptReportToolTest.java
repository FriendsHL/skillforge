package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.optreport.dto.OptReportIssueDto;
import com.skillforge.server.optreport.dto.OptReportSummaryJson;
import com.skillforge.server.optreport.dto.OptReportSummaryParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C — {@link GetOptReportTool}: reads a
 * completed opt-report's topIssues, pins to the expected agent, and surfaces
 * not-completed / wrong-kind / not-found cleanly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetOptReportTool")
class GetOptReportToolTest {

    @Mock private FlywheelRunRepository runRepository;
    @Mock private OptReportSummaryParser summaryParser;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GetOptReportTool tool;

    @BeforeEach
    void setUp() {
        // small block timeout/interval so the not-completed test doesn't wait 90s
        tool = new GetOptReportTool(runRepository, summaryParser, objectMapper, 80L, 20L);
    }

    private SkillResult run(Map<String, Object> input) {
        return tool.execute(input, new SkillContext("/tmp", "sess", 7L));
    }

    private FlywheelRunEntity report(String id, Long agentId, String status, String summaryJson) {
        FlywheelRunEntity r = new FlywheelRunEntity();
        r.setId(id);
        r.setAgentId(agentId);
        r.setLoopKind(FlywheelRunEntity.LOOP_KIND_OPT_REPORT);
        r.setStatus(status);
        r.setSummaryJson(summaryJson);
        return r;
    }

    private OptReportIssueDto issue(String id, String suspect, String fix) {
        return new OptReportIssueDto(id, "title-" + id, "high", 3,
                List.of("s1", "s2"), suspect, fix, 0.8, "fix the thing",
                "less failure", "modify", null,
                // G4: friction / recurrence / rootCause / proposedFix
                "repeated_tool_failure", 1, null, null);
    }

    @Test
    @DisplayName("completed report: returns topIssues with effective surface + convertible flag")
    void completedReport_returnsIssues() {
        when(runRepository.findById("rep-1"))
                .thenReturn(Optional.of(report("rep-1", 42L, "completed", "{...}")));
        when(summaryParser.parse("{...}")).thenReturn(new OptReportSummaryJson(List.of(
                issue("issue-1", "prompt", null),          // effective=prompt, convertible
                issue("issue-2", "unclear", "other"))));   // effective=other, NOT convertible

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reportId", "rep-1");
        input.put("expectedAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"reportId\":\"rep-1\"");
        assertThat(result.getOutput()).contains("\"agentId\":42");
        assertThat(result.getOutput()).contains("\"issueCount\":2");
        assertThat(result.getOutput()).contains("\"id\":\"issue-1\"");
        assertThat(result.getOutput()).contains("\"surface\":\"prompt\"");
        assertThat(result.getOutput()).contains("\"convertible\":true");
        // issue-2 effective surface = other → not convertible
        assertThat(result.getOutput()).contains("\"surface\":\"other\"");
        assertThat(result.getOutput()).contains("\"convertible\":false");
    }

    @Test
    @DisplayName("AUTOEVOLVE: accepts a workflow-kind opt-report (RunWorkflow producer)")
    void workflowKindReport_accepted() {
        FlywheelRunEntity r = report("wf-1", 5L, "completed", "{...}");
        r.setLoopKind(FlywheelRunEntity.LOOP_KIND_WORKFLOW);
        when(runRepository.findById("wf-1")).thenReturn(Optional.of(r));
        when(summaryParser.parse("{...}")).thenReturn(new OptReportSummaryJson(List.of(
                issue("issue-1", "prompt", null))));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reportId", "wf-1");
        input.put("expectedAgentId", "5");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"issueCount\":1");
        assertThat(result.getOutput()).contains("\"id\":\"issue-1\"");
    }

    @Test
    @DisplayName("report not found → validation error")
    void notFound_validationError() {
        when(runRepository.findById("nope")).thenReturn(Optional.empty());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reportId", "nope");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("not found");
    }

    @Test
    @DisplayName("run is not an opt-report (wrong loop_kind) → validation error")
    void wrongLoopKind_validationError() {
        FlywheelRunEntity r = report("rep-9", 42L, "completed", "{}");
        r.setLoopKind("evolve");
        when(runRepository.findById("rep-9")).thenReturn(Optional.of(r));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reportId", "rep-9");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("not an opt-report");
    }

    @Test
    @DisplayName("expectedAgentId mismatch → validation error, does not leak issues")
    void expectedAgentMismatch_validationError() {
        when(runRepository.findById("rep-1"))
                .thenReturn(Optional.of(report("rep-1", 99L, "completed", "{...}")));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reportId", "rep-1");
        input.put("expectedAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("belongs to agent 99");
    }

    @Test
    @DisplayName("report still running after bounded wait → non-validation error (agent calls again)")
    void notCompleted_error() {
        // stays 'running' for every poll → tool blocks ~80ms then returns "still running"
        when(runRepository.findById("rep-1"))
                .thenReturn(Optional.of(report("rep-1", 42L, "running", null)));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reportId", "rep-1");
        input.put("expectedAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isNotEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("still running");
    }

    @Test
    @DisplayName("report completes mid-wait → blocking poll returns the issues")
    void completesMidWait_returnsIssues() {
        // first read 'running', subsequent reads 'completed' → the poll loop picks it up
        FlywheelRunEntity running = report("rep-2", 42L, "running", null);
        FlywheelRunEntity done = report("rep-2", 42L, "completed", "{...}");
        when(runRepository.findById("rep-2"))
                .thenReturn(Optional.of(running), Optional.of(done));
        when(summaryParser.parse("{...}")).thenReturn(new OptReportSummaryJson(List.of(
                issue("issue-1", "prompt", null))));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reportId", "rep-2");
        input.put("expectedAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"issueCount\":1");
    }

    @Test
    @DisplayName("missing reportId → validation error")
    void missingReportId_validationError() {
        SkillResult result = run(new LinkedHashMap<>());
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    @Test
    @DisplayName("missing expectedAgentId → validation error (required access pin)")
    void missingExpectedAgentId_validationError() {
        when(runRepository.findById("rep-1"))
                .thenReturn(Optional.of(report("rep-1", 42L, "completed", "{...}")));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reportId", "rep-1");   // no expectedAgentId

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("expectedAgentId is required");
    }

    @Test
    @DisplayName("tool metadata: name, read-only")
    void metadata() {
        assertThat(tool.getName()).isEqualTo("GetOptReport");
        assertThat(tool.isReadOnly()).isTrue();
    }
}
