package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.improve.BehaviorRuleImproverService;
import com.skillforge.server.improve.EvolveEditorContext;
import com.skillforge.server.improve.ImprovementStartResult;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.SkillDraftService;
import com.skillforge.server.optreport.OptReportToEventBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C2) — {@link GenerateCandidateTool}:
 * surface routing to the right improver (prompt / skill / behavior_rule), the
 * two audit-anchor input modes (direct eventId / report-issue bridge), ownerId
 * + patternId defaults, and validation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GenerateCandidateTool")
class GenerateCandidateToolTest {

    @Mock private PromptImproverService promptImproverService;
    @Mock private SkillDraftService skillDraftService;
    @Mock private BehaviorRuleImproverService behaviorRuleImproverService;
    @Mock private OptReportToEventBridge optReportToEventBridge;
    @Mock private FlywheelRunRepository flywheelRunRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GenerateCandidateTool tool;

    @BeforeEach
    void setUp() {
        tool = new GenerateCandidateTool(promptImproverService, skillDraftService,
                behaviorRuleImproverService, optReportToEventBridge,
                flywheelRunRepository, objectMapper);
    }

    private SkillResult run(Map<String, Object> input) {
        return tool.execute(input, new SkillContext("/tmp", "sess", 7L));
    }

    /** Build a stubbed minted event the bridge returns for (reportId, issueId). */
    private OptReportToEventBridge.ConvertResult mintedEvent(Long eventId, Long agentId) {
        OptimizationEventEntity ev = new OptimizationEventEntity();
        ev.setId(eventId);
        ev.setAgentId(agentId);
        return new OptReportToEventBridge.ConvertResult(ev, false);
    }

    /** Stub the report-ownership lookup the tool does BEFORE minting the event. */
    private void stubReport(String reportId, Long agentId) {
        FlywheelRunEntity r = new FlywheelRunEntity();
        r.setId(reportId);
        r.setAgentId(agentId);
        when(flywheelRunRepository.findById(reportId)).thenReturn(Optional.of(r));
    }

    // ── direct eventId mode (backward compat) ──────────────────────────────

