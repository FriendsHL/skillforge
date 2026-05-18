package com.skillforge.server.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.EphemeralScenarioCleanupService;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.1 (2026-05-18) — async coordinator that
 * watches eval-tagged child sessions complete, aggregates the per-pair judge
 * output, and writes the final {@link EvaluationResult} back into the
 * originating {@link SkillDraftEntity}.
 *
 * <p>Listener pattern follows V6
 * {@code OptimizationEventAutoTriggerListener.onStageCandidateReady}
 * (verified Phase 1.0 — same triple-annotation, same isolation rationale):
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — fire only after
 *       the publishing transaction (the child loop teardown) has actually
 *       committed, so {@code session.runtime_status='completed'} is visible
 *       to the listener's own read.</li>
 *   <li>{@code @Async} — keep the loop teardown thread free; aggregation +
 *       judge can take seconds.</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — own transaction so the
 *       coordinator's writes commit independently of any caller transaction
 *       (Phase 1.0 verify path d / java.md footgun #2 family).</li>
 * </ul>
 *
 * <p>Decision logic on each event:
 * <ol>
 *   <li>Look up {@code SessionEntity} by event sessionId; bail if missing
 *       or {@code eval_context_json} is null (non-eval traffic).</li>
 *   <li>Parse {@code eval_context_json → {draftId, scenarioId, baselineLabel}}.</li>
 *   <li>Look up the draft + its pending stub ({@code _pending:true, expectedCount, ...}).</li>
 *   <li>Count terminal sibling sessions sharing the same {@code draftId} in
 *       their {@code eval_context_json}. If less than {@code expectedCount},
 *       just persist this completion (write transcript+score to the session)
 *       and bail — another event will re-fire when the last sibling lands.</li>
 *   <li>When all {@code expectedCount} are terminal: build the
 *       {@link EvaluationResult}, write it back to
 *       {@code draft.evaluationResultJson}, transition status, and trigger
 *       ephemeral cleanup of the scenario rows.</li>
 * </ol>
 *
 * <p>Phase 1.1 simplification: the per-side judge integration uses a
 * lightweight placeholder ({@code finalMessage} length + status proxy).
 * Phase 1.6 / Phase 2 review will wire the real
 * {@code EvalJudgeTool.judgeMultiTurnConversation} call once the eval-side
 * scenario → ScenarioRunResult shape lands; tracked as a Phase 1.6 follow-up
 * in the post-mortem doc. {@link EvaluationResult} carries the
 * already-aggregated shape so the FE panel renders identically regardless
 * of judge-source detail (per spec D6).
 */
@Component
public class SkillCreatorEvalCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SkillCreatorEvalCoordinator.class);

    /** Status values written by ChatService.runLoop on loop teardown. */
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "completed", "cancelled", "error", "aborted_by_hook");

    private final SessionRepository sessionRepository;
    private final SkillDraftRepository draftRepository;
    private final EphemeralScenarioCleanupService cleanupService;
    private final ObjectMapper objectMapper;
    /**
     * Phase 1.1 B1-fix (2026-05-18, java-reviewer Phase 2.0): wired with
     * {@code @Lazy} to dodge the circular bean cycle (ChatService → SkillRegistry
     * → SubAgentTool → ApplicationEventPublisher → coordinator → ChatService).
     * Nullable in test fixtures that only exercise the
     * {@link #onSessionLoopFinished} aggregation path.
     */
    private final ChatService chatService;

    /**
     * Test-only ctor — preserves the Phase 1.1 5-case unit tests that don't
     * exercise the {@link #onSkillEvalDispatchReady} fire path. Calling that
     * method on this instance is a no-op (returns early on null chatService).
     */
    public SkillCreatorEvalCoordinator(SessionRepository sessionRepository,
                                        SkillDraftRepository draftRepository,
                                        EphemeralScenarioCleanupService cleanupService,
                                        ObjectMapper objectMapper) {
        this(sessionRepository, draftRepository, cleanupService, objectMapper, null);
    }

    @Autowired
    public SkillCreatorEvalCoordinator(SessionRepository sessionRepository,
                                        SkillDraftRepository draftRepository,
                                        EphemeralScenarioCleanupService cleanupService,
                                        ObjectMapper objectMapper,
                                        @Lazy ChatService chatService) {
        this.sessionRepository = sessionRepository;
        this.draftRepository = draftRepository;
        this.cleanupService = cleanupService;
        this.objectMapper = objectMapper;
        this.chatService = chatService;
    }

    /**
     * Phase 1.1 B1-fix (2026-05-18, java-reviewer Phase 2.0): AFTER_COMMIT
     * fan-out of {@code chatService.chatAsync} for the 2N child sessions just
     * persisted by {@link SkillCreatorService#dispatchEvaluation}. Splitting
     * dispatch (sync, persists rows + override columns) from chat-loop
     * start (here, after commit) closes the upstream-tx race that would
     * otherwise let the async runLoop see {@code skill_overrides_json=NULL}
     * and silently fall back to {@code agent.skillIds}.
     *
     * <p>Same triple-annotation pattern as {@link #onSessionLoopFinished}:
     * <ul>
     *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — only fire
     *       when the dispatching tx successfully committed; on rollback we
     *       never started chats for sessions that don't exist.</li>
     *   <li>{@code @Async} — keep the publishing thread free; chatAsync
     *       internally schedules N tasks on chatLoopExecutor and that
     *       enqueueing shouldn't block the dispatch caller's return.</li>
     *   <li>{@code @Transactional(REQUIRES_NEW)} — isolated tx for any
     *       reads the chatAsync path needs (session lookup); doesn't
     *       affect chat-loop execution which runs on a separate thread.</li>
     * </ul>
     *
     * <p>If chatService isn't wired (test ctor used), this method is a no-op
     * with a debug log — Phase 1.1 unit tests don't exercise this path.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSkillEvalDispatchReady(SkillEvalDispatchReadyEvent event) {
        if (event == null || event.childSessionIds() == null || event.childSessionIds().isEmpty()) {
            return;
        }
        if (chatService == null) {
            log.debug("[SkillCreatorEvalCoordinator] chatService not wired — skipping AFTER_COMMIT "
                    + "fan-out for draftId={} ({} children)",
                    event.draftId(), event.childSessionIds().size());
            return;
        }
        java.util.Map<String, String> taskBySession = event.taskBySession() == null
                ? java.util.Collections.emptyMap() : event.taskBySession();
        int started = 0;
        for (String childSessionId : event.childSessionIds()) {
            String task = taskBySession.get(childSessionId);
            if (task == null || task.isBlank()) {
                log.warn("[SkillCreatorEvalCoordinator] missing task for childSessionId={} (draftId={}) — "
                        + "skipping async start", childSessionId, event.draftId());
                continue;
            }
            try {
                // OBS-4 §2.1: preserveActiveRoot=true mirrors SubAgentTool.handleDispatch /
                // SkillCreatorService.dispatchOne semantics (the child inherits parent's
                // active_root for trace continuity within the eval batch).
                chatService.chatAsync(childSessionId, task, event.userId(), true);
                started++;
            } catch (RuntimeException ex) {
                // Don't fail the whole batch — one bad session shouldn't strand the others.
                // The coordinator's onSessionLoopFinished path is idempotent and will still
                // aggregate as long as terminalCount eventually equals expectedCount.
                log.error("[SkillCreatorEvalCoordinator] chatAsync failed for childSessionId={} "
                        + "(draftId={}); other siblings may still complete the batch",
                        childSessionId, event.draftId(), ex);
            }
        }
        log.info("[SkillCreatorEvalCoordinator] onSkillEvalDispatchReady draftId={} started={}/{} "
                        + "child loops (AFTER_COMMIT)",
                event.draftId(), started, event.childSessionIds().size());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSessionLoopFinished(SessionLoopFinishedEvent event) {
        if (event == null || event.sessionId() == null) return;
        try {
            handleFinish(event);
        } catch (RuntimeException ex) {
            // Defense-in-depth: never let coordinator failure surface back into
            // the publishing transaction (it's already committed).
            log.error("[SkillCreatorEvalCoordinator] failed to process SessionLoopFinishedEvent "
                    + "sessionId={} — eval batch may be left in 'evaluating' state",
                    event.sessionId(), ex);
        }
    }

    private void handleFinish(SessionLoopFinishedEvent event) {
        SessionEntity session = sessionRepository.findById(event.sessionId()).orElse(null);
        if (session == null) return;

        String ctxJson = session.getEvalContextJson();
        if (ctxJson == null || ctxJson.isBlank()) {
            // Non-eval session — vast majority of events fall through here. No DB writes.
            return;
        }

        EvalContext ctx = parseEvalContext(ctxJson);
        if (ctx == null || ctx.draftId == null) {
            log.warn("[SkillCreatorEvalCoordinator] sessionId={} has malformed eval_context_json: {}",
                    event.sessionId(), ctxJson);
            return;
        }

        SkillDraftEntity draft = draftRepository.findById(ctx.draftId).orElse(null);
        if (draft == null) {
            log.warn("[SkillCreatorEvalCoordinator] sessionId={} eval-tagged for missing draftId={}",
                    event.sessionId(), ctx.draftId);
            return;
        }

        PendingState pending = parsePending(draft.getEvaluationResultJson());
        if (pending == null || !pending.pending) {
            // Already aggregated (or pre-Phase 1.1 row) — idempotent no-op.
            log.debug("[SkillCreatorEvalCoordinator] draftId={} already aggregated (or never pending); "
                    + "ignoring completion for sessionId={}", ctx.draftId, event.sessionId());
            return;
        }

        // Find all sibling eval sessions that belong to the same draft batch.
        List<SessionEntity> siblings = sessionRepository
                .findByParentSessionId(pending.parentSessionId == null
                        ? Objects.toString(session.getParentSessionId(), "")
                        : pending.parentSessionId);

        // Filter to eval-tagged siblings sharing this draftId.
        List<SessionEntity> evalSiblings = new ArrayList<>();
        for (SessionEntity s : siblings) {
            String sCtx = s.getEvalContextJson();
            if (sCtx == null || sCtx.isBlank()) continue;
            EvalContext sParsed = parseEvalContext(sCtx);
            if (sParsed != null && ctx.draftId.equals(sParsed.draftId)) {
                evalSiblings.add(s);
            }
        }

        long terminalCount = evalSiblings.stream()
                .filter(s -> isTerminal(s.getRuntimeStatus()))
                .count();

        log.info("[SkillCreatorEvalCoordinator] draftId={} sessionId={} baselineLabel={} "
                        + "terminalCount={}/{} (expected)", ctx.draftId, event.sessionId(),
                ctx.baselineLabel, terminalCount, pending.expectedCount);

        if (terminalCount < pending.expectedCount) {
            // Wait for more siblings; another event will re-fire.
            return;
        }

        // All 2N done — aggregate.
        EvaluationResult result = aggregate(draft, pending, evalSiblings);

        try {
            draft.setEvaluationResultJson(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            log.error("[SkillCreatorEvalCoordinator] failed to serialize EvaluationResult for draft={} — "
                    + "leaving pending stub in place so next retry can recover", ctx.draftId, e);
            return;
        }
        boolean passed = result.delta() != null
                && result.delta().passRate() >= SkillCreatorService.PASS_RATE_DELTA_THRESHOLD;
        draft.setStatus(passed
                ? SkillCreatorService.STATUS_EVALUATED_PASSED
                : SkillCreatorService.STATUS_REJECTED);
        draftRepository.save(draft);

        // Ephemeral scenario cleanup — best-effort, never throws (see
        // EphemeralScenarioCleanupService.cleanupEphemerals contract).
        cleanupService.cleanupEphemerals(pending.scenarioIds);

        log.info("[SkillCreatorEvalCoordinator] draftId={} aggregated: status={} passRateDelta={}",
                ctx.draftId, draft.getStatus(),
                result.delta() == null ? "null" : String.format("%.3f", result.delta().passRate()));
    }

    /**
     * Aggregate sibling sessions into an {@link EvaluationResult}. Phase 1.1
     * uses a lightweight per-side aggregation; Phase 1.6 will wire the real
     * EvalJudgeTool integration (see class javadoc).
     */
    private EvaluationResult aggregate(SkillDraftEntity draft, PendingState pending,
                                        List<SessionEntity> siblings) {
        List<SessionEntity> withSkillSiblings = new ArrayList<>();
        List<SessionEntity> withoutSkillSiblings = new ArrayList<>();
        for (SessionEntity s : siblings) {
            EvalContext c = parseEvalContext(s.getEvalContextJson());
            if (c == null) continue;
            if (SkillCreatorService.BASELINE_WITH_SKILL.equals(c.baselineLabel)) {
                withSkillSiblings.add(s);
            } else if (SkillCreatorService.BASELINE_WITHOUT_SKILL.equals(c.baselineLabel)) {
                withoutSkillSiblings.add(s);
            }
        }

        EvaluationResult.SkillMetrics withMetrics = computeMetrics(withSkillSiblings);
        EvaluationResult.SkillMetrics withoutMetrics = computeMetrics(withoutSkillSiblings);
        EvaluationResult.SkillMetrics delta = new EvaluationResult.SkillMetrics(
                withMetrics.compositeScore() - withoutMetrics.compositeScore(),
                withMetrics.overallScore() - withoutMetrics.overallScore(),
                withMetrics.passRate() - withoutMetrics.passRate(),
                withMetrics.avgLatencyMs() - withoutMetrics.avgLatencyMs(),
                withMetrics.totalCostUsd() - withoutMetrics.totalCostUsd());

        String llmSummary = buildPlaceholderSummary(draft, withMetrics, withoutMetrics, delta);
        List<String> sourceSessionIds = new ArrayList<>();
        for (SessionEntity s : siblings) sourceSessionIds.add(s.getId());

        return new EvaluationResult(
                withMetrics, withoutMetrics, delta,
                llmSummary,
                sourceSessionIds,
                pending.scenarioIds == null ? 0 : pending.scenarioIds.size(),
                Instant.now(),
                SkillCreatorService.EVALUATOR_VERSION);
    }

    /**
     * Phase 1.1 placeholder metrics computation. Each session contributes:
     * <ul>
     *   <li>{@code compositeScore} / {@code overallScore}: 1.0 if
     *       {@code runtime_status='completed'}, 0.0 otherwise. This is the
     *       crude "did the loop reach a terminal answer" proxy; Phase 1.6
     *       wires the real EvalJudgeTool to score per-turn quality.</li>
     *   <li>{@code passRate}: same proxy (count of completed / count total).</li>
     *   <li>{@code avgLatencyMs}: not tracked yet — Phase 1.6 will pull from
     *       per-session wall-time on {@code completedAt - createdAt}.</li>
     *   <li>{@code totalCostUsd}: zero placeholder; Phase 1.6 will sum
     *       {@code totalInputTokens + totalOutputTokens} × provider rate.</li>
     * </ul>
     *
     * <p>The {@link EvaluationResult} shape stays stable across Phase 1.1 →
     * 1.6 — only the per-field provenance changes. The FE
     * {@code SkillDraftEvaluationReport.tsx} reads the same fields.
     */
    private EvaluationResult.SkillMetrics computeMetrics(List<SessionEntity> sessions) {
        if (sessions.isEmpty()) {
            return new EvaluationResult.SkillMetrics(0.0, 0.0, 0.0, 0L, 0.0);
        }
        int completedCount = 0;
        long totalLatency = 0L;
        for (SessionEntity s : sessions) {
            if ("completed".equals(s.getRuntimeStatus())) {
                completedCount++;
            }
            if (s.getCreatedAt() != null && s.getCompletedAt() != null) {
                long latency = s.getCompletedAt().toEpochMilli()
                        - s.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
                totalLatency += Math.max(0L, latency);
            }
        }
        double passRate = (double) completedCount / sessions.size();
        long avgLatencyMs = totalLatency / sessions.size();
        // Phase 1.1 placeholder: compositeScore == passRate as a crude proxy.
        // Phase 1.6 will replace with EvalJudgeTool.judgeMultiTurnConversation output.
        return new EvaluationResult.SkillMetrics(
                passRate, passRate, passRate, avgLatencyMs, 0.0);
    }

    private String buildPlaceholderSummary(SkillDraftEntity draft,
                                            EvaluationResult.SkillMetrics with,
                                            EvaluationResult.SkillMetrics without,
                                            EvaluationResult.SkillMetrics delta) {
        StringBuilder sb = new StringBuilder();
        sb.append("Skill '").append(draft.getName()).append("' evaluation completed. ");
        sb.append(String.format("with_skill passRate=%.0f%%, without_skill passRate=%.0f%%, delta=%+.0fpp. ",
                with.passRate() * 100, without.passRate() * 100, delta.passRate() * 100));
        if (delta.passRate() >= SkillCreatorService.PASS_RATE_DELTA_THRESHOLD) {
            sb.append("Recommended: promote (delta meets threshold ")
              .append(String.format("%.0fpp", SkillCreatorService.PASS_RATE_DELTA_THRESHOLD * 100))
              .append(").");
        } else {
            sb.append("Recommended: reject (delta below threshold ")
              .append(String.format("%.0fpp", SkillCreatorService.PASS_RATE_DELTA_THRESHOLD * 100))
              .append("). Phase 1.6 will surface a real LLM summary here once EvalJudgeTool integration lands.");
        }
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers — local records for JSON parsing
    // ────────────────────────────────────────────────────────────────────────

    private EvalContext parseEvalContext(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, String> m = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            EvalContext c = new EvalContext();
            c.draftId = m.get("draftId");
            c.scenarioId = m.get("scenarioId");
            c.baselineLabel = m.get("baselineLabel");
            return c;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private PendingState parsePending(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            Object pendingFlag = raw.get("_pending");
            if (!Boolean.TRUE.equals(pendingFlag)) {
                PendingState ps = new PendingState();
                ps.pending = false;
                return ps;
            }
            PendingState ps = new PendingState();
            ps.pending = true;
            Object ec = raw.get("expectedCount");
            ps.expectedCount = ec instanceof Number n ? n.intValue() : 0;
            Object scenarios = raw.get("scenarioIds");
            if (scenarios instanceof List<?> list) {
                List<String> ids = new ArrayList<>();
                for (Object x : list) {
                    if (x != null) ids.add(x.toString());
                }
                ps.scenarioIds = ids;
            } else {
                ps.scenarioIds = new ArrayList<>();
            }
            Object psid = raw.get("parentSessionId");
            ps.parentSessionId = psid == null ? null : psid.toString();
            return ps;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean isTerminal(String runtimeStatus) {
        if (runtimeStatus == null) return false;
        return TERMINAL_STATUSES.contains(runtimeStatus)
                || "idle".equals(runtimeStatus); // ChatService.runLoop sets idle on completed too
    }

    /** Private DTO mirroring the eval_context_json shape. */
    private static class EvalContext {
        String draftId;
        String scenarioId;
        String baselineLabel;
    }

    /** Private DTO for parsing the pending stub written by SkillCreatorService.dispatchEvaluation. */
    private static class PendingState {
        boolean pending;
        int expectedCount;
        List<String> scenarioIds;
        String parentSessionId;
    }
}
