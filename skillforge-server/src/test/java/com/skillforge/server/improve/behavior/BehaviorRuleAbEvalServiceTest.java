package com.skillforge.server.improve.behavior;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.improve.AbEvalPipeline;
import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.BehaviorRuleAbRunRepository;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.EvalDatasetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BehaviorRuleAbEvalService")
class BehaviorRuleAbEvalServiceTest {

    @Mock private BehaviorRuleVersionRepository versionRepository;
    @Mock private BehaviorRuleAbRunRepository abRunRepository;
    @Mock private EvalScenarioDraftRepository scenarioRepository;
    @Mock private EvalDatasetService evalDatasetService;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentService agentService;
    @Mock private AbEvalPipeline abEvalPipeline;
    @Mock private ChatEventBroadcaster broadcaster;

    // Mirror Spring Boot's auto-configured ObjectMapper: JavaTimeModule
    // registered (footgun #1) AND FAIL_ON_UNKNOWN_PROPERTIES disabled
    // (Spring Boot default — keeps AgentDefinition.cloneDef's JSON roundtrip
    // working in the presence of derived getters like getMaxTokens() that
    // serialize but have no setter).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ExecutorService directExecutor = Executors.newSingleThreadExecutor();

    private BehaviorRuleAbEvalService service;
    private BehaviorRuleVersionToCustomRulesMapper rulesMapper;

    @BeforeEach
    void setUp() {
        rulesMapper = new BehaviorRuleVersionToCustomRulesMapper(objectMapper);
        AgentRoleResolver agentRoleResolver = new AgentRoleResolver();
        service = new BehaviorRuleAbEvalService(
                versionRepository, abRunRepository, scenarioRepository,
                evalDatasetService, agentRepository, agentService,
                rulesMapper, abEvalPipeline, broadcaster, objectMapper,
                agentRoleResolver, directExecutor);
    }

    @DisplayName("startAbForVersion throws when version not in 'candidate' status")
    @Test
    void startAb_rejects_non_candidate_version() {
        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        v.setStatus(BehaviorRuleVersionEntity.STATUS_ACTIVE);
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.startAbForVersion("v1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only candidate")
                .hasMessageContaining("active");
    }

