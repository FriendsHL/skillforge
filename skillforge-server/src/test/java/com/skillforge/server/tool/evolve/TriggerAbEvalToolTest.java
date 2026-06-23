package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.improve.AbEvalRunRequest;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.SkillDraftService;
import com.skillforge.server.improve.behavior.BehaviorRuleAbEvalService;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module B — {@link TriggerAbEvalTool}: surface
 * routing (prompt / skill / behavior_rule → right service), validation errors,
 * baseline pass-through, and SECURITY candidate-ownership checks for skill and
 * behavior_rule (cross-agent candidates rejected before eval fires).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerAbEvalTool")
class TriggerAbEvalToolTest {

    @Mock private PromptImproverService promptImproverService;
    @Mock private SkillDraftService skillDraftService;
    @Mock private BehaviorRuleAbEvalService behaviorRuleAbEvalService;
    @Mock private com.skillforge.server.improve.agent.AgentEvolveAbEvalService agentEvolveAbEvalService;
    @Mock private SkillDraftRepository skillDraftRepository;
    @Mock private BehaviorRuleVersionRepository behaviorRuleVersionRepository;
    @Mock private FlywheelRunService flywheelRunService;

    private static final int AB_BUDGET = 3;
    private static final int AB_BUDGET_WINDOW_HOURS = 168;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TriggerAbEvalTool tool;

    @BeforeEach
    void setUp() {
        tool = new TriggerAbEvalTool(promptImproverService, skillDraftService,
                behaviorRuleAbEvalService, agentEvolveAbEvalService, skillDraftRepository,
                behaviorRuleVersionRepository, flywheelRunService, AB_BUDGET,
                AB_BUDGET_WINDOW_HOURS, objectMapper);
    }

    private SkillResult run(Map<String, Object> input) {
        return tool.execute(input, new SkillContext("/tmp", "sess", 7L));
    }

    // ─────────────────── prompt surface ───────────────────

    @Test
    @DisplayName("prompt surface: routes to runAbTestAgainst with baseline/scenarios passed through")
    void promptSurface_routesToPromptService() {
        when(promptImproverService.runAbTestAgainst(any(AbEvalRunRequest.class)))
                .thenReturn("prompt-ab-1");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("candidateId", "cand-v2");
        input.put("targetAgentId", "42");
        input.put("baselineId", "base-v1");
        input.put("evalScenarioIds", List.of("s1", "s2"));

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"abRunId\":\"prompt-ab-1\"");
        assertThat(result.getOutput()).contains("\"surface\":\"prompt\"");

