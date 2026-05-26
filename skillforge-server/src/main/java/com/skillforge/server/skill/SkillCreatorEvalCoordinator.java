package com.skillforge.server.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.eval.EvalJudgeMultiTurnOutput;
import com.skillforge.server.eval.EvalJudgeTool;
import com.skillforge.server.eval.MultiTurnTranscript;
import com.skillforge.server.eval.ScenarioRunResult;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.improve.EphemeralScenarioCleanupService;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillRepository;
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
     * SKILL-CREATOR-PHASE-1.6 Phase 1.1 (2026-05-19): real LLM-as-judge call
     * replaces the runtime_status proxy (D12 deviation). Per-child-session
     * transcript is rendered via {@link MultiTurnTranscriptBuilder}, paired with
     * a {@link ScenarioRunResult} adapter built from the child session metrics
     * + the {@link EvalScenarioEntity} → {@link EvalScenario} adapter (same
     * shape as {@code AbEvalPipeline.toEvalScenario}). Nullable in legacy
     * fixtures that exercise the proxy path only — see
     * {@link #aggregateSideViaJudge(java.util.List, java.util.Map)} fallback.
     */
    private final EvalJudgeTool evalJudgeTool;

    /**
     * Phase 1.1: scenarios are looked up by id (extracted from each child
     * session's {@code eval_context_json.scenarioId}) so the judge call has
     * access to {@code task} + {@code oracle.expected}. Nullable for legacy
     * fixtures.
     */
    private final EvalScenarioDraftRepository scenarioRepository;

    /**
     * Phase 1.1: transcript renderer (lazy bean wiring same as
     * {@link #chatService} since this lives in the same module). Nullable
     * for legacy fixtures.
     */
    private final MultiTurnTranscriptBuilder transcriptBuilder;

    /**
     * Phase 1.6 F3 fix (2026-05-19): in-memory unregister of the transient
     * SkillDefinition after aggregation completes. Pairs with
     * {@code SkillCreatorService.renderTransientCandidateSkill}'s registerSkillDefinition.
     * Skipping this would leak the {@code _eval_<8char>} entry in the
     * registry indefinitely — harmless in dev (just clutter) but in prod
     * a long-running BE would accumulate dead candidates.
     *
     * <p>Nullable for legacy fixtures.
     */
    private final SkillRegistry skillRegistry;

    /**
     * Phase 1.6 F3: lookup of the transient candidate's name (the {@code
     * SkillEntity.name} that got registered) so we can unregister by name.
     * Nullable for legacy fixtures.
     */
    private final SkillRepository skillRepository;

    /**
     * Phase 1.1 D12-fix (2026-05-19, Phase 1.6): judge-score → 0..1 normalize
     * divisor. {@link EvalJudgeTool#judgeMultiTurnConversation} returns
     * {@code compositeScore} / {@code overallScore} in 0..100 (verified
     * Phase 1.0); {@link EvaluationResult.SkillMetrics} stores 0..1
     * (FE depends on this scale). All arithmetic stays in 0..1 from here.
     */
    private static final double JUDGE_SCORE_SCALE = 100.0;

    /**
     * Test-only ctor — preserves Phase 1.1 5-case unit tests + the
     * Phase 1.6 Phase 1.0 red-test anchor. Calling
     * {@link #onSkillEvalDispatchReady} on this instance is a no-op (chatService
     * null), and {@link #onSessionLoopFinished} aggregation falls back to the
     * pre-Phase-1.6 proxy formula when the judge / scenario deps are null
     * (so legacy 5-case tests keep their assertions).
     */
    public SkillCreatorEvalCoordinator(SessionRepository sessionRepository,
                                        SkillDraftRepository draftRepository,
                                        EphemeralScenarioCleanupService cleanupService,
                                        ObjectMapper objectMapper) {
        this(sessionRepository, draftRepository, cleanupService, objectMapper, null,
                null, null, null, null, null);
    }

    /**
     * Phase 1.6 test ctor variant — pass the judge mock + scenario repo +
     * transcript builder to drive the new aggregate-via-judge path. Used by
     * {@code SkillCreatorEvalCoordinatorJudgeIT} after the Phase 1.0 → 1.1
     * red-test inversion.
     */
    public SkillCreatorEvalCoordinator(SessionRepository sessionRepository,
                                        SkillDraftRepository draftRepository,
                                        EphemeralScenarioCleanupService cleanupService,
                                        ObjectMapper objectMapper,
                                        EvalJudgeTool evalJudgeTool,
                                        EvalScenarioDraftRepository scenarioRepository,
                                        MultiTurnTranscriptBuilder transcriptBuilder) {
        this(sessionRepository, draftRepository, cleanupService, objectMapper, null,
                evalJudgeTool, scenarioRepository, transcriptBuilder, null, null);
    }

    /**
     * Phase 1.6 F3 fix test ctor variant — pass the SkillRegistry +
     * SkillRepository mocks too to exercise the candidate-skill cleanup
     * (unregister + log) at aggregate finish. Used by F3-fix regression
     * tests added in {@code SkillCreatorEvalCoordinatorJudgeIT}.
     */
    public SkillCreatorEvalCoordinator(SessionRepository sessionRepository,
                                        SkillDraftRepository draftRepository,
                                        EphemeralScenarioCleanupService cleanupService,
                                        ObjectMapper objectMapper,
                                        EvalJudgeTool evalJudgeTool,
                                        EvalScenarioDraftRepository scenarioRepository,
                                        MultiTurnTranscriptBuilder transcriptBuilder,
                                        SkillRegistry skillRegistry,
                                        SkillRepository skillRepository) {
        this(sessionRepository, draftRepository, cleanupService, objectMapper, null,
                evalJudgeTool, scenarioRepository, transcriptBuilder,
                skillRegistry, skillRepository);
    }

    @Autowired
    public SkillCreatorEvalCoordinator(SessionRepository sessionRepository,
                                        SkillDraftRepository draftRepository,
                                        EphemeralScenarioCleanupService cleanupService,
                                        ObjectMapper objectMapper,
                                        @Lazy ChatService chatService,
                                        EvalJudgeTool evalJudgeTool,
                                        EvalScenarioDraftRepository scenarioRepository,
                                        MultiTurnTranscriptBuilder transcriptBuilder,
                                        SkillRegistry skillRegistry,
                                        SkillRepository skillRepository) {
        this.sessionRepository = sessionRepository;
        this.draftRepository = draftRepository;
        this.cleanupService = cleanupService;
        this.objectMapper = objectMapper;
        this.chatService = chatService;
        this.evalJudgeTool = evalJudgeTool;
        this.scenarioRepository = scenarioRepository;
        this.transcriptBuilder = transcriptBuilder;
        this.skillRegistry = skillRegistry;
        this.skillRepository = skillRepository;
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
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            // Phase 1.6 hotfix r5 (2026-05-19): ChatService.runLoop publishes
            // SessionLoopFinishedEvent in a non-@Transactional context (loop
            // teardown is fire-and-forget, no surrounding tx). Without
            // fallbackExecution=true, Spring silently drops AFTER_COMMIT
            // listeners when no tx is active → onSessionLoopFinished never
            // fires → aggregate never runs → status stuck on 'evaluating'
            // forever. fallbackExecution=true makes the listener fire
            // immediately when there's no tx (no wait), while still honoring
            // AFTER_COMMIT semantics when a tx IS present.
            fallbackExecution = true)
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

        // Phase 1.6 F3 fix (2026-05-19): unregister the transient candidate
        // SkillDefinition from the in-memory SkillRegistry. Pairs with
        // SkillCreatorService.renderTransientCandidateSkill's registration.
        // Skipping leaks the entry (harmless in dev, accumulates in prod).
        unregisterTransientCandidate(draft);

        log.info("[SkillCreatorEvalCoordinator] draftId={} aggregated: status={} passRateDelta={}",
                ctx.draftId, draft.getStatus(),
                result.delta() == null ? "null" : String.format("%.3f", result.delta().passRate()));
    }

    /**
     * Phase 1.6 F3 fix (2026-05-19) — remove the transient candidate's
     * {@link com.skillforge.core.model.SkillDefinition} from the in-memory
     * registry. Best-effort: registry / repo deps null in legacy fixtures →
     * silent no-op. Lookup failure logs a warning and continues (the
     * aggregate already wrote its final result; leaking the registry entry
     * is non-fatal).
     */
    private void unregisterTransientCandidate(SkillDraftEntity draft) {
        if (skillRegistry == null || skillRepository == null) {
            return;
        }
        Long candidateSkillId = draft.getCandidateSkillId();
        if (candidateSkillId == null) {
            log.debug("[SkillCreatorEvalCoordinator] draft {} has no candidateSkillId — "
                    + "nothing to unregister", draft.getId());
            return;
        }
        try {
            SkillEntity candidate = skillRepository.findById(candidateSkillId).orElse(null);
            if (candidate == null) {
                log.warn("[SkillCreatorEvalCoordinator] candidateSkillId {} not found "
                                + "for draft {} — registry entry may leak",
                        candidateSkillId, draft.getId());
                return;
            }
            skillRegistry.unregisterSkillDefinition(candidate.getName());
            log.debug("[SkillCreatorEvalCoordinator] unregistered transient SkillDefinition "
                            + "name='{}' for draft {}",
                    candidate.getName(), draft.getId());

            // 2026-05-26: also delete the transient SkillEntity row from the DB
            // and null out the draft's candidateSkillId. Without this, every
            // skill-creator eval run leaks one row in t_skill (source=
            // 'skill-creator-eval-transient', enabled=false) that piles up in
            // the dashboard skill listing forever. Best-effort: any failure
            // logs WARN but doesn't propagate (registry unregister already
            // succeeded; DB leak is non-fatal).
            try {
                draft.setCandidateSkillId(null);
                draftRepository.save(draft);
                skillRepository.delete(candidate);
                log.info("[SkillCreatorEvalCoordinator] deleted transient SkillEntity "
                                + "id={} name='{}' for draft {} (candidate_skill_id nullified)",
                        candidate.getId(), candidate.getName(), draft.getId());
            } catch (RuntimeException ex) {
                log.warn("[SkillCreatorEvalCoordinator] failed to delete transient "
                                + "SkillEntity id={} name='{}' for draft {} (registry "
                                + "already unregistered, row will leak): {}",
                        candidate.getId(), candidate.getName(), draft.getId(),
                        ex.getMessage());
            }
        } catch (RuntimeException ex) {
            log.warn("[SkillCreatorEvalCoordinator] failed to unregister transient "
                            + "candidate skill for draft {}: {}",
                    draft.getId(), ex.getMessage());
        }
    }

    /**
     * Aggregate sibling sessions into an {@link EvaluationResult}.
     *
     * <p>Phase 1.6 (2026-05-19): when the judge surface is wired
     * ({@link #evalJudgeTool} / {@link #scenarioRepository} /
     * {@link #transcriptBuilder} all non-null), aggregation goes via
     * {@link #computeMetricsViaJudge(List, Map)} — real LLM scoring of each
     * child session's transcript. When the judge surface isn't wired (legacy
     * test fixtures), falls back to {@link #computeMetricsViaProxy(List)} —
     * the Phase 1.1 runtime_status proxy. Production wiring is via the
     * 9-arg {@code @Autowired} ctor; tests choose which path by which test
     * ctor they construct.
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

        boolean judgeWired = evalJudgeTool != null && scenarioRepository != null
                && transcriptBuilder != null;
        Map<String, EvalScenarioEntity> scenarioCache = judgeWired
                ? loadScenarios(pending.scenarioIds)
                : java.util.Collections.emptyMap();

        EvaluationResult.SkillMetrics withMetrics = judgeWired
                ? computeMetricsViaJudge(withSkillSiblings, scenarioCache)
                : computeMetricsViaProxy(withSkillSiblings);
        EvaluationResult.SkillMetrics withoutMetrics = judgeWired
                ? computeMetricsViaJudge(withoutSkillSiblings, scenarioCache)
                : computeMetricsViaProxy(withoutSkillSiblings);
        EvaluationResult.SkillMetrics delta = new EvaluationResult.SkillMetrics(
                withMetrics.compositeScore() - withoutMetrics.compositeScore(),
                withMetrics.overallScore() - withoutMetrics.overallScore(),
                withMetrics.passRate() - withoutMetrics.passRate(),
                withMetrics.avgLatencyMs() - withoutMetrics.avgLatencyMs(),
                withMetrics.totalCostUsd() - withoutMetrics.totalCostUsd());

        String llmSummary = buildPlaceholderSummary(draft, withMetrics, withoutMetrics, delta,
                judgeWired);
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
     * Phase 1.6 real-judge aggregator. For each sibling session:
     * <ol>
     *   <li>Resolve {@link EvalScenarioEntity} from {@code eval_context_json.scenarioId}</li>
     *   <li>Render transcript via {@link MultiTurnTranscriptBuilder#fromSession(String)}</li>
     *   <li>Build {@link ScenarioRunResult} adapter from session metrics
     *       (status / wall-time / token counts)</li>
     *   <li>Call {@link EvalJudgeTool#judgeMultiTurnConversation}</li>
     *   <li>Normalize judge {@code compositeScore} / {@code overallScore} from
     *       0..100 → 0..1 (see {@link #JUDGE_SCORE_SCALE} — Phase 1.0 verify (a))</li>
     * </ol>
     *
     * <p>Then aggregate across the side's N sessions:
     * <ul>
     *   <li>{@code compositeScore} = mean per-session composite (0..1)</li>
     *   <li>{@code overallScore} = mean per-session overall (0..1)</li>
     *   <li>{@code passRate} = count(per-session composite ≥ {@link
     *       SkillCreatorService#SCENARIO_PASS_SCORE} 0.7) / N — cc convention</li>
     *   <li>{@code avgLatencyMs} = mean wall-time</li>
     *   <li>{@code totalCostUsd} = 0.0 (provider-rate lookup is a separate
     *       backlog; ScenarioRunResult tokens are captured but not converted)</li>
     * </ul>
     *
     * <p>Failure modes (judge call throws / scenario not found / transcript
     * empty): handled per session — that session contributes {@code (0, 0)}
     * to the means + counts as a non-pass. Other sessions still aggregate
     * normally. The judge contract itself never throws — it degrades to 0
     * score (see {@code EvalJudgeTool.judgeMultiTurnConversation} catch).
     */
    private EvaluationResult.SkillMetrics computeMetricsViaJudge(List<SessionEntity> sessions,
                                                                  Map<String, EvalScenarioEntity> scenarioCache) {
        if (sessions == null || sessions.isEmpty()) {
            return new EvaluationResult.SkillMetrics(0.0, 0.0, 0.0, 0L, 0.0);
        }
        double sumComposite = 0.0;
        double sumOverall = 0.0;
        int passCount = 0;
        long totalLatency = 0L;
        int N = sessions.size();

        for (SessionEntity s : sessions) {
            EvalContext ctx = parseEvalContext(s.getEvalContextJson());
            EvalScenarioEntity scenarioEntity = ctx == null ? null : scenarioCache.get(ctx.scenarioId);
            EvalScenario scenario = scenarioEntity == null ? null : toEvalScenario(scenarioEntity);

            MultiTurnTranscript transcript;
            try {
                transcript = transcriptBuilder.fromSession(s.getId());
            } catch (RuntimeException ex) {
                log.warn("[SkillCreatorEvalCoordinator] transcript build failed sessionId={}: {}",
                        s.getId(), ex.getMessage());
                transcript = new MultiTurnTranscript();
            }

            ScenarioRunResult runResult = buildScenarioRunResult(s,
                    ctx == null ? null : ctx.scenarioId);

            double composite01;
            double overall01;
            if (scenario == null) {
                // No scenario to judge against → can't call judge; mark as 0.
                log.warn("[SkillCreatorEvalCoordinator] sessionId={} has no resolvable scenario "
                        + "(scenarioId={}); contributing 0/0 to aggregate", s.getId(),
                        ctx == null ? null : ctx.scenarioId);
                composite01 = 0.0;
                overall01 = 0.0;
            } else {
                EvalJudgeMultiTurnOutput judgeOut;
                try {
                    judgeOut = evalJudgeTool.judgeMultiTurnConversation(scenario, runResult, transcript);
                } catch (RuntimeException ex) {
                    // EvalJudgeTool internally catches Exception → returns 0 score, so
                    // this branch is defensive only (e.g. wiring bug). Don't kill the
                    // whole side just because one session's judge call exploded.
                    log.error("[SkillCreatorEvalCoordinator] judge call exploded sessionId={} "
                            + "scenarioId={}", s.getId(),
                            ctx == null ? null : ctx.scenarioId, ex);
                    judgeOut = new EvalJudgeMultiTurnOutput();
                    judgeOut.setCompositeScore(0.0);
                    judgeOut.setOverallScore(0.0);
                }
                composite01 = clamp01(judgeOut.getCompositeScore() / JUDGE_SCORE_SCALE);
                overall01 = clamp01(judgeOut.getOverallScore() / JUDGE_SCORE_SCALE);
            }
            sumComposite += composite01;
            sumOverall += overall01;
            if (composite01 >= SkillCreatorService.SCENARIO_PASS_SCORE) {
                passCount++;
            }
            totalLatency += sessionWallTimeMs(s);
        }

        double meanComposite = sumComposite / N;
        double meanOverall = sumOverall / N;
        double passRate = (double) passCount / N;
        long avgLatencyMs = totalLatency / N;
        // Phase 1.6 TODO: per-side totalCostUsd from token counts × provider
        // rates. ScenarioRunResult carries input/output tokens (captured from
        // SessionEntity.totalInputTokens / totalOutputTokens) but the provider-
        // rate lookup is a separate backlog (no per-provider cost table yet).
        return new EvaluationResult.SkillMetrics(
                meanComposite, meanOverall, passRate, avgLatencyMs, 0.0);
    }

    /**
     * Phase 1.1 runtime_status proxy (D12 deviation) — retained for legacy
     * test fixtures that don't wire the judge surface. {@link #aggregate}
     * picks this path when {@link #evalJudgeTool} (or its sibling deps) is
     * null. Production wiring always picks the judge path because the
     * Spring ctor passes all four deps.
     *
     * <p>Per session: {@code passRate} = 1.0 if {@code runtime_status='completed'},
     * 0.0 otherwise. {@code avgLatencyMs} = real wall-time. {@code totalCostUsd} = 0.
     */
    private EvaluationResult.SkillMetrics computeMetricsViaProxy(List<SessionEntity> sessions) {
        if (sessions.isEmpty()) {
            return new EvaluationResult.SkillMetrics(0.0, 0.0, 0.0, 0L, 0.0);
        }
        int completedCount = 0;
        long totalLatency = 0L;
        for (SessionEntity s : sessions) {
            if ("completed".equals(s.getRuntimeStatus())) {
                completedCount++;
            }
            totalLatency += sessionWallTimeMs(s);
        }
        double passRate = (double) completedCount / sessions.size();
        long avgLatencyMs = totalLatency / sessions.size();
        return new EvaluationResult.SkillMetrics(
                passRate, passRate, passRate, avgLatencyMs, 0.0);
    }

    private Map<String, EvalScenarioEntity> loadScenarios(List<String> scenarioIds) {
        if (scenarioIds == null || scenarioIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        Map<String, EvalScenarioEntity> out = new LinkedHashMap<>();
        for (String id : scenarioIds) {
            if (id == null) continue;
            scenarioRepository.findById(id).ifPresent(e -> out.put(id, e));
        }
        return out;
    }

    /**
     * EvalScenarioEntity → EvalScenario adapter. Mirrors {@code
     * AbEvalPipeline.toEvalScenario} (single-turn ephemerals); multi-turn
     * is not used by skill-creator-eval (each ephemeral scenario is a
     * single user task per Phase 1.2's
     * {@code SkillCreatorService.buildEphemeralScenariosFromZip /
     * buildEphemeralScenariosFromSessions}).
     */
    private static EvalScenario toEvalScenario(EvalScenarioEntity entity) {
        EvalScenario scenario = new EvalScenario();
        scenario.setId(entity.getId());
        scenario.setName(entity.getName());
        scenario.setDescription(entity.getDescription());
        scenario.setCategory(entity.getCategory());
        scenario.setSplit(entity.getSplit());
        scenario.setTask(entity.getTask());
        EvalScenario.ScenarioOracle oracle = new EvalScenario.ScenarioOracle();
        oracle.setType(entity.getOracleType());
        oracle.setExpected(entity.getOracleExpected());
        scenario.setOracle(oracle);
        return scenario;
    }

    /**
     * Build a {@link ScenarioRunResult} adapter from the child SessionEntity.
     * EvalJudgeTool's multi-turn judge reads these signals to anchor failure
     * attribution (e.g. {@code engineThrewException} biases toward VETO,
     * {@code hitLoopLimit} biases toward TIMEOUT-like attribution).
     *
     * <p>Phase 1.0 verify (d): {@code t_subagent_run} doesn't store
     * token / cost; we pull them from the child {@link SessionEntity}
     * ({@code totalInputTokens} / {@code totalOutputTokens}) directly.
     */
    private static ScenarioRunResult buildScenarioRunResult(SessionEntity session, String scenarioId) {
        boolean completed = "completed".equals(session.getRuntimeStatus())
                || "idle".equals(session.getRuntimeStatus());
        boolean errored = "error".equals(session.getRuntimeStatus())
                || "aborted_by_hook".equals(session.getRuntimeStatus());
        boolean cancelled = "cancelled".equals(session.getRuntimeStatus());

        ScenarioRunResult result;
        if (completed) {
            result = ScenarioRunResult.pass(scenarioId);
        } else if (errored) {
            result = ScenarioRunResult.error(scenarioId,
                    "Child session runtime_status='" + session.getRuntimeStatus() + "'");
        } else if (cancelled) {
            result = ScenarioRunResult.timeout(scenarioId, "Cancelled");
        } else {
            result = ScenarioRunResult.fail(scenarioId);
        }
        result.setSessionId(session.getId());
        result.setExecutionTimeMs(sessionWallTimeMs(session));
        result.setInputTokens(session.getTotalInputTokens());
        result.setOutputTokens(session.getTotalOutputTokens());
        result.setRootTraceId(session.getActiveRootTraceId());
        // loopCount / agentFinalOutput / tool-related signals: not tracked at this
        // surface yet. Phase 1.7 dogfood may augment if attribution surfaces want
        // them; for now judge has enough (transcript + status + oracle).
        return result;
    }

    private static long sessionWallTimeMs(SessionEntity s) {
        if (s.getCreatedAt() == null || s.getCompletedAt() == null) return 0L;
        long latency = s.getCompletedAt().toEpochMilli()
                - s.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        return Math.max(0L, latency);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Build a human-readable rollup of the per-side metrics. Phase 1.6 still
     * uses a deterministic template (no separate LLM "rollup" call); the
     * meaningful LLM call is the per-session judge inside
     * {@link #computeMetricsViaJudge(List, Map)}. A future Phase 1.7 may
     * add a second LLM pass to summarize attribution / failure modes per
     * judge {@code rationale} — tracked as backlog.
     */
    private String buildPlaceholderSummary(SkillDraftEntity draft,
                                            EvaluationResult.SkillMetrics with,
                                            EvaluationResult.SkillMetrics without,
                                            EvaluationResult.SkillMetrics delta,
                                            boolean judgeWired) {
        StringBuilder sb = new StringBuilder();
        sb.append("Skill '").append(draft.getName()).append("' evaluation completed");
        sb.append(judgeWired ? " (LLM judge). " : " (runtime_status proxy). ");
        sb.append(String.format("with_skill passRate=%.0f%%, without_skill passRate=%.0f%%, delta=%+.0fpp. ",
                with.passRate() * 100, without.passRate() * 100, delta.passRate() * 100));
        if (judgeWired) {
            sb.append(String.format("Mean composite (0..1): with=%.2f, without=%.2f, delta=%+.2f. ",
                    with.compositeScore(), without.compositeScore(), delta.compositeScore()));
        }
        if (delta.passRate() >= SkillCreatorService.PASS_RATE_DELTA_THRESHOLD) {
            sb.append("Recommended: promote (delta meets threshold ")
              .append(String.format("%.0fpp", SkillCreatorService.PASS_RATE_DELTA_THRESHOLD * 100))
              .append(").");
        } else {
            sb.append("Recommended: reject (delta below threshold ")
              .append(String.format("%.0fpp", SkillCreatorService.PASS_RATE_DELTA_THRESHOLD * 100))
              .append(").");
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