    @Test
    @DisplayName("prompt + direct eventId: routes to prompt improver, ownerId defaults to system (0)")
    void promptSurface_directEventId() {
        when(promptImproverService.startImprovementFromAttribution(eq(55L), eq("42"), eq("the issue"), eq(0L)))
                .thenReturn(new ImprovementStartResult("42", null, "prompt-v2", "PENDING"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("issue", "the issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"prompt-v2\"");
        assertThat(result.getOutput()).contains("\"surface\":\"prompt\"");
        verify(promptImproverService).startImprovementFromAttribution(55L, "42", "the issue", 0L);
        verifyNoInteractions(skillDraftService, behaviorRuleImproverService, optReportToEventBridge);
    }

    @Test
    @DisplayName("behavior_rule + explicit ownerId: routes to behavior improver with that owner")
    void behaviorRuleSurface_explicitOwner() {
        when(behaviorRuleImproverService.startImprovementFromAttribution(eq(55L), eq("42"), eq("issue"), eq(3L)))
                .thenReturn(new ImprovementStartResult("42", null, "brule-v3", "PENDING"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "behavior_rule");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");
        input.put("ownerId", "3");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"brule-v3\"");
        verify(behaviorRuleImproverService).startImprovementFromAttribution(55L, "42", "issue", 3L);
        verifyNoInteractions(promptImproverService, skillDraftService, optReportToEventBridge);
    }

    @Test
    @DisplayName("behavior_rule + baseVersionId: routes to carry-forward improveFromBaseVersion (§8 #5)")
    void behaviorRuleSurface_baseVersionId_routesToCarryForward() {
        when(behaviorRuleImproverService.startImprovementFromBaseVersion(
                eq(55L), eq("42"), eq("best-brv"), eq("issue"), eq(0L)))
                .thenReturn(new ImprovementStartResult("42", null, "brule-v7", "PENDING"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "behavior_rule");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");
        input.put("baseVersionId", "best-brv");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"brule-v7\"");
        verify(behaviorRuleImproverService)
                .startImprovementFromBaseVersion(55L, "42", "best-brv", "issue", 0L);
        verify(behaviorRuleImproverService, org.mockito.Mockito.never())
                .startImprovementFromAttribution(any(), any(), any(), any());
        verifyNoInteractions(promptImproverService, skillDraftService, optReportToEventBridge);
    }

    @Test
    @DisplayName("behavior_rule + priorChange/priorEvalReport: routes to editor-aware overload (§9 line A #2)")
    void behaviorRuleSurface_reflection_routesToEditorOverload() {
        when(behaviorRuleImproverService.startImprovementFromAttribution(
                eq(55L), eq("42"), eq("issue"), eq(0L), any(EvolveEditorContext.class)))
                .thenReturn(new ImprovementStartResult("42", null, "brule-v8", "PENDING"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "behavior_rule");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");
        input.put("priorChange", "last round added a refusal rule");
        input.put("priorEvalReport", "{\"s1\":\"improved\"}");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"brule-v8\"");
        ArgumentCaptor<EvolveEditorContext> editorCap = ArgumentCaptor.forClass(EvolveEditorContext.class);
        verify(behaviorRuleImproverService).startImprovementFromAttribution(
                eq(55L), eq("42"), eq("issue"), eq(0L), editorCap.capture());
        EvolveEditorContext editor = editorCap.getValue();
        assertThat(editor.priorChangeSummary()).isEqualTo("last round added a refusal rule");
        assertThat(editor.priorEvalReportJson()).isEqualTo("{\"s1\":\"improved\"}");
        // editor-aware overload chosen, NOT the no-editor one
        verify(behaviorRuleImproverService, org.mockito.Mockito.never())
                .startImprovementFromAttribution(any(), any(), any(), any());
    }

    @Test
    @DisplayName("behavior_rule + baseVersionId + reflection: routes to editor-aware carry-forward overload")
    void behaviorRuleSurface_baseVersion_reflection_routesToEditorCarryForward() {
        when(behaviorRuleImproverService.startImprovementFromBaseVersion(
                eq(55L), eq("42"), eq("best-brv"), eq("issue"), eq(0L), any(EvolveEditorContext.class)))
                .thenReturn(new ImprovementStartResult("42", null, "brule-v9", "PENDING"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "behavior_rule");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");
        input.put("baseVersionId", "best-brv");
        input.put("priorChange", "prev change");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"brule-v9\"");
        verify(behaviorRuleImproverService).startImprovementFromBaseVersion(
                eq(55L), eq("42"), eq("best-brv"), eq("issue"), eq(0L), any(EvolveEditorContext.class));
        verify(behaviorRuleImproverService, org.mockito.Mockito.never())
                .startImprovementFromBaseVersion(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("skill + direct eventId: routes with explicit patternId + ownerId")
    void skillSurface_directEventId() {
        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setId("draft-77");
        when(skillDraftService.createDraftFromAttribution(
                eq(55L), eq(9L), eq("issue"), isNull(), isNull(), eq(3L), any()))
                .thenReturn(draft);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "skill");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");
        input.put("patternId", "9");
        input.put("ownerId", "3");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"draft-77\"");
        verify(skillDraftService).createDraftFromAttribution(
                eq(55L), eq(9L), eq("issue"), isNull(), isNull(), eq(3L), any());
        verifyNoInteractions(promptImproverService, behaviorRuleImproverService, optReportToEventBridge);
    }

    @Test
    @DisplayName("skill: missing patternId synthesises patternId=eventId, ownerId defaults to system (0)")
    void skillSurface_missingPatternId_synthesises() {
        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setId("draft-88");
        when(skillDraftService.createDraftFromAttribution(
                eq(55L), eq(55L), eq("issue"), isNull(), isNull(), eq(0L), any()))
                .thenReturn(draft);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "skill");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"draft-88\"");
        // patternId synthesised = eventId (55), ownerId defaulted = 0
        verify(skillDraftService).createDraftFromAttribution(
                eq(55L), eq(55L), eq("issue"), isNull(), isNull(), eq(0L), any());
    }

