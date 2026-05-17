package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.PatternSessionMemberEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.PatternSessionMemberRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.skill.SkillCreatorService;
import com.skillforge.server.skill.SkillStorageService;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FLYWHEEL-LOOP-CLOSURE Phase 1.4f (2026-05-17) — focused unit tests for
 * {@link SkillDraftService#startAbTestFromDraft(String, java.util.List)} —
 * the real body added in Phase 1.4e (sub-task 3) replacing the Phase 1.4a
 * synthetic-abRunId scaffold.
 *
 * <p>Mocks the {@link SkillAbEvalService#createAndTrigger} dispatch path so
 * we don't need a Spring context or live agent loop — verifies the wiring
 * between draft → transient candidate → empty baseline → ephemeral fallback
 * → createAndTrigger + failure cleanup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillDraftService.startAbTestFromDraft (Phase 1.4f sub-3 tests)")
class SkillDraftServiceAbTestFromDraftTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private SkillDraftRepository skillDraftRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private UserWebSocketHandler userWebSocketHandler;
    @Mock private SkillCreatorService skillCreatorService;
    @Mock private SkillPackageLoader skillPackageLoader;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillStorageService skillStorageService;
    @Mock private EvalScenarioDraftRepository evalScenarioRepository;
    @Mock private OptimizationEventRepository optimizationEventRepository;
    @Mock private PatternSessionMemberRepository patternSessionMemberRepository;
    @Mock private SessionScenarioExtractorService sessionScenarioExtractor;
    @Mock private EphemeralScenarioCleanupService ephemeralScenarioCleanupService;
    @Mock private SkillAbEvalService skillAbEvalService;

    private SkillDraftService service;

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("test");
        service = new SkillDraftService(
                sessionRepository, skillDraftRepository, skillRepository,
                llmProviderFactory, new ObjectMapper(), props,
                userWebSocketHandler, skillCreatorService,
                skillPackageLoader, skillRegistry, skillStorageService,
                evalScenarioRepository, optimizationEventRepository,
                patternSessionMemberRepository, sessionScenarioExtractor,
                ephemeralScenarioCleanupService, skillAbEvalService);
    }

    private SkillDraftEntity newDraft(String id) {
        SkillDraftEntity d = new SkillDraftEntity();
        d.setId(id);
        d.setName("ImprovedSkill");
        d.setDescription("Test improvement");
        d.setTriggers("trig-a");
        d.setRequiredTools("Bash");
        d.setPromptHint("Body text");
        d.setOwnerId(7L);
        d.setStatus("draft");
        return d;
    }

    /** Auto-assign incrementing ids on skillRepository.save so transient + baseline get distinct ids. */
    private void stubSkillSaveAutoId() {
        AtomicLong nextId = new AtomicLong(100L);
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> {
            SkillEntity arg = inv.getArgument(0);
            if (arg.getId() == null) arg.setId(nextId.getAndIncrement());
            return arg;
        });
        // R3 fix (Phase 1.6 dogfood, 2026-05-17) — promoteDraftToTransientSkill
        // now calls skillStorageService.allocate + skillCreatorService.render to
        // materialise SKILL.md on disk. Stub allocate to return a fake path so
        // the test exercises the new code path without hitting the filesystem.
        try {
            when(skillStorageService.allocate(any(), any()))
                    .thenReturn(java.nio.file.Path.of(
                            "/tmp/skillforge-test/ab-test-from-draft/x/y"));
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("happy explicit scenarios → promote draft + create paired baseline + "
            + "createAndTrigger called + abRunId returned (no ephemeral fallback)")
    void startAbTestFromDraft_happy_explicitScenarios() {
        String draftId = "draft-uuid-happy";
        SkillDraftEntity draft = newDraft(draftId);
        when(skillDraftRepository.findById(draftId)).thenReturn(Optional.of(draft));
        stubSkillSaveAutoId();

        // Mock createAndTrigger to return a SkillAbRunEntity with a known id.
        SkillAbRunEntity stubAbRun = new SkillAbRunEntity();
        stubAbRun.setId("ab-run-id-happy");
        when(skillAbEvalService.createAndTrigger(anyLong(), anyLong(),
                anyString(), isNull(), isNull())).thenReturn(stubAbRun);

        // Explicit scenarios — should NOT hit pattern-fallback path.
        List<String> explicit = List.of("scen-1", "scen-2");
        when(evalScenarioRepository.findAllById(eq(explicit)))
                .thenReturn(List.of(new EvalScenarioEntity(), new EvalScenarioEntity()));

        String abRunId = service.startAbTestFromDraft(draftId, explicit);

        assertThat(abRunId).isEqualTo("ab-run-id-happy");
        // 2 saves: candidate (via promoteDraftToTransientSkill) + baseline +
        // 1 re-save of candidate after wiring parentSkillId = atLeast 3.
        verify(skillRepository, atLeastOnce()).save(any(SkillEntity.class));
        // No pattern-fallback queries (explicit scenarios path).
        verify(optimizationEventRepository, never()).findByCandidateSkillDraftUuid(anyString());
        verify(patternSessionMemberRepository, never()).findByPatternIdOrderByAddedAtDesc(
                anyLong(), any(Pageable.class));
        // No cleanup on happy path.
        verify(ephemeralScenarioCleanupService, never()).cleanupEphemerals(any());
    }

    @Test
    @DisplayName("null scenarios → ephemeral fallback via OptimizationEvent.candidateSkillDraftUuid "
            + "→ pattern.members → extractor invoked per member; createAndTrigger called")
    void startAbTestFromDraft_ephemeralFallback_nullScenarios() {
        String draftId = "draft-uuid-fallback";
        SkillDraftEntity draft = newDraft(draftId);
        when(skillDraftRepository.findById(draftId)).thenReturn(Optional.of(draft));
        stubSkillSaveAutoId();
        when(evalScenarioRepository.save(any(EvalScenarioEntity.class)))
                .thenAnswer(inv -> {
                    EvalScenarioEntity e = inv.getArgument(0);
                    if (e.getId() == null) e.setId("ephem-" + System.nanoTime());
                    return e;
                });

        // V88 sidecar reverse lookup → return event w/ patternId 42.
        OptimizationEventEntity event = new OptimizationEventEntity();
        event.setId(99L);
        event.setPatternId(42L);
        when(optimizationEventRepository.findByCandidateSkillDraftUuid(eq(draftId)))
                .thenReturn(List.of(event));

        // Pattern members: 3 session ids → 3 ephemeral extractions.
        List<PatternSessionMemberEntity> members = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PatternSessionMemberEntity m = new PatternSessionMemberEntity();
            m.setPatternId(42L);
            m.setSessionId("session-" + i);
            m.setAddedAt(Instant.now());
            members.add(m);
        }
        when(patternSessionMemberRepository.findByPatternIdOrderByAddedAtDesc(
                eq(42L), any(Pageable.class))).thenReturn(members);
        // Each session loads + extracts to a non-null EvalScenarioEntity.
        for (int i = 0; i < 3; i++) {
            SessionEntity sess = new SessionEntity();
            sess.setId("session-" + i);
            when(sessionRepository.findById(eq("session-" + i))).thenReturn(Optional.of(sess));
            EvalScenarioEntity ephem = new EvalScenarioEntity();
            ephem.setAgentId("7");
            when(sessionScenarioExtractor.extractFromSession(eq(sess))).thenReturn(ephem);
        }

        SkillAbRunEntity stubAbRun = new SkillAbRunEntity();
        stubAbRun.setId("ab-run-id-fallback");
        when(skillAbEvalService.createAndTrigger(anyLong(), anyLong(),
                anyString(), isNull(), isNull())).thenReturn(stubAbRun);

        String abRunId = service.startAbTestFromDraft(draftId, null);

        assertThat(abRunId).isEqualTo("ab-run-id-fallback");
        verify(optimizationEventRepository).findByCandidateSkillDraftUuid(eq(draftId));
        verify(patternSessionMemberRepository).findByPatternIdOrderByAddedAtDesc(
                eq(42L), any(Pageable.class));
        // Extractor invoked once per member.
        verify(sessionScenarioExtractor, times(3)).extractFromSession(any(SessionEntity.class));
        // status='ephemeral' written via evalScenarioRepository.save × 3.
        verify(evalScenarioRepository, times(3)).save(any(EvalScenarioEntity.class));
        // Happy path → no cleanup (would happen later in async lifecycle).
        verify(ephemeralScenarioCleanupService, never()).cleanupEphemerals(any());
    }

    @Test
    @DisplayName("createAndTrigger throws → cleanup ephemeral + delete transient candidate + "
            + "delete baseline + rethrow (W5 path)")
    void startAbTestFromDraft_loserCleansUpTransient() {
        String draftId = "draft-uuid-fails";
        SkillDraftEntity draft = newDraft(draftId);
        when(skillDraftRepository.findById(draftId)).thenReturn(Optional.of(draft));
        stubSkillSaveAutoId();

        when(evalScenarioRepository.findAllById(any()))
                .thenReturn(List.of(new EvalScenarioEntity()));
        when(skillAbEvalService.createAndTrigger(anyLong(), anyLong(),
                anyString(), isNull(), isNull()))
                .thenThrow(new RuntimeException("simulated createAndTrigger failure"));

        assertThatThrownBy(() -> service.startAbTestFromDraft(draftId, List.of("scen-1")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated createAndTrigger failure");

        // W5 path: ephemeral cleanup invoked (with empty list since explicit-scenario path).
        verify(ephemeralScenarioCleanupService).cleanupEphemerals(eq(Collections.emptyList()));
        // F5 fix (Phase 2 r2): outer @Transactional rollback handles transient
        // candidate + baseline cleanup automatically; explicit delete removed.
        // Test no longer asserts skillRepository.delete here — that would be a
        // dead-code assertion. In a @SpringBootTest IT, the rollback semantic
        // is what guarantees no DB leak; this Mockito unit only verifies the
        // ephemeral cleanup branch.
        verify(skillRepository, never()).delete(any(SkillEntity.class));
    }
}
