package com.skillforge.server.optreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.optreport.dto.OptReportIssueDto;
import com.skillforge.server.optreport.dto.OptReportSummaryParser;
import com.skillforge.server.repository.OptimizationEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OptReportToEventBridge")
class OptReportToEventBridgeTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-23T10:00:00Z");

    @Mock private FlywheelRunRepository reportRepository;
    @Mock private OptimizationEventRepository eventRepository;

    private OptReportSummaryParser parser;
    private OptReportToEventBridge bridge;

    @BeforeEach
    void setUp() {
        parser = new OptReportSummaryParser(new ObjectMapper().findAndRegisterModules());
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
        bridge = new OptReportToEventBridge(reportRepository, eventRepository, parser, fixedClock);
    }

    @Test
    @DisplayName("happy path — converts a skill-surface issue into a proposal_pending event")
    void convertIssueToEvent_happyPath_createsEvent() {
        String reportId = "rep-1";
        String issueId = "issue-1";
        FlywheelRunEntity report = completedReport(reportId, 7L, """
                { "topIssues": [
                    {
                      "id": "issue-1",
                      "title": "ReadFile fails on absolute paths",
                      "severity": "high",
                      "sessionCount": 4,
                      "exampleSessionIds": ["sess-abc", "sess-def"],
                      "suspectSurface": "skill",
                      "confidence": 0.85,
                      "suggestion": "Rewrite ReadFile skill to accept absolute paths",
                      "expectedImpact": "Fix 30% file-read failure rate"
                    }
                ]}
                """);
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(eventRepository.findFirstBySourceReportIdAndSourceIssueId(reportId, issueId))
                .thenReturn(Optional.empty());
        when(eventRepository.save(any(OptimizationEventEntity.class)))
                .thenAnswer(inv -> {
                    OptimizationEventEntity e = inv.getArgument(0);
                    e.setId(123L);
                    return e;
                });

        OptReportToEventBridge.ConvertResult result = bridge.convertIssueToEvent(reportId, issueId);

        assertThat(result.alreadyConverted()).isFalse();
        ArgumentCaptor<OptimizationEventEntity> captor =
                ArgumentCaptor.forClass(OptimizationEventEntity.class);
        verify(eventRepository).save(captor.capture());
        OptimizationEventEntity saved = captor.getValue();
        assertThat(saved.getAgentId()).isEqualTo(7L);
        assertThat(saved.getPatternId()).isNull();  // V1.2 — null for report-derived events
        assertThat(saved.getSurfaceType()).isEqualTo("skill");
        assertThat(saved.getChangeType()).isEqualTo("from_opt_report");
        assertThat(saved.getStage()).isEqualTo(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        assertThat(saved.getSourceReportId()).isEqualTo(reportId);
        assertThat(saved.getSourceIssueId()).isEqualTo(issueId);
        assertThat(saved.getConfidence()).isEqualByComparingTo(new BigDecimal("0.85"));
        assertThat(saved.getRisk()).isEqualTo("high");  // severity=high → risk=high
        assertThat(saved.getDescription()).contains("ReadFile fails on absolute paths");
        assertThat(saved.getDescription()).contains("Rewrite ReadFile skill");
        assertThat(saved.getDescription()).contains("Fix 30% file-read failure rate");
        assertThat(saved.getExpectedImpact()).isEqualTo("Fix 30% file-read failure rate");
        assertThat(saved.getCooldownExpiresAt()).isEqualTo(FIXED_NOW.plusSeconds(24 * 3600));
    }

    @Test
    @DisplayName("idempotent re-convert returns existing event with alreadyConverted=true")
    void convertIssueToEvent_alreadyExists_returnsExisting() {
        String reportId = "rep-1";
        String issueId = "issue-1";
        FlywheelRunEntity report = completedReport(reportId, 7L, """
                { "topIssues": [
                    {
                      "id": "issue-1", "title": "x", "severity": "low",
                      "sessionCount": 1, "exampleSessionIds": ["a"],
                      "suspectSurface": "prompt", "confidence": 0.5,
                      "suggestion": "y"
                    }
                ]}
                """);
        OptimizationEventEntity existing = new OptimizationEventEntity();
        existing.setId(999L);
        existing.setSourceReportId(reportId);
        existing.setSourceIssueId(issueId);
        existing.setStage(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(eventRepository.findFirstBySourceReportIdAndSourceIssueId(reportId, issueId))
                .thenReturn(Optional.of(existing));

        OptReportToEventBridge.ConvertResult result = bridge.convertIssueToEvent(reportId, issueId);

        assertThat(result.alreadyConverted()).isTrue();
        assertThat(result.event().getId()).isEqualTo(999L);
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("'other' surface → IllegalArgumentException")
    void convertIssueToEvent_otherSurface_throws() {
        String reportId = "rep-1";
        String issueId = "issue-1";
        FlywheelRunEntity report = completedReport(reportId, 7L, """
                { "topIssues": [
                    {
                      "id": "issue-1", "title": "x", "severity": "low",
                      "sessionCount": 1, "exampleSessionIds": ["a"],
                      "suspectSurface": "other", "confidence": 0.5,
                      "suggestion": "y"
                    }
                ]}
                """);
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> bridge.convertIssueToEvent(reportId, issueId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveSurface='other'");
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("'unclear' surface → IllegalArgumentException")
    void convertIssueToEvent_unclearSurface_throws() {
        String reportId = "rep-1";
        String issueId = "issue-1";
        FlywheelRunEntity report = completedReport(reportId, 7L, """
                { "topIssues": [
                    {
                      "id": "issue-1", "title": "x", "severity": "low",
                      "sessionCount": 1, "exampleSessionIds": ["a"],
                      "suspectSurface": "unclear", "confidence": 0.5,
                      "suggestion": "y"
                    }
                ]}
                """);
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> bridge.convertIssueToEvent(reportId, issueId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unclear");
    }

    @Test
    @DisplayName("V1.3+ fixSurface 跟 suspectSurface 不同 → OptEvent.surfaceType = fixSurface")
    void convertIssueToEvent_fixSurfaceDifferentFromSuspect_routesByFixSurface() {
        String reportId = "rep-fix-split";
        String issueId = "issue-1";
        // 经典场景：agent 调 Bash 反复失败 → 根因 surface=skill，
        // 但修复落点是 behavior_rule（加"连续失败后停"的行为规则）。
        FlywheelRunEntity report = completedReport(reportId, 7L, """
                { "topIssues": [
                    {
                      "id": "issue-1", "title": "Bash 路径循环",
                      "severity": "high", "sessionCount": 4,
                      "exampleSessionIds": ["sess-a", "sess-b"],
                      "suspectSurface": "skill",
                      "fixSurface": "behavior_rule",
                      "confidence": 0.85,
                      "suggestion": "加 N 次失败后停"
                    }
                ]}
                """);
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(eventRepository.findFirstBySourceReportIdAndSourceIssueId(reportId, issueId))
                .thenReturn(Optional.empty());
        when(eventRepository.save(any(OptimizationEventEntity.class)))
                .thenAnswer(inv -> {
                    OptimizationEventEntity e = inv.getArgument(0);
                    e.setId(999L);
                    return e;
                });

        OptReportToEventBridge.ConvertResult result =
                bridge.convertIssueToEvent(reportId, issueId);
        org.mockito.ArgumentCaptor<OptimizationEventEntity> captor =
                org.mockito.ArgumentCaptor.forClass(OptimizationEventEntity.class);
        verify(eventRepository).save(captor.capture());
        OptimizationEventEntity saved = captor.getValue();
        // 关键断言：surfaceType 走 fixSurface (behavior_rule)，不是 suspectSurface (skill)
        assertThat(saved.getSurfaceType()).isEqualTo("behavior_rule");
        assertThat(result.alreadyConverted()).isFalse();
    }

    @Test
    @DisplayName("V1.3+ fixSurface 缺省 → fallback 到 suspectSurface (向后兼容)")
    void convertIssueToEvent_fixSurfaceMissing_fallbackToSuspect() {
        String reportId = "rep-legacy";
        String issueId = "issue-1";
        FlywheelRunEntity report = completedReport(reportId, 7L, """
                { "topIssues": [
                    {
                      "id": "issue-1", "title": "x",
                      "severity": "low", "sessionCount": 1,
                      "exampleSessionIds": ["a"],
                      "suspectSurface": "prompt", "confidence": 0.6,
                      "suggestion": "y"
                    }
                ]}
                """);
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(eventRepository.findFirstBySourceReportIdAndSourceIssueId(reportId, issueId))
                .thenReturn(Optional.empty());
        when(eventRepository.save(any(OptimizationEventEntity.class)))
                .thenAnswer(inv -> {
                    OptimizationEventEntity e = inv.getArgument(0);
                    e.setId(998L);
                    return e;
                });

        bridge.convertIssueToEvent(reportId, issueId);
        org.mockito.ArgumentCaptor<OptimizationEventEntity> captor =
                org.mockito.ArgumentCaptor.forClass(OptimizationEventEntity.class);
        verify(eventRepository).save(captor.capture());
        // suspectSurface=prompt，fixSurface 缺省 → surfaceType=prompt（fallback OK）
        assertThat(captor.getValue().getSurfaceType()).isEqualTo("prompt");
    }

    @Test
    @DisplayName("report not completed → IllegalStateException")
    void convertIssueToEvent_reportRunning_throws() {
        String reportId = "rep-running";
        FlywheelRunEntity report = new FlywheelRunEntity();
        report.setId(reportId);
        report.setAgentId(7L);
        report.setStatus(FlywheelRunEntity.STATUS_RUNNING);
        report.setSummaryJson("{ \"topIssues\": [] }");
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> bridge.convertIssueToEvent(reportId, "issue-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("running");
    }

    @Test
    @DisplayName("report not found → NoSuchElementException")
    void convertIssueToEvent_reportMissing_throws() {
        when(reportRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bridge.convertIssueToEvent("nope", "issue-1"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Report not found");
    }

    @Test
    @DisplayName("issue id not in summary → NoSuchElementException")
    void convertIssueToEvent_issueMissing_throws() {
        String reportId = "rep-1";
        FlywheelRunEntity report = completedReport(reportId, 7L, """
                { "topIssues": [
                    {
                      "id": "issue-1", "title": "x", "severity": "low",
                      "sessionCount": 1, "exampleSessionIds": ["a"],
                      "suspectSurface": "skill", "confidence": 0.5,
                      "suggestion": "y"
                    }
                ]}
                """);
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> bridge.convertIssueToEvent(reportId, "issue-99"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Issue not found");
    }

    @Test
    @DisplayName("schema-invalid summary_json → IllegalArgumentException (operator gets 400)")
    void convertIssueToEvent_invalidSchema_throws() {
        String reportId = "rep-1";
        FlywheelRunEntity report = new FlywheelRunEntity();
        report.setId(reportId);
        report.setAgentId(7L);
        report.setStatus(FlywheelRunEntity.STATUS_COMPLETED);
        // Missing severity — parser will throw
        report.setSummaryJson("""
                { "topIssues": [
                    {
                      "id": "issue-1", "title": "x",
                      "sessionCount": 1, "exampleSessionIds": ["a"],
                      "suspectSurface": "skill", "confidence": 0.5,
                      "suggestion": "y"
                    }
                ]}
                """);
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> bridge.convertIssueToEvent(reportId, "issue-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("severity");
    }

    @Test
    @DisplayName("severity → risk mapping for medium and low")
    void convertIssueToEvent_severityMapping() {
        String reportId = "rep-1";
        FlywheelRunEntity report = completedReport(reportId, 7L, """
                { "topIssues": [
                    {
                      "id": "issue-medium", "title": "x", "severity": "medium",
                      "sessionCount": 1, "exampleSessionIds": ["a"],
                      "suspectSurface": "behavior_rule", "confidence": 0.7,
                      "suggestion": "y"
                    }
                ]}
                """);
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(eventRepository.findFirstBySourceReportIdAndSourceIssueId(eq(reportId), anyString()))
                .thenReturn(Optional.empty());
        when(eventRepository.save(any(OptimizationEventEntity.class)))
                .thenAnswer(inv -> {
                    OptimizationEventEntity e = inv.getArgument(0);
                    e.setId(7L);
                    return e;
                });

        bridge.convertIssueToEvent(reportId, "issue-medium");

        ArgumentCaptor<OptimizationEventEntity> captor =
                ArgumentCaptor.forClass(OptimizationEventEntity.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getRisk()).isEqualTo("medium");
        assertThat(captor.getValue().getSurfaceType()).isEqualTo("behavior_rule");
    }

    @Test
    @DisplayName("V1.5+ enrichTopIssues includes actionType + targetRuleText")
    void enrichTopIssues_includesActionTypeAndTargetRuleText() {
        String reportId = "rep-1";
        String summaryJson = """
                { "topIssues": [
                    {
                      "id": "issue-1", "title": "重复建议",
                      "severity": "medium", "sessionCount": 2,
                      "exampleSessionIds": ["sess-a", "sess-b"],
                      "suspectSurface": "behavior_rule", "confidence": 0.6,
                      "suggestion": "现有 rule 似未生效,建议提升 severity",
                      "actionType": "duplicate",
                      "targetRuleText": "git 操作前确认目录"
                    },
                    {
                      "id": "issue-2", "title": "纯新加",
                      "severity": "low", "sessionCount": 1,
                      "exampleSessionIds": ["sess-c"],
                      "suspectSurface": "skill", "confidence": 0.5,
                      "suggestion": "写新 skill",
                      "actionType": "new"
                    }
                ]}
                """;
        when(eventRepository.findBySourceReportId(reportId))
                .thenReturn(java.util.Collections.emptyList());

        java.util.List<java.util.Map<String, Object>> enriched =
                bridge.enrichTopIssues(reportId, summaryJson);

        assertThat(enriched).hasSize(2);
        // issue-1: actionType=duplicate + targetRuleText 引用原文
        assertThat(enriched.get(0)).containsEntry("actionType", "duplicate");
        assertThat(enriched.get(0)).containsEntry("targetRuleText", "git 操作前确认目录");
        // issue-2: actionType=new + targetRuleText 省略 → null
        assertThat(enriched.get(1)).containsEntry("actionType", "new");
        assertThat(enriched.get(1)).containsEntry("targetRuleText", null);
        // Key 必须真出现 (containsEntry value=null 也算 contains)
        assertThat(enriched.get(1)).containsKey("targetRuleText");
    }

    @Test
    @DisplayName("V1.5+ enrichTopIssues legacy report (no actionType) → fields present as null")
    void enrichTopIssues_legacyReportNoActionType_fieldsAreNull() {
        String reportId = "rep-legacy";
        String summaryJson = """
                { "topIssues": [
                    {
                      "id": "issue-1", "title": "legacy",
                      "severity": "high", "sessionCount": 1,
                      "exampleSessionIds": ["a"],
                      "suspectSurface": "skill", "confidence": 0.5,
                      "suggestion": "y"
                    }
                ]}
                """;
        when(eventRepository.findBySourceReportId(reportId))
                .thenReturn(java.util.Collections.emptyList());

        java.util.List<java.util.Map<String, Object>> enriched =
                bridge.enrichTopIssues(reportId, summaryJson);

        assertThat(enriched).hasSize(1);
        assertThat(enriched.get(0)).containsKey("actionType");
        assertThat(enriched.get(0).get("actionType")).isNull();
        assertThat(enriched.get(0)).containsKey("targetRuleText");
        assertThat(enriched.get(0).get("targetRuleText")).isNull();
    }

    @Test
    @DisplayName("null/blank reportId → IllegalArgumentException")
    void convertIssueToEvent_blankReportId_throws() {
        assertThatThrownBy(() -> bridge.convertIssueToEvent(null, "issue-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bridge.convertIssueToEvent("  ", "issue-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bridge.convertIssueToEvent("rep-1", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // V1.6 (G4) buildDescription prefers rootCause+proposedFix + null-safe
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("G4: buildDescription prefers rootCause + proposedFix over suggestion")
    void buildDescription_prefersRootCauseAndProposedFix() {
        OptReportIssueDto issue = new OptReportIssueDto(
                "issue-1", "Bash 路径循环", "high", 4, java.util.List.of("a", "b"),
                "skill", null, 0.85, "一句话 suggestion", "降低失败率", "new", null,
                "repeated_tool_failure", 5, "agent 没在 cd 前确认目录", "加 pwd 检查规则");

        String desc = OptReportToEventBridge.buildDescription(issue);

        assertThat(desc).contains("Bash 路径循环");
        assertThat(desc).contains("Root cause: agent 没在 cd 前确认目录");
        assertThat(desc).contains("Proposed fix: 加 pwd 检查规则");
        assertThat(desc).contains("Expected: 降低失败率");
        // suggestion is NOT used when rootCause/proposedFix present
        assertThat(desc).doesNotContain("一句话 suggestion");
    }

    @Test
    @DisplayName("G4: buildDescription null-safe when suggestion is null (rootCause-only DTO)")
    void buildDescription_suggestionNull_noNpe_usesRootCause() {
        // V1.6 demoted suggestion to optional → a parser-produced DTO can have
        // suggestion=null. buildDescription must not NPE.
        OptReportIssueDto issue = new OptReportIssueDto(
                "issue-1", "标题", "medium", 2, java.util.List.of("a"),
                "prompt", null, 0.6, null, null, "new", null,
                "missing_context", 1, "没读到关键上下文", null);

        String desc = OptReportToEventBridge.buildDescription(issue);

        assertThat(desc).contains("标题");
        assertThat(desc).contains("Root cause: 没读到关键上下文");
    }

    @Test
    @DisplayName("G4: buildDescription falls back to suggestion when no rootCause/proposedFix (legacy)")
    void buildDescription_legacyNoSplit_fallsBackToSuggestion() {
        OptReportIssueDto issue = new OptReportIssueDto(
                "issue-1", "标题", "low", 1, java.util.List.of("a"),
                "skill", null, 0.5, "改这个", null, "new", null,
                null, 1, null, null);

        String desc = OptReportToEventBridge.buildDescription(issue);

        assertThat(desc).contains("标题");
        assertThat(desc).contains("改这个");
        assertThat(desc).doesNotContain("Root cause:");
    }

    @Test
    @DisplayName("G4: enrichTopIssues coerces null suggestion → \"\" and exposes friction/recurrence/rootCause/proposedFix")
    void enrichTopIssues_g4FacetsAndSuggestionNullSafe() {
        String reportId = "rep-g4";
        // issue-1 omits suggestion (rootCause carries content) → enrich must
        // surface suggestion as "" (FE non-null contract) + the G4 facets.
        String summaryJson = """
                { "topIssues": [
                    {
                      "id": "issue-1", "title": "高复现 Bash 失败",
                      "severity": "high", "sessionCount": 4,
                      "exampleSessionIds": ["a", "b"],
                      "suspectSurface": "skill", "confidence": 0.8,
                      "friction": "repeated_tool_failure",
                      "recurrence": 5,
                      "rootCause": "没确认目录",
                      "proposedFix": "加 pwd 检查"
                    }
                ]}
                """;
        when(eventRepository.findBySourceReportId(reportId))
                .thenReturn(java.util.Collections.emptyList());

        java.util.List<java.util.Map<String, Object>> enriched =
                bridge.enrichTopIssues(reportId, summaryJson);

        assertThat(enriched).hasSize(1);
        java.util.Map<String, Object> m = enriched.get(0);
        // suggestion null → "" (not null, not "null")
        assertThat(m).containsEntry("suggestion", "");
        assertThat(m).containsEntry("friction", "repeated_tool_failure");
        assertThat(m).containsEntry("recurrence", 5);
        assertThat(m).containsEntry("rootCause", "没确认目录");
        assertThat(m).containsEntry("proposedFix", "加 pwd 检查");
    }

    private static FlywheelRunEntity completedReport(String id, long agentId, String summaryJson) {
        FlywheelRunEntity r = new FlywheelRunEntity();
        r.setId(id);
        r.setAgentId(agentId);
        r.setStatus(FlywheelRunEntity.STATUS_COMPLETED);
        r.setSummaryJson(summaryJson);
        return r;
    }
}
