package com.skillforge.server.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.1 B1-fix (2026-05-18, java-reviewer Phase 2.0)
 * — regression test for the upstream-transaction race.
 *
 * <p>Before the fix, {@code SkillCreatorService.dispatchEvaluation} called
 * {@code chatService.chatAsync} <em>inside</em> the {@code @Transactional}
 * dispatch path. The async runLoop then read
 * {@code freshSession.getSkillOverridesJson()} in its own fresh transaction
 * that couldn't yet see the row we'd just saved (the outer tx hadn't
 * committed), so the override silently fell back to {@code agent.skillIds}
 * — making with_skill / without_skill evaluation identical.
 *
 * <p>This test pins three invariants that prevent the race coming back:
 * <ol>
 *   <li>dispatchEvaluation does <b>not</b> call {@code chatAsync} during
 *       its synchronous run.</li>
 *   <li>dispatchEvaluation publishes a {@link SkillEvalDispatchReadyEvent}
 *       with the 2N child session ids + per-session task — so the
 *       AFTER_COMMIT listener has everything it needs.</li>
 *   <li>The published event's child sessions are exactly the ones whose
 *       {@code skill_overrides_json} + {@code eval_context_json} columns
 *       were just stamped — same id round-trip, no aliasing.</li>
 * </ol>
 *
 * <p>The AFTER_COMMIT listener itself (which fires {@code chatAsync}) is
 * tested separately by {@link SkillCreatorEvalCoordinatorTest}; here we
 * verify only the dispatch-side contract.
 */
@DisplayName("SkillCreatorService.dispatchEvaluation — tx-boundary race regression (B1)")
class SkillCreatorServiceDispatchTxBoundaryTest {

    private SkillDraftRepository draftRepository;
    private SkillRepository skillRepository;
    private EvalScenarioDraftRepository scenarioRepository;
    private SessionRepository sessionRepository;
    private SessionService sessionService;
    private ChatService chatService;
    private AgentService agentService;
    private SubAgentRegistry subAgentRegistry;
    private ApplicationEventPublisher eventPublisher;
    private SkillCreatorService service;

    @BeforeEach
    void setUp() {
        draftRepository = mock(SkillDraftRepository.class);
        skillRepository = mock(SkillRepository.class);
        scenarioRepository = mock(EvalScenarioDraftRepository.class);
        sessionRepository = mock(SessionRepository.class);
        sessionService = mock(SessionService.class);
        chatService = mock(ChatService.class);
        agentService = mock(AgentService.class);
        subAgentRegistry = mock(SubAgentRegistry.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        service = new SkillCreatorService(
                draftRepository, skillRepository, scenarioRepository,
                sessionRepository, sessionService, chatService,
                agentService, subAgentRegistry, objectMapper, eventPublisher);
    }

    @Test
    @DisplayName("dispatchEvaluation must NOT call chatService.chatAsync inline (B1 fix)")
    void dispatchEvaluation_doesNotCallChatAsyncInline() {
        wireHappyDispatch();

        service.dispatchEvaluation("parent-1", "draft-1", List.of("scenario-1"));

        // Iron Law: chatAsync is the symptom of the race. If it fires inside
        // dispatchEvaluation, the async runLoop reads pre-commit state.
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong(), anyBoolean());
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("dispatchEvaluation publishes SkillEvalDispatchReadyEvent with all 2N child session ids")
    void dispatchEvaluation_publishesAfterCommitEvent() {
        SessionEntity sessA = wireHappyDispatch();
        // Spy: capture each createSubSession return so we can later cross-check the
        // ids in the event against the rows that actually got setSkillOverridesJson stamped.
        ArgumentCaptor<SkillEvalDispatchReadyEvent> evtCap =
                ArgumentCaptor.forClass(SkillEvalDispatchReadyEvent.class);

        service.dispatchEvaluation("parent-1", "draft-1", List.of("scenario-1", "scenario-2"));

        verify(eventPublisher).publishEvent(evtCap.capture());
        SkillEvalDispatchReadyEvent evt = evtCap.getValue();

        assertThat(evt.draftId()).isEqualTo("draft-1");
        assertThat(evt.userId()).isEqualTo(7L);
        // 2 scenarios × 2 baselines = 4 child sessions
        assertThat(evt.childSessionIds())
                .as("AFTER_COMMIT listener needs every child id to fire chatAsync — none can be missing")
                .hasSize(4);
        assertThat(evt.taskBySession())
                .as("listener needs per-session task to pass to chatAsync — one entry per child")
                .hasSize(4);
        // taskBySession values come from the EvalScenarioEntity.task we stubbed
        assertThat(evt.taskBySession().values())
                .containsOnly("scenario-1-task", "scenario-2-task");
    }

    @Test
    @DisplayName("dispatchEvaluation stamps skill_overrides_json + eval_context_json on EVERY child before publishing event")
    void dispatchEvaluation_stampsOverridesOnEveryChild() {
        wireHappyDispatch();

        service.dispatchEvaluation("parent-1", "draft-1", List.of("scenario-1"));

        // Each createSubSession spawns a SessionEntity; sessionService.saveSession
        // is called once per child after stamping. Capture every saved session and
        // assert both columns are non-null.
        ArgumentCaptor<SessionEntity> sessCap = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionService, org.mockito.Mockito.atLeast(2)).saveSession(sessCap.capture());

        // Filter to the 2 child sessions (the dispatch may also save the orchestrator);
        // each child carries non-null both columns + correct draft id in the JSON.
        List<SessionEntity> children = sessCap.getAllValues().stream()
                .filter(s -> s.getEvalContextJson() != null)
                .toList();
        assertThat(children)
                .as("both child sessions must have eval_context_json stamped before AFTER_COMMIT")
                .hasSizeGreaterThanOrEqualTo(2);
        for (SessionEntity child : children) {
            assertThat(child.getSkillOverridesJson())
                    .as("skill_overrides_json must be non-null on every child — otherwise " +
                            "ChatService.runLoop falls back to agent.skillIds and the eval is bogus")
                    .isNotNull();
            assertThat(child.getEvalContextJson())
                    .as("eval_context_json must contain the draftId for the coordinator to match")
                    .contains("draft-1")
                    .contains("scenario-1")
                    .containsAnyOf(SkillCreatorService.BASELINE_WITH_SKILL,
                            SkillCreatorService.BASELINE_WITHOUT_SKILL);
        }
    }