    @DisplayName("startAbForVersion throws when no dataset version available")
    @Test
    void startAb_rejects_when_no_dataset() {
        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        when(abRunRepository.findFirstByCandidateVersionIdAndStatusIn(eq("v1"), anyList()))
                .thenReturn(Optional.empty());
        when(evalDatasetService.findDefaultVersionIdForAgent("100")).thenReturn(null);

        assertThatThrownBy(() -> service.startAbForVersion("v1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No dataset version");
    }

    @DisplayName("startAbForVersion marks prior PENDING/RUNNING run SUPERSEDED (INV-6)")
    @Test
    void startAb_supersedes_prior_inflight_run() {
        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        BehaviorRuleAbRunEntity prior = new BehaviorRuleAbRunEntity();
        prior.setId("prior-1");
        prior.setStatus(BehaviorRuleAbRunEntity.STATUS_RUNNING);
        when(abRunRepository.findFirstByCandidateVersionIdAndStatusIn(eq("v1"), anyList()))
                .thenReturn(Optional.of(prior));
        when(evalDatasetService.findDefaultVersionIdForAgent("100")).thenReturn("dv-1");

        service.startAbForVersion("v1", null);

        // prior was saved with SUPERSEDED before the new abRun was saved
        ArgumentCaptor<BehaviorRuleAbRunEntity> savedCaptor =
                ArgumentCaptor.forClass(BehaviorRuleAbRunEntity.class);
        verify(abRunRepository, times(2)).save(savedCaptor.capture());
        List<BehaviorRuleAbRunEntity> saves = savedCaptor.getAllValues();
        assertThat(saves.get(0).getStatus()).isEqualTo(BehaviorRuleAbRunEntity.STATUS_SUPERSEDED);
        assertThat(saves.get(0).getCompletedAt()).isNotNull();
        // The new abRun is saved second with PENDING status
        assertThat(saves.get(1).getStatus()).isEqualTo(BehaviorRuleAbRunEntity.STATUS_PENDING);
    }

    @DisplayName("startAbForVersion accepts override dataset")
    @Test
    void startAb_accepts_dataset_override() {
        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        when(abRunRepository.findFirstByCandidateVersionIdAndStatusIn(eq("v1"), anyList()))
                .thenReturn(Optional.empty());

        String abRunId = service.startAbForVersion("v1", "explicit-dv");

        assertThat(abRunId).isNotBlank();
        ArgumentCaptor<BehaviorRuleAbRunEntity> savedCaptor =
                ArgumentCaptor.forClass(BehaviorRuleAbRunEntity.class);
        verify(abRunRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getDatasetVersionId()).isEqualTo("explicit-dv");
        // Agent-default lookup should NOT have been called when override is set.
        verify(evalDatasetService, never()).findDefaultVersionIdForAgent(anyString());
    }

    @DisplayName("runAsync — INV-4 fallback: empty target_trigger_tags → target_delta=null + regression-only")
    @Test
    void runAsync_fallback_mode_target_delta_null() {
        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        v.setTargetTriggerTags(List.of()); // empty → fallback
        BehaviorRuleAbRunEntity abRun = newAbRun("ab-1", "v1");
        when(abRunRepository.findById("ab-1")).thenReturn(Optional.of(abRun));
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        when(agentRepository.findById(100L)).thenReturn(Optional.of(agent));
        AgentDefinition baseDef = new AgentDefinition();
        when(agentService.toAgentDefinition(agent)).thenReturn(baseDef);
        when(scenarioRepository.findAllByDatasetVersionId("dv-1"))
                .thenReturn(List.of()); // empty also exercises the "no scenarios" guard

        service.runAsync("ab-1");

        // findAllByDatasetVersionId is the fallback path; verifies the
        // non-targeting branch was taken.
        verify(scenarioRepository).findAllByDatasetVersionId("dv-1");
        verify(scenarioRepository, never()).findTargetSubsetByDatasetVersionAndTags(anyString(), any());

        // Run failed (no scenarios) → FAILED status persisted
        ArgumentCaptor<BehaviorRuleAbRunEntity> savedCaptor =
                ArgumentCaptor.forClass(BehaviorRuleAbRunEntity.class);
        verify(abRunRepository, times(2)).save(savedCaptor.capture()); // RUNNING then FAILED
        BehaviorRuleAbRunEntity finalSave = savedCaptor.getAllValues().get(1);
        assertThat(finalSave.getStatus()).isEqualTo(BehaviorRuleAbRunEntity.STATUS_FAILED);
    }

    @DisplayName("INV-2: baseline AgentDefinition strips candidate rule text")
    @Test
    void baselineDef_does_not_contain_candidate_rule_text() {
        // Setup: a base agent that ALREADY happens to carry a rule whose text
        // matches what the candidate's rulesJson would produce. The stripper
        // must filter it out.
        AgentDefinition base = new AgentDefinition();
        AgentDefinition.BehaviorRulesConfig existing = new AgentDefinition.BehaviorRulesConfig();
        AgentDefinition.BehaviorRulesConfig.CustomRule keep =
                new AgentDefinition.BehaviorRulesConfig.CustomRule(
                        AgentDefinition.BehaviorRulesConfig.Severity.SHOULD, "Keep me");
        AgentDefinition.BehaviorRulesConfig.CustomRule strip =
                new AgentDefinition.BehaviorRulesConfig.CustomRule(
                        AgentDefinition.BehaviorRulesConfig.Severity.MUST, "When X, drop me");
        existing.setCustomRules(List.of(keep, strip));
        base.setBehaviorRules(existing);

        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        v.setRulesJson("""
                [{"id":"r1","priority":"P0","when":"X","then":"drop me"}]
                """);

        AgentDefinition baseline = service.stripCandidateRule(base, v);

        List<AgentDefinition.BehaviorRulesConfig.CustomRule> remaining =
                baseline.getBehaviorRules().getCustomRules();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getText()).isEqualTo("Keep me");
        // Also confirm original base wasn't mutated (deep-clone invariant).
        assertThat(base.getBehaviorRules().getCustomRules()).hasSize(2);
    }

