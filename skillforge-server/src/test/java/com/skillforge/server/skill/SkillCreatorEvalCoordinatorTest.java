package com.skillforge.server.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.EphemeralScenarioCleanupService;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.1 (2026-05-18) — coordinator listener
 * behavioural tests. Mocks the JPA repos so we can drive event timing without
 * an actual Postgres / async executor.
 *
 * <p>Pin the 4 critical behaviours:
 * <ol>
 *   <li>Non-eval session (eval_context_json == null) → no-op, no DB writes</li>
 *   <li>Eval session but batch incomplete → no aggregation, no status change</li>
 *   <li>Eval session and batch complete + with > without → status = evaluated_passed</li>
 *   <li>Eval session and batch complete + with ≈ without → status = rejected</li>
 * </ol>
 */
@DisplayName("SkillCreatorEvalCoordinator — listener aggregation logic")
class SkillCreatorEvalCoordinatorTest {

    private SessionRepository sessionRepository;
    private SkillDraftRepository draftRepository;
    private EphemeralScenarioCleanupService cleanupService;
    private ObjectMapper objectMapper;

    private SkillCreatorEvalCoordinator coordinator;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        draftRepository = mock(SkillDraftRepository.class);
        cleanupService = mock(EphemeralScenarioCleanupService.class);
        // java.md footgun #1: must register JavaTimeModule or Instant
        // serialisation silently fails — exactly the kind of test-only
        // ObjectMapper trap the rule was written to catch. EvaluationResult
        // has Instant evaluatedAt, so the coordinator's write path would
        // otherwise return early via the catch block.
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        coordinator = new SkillCreatorEvalCoordinator(
                sessionRepository, draftRepository, cleanupService, objectMapper);
    }

    @Test
    @DisplayName("Non-eval session (no eval_context_json) → no aggregation, no DB writes")
    void nonEvalSession_bailsOut() {
        SessionEntity sess = newSession("sess-prod", null);
        when(sessionRepository.findById("sess-prod")).thenReturn(Optional.of(sess));

        coordinator.onSessionLoopFinished(
                new SessionLoopFinishedEvent("sess-prod", "done", "completed", 7L));

        verify(draftRepository, never()).save(any(SkillDraftEntity.class));
        verify(cleanupService, never()).cleanupEphemerals(anyList());
    }

    @Test
    @DisplayName("Eval session but batch incomplete (only 1 of 2 terminal) → no aggregation yet")
    void evalBatchIncomplete_waitsForSiblings() {
        // Pair: scenario-a × {with_skill, without_skill} = 2 sessions, expectedCount=2.
        SessionEntity withSess = newSession("sess-with", evalCtx("draft-x", "scenario-a", "with_skill"));
        withSess.setRuntimeStatus("completed");
        withSess.setParentSessionId("parent-1");

        SessionEntity withoutSess = newSession("sess-without",
                evalCtx("draft-x", "scenario-a", "without_skill"));
        withoutSess.setRuntimeStatus("running"); // not yet terminal
        withoutSess.setParentSessionId("parent-1");

        when(sessionRepository.findById("sess-with")).thenReturn(Optional.of(withSess));
        when(sessionRepository.findByParentSessionId("parent-1"))
                .thenReturn(Arrays.asList(withSess, withoutSess));

        SkillDraftEntity draft = newDraft("draft-x");
        draft.setEvaluationResultJson(pendingStub(2, List.of("scenario-a"), "parent-1"));
        when(draftRepository.findById("draft-x")).thenReturn(Optional.of(draft));

        coordinator.onSessionLoopFinished(
                new SessionLoopFinishedEvent("sess-with", "done", "completed", 7L));

        // Batch not complete → no aggregation write.
        verify(draftRepository, never()).save(any(SkillDraftEntity.class));
        verify(cleanupService, never()).cleanupEphemerals(anyList());
    }

    @Test
    @DisplayName("Batch complete + with-skill outperforms → status='evaluated_passed', cleanup fires")
    void batchComplete_withSkillBetter_evaluatedPassed() {
        SessionEntity withSess = newSession("sess-with", evalCtx("draft-y", "sc-a", "with_skill"));
        withSess.setRuntimeStatus("completed"); // composite = 1.0 (proxy)
        withSess.setParentSessionId("parent-2");

        SessionEntity withoutSess = newSession("sess-without",
                evalCtx("draft-y", "sc-a", "without_skill"));
        withoutSess.setRuntimeStatus("error"); // composite = 0.0 (proxy) → delta passRate = +1.0
        withoutSess.setParentSessionId("parent-2");

        when(sessionRepository.findById("sess-with")).thenReturn(Optional.of(withSess));
        when(sessionRepository.findByParentSessionId("parent-2"))
                .thenReturn(Arrays.asList(withSess, withoutSess));

        SkillDraftEntity draft = newDraft("draft-y");
        draft.setEvaluationResultJson(pendingStub(2, List.of("sc-a"), "parent-2"));
        when(draftRepository.findById("draft-y")).thenReturn(Optional.of(draft));

        coordinator.onSessionLoopFinished(
                new SessionLoopFinishedEvent("sess-with", "done", "completed", 7L));

        // Aggregation should have fired.
        verify(draftRepository).save(any(SkillDraftEntity.class));
        verify(cleanupService).cleanupEphemerals(anyList());
        assertThat(draft.getStatus())
                .as("delta passRate = 1.0 - 0.0 = +100pp ≥ 5pp threshold → promote")
                .isEqualTo(SkillCreatorService.STATUS_EVALUATED_PASSED);
        assertThat(draft.getEvaluationResultJson())
                .as("evaluation_result_json should be the real EvaluationResult, not the pending stub")
                .doesNotContain("_pending");
    }

    @Test
    @DisplayName("Batch complete + delta below threshold → status='rejected'")
    void batchComplete_withSkillNoBenefit_rejected() {
        // Both sides have the same status (both completed) → composite proxy
        // is equal → delta passRate = 0 < threshold → reject.
        SessionEntity withSess = newSession("sess-with", evalCtx("draft-z", "sc-a", "with_skill"));
        withSess.setRuntimeStatus("completed");
        withSess.setParentSessionId("parent-3");

        SessionEntity withoutSess = newSession("sess-without",
                evalCtx("draft-z", "sc-a", "without_skill"));
        withoutSess.setRuntimeStatus("completed");
        withoutSess.setParentSessionId("parent-3");

        when(sessionRepository.findById("sess-with")).thenReturn(Optional.of(withSess));
        when(sessionRepository.findByParentSessionId("parent-3"))
                .thenReturn(Arrays.asList(withSess, withoutSess));

        SkillDraftEntity draft = newDraft("draft-z");
        draft.setEvaluationResultJson(pendingStub(2, List.of("sc-a"), "parent-3"));
        when(draftRepository.findById("draft-z")).thenReturn(Optional.of(draft));

        coordinator.onSessionLoopFinished(
                new SessionLoopFinishedEvent("sess-with", "done", "completed", 7L));

        verify(draftRepository).save(any(SkillDraftEntity.class));
        assertThat(draft.getStatus())
                .as("equal pass rates → delta = 0 < 0.05 threshold → reject")
                .isEqualTo(SkillCreatorService.STATUS_REJECTED);
    }

    @Test
    @DisplayName("Idempotency: re-firing the same event on an already-aggregated draft is a no-op")
    void alreadyAggregated_idempotent() {
        SessionEntity sess = newSession("sess-x", evalCtx("draft-done", "sc-a", "with_skill"));
        sess.setRuntimeStatus("completed");
        when(sessionRepository.findById("sess-x")).thenReturn(Optional.of(sess));

        SkillDraftEntity draft = newDraft("draft-done");
        // Already aggregated — evaluation_result_json doesn't have _pending flag.
        draft.setEvaluationResultJson("{\"withSkill\":{\"passRate\":1.0}}");
        when(draftRepository.findById("draft-done")).thenReturn(Optional.of(draft));

        coordinator.onSessionLoopFinished(
                new SessionLoopFinishedEvent("sess-x", "done", "completed", 7L));

        verify(draftRepository, never()).save(any(SkillDraftEntity.class));
        verify(cleanupService, never()).cleanupEphemerals(anyList());
    }

    // -------------------------- helpers --------------------------

    private SessionEntity newSession(String id, String evalContextJson) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(7L);
        s.setAgentId(100L);
        s.setMessageCount(0);
        s.setEvalContextJson(evalContextJson);
        s.setRuntimeStatus("idle");
        // Stamp wall-time so computeMetrics' latency math doesn't divide by zero.
        s.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        s.setCompletedAt(Instant.now());
        return s;
    }

    private SkillDraftEntity newDraft(String id) {
        SkillDraftEntity d = new SkillDraftEntity();
        d.setId(id);
        d.setName("test-skill");
        d.setOwnerId(1L);
        d.setStatus(SkillCreatorService.STATUS_EVALUATING);
        return d;
    }

    private String evalCtx(String draftId, String scenarioId, String baselineLabel) {
        return String.format("{\"draftId\":\"%s\",\"scenarioId\":\"%s\",\"baselineLabel\":\"%s\"}",
                draftId, scenarioId, baselineLabel);
    }

    private String pendingStub(int expectedCount, List<String> scenarioIds, String parentSessionId) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"_pending\":true,");
        sb.append("\"expectedCount\":").append(expectedCount).append(",");
        sb.append("\"parentSessionId\":\"").append(parentSessionId).append("\",");
        sb.append("\"scenarioIds\":[");
        for (int i = 0; i < scenarioIds.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(scenarioIds.get(i)).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }
}
