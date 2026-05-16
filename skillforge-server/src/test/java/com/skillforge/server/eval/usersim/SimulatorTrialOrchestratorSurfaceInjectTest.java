package com.skillforge.server.eval.usersim;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator.SurfaceInjectResult;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator.TrialRequest;
import com.skillforge.server.improve.surface.OptimizableSurface;
import com.skillforge.server.improve.surface.SurfaceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2 (re-fix per team-lead push-back 2026-05-16):
 * unit tests covering the 3 candidate-surface inject paths.
 *
 * <p>Per team-lead invariant: operator who selects a candidate must see the candidate
 * actually drive the trial, not baseline. Specifically:
 * <ol>
 *   <li>{@code prompt} surface → {@code candidateDef.systemPrompt} mutated to candidate
 *       content (engine.run picks it up).</li>
 *   <li>{@code skill} surface → V4 {@code SandboxSkillRegistryFactory} mechanism kicks
 *       in: candidate {@link SkillDefinition} registered into a sandbox
 *       {@link SkillRegistry}, returned as the engine's registry (mirroring
 *       {@code SkillAbEvalService.runMultiTurnScenario}).</li>
 *   <li>{@code behavior_rule} surface → orchestrator throws
 *       {@code IllegalArgumentException} at {@code runTrial} entry (rejected upstream of
 *       any engine work, so operator never sees a misleading trial).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SimulatorTrialOrchestratorSurfaceInjectTest {

    @Mock private com.skillforge.server.repository.SimulatorTrialRepository trialRepository;
    @Mock private com.skillforge.server.repository.EvalScenarioDraftRepository scenarioRepository;
    @Mock private com.skillforge.server.repository.AgentRepository agentRepository;
    @Mock private com.skillforge.server.service.SessionService sessionService;
    @Mock private com.skillforge.server.eval.EvalEngineFactory evalEngineFactory;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SurfaceRegistry surfaceRegistry;
    @Mock private SandboxSkillRegistryFactory sandboxFactory;
    @Mock private com.skillforge.core.skill.SkillPackageLoader skillPackageLoader;
    @Mock private com.skillforge.server.config.EvalUserSimulatorProperties properties;

    private SimulatorTrialOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new SimulatorTrialOrchestrator(
                trialRepository, scenarioRepository, agentRepository, sessionService,
                evalEngineFactory, skillRegistry, surfaceRegistry, sandboxFactory,
                skillPackageLoader, properties, java.util.concurrent.Executors.newSingleThreadExecutor());
    }

    // ──────────────────────── prompt surface ────────────────────────

    @Test
    @DisplayName("prompt surface inject substitutes candidateDef.systemPrompt with candidate content")
    void promptSurface_substitutesSystemPrompt() throws Exception {
        AgentDefinition candidateDef = new AgentDefinition();
        candidateDef.setSystemPrompt("BASELINE prompt — must be replaced");

        FakePromptVersion candidateVersion = new FakePromptVersion("CANDIDATE prompt v42 — should win");

        @SuppressWarnings("rawtypes")
        OptimizableSurface promptSurface = org.mockito.Mockito.mock(OptimizableSurface.class);
        when(surfaceRegistry.get("prompt")).thenReturn(promptSurface);
        lenient().when(promptSurface.loadVersion("ver-42")).thenReturn(candidateVersion);

        SessionEntity session = newSession();
        TrialRequest req = new TrialRequest("scen-1", "ver-42", "prompt", "persona-X", null);

        SurfaceInjectResult result = orchestrator.applyCandidateSurfaceInject(
                candidateDef, session, 42L, req, "trial-x");

        assertThat(result.sandboxRegistry()).isNull();   // prompt → no sandbox swap
        assertThat(candidateDef.getSystemPrompt()).isEqualTo("CANDIDATE prompt v42 — should win");
        // Sandbox factory NEVER touched on prompt path
        verify(sandboxFactory, never()).buildSandboxRegistryWithSkills(anyString(), anyString(), anyList());
    }

    // ──────────────────────── skill surface ────────────────────────

    @Test
    @DisplayName("skill surface inject builds sandbox SkillRegistry with candidate SkillDefinition")
    void skillSurface_buildsSandboxRegistryWithCandidate() throws Exception {
        AgentDefinition candidateDef = new AgentDefinition();
        candidateDef.setSystemPrompt("baseline prompt — unchanged on skill surface");

        SkillEntity candidateSkill = new SkillEntity();
        candidateSkill.setId(777L);
        candidateSkill.setName("MyCandidateSkill");
        candidateSkill.setDescription("Replaces baseline skill v1 with candidate v2");
        candidateSkill.setTriggers("test,review");
        candidateSkill.setRequiredTools("BashTool");

        @SuppressWarnings("rawtypes")
        OptimizableSurface skillSurface = org.mockito.Mockito.mock(OptimizableSurface.class);
        when(surfaceRegistry.get("skill")).thenReturn(skillSurface);
        lenient().when(skillSurface.loadVersion("777")).thenReturn(candidateSkill);

        SkillRegistry sandbox = new SkillRegistry();
        when(sandboxFactory.buildSandboxRegistryWithSkills(
                eq("trial-Y"), eq("scen-1"), anyList())).thenReturn(sandbox);

        SessionEntity session = newSession();
        TrialRequest req = new TrialRequest("scen-1", "777", "skill", "persona-Y", null);

        SurfaceInjectResult result = orchestrator.applyCandidateSurfaceInject(
                candidateDef, session, 42L, req, "trial-Y");

        assertThat(result.sandboxRegistry()).isSameAs(sandbox);
        assertThat(candidateDef.getSystemPrompt()).isEqualTo("baseline prompt — unchanged on skill surface");

        // Verify the candidate SkillDefinition reached the sandbox factory.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillDefinition>> defsCaptor = ArgumentCaptor.forClass(List.class);
        verify(sandboxFactory).buildSandboxRegistryWithSkills(
                eq("trial-Y"), eq("scen-1"), defsCaptor.capture());
        List<SkillDefinition> defs = defsCaptor.getValue();
        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).getName()).isEqualTo("MyCandidateSkill");
        // metadata fields propagated
        assertThat(defs.get(0).getDescription()).contains("candidate");
    }

    // ──────────────────────── behavior_rule reject ────────────────────────

    @Test
    @DisplayName("behavior_rule surface — runTrial rejects with IllegalArgumentException (V5 limitation)")
    void behaviorRuleSurface_runTrialRejects() throws Exception {
        TrialRequest req = new TrialRequest("scen-1", "ver-X", "behavior_rule", "persona-Z", null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orchestrator.runTrial(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("behavior_rule")
                .hasMessageContaining("V5.1 backlog");

        // No scenario / agent / session lookup — rejected at the very top of runTrial.
        verify(scenarioRepository, never()).findById(anyString());
        verify(sandboxFactory, never()).buildSandboxRegistryWithSkills(anyString(), anyString(), anyList());
    }

    // ──────────────────────── helpers ────────────────────────

    private SessionEntity newSession() {
        SessionEntity s = new SessionEntity();
        s.setId("sess-test");
        s.setAgentId(42L);
        return s;
    }

    /**
     * Fake stand-in for PromptVersionEntity exposing getContent(). The orchestrator uses
     * reflection to read this method so we don't take a hard dep on the prompt-versioning
     * package from the test.
     */
    public static class FakePromptVersion {
        private final String content;
        public FakePromptVersion(String content) { this.content = content; }
        public String getContent() { return content; }
    }
}