    @DisplayName("injectCandidateRule appends candidate's rules to baseDef customRules")
    @Test
    void injectCandidate_appends_rules() {
        AgentDefinition base = new AgentDefinition();
        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        v.setRulesJson("""
                [{"id":"r1","priority":"P0","when":"X","then":"do Y"}]
                """);

        AgentDefinition candidate = service.injectCandidateRule(base, v);

        assertThat(candidate.getBehaviorRules()).isNotNull();
        assertThat(candidate.getBehaviorRules().getCustomRules()).hasSize(1);
        assertThat(candidate.getBehaviorRules().getCustomRules().get(0).getText())
                .isEqualTo("When X, do Y");
        // Original base must not have been mutated.
        assertThat(base.getBehaviorRules()).isNull();
    }

    @DisplayName("WebSocket broadcast payload matches FE-BE contract C2 (type=behavior_rule_ab_run_updated)")
    @Test
    void broadcast_payload_shape_matches_contract_c2() {
        // Setup minimal happy path that just exercises the broadcast at
        // ab_running stage; capture the userEvent payload via Mockito.
        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        v.setTargetTriggerTags(List.of());
        BehaviorRuleAbRunEntity abRun = newAbRun("ab-c2", "v1");
        abRun.setTriggeredByUserId(42L);  // required for userEvent fan-out
        when(abRunRepository.findById("ab-c2")).thenReturn(Optional.of(abRun));
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        when(agentRepository.findById(100L)).thenReturn(Optional.of(agent));
        when(agentService.toAgentDefinition(agent)).thenReturn(new AgentDefinition());
        when(scenarioRepository.findAllByDatasetVersionId("dv-1")).thenReturn(List.of());

        service.runAsync("ab-c2");

        // Capture all userEvent payloads (ab_running + ab_failed since the
        // empty scenario set fails the run — both should follow the contract).
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass((Class) java.util.Map.class);
        verify(broadcaster, times(2)).userEvent(eq(42L), payloadCaptor.capture());

        for (java.util.Map<String, Object> payload : payloadCaptor.getAllValues()) {
            assertThat(payload.get("type")).isEqualTo("behavior_rule_ab_run_updated");
            assertThat(payload).containsKeys("event", "abRunId", "candidateVersionId", "status");
            assertThat(payload.get("abRunId")).isEqualTo("ab-c2");
            assertThat(payload.get("candidateVersionId")).isEqualTo("v1");
            // event ∈ {ab_running, ab_completed, ab_failed}
            assertThat(String.valueOf(payload.get("event")))
                    .matches("ab_running|ab_completed|ab_failed");
        }
    }

    @DisplayName("broadcast skipped (no NPE) when triggeredByUserId null — FE recovers via polling")
    @Test
    void broadcast_skipped_when_no_user_id() {
        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        v.setTargetTriggerTags(List.of());
        BehaviorRuleAbRunEntity abRun = newAbRun("ab-nouser", "v1");
        // triggeredByUserId intentionally left null
        when(abRunRepository.findById("ab-nouser")).thenReturn(Optional.of(abRun));
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        when(agentRepository.findById(100L)).thenReturn(Optional.of(agent));
        when(agentService.toAgentDefinition(agent)).thenReturn(new AgentDefinition());
        when(scenarioRepository.findAllByDatasetVersionId("dv-1")).thenReturn(List.of());

        service.runAsync("ab-nouser");

        // No userEvent call at all — silent skip + debug log (FE polls fallback)
        verify(broadcaster, never()).userEvent(any(), any());
    }