    @Test
    @DisplayName("skill + baseDraftId (hill-climb): routes to createDraftFromBaseDraft, not Attribution")
    void skillSurface_baseDraftId_routesCarryForward() {
        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setId("draft-cf");
        when(skillDraftService.createDraftFromBaseDraft(
                eq(55L), eq("best-draft"), eq("issue"), eq(3L)))
                .thenReturn(draft);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "skill");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");
        input.put("ownerId", "3");
        input.put("baseDraftId", "best-draft");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"draft-cf\"");
        verify(skillDraftService).createDraftFromBaseDraft(eq(55L), eq("best-draft"), eq("issue"), eq(3L));
        // fresh path NOT taken
        verify(skillDraftService, org.mockito.Mockito.never())
                .createDraftFromAttribution(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("skill + baseDraftId + reflection: routes to the editor-aware carry-forward overload")
    void skillSurface_baseDraftId_withReflection_routesEditorOverload() {
        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setId("draft-cf2");
        when(skillDraftService.createDraftFromBaseDraft(
                eq(55L), eq("best-draft"), eq("issue"), eq(0L), any(EvolveEditorContext.class)))
                .thenReturn(draft);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "skill");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");
        input.put("baseDraftId", "best-draft");
        input.put("priorChange", "last round refined the body");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"draft-cf2\"");
        verify(skillDraftService).createDraftFromBaseDraft(
                eq(55L), eq("best-draft"), eq("issue"), eq(0L), any(EvolveEditorContext.class));
        verify(skillDraftService, org.mockito.Mockito.never())
                .createDraftFromBaseDraft(any(), any(), any(), any());
    }

    // ── report-issue bridge mode (preferred) ───────────────────────────────

    @Test
    @DisplayName("prompt + reportId/issueId: ownership ok → mints eventId via bridge, routes to improver")
    void promptSurface_reportIssueBridge() {
        // ownership gate: report belongs to target agent 42 (use a >127 id to prove
        // numeric, not reference, comparison).
        stubReport("rep-1", 900L);
        when(optReportToEventBridge.convertIssueToEvent("rep-1", "issue-3"))
                .thenReturn(mintedEvent(7700L, 900L));
        when(promptImproverService.startImprovementFromAttribution(eq(7700L), eq("900"), eq("fix it"), eq(0L)))
                .thenReturn(new ImprovementStartResult("900", null, "prompt-v9", "PENDING"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("issue", "fix it");
        input.put("targetAgentId", "900");
        input.put("reportId", "rep-1");
        input.put("issueId", "issue-3");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"candidateId\":\"prompt-v9\"");
        verify(optReportToEventBridge).convertIssueToEvent("rep-1", "issue-3");
        verify(promptImproverService).startImprovementFromAttribution(7700L, "900", "fix it", 0L);
    }

    @Test
    @DisplayName("concern#2: report-issue mode feeds the event's enriched description (rootCause/proposedFix) to the improver, NOT the thin orchestrator issue")
    void reportIssueBridge_enrichedDescription_feedsImproverNotThinIssue() {
        stubReport("rep-9", 900L);
        OptReportToEventBridge.ConvertResult minted = mintedEvent(8800L, 900L);
        // The minted event's description = G4/G5-enriched buildDescription output.
        String enriched = "Edit fails repeatedly\n\nRoot cause: old_string came from a stale "
                + "snapshot\n\nProposed fix: fresh Read of the file immediately before the Edit";
        minted.event().setDescription(enriched);
        when(optReportToEventBridge.convertIssueToEvent("rep-9", "issue-7")).thenReturn(minted);
        when(promptImproverService.startImprovementFromAttribution(eq(8800L), eq("900"), any(), eq(0L)))
                .thenReturn(new ImprovementStartResult("900", null, "prompt-v10", "PENDING"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("issue", "thin orchestrator issue");   // should be OVERRIDDEN by the enriched description
        input.put("targetAgentId", "900");
        input.put("reportId", "rep-9");
        input.put("issueId", "issue-7");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<String> issueCap = ArgumentCaptor.forClass(String.class);
        verify(promptImproverService).startImprovementFromAttribution(eq(8800L), eq("900"), issueCap.capture(), eq(0L));
        assertThat(issueCap.getValue())
                .isEqualTo(enriched)
                .contains("Root cause:")
                .contains("Proposed fix:")
                .isNotEqualTo("thin orchestrator issue");
    }

    @Test
    @DisplayName("report-issue: report belongs to another agent → rejected BEFORE minting (bridge NOT called)")
    void reportIssueBridge_crossAgentMismatch_rejectedBeforeMint() {
        stubReport("rep-1", 99L);   // report agent 99 != target 42

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("issue", "fix it");
        input.put("targetAgentId", "42");
        input.put("reportId", "rep-1");
        input.put("issueId", "issue-3");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("belongs to agent 99");
        // SECURITY: the event must NOT be minted for a cross-agent report.
        verifyNoInteractions(optReportToEventBridge,
                promptImproverService, skillDraftService, behaviorRuleImproverService);
    }

    @Test
    @DisplayName("report-issue: unknown reportId → validation error, bridge NOT called")
    void reportIssueBridge_unknownReportId_validationError() {
        when(flywheelRunRepository.findById("nope")).thenReturn(Optional.empty());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("issue", "fix it");
        input.put("targetAgentId", "42");
        input.put("reportId", "nope");
        input.put("issueId", "issue-3");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("not found");
        verifyNoInteractions(optReportToEventBridge,
                promptImproverService, skillDraftService, behaviorRuleImproverService);
    }

    @Test
    @DisplayName("both modes supplied (eventId AND reportId) → validation error")
    void bothModes_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");
        input.put("reportId", "rep-1");
        input.put("issueId", "issue-3");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("not both");
        verifyNoInteractions(promptImproverService, skillDraftService,
                behaviorRuleImproverService, optReportToEventBridge);
    }

    @Test
    @DisplayName("reportId without issueId → validation error")
    void reportIdWithoutIssueId_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("reportId", "rep-1");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        verifyNoInteractions(promptImproverService, skillDraftService,
                behaviorRuleImproverService, optReportToEventBridge);
    }

    // ── general validation ─────────────────────────────────────────────────

    @Test
    @DisplayName("no audit anchor (no eventId, no reportId) → validation error")
    void noAuditAnchor_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("audit anchor is required");
        verifyNoInteractions(promptImproverService, skillDraftService,
                behaviorRuleImproverService, optReportToEventBridge);
    }

    @Test
    @DisplayName("unknown surface → validation error")
    void unknownSurface_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "bogus");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "55");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("surface");
        verifyNoInteractions(promptImproverService, skillDraftService,
                behaviorRuleImproverService, optReportToEventBridge);
    }

    @Test
    @DisplayName("non-numeric eventId → validation error")
    void nonNumericEventId_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("issue", "issue");
        input.put("targetAgentId", "42");
        input.put("eventId", "abc");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    @Test
    @DisplayName("tool metadata: name, not read-only")
    void metadata() {
        assertThat(tool.getName()).isEqualTo("GenerateCandidate");
        assertThat(tool.isReadOnly()).isFalse();
    }
}