        ArgumentCaptor<AbEvalRunRequest> cap = ArgumentCaptor.forClass(AbEvalRunRequest.class);
        verify(promptImproverService).runAbTestAgainst(cap.capture());
        AbEvalRunRequest req = cap.getValue();
        assertThat(req.agentId()).isEqualTo("42");
        assertThat(req.baselineVersionId()).isEqualTo("base-v1");
        assertThat(req.candidateVersionId()).isEqualTo("cand-v2");
        assertThat(req.evalScenarioIds()).containsExactly("s1", "s2");
        // prompt surface does not use the candidate-entity repos
        verifyNoInteractions(skillDraftRepository, behaviorRuleVersionRepository);
        verifyNoInteractions(skillDraftService, behaviorRuleAbEvalService);
    }

    @Test
    @DisplayName("prompt surface: null baselineId passes through (B4 active-baseline reuse)")
    void promptSurface_nullBaseline_passesThrough() {
        when(promptImproverService.runAbTestAgainst(any(AbEvalRunRequest.class)))
                .thenReturn("prompt-ab-2");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("candidateId", "cand-v2");
        input.put("targetAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<AbEvalRunRequest> cap = ArgumentCaptor.forClass(AbEvalRunRequest.class);
        verify(promptImproverService).runAbTestAgainst(cap.capture());
        assertThat(cap.getValue().baselineVersionId()).isNull();
    }

    // ─────────────────── skill surface ───────────────────

    @Test
    @DisplayName("skill surface: ownership confirmed → routes to startAbTestFromDraft")
    void skillSurface_ownershipOk_routesToSkillDraftService() {
        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setTargetAgentId(42L);
        when(skillDraftRepository.findById("draft-9")).thenReturn(Optional.of(draft));
        when(skillDraftService.startAbTestFromDraft(eq("draft-9"), isNull()))
                .thenReturn("skill-ab-1");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "skill");
        input.put("candidateId", "draft-9");
        input.put("targetAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"abRunId\":\"skill-ab-1\"");
        verify(skillDraftService).startAbTestFromDraft("draft-9", null);
        verifyNoInteractions(promptImproverService, behaviorRuleAbEvalService);
    }

    @Test
    @DisplayName("SECURITY skill: draft belongs to another agent → rejected, service NOT called")
    void skillSurface_crossAgentCandidate_rejected() {
        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setTargetAgentId(99L);  // belongs to agent 99
        when(skillDraftRepository.findById("draft-9")).thenReturn(Optional.of(draft));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "skill");
        input.put("candidateId", "draft-9");
        input.put("targetAgentId", "42");  // caller claims agent 42

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"status\":\"rejected\"");
        assertThat(result.getOutput()).contains("ownership mismatch");
        verify(skillDraftService, never()).startAbTestFromDraft(any(), any());
    }

    @Test
    @DisplayName("skill surface: draft not found → passes through to service (service reports error)")
    void skillSurface_draftNotFound_passesThrough() {
        // When the draft doesn't exist, we let the downstream service produce the error.
        when(skillDraftRepository.findById("draft-missing")).thenReturn(Optional.empty());
        when(skillDraftService.startAbTestFromDraft(eq("draft-missing"), isNull()))
                .thenThrow(new IllegalArgumentException("Candidate skill draft not found"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "skill");
        input.put("candidateId", "draft-missing");
        input.put("targetAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("not found");
    }

    // ─────────────────── behavior_rule surface ───────────────────

    @Test
    @DisplayName("behavior_rule surface: ownership confirmed → routes to startAbForVersion")
    void behaviorRuleSurface_ownershipOk_routesToBehaviorService() {
        BehaviorRuleVersionEntity version = new BehaviorRuleVersionEntity();
        version.setAgentId("42");
        when(behaviorRuleVersionRepository.findById("ver-7")).thenReturn(Optional.of(version));
        when(behaviorRuleAbEvalService.startAbForVersion(eq("ver-7"), eq("ds-1")))
                .thenReturn("brule-ab-1");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "behavior_rule");
        input.put("candidateId", "ver-7");
        input.put("targetAgentId", "42");
        input.put("datasetVersionId", "ds-1");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"abRunId\":\"brule-ab-1\"");
        verify(behaviorRuleAbEvalService).startAbForVersion("ver-7", "ds-1");
        verifyNoInteractions(promptImproverService, skillDraftService);
    }

    @Test
    @DisplayName("SECURITY behavior_rule: version belongs to another agent → rejected, service NOT called")
    void behaviorRuleSurface_crossAgentCandidate_rejected() {
        BehaviorRuleVersionEntity version = new BehaviorRuleVersionEntity();
        version.setAgentId("99");  // belongs to agent 99
        when(behaviorRuleVersionRepository.findById("ver-7")).thenReturn(Optional.of(version));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "behavior_rule");
        input.put("candidateId", "ver-7");
        input.put("targetAgentId", "42");  // caller claims agent 42

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"status\":\"rejected\"");
        assertThat(result.getOutput()).contains("ownership mismatch");
        verify(behaviorRuleAbEvalService, never()).startAbForVersion(any(), any());
    }

    // ─────────────────── validation ───────────────────

    @Test
    @DisplayName("unknown surface → validation error, no service called")
    void unknownSurface_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "bogus");
        input.put("candidateId", "x");
        input.put("targetAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("surface");
        verifyNoInteractions(promptImproverService, skillDraftService, behaviorRuleAbEvalService);
    }

    @Test
    @DisplayName("missing candidateId → validation error")
    void missingCandidateId_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("targetAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("candidateId is required");
        verify(promptImproverService, never()).runAbTestAgainst(any(AbEvalRunRequest.class));
    }

    @Test
    @DisplayName("missing targetAgentId → validation error")
    void missingTargetAgentId_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("candidateId", "c");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("targetAgentId is required");
    }

    @Test
    @DisplayName("IllegalStateException from service (e.g. no dataset) → error result, not crash")
    void serviceIllegalState_mappedToError() {
        // version found with correct ownership, but service throws (no dataset)
        BehaviorRuleVersionEntity version = new BehaviorRuleVersionEntity();
        version.setAgentId("42");
        when(behaviorRuleVersionRepository.findById("ver-7")).thenReturn(Optional.of(version));
        when(behaviorRuleAbEvalService.startAbForVersion(any(), any()))
                .thenThrow(new IllegalStateException("No dataset version available"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "behavior_rule");
        input.put("candidateId", "ver-7");
        input.put("targetAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No dataset version available");
    }

    // ─────────────────── FR-C7 (CRIT-1 fix): per-agent A/B budget cap ───────────────────
    //
    // Cap is always enforced on targetAgentId (always-required). An LLM that omits
    // evolveRunId cannot bypass it. evolveRunId is optional metadata for per-run
    // precision. DB failure during count → fail closed (reject).

    @Test
    @DisplayName("FR-C7 CRIT-1: cap fires on agent count under cap → A/B fires")
    void abBudget_perAgent_underCap_fires() {
        // Per-agent count under cap → should allow through.
        when(flywheelRunService.countEvolveAbTriggersForAgent(eq(42L), eq(AB_BUDGET_WINDOW_HOURS))).thenReturn(2L); // < AB_BUDGET=3
        when(promptImproverService.runAbTestAgainst(any(AbEvalRunRequest.class)))
                .thenReturn("prompt-ab-ok");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("candidateId", "cand-v2");
        input.put("targetAgentId", "42");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"abRunId\":\"prompt-ab-ok\"");
        verify(flywheelRunService).countEvolveAbTriggersForAgent(eq(42L), eq(AB_BUDGET_WINDOW_HOURS));
        verify(promptImproverService).runAbTestAgainst(any(AbEvalRunRequest.class));
    }

    @Test
    @DisplayName("FR-C7 CRIT-1: cap fires on agent count at cap → REJECTED (no evolveRunId = no bypass)")
    void abBudget_perAgent_atCap_rejected_withoutEvolveRunId() {
        // Per-agent count at cap — LLM omitted evolveRunId, should still be REJECTED.
        when(flywheelRunService.countEvolveAbTriggersForAgent(eq(42L), eq(AB_BUDGET_WINDOW_HOURS))).thenReturn(3L); // == AB_BUDGET=3

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("candidateId", "cand-v2");
        input.put("targetAgentId", "42");
        // evolveRunId deliberately omitted — must NOT bypass cap

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"status\":\"rejected\"");
        assertThat(result.getOutput()).contains("A/B budget reached");
        assertThat(result.getOutput()).contains("targetAgentId");
        verify(flywheelRunService).countEvolveAbTriggersForAgent(eq(42L), eq(AB_BUDGET_WINDOW_HOURS));
        verify(promptImproverService, never()).runAbTestAgainst(any(AbEvalRunRequest.class));
    }

    @Test
    @DisplayName("FR-C7 CRIT-1: per-run count higher than per-agent → higher count used (conservative)")
    void abBudget_perRunCountHigher_takesMax() {
        // Agent count under cap, but per-run count is at cap → still reject.
        when(flywheelRunService.countEvolveAbTriggersForAgent(eq(42L), eq(AB_BUDGET_WINDOW_HOURS))).thenReturn(1L); // < cap
        when(flywheelRunService.countEvolveAbTriggers("evolve-1")).thenReturn(3L); // == cap

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("candidateId", "cand-v2");
        input.put("targetAgentId", "42");
        input.put("evolveRunId", "evolve-1");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"status\":\"rejected\"");
        verify(promptImproverService, never()).runAbTestAgainst(any(AbEvalRunRequest.class));
    }

    @Test
    @DisplayName("FR-C7 HIGH-3: DB error during cap count → FAIL CLOSED (reject, not allow)")
    void abBudget_dbError_failsClosed() {
        when(flywheelRunService.countEvolveAbTriggersForAgent(eq(42L), eq(AB_BUDGET_WINDOW_HOURS)))
                .thenThrow(new RuntimeException("DB connection lost"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("candidateId", "cand-v2");
        input.put("targetAgentId", "42");

        SkillResult result = run(input);

        // Must fail closed — error (not success/allowed through).
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("budget check failed");
        verify(promptImproverService, never()).runAbTestAgainst(any(AbEvalRunRequest.class));
    }

    @Test
    @DisplayName("FR-C7 CRIT-1: evolveRunId present + both counts under cap → A/B fires")
    void abBudget_withEvolveRunId_bothUnderCap_fires() {
        when(flywheelRunService.countEvolveAbTriggersForAgent(eq(42L), eq(AB_BUDGET_WINDOW_HOURS))).thenReturn(2L);
        when(flywheelRunService.countEvolveAbTriggers("evolve-1")).thenReturn(1L);
        when(promptImproverService.runAbTestAgainst(any(AbEvalRunRequest.class)))
                .thenReturn("prompt-ab-ok");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "prompt");
        input.put("candidateId", "cand-v2");
        input.put("targetAgentId", "42");
        input.put("evolveRunId", "evolve-1");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"abRunId\":\"prompt-ab-ok\"");
        verify(flywheelRunService).countEvolveAbTriggersForAgent(eq(42L), eq(AB_BUDGET_WINDOW_HOURS));
        verify(flywheelRunService).countEvolveAbTriggers("evolve-1");
    }

    // ─────────────────── agent surface (whole-agent bundle A/B) ───────────────────

    @Test
    @DisplayName("agent surface: routes to startAgentAb with parsed bundles (full run, no cached rate)")
    void agentSurface_routesToStartAgentAb() {
        when(agentEvolveAbEvalService.startAgentAb(any(), any(), eq("42"), eq("ds-1"), isNull(), isNull(), isNull()))
                .thenReturn("agent-ab-1");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "agent");
        input.put("targetAgentId", "42");
        input.put("candidateBundle", Map.of("promptVersionId", "pv-cand"));
        input.put("baselineBundle", Map.of("promptVersionId", "pv-best"));
        input.put("datasetVersionId", "ds-1");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"abRunId\":\"agent-ab-1\"");
        assertThat(result.getOutput()).contains("\"surface\":\"agent\"");
        // no evalScenarioIds supplied → explicit target ids null (role-based split)
        verify(agentEvolveAbEvalService).startAgentAb(any(), any(), eq("42"), eq("ds-1"), isNull(), isNull(), isNull());
        // agent surface does not use the per-surface candidate repos / services
        verifyNoInteractions(promptImproverService, skillDraftService, behaviorRuleAbEvalService);
    }

    @Test
    @DisplayName("agent surface: evalScenarioIds threads through as the explicit target subset (①d)")
    void agentSurface_evalScenarioIds_threadedAsTargetSplit() {
        when(agentEvolveAbEvalService.startAgentAb(
                any(), any(), eq("42"), eq("ds-1"), isNull(), eq(List.of("bad-1", "bad-2")), isNull()))
                .thenReturn("agent-ab-2");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "agent");
        input.put("targetAgentId", "42");
        input.put("candidateBundle", Map.of("promptVersionId", "pv-cand"));
        input.put("baselineBundle", Map.of("promptVersionId", "pv-best"));
        input.put("datasetVersionId", "ds-1");
        input.put("evalScenarioIds", List.of("bad-1", "bad-2"));

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"abRunId\":\"agent-ab-2\"");
        verify(agentEvolveAbEvalService).startAgentAb(
                any(), any(), eq("42"), eq("ds-1"), isNull(), eq(List.of("bad-1", "bad-2")), isNull());
    }

    @Test
    @DisplayName("W-WARN-2 agent surface: present-but-out-of-range cachedBaselineScore → validationError (no run)")
    void agentSurface_cachedScoreOutOfRange_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "agent");
        input.put("targetAgentId", "42");
        input.put("candidateBundle", Map.of("promptVersionId", "pv-cand"));
        input.put("baselineBundle", Map.of("promptVersionId", "pv-best"));
        input.put("datasetVersionId", "ds-1");
        input.put("cachedBaselineScore", 150.0);   // present but out of [0,100]

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("cachedBaselineScore must be a number in [0, 100]");
        // Must NOT silently degrade to a full A/B run.
        verify(agentEvolveAbEvalService, never()).startAgentAb(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("F4 agent surface: priorWinnerAbRunId + cachedBaselineScore thread through to startAgentAb")
    void agentSurface_priorWinnerAbRunId_threadedThrough() {
        when(agentEvolveAbEvalService.startAgentAb(
                any(), any(), eq("42"), eq("ds-1"), eq(72.5), isNull(), eq("ab-prior-1")))
                .thenReturn("agent-ab-3");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "agent");
        input.put("targetAgentId", "42");
        input.put("candidateBundle", Map.of("promptVersionId", "pv-cand"));
        input.put("baselineBundle", Map.of("promptVersionId", "pv-best"));
        input.put("datasetVersionId", "ds-1");
        input.put("cachedBaselineScore", 72.5);
        input.put("priorWinnerAbRunId", "ab-prior-1");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"abRunId\":\"agent-ab-3\"");
        verify(agentEvolveAbEvalService).startAgentAb(
                any(), any(), eq("42"), eq("ds-1"), eq(72.5), isNull(), eq("ab-prior-1"));
    }

    @Test
    @DisplayName("F4 agent surface: priorWinnerAbRunId WITHOUT cachedBaselineScore → validationError (no run)")
    void agentSurface_priorWinnerWithoutCachedScore_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "agent");
        input.put("targetAgentId", "42");
        input.put("candidateBundle", Map.of("promptVersionId", "pv-cand"));
        input.put("baselineBundle", Map.of("promptVersionId", "pv-best"));
        input.put("datasetVersionId", "ds-1");
        input.put("priorWinnerAbRunId", "ab-prior-1");   // unpaired — caller bug

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("priorWinnerAbRunId requires cachedBaselineScore");
        verify(agentEvolveAbEvalService, never()).startAgentAb(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("agent surface: missing candidateBundle → validationError")
    void agentSurface_missingBundle_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("surface", "agent");
        input.put("targetAgentId", "42");
        input.put("datasetVersionId", "ds-1");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isFalse();
        verify(agentEvolveAbEvalService, never()).startAgentAb(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("tool metadata: name, not read-only")
    void metadata() {
        assertThat(tool.getName()).isEqualTo("TriggerAbEval");
        assertThat(tool.isReadOnly()).isFalse();
    }
}