    @DisplayName("runAsync — successful path with target + regression scenarios records dual deltas")
    @Test
    void runAsync_success_path_persists_dual_deltas() {
        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        BehaviorRuleAbRunEntity abRun = newAbRun("ab-1", "v1");
        when(abRunRepository.findById("ab-1")).thenReturn(Optional.of(abRun));
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        // FLYWHEEL-AB-AGENT-AWARE-DATASET V1: ownerRole now drives subset
        // split (not candidate.target_trigger_tags). Agent name "Design Agent"
        // → AgentRoleResolver returns DESIGN.
        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        agent.setName("Design Agent");
        when(agentRepository.findById(100L)).thenReturn(Optional.of(agent));
        when(agentService.toAgentDefinition(agent)).thenReturn(new AgentDefinition());

        // Two scenarios: tgt-1 in target (design role), reg-1 in regression (general role)
        com.skillforge.server.entity.EvalScenarioEntity tgt = newScenario("tgt-1");
        com.skillforge.server.entity.EvalScenarioEntity reg = newScenario("reg-1");
        when(scenarioRepository.findByDatasetVersionAndAgentRoles(eq("dv-1"),
                org.mockito.ArgumentMatchers.argThat(roles -> roles != null && roles.length == 1
                        && AgentRoleConstants.DESIGN.equals(roles[0]))))
                .thenReturn(List.of(tgt));
        when(scenarioRepository.findByDatasetVersionAndAgentRoles(eq("dv-1"),
                org.mockito.ArgumentMatchers.argThat(roles -> roles != null && roles.length == 1
                        && AgentRoleConstants.GENERAL.equals(roles[0]))))
                .thenReturn(List.of(reg));

        // Pipeline returns: candidate passes target but baseline doesn't;
        // regression: both pass → regression delta = 0.
        when(abEvalPipeline.runWithExplicitDefs(anyString(), anyList(), any(), any()))
                .thenReturn(List.of(
                        new AbScenarioResult("tgt-1", "tgt-1",
                                new AbScenarioResult.RunResult("PENDING_JUDGE", 0.1),  // baseline fail
                                new AbScenarioResult.RunResult("PENDING_JUDGE", 0.9)), // candidate pass
                        new AbScenarioResult("reg-1", "reg-1",
                                new AbScenarioResult.RunResult("PENDING_JUDGE", 0.8),
                                new AbScenarioResult.RunResult("PENDING_JUDGE", 0.8))));

        service.runAsync("ab-1");

        ArgumentCaptor<BehaviorRuleAbRunEntity> savedCaptor =
                ArgumentCaptor.forClass(BehaviorRuleAbRunEntity.class);
        verify(abRunRepository, times(2)).save(savedCaptor.capture()); // RUNNING then COMPLETED
        BehaviorRuleAbRunEntity completed = savedCaptor.getAllValues().get(1);
        assertThat(completed.getStatus()).isEqualTo(BehaviorRuleAbRunEntity.STATUS_COMPLETED);
        assertThat(completed.getTargetCount()).isEqualTo(1);
        assertThat(completed.getRegressionCount()).isEqualTo(1);
        // target: baseline 0% candidate 100% → delta +100pp
        assertThat(completed.getTargetDeltaPp()).isEqualTo(100.0);
        // regression: both pass → delta 0
        assertThat(completed.getRegressionDeltaPp()).isEqualTo(0.0);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private BehaviorRuleVersionEntity newCandidateVersion(String id) {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId(id);
        v.setAgentId("100");
        v.setVersionNumber(1);
        v.setStatus(BehaviorRuleVersionEntity.STATUS_CANDIDATE);
        v.setRulesJson("[]");
        v.setSource(BehaviorRuleVersionEntity.SOURCE_MANUAL);
        return v;
    }

    private BehaviorRuleAbRunEntity newAbRun(String id, String candidateVersionId) {
        BehaviorRuleAbRunEntity r = new BehaviorRuleAbRunEntity();
        r.setId(id);
        r.setAgentId("100");
        r.setCandidateVersionId(candidateVersionId);
        r.setBaselineVersionId("");
        r.setStatus(BehaviorRuleAbRunEntity.STATUS_PENDING);
        r.setDatasetVersionId("dv-1");
        r.setAbRunKind(BehaviorRuleAbRunEntity.KIND_WITH_VS_WITHOUT);
        return r;
    }

    private com.skillforge.server.entity.EvalScenarioEntity newScenario(String id) {
        com.skillforge.server.entity.EvalScenarioEntity s =
                new com.skillforge.server.entity.EvalScenarioEntity();
        s.setId(id);
        s.setName(id);
        s.setTask("task " + id);
        return s;
    }
}