    @Test
    @DisplayName("dispatchEvaluation: published event includes exactly the child session ids that were stamped (no aliasing / drop)")
    void dispatchEvaluation_eventIdsMatchStampedSessions() {
        wireHappyDispatch();
        ArgumentCaptor<SessionEntity> sessCap = ArgumentCaptor.forClass(SessionEntity.class);
        ArgumentCaptor<SkillEvalDispatchReadyEvent> evtCap =
                ArgumentCaptor.forClass(SkillEvalDispatchReadyEvent.class);

        service.dispatchEvaluation("parent-1", "draft-1", List.of("scenario-1"));

        verify(sessionService, org.mockito.Mockito.atLeast(2)).saveSession(sessCap.capture());
        verify(eventPublisher).publishEvent(evtCap.capture());

        List<String> stampedChildIds = sessCap.getAllValues().stream()
                .filter(s -> s.getEvalContextJson() != null)
                .map(SessionEntity::getId)
                .toList();
        assertThat(evtCap.getValue().childSessionIds())
                .as("every stamped child must also appear in the event (no drop), and vice versa")
                .containsExactlyInAnyOrderElementsOf(stampedChildIds);
    }

    // -------------------------- helpers --------------------------

    /**
     * Wire up the happy path: parent session exists, draft exists with
     * targetAgentId, two scenarios resolvable. createSubSession returns a
     * fresh entity each call so we get distinct child ids; saveSession is a
     * pass-through.
     */
    private SessionEntity wireHappyDispatch() {
        SessionEntity parent = new SessionEntity();
        parent.setId("parent-1");
        parent.setUserId(7L);
        parent.setAgentId(100L);
        lenient().when(sessionRepository.findById("parent-1")).thenReturn(Optional.of(parent));

        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setId("draft-1");
        draft.setName("test-skill");
        draft.setOwnerId(7L);
        draft.setTargetAgentId(100L);
        draft.setPromptHint("do the thing");
        lenient().when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));

        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        agent.setName("test-agent");
        lenient().when(agentService.getAgent(100L)).thenReturn(agent);
        AgentDefinition agentDef = new AgentDefinition();
        agentDef.setId("100");
        agentDef.setSkillIds(new java.util.ArrayList<>(List.of("existing-skill-a")));
        lenient().when(agentService.toAgentDefinition(agent)).thenReturn(agentDef);

        // Two scenarios resolvable
        EvalScenarioEntity sc1 = scenario("scenario-1", "scenario-1-task");
        EvalScenarioEntity sc2 = scenario("scenario-2", "scenario-2-task");
        lenient().when(scenarioRepository.findById("scenario-1")).thenReturn(Optional.of(sc1));
        lenient().when(scenarioRepository.findById("scenario-2")).thenReturn(Optional.of(sc2));

        // SubAgentRegistry.registerRun returns a fresh run each time
        lenient().when(subAgentRegistry.registerRun(any(), anyLong(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    SubAgentRegistry.SubAgentRun r = new SubAgentRegistry.SubAgentRun();
                    r.runId = java.util.UUID.randomUUID().toString();
                    r.parentSessionId = "parent-1";
                    return r;
                });

        // createSubSession returns a fresh child each call
        lenient().when(sessionService.createSubSession(any(), anyLong(), anyString()))
                .thenAnswer(inv -> {
                    SessionEntity child = new SessionEntity();
                    child.setId(java.util.UUID.randomUUID().toString());
                    child.setUserId(7L);
                    child.setAgentId(100L);
                    child.setParentSessionId("parent-1");
                    return child;
                });

        // skillRepository.save returns a fresh transient SkillEntity
        lenient().when(skillRepository.save(any())).thenAnswer(inv -> {
            SkillEntity s = inv.getArgument(0);
            // Give it a positive id so candidateSkillId is set
            if (s.getId() == null) s.setId(System.nanoTime());
            return s;
        });

        // sessionService.saveSession is pass-through
        lenient().when(sessionService.saveSession(any())).thenAnswer(inv -> inv.getArgument(0));

        return parent;
    }

    private EvalScenarioEntity scenario(String id, String task) {
        EvalScenarioEntity sc = new EvalScenarioEntity();
        sc.setId(id);
        sc.setAgentId("100");
        sc.setName(id);
        sc.setTask(task);
        sc.setStatus("ephemeral");
        return sc;
    }
}
