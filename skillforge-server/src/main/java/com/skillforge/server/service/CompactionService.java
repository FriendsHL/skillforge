package com.skillforge.server.service;

import com.skillforge.core.compact.CompactableToolRegistry;
import com.skillforge.core.compact.CompactResult;
import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.compact.recovery.RecoveryPayloadBuilder;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.ModelConfig;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.dto.SessionCompactionCheckpointDto;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.CompactionEventEntity;
import com.skillforge.server.entity.SessionCompactionCheckpointEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionSummaryEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.CompactionEventRepository;
import com.skillforge.server.repository.SessionCompactionCheckpointRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上下文压缩服务。实现 {@link ContextCompactorCallback} 供 core 层 AgentLoopEngine 调用,
 * 同时暴露 REST 入口给 ChatController。
 *
 * <p>职责:
 * <ul>
 *   <li>持久化 messages 变更到 session</li>
 *   <li>持久化压缩事件到 t_compaction_event (no-op 不入表, 只 INFO 日志)</li>
 *   <li>更新 session 上的 light/full count 与 lastCompactedAtMessageCount</li>
 *   <li>通过 per-user 通道广播 session_updated</li>
 *   <li>idempotency guard: 除 user-manual / A1 light 外, 连续 &lt; 5 message 间隔内的新请求直接 no-op</li>
 * </ul>
 *
 * <p>线程安全: 按 sessionId 哈希到 64 槽 stripe lock, 串行化同一 session 的压缩操作.
 * ChatService.chatAsync 也通过 {@link #lockFor(String)} 取到同一个锁, 保证 C1 vs running
 * 的 TOCTOU 竞争被消除.
 *
 * <p>Full compact 三阶段设计（P1-2）:
 * <ul>
 *   <li>Phase 1 (under stripe lock): 守卫检查 + prepareCompact (纯 Java, 无 LLM)</li>
 *   <li>Phase 2 (stripe lock 已释放): applyPrepared — 阻塞 LLM 调用, 不持锁</li>
 *   <li>Phase 3 (under stripe lock + transaction): 持久化结果</li>
 * </ul>
 * 通过 {@link #fullCompactInFlight} Set 防止同一 session 同时有两个 full compact 并发进行.
 *
 * <p>事务: light 路径通过 {@link TransactionTemplate} 包裹 DB 操作; full 路径 Phase 3 同样.
 */
@Service
public class CompactionService implements ContextCompactorCallback, SessionService.CompactionLockProvider {

    private static final Logger log = LoggerFactory.getLogger(CompactionService.class);

    /** Shared mapper for parsing the small agent config JSON (read-only → thread-safe; avoids
     *  allocating a new ObjectMapper per call now that resolveContextWindowForSession runs per turn
     *  via the reminder path). */
    private static final com.fasterxml.jackson.databind.ObjectMapper CONFIG_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final int LOCK_STRIPES = 64;
    private static final int IDEMPOTENCY_MIN_GAP_MESSAGES = 5;

    /**
     * COMPACT-IDEMPOTENCY-FIX ①: engine-triggered "the context is genuinely over the window"
     * sources. When the engine fires one of these it has already measured ratio &gt; threshold
     * (or hit a context_length_exceeded overflow), so the compaction is REQUIRED and must never
     * be skipped by the idempotency guard — otherwise a session that was previously compacted to
     * a high persisted high-water mark gets its in-loop compaction permanently suppressed while
     * the live context keeps growing past the window (observed: ratio=1.72 but "skipped gap=-97").
     */
    private static final Set<String> PREEMPTIVE_SOURCES = Set.of(
            ContextCompactorCallback.SOURCE_ENGINE_SOFT,
            ContextCompactorCallback.SOURCE_ENGINE_HARD,
            ContextCompactorCallback.SOURCE_ENGINE_PREEMPTIVE,
            ContextCompactorCallback.SOURCE_POST_OVERFLOW);

    /**
     * COMPACT-IDEMPOTENCY-FIX ②: minimum compactable-window size (number of messages BEFORE the
     * young-gen) for a full compact to be worth doing. Below this the boundary has degenerated to
     * a tiny prefix (common on tool-heavy sessions where the safe boundary cannot cut inside
     * tool_use↔tool_result pairs), so the compaction reclaims ≈0 tokens for ~0 value and is
     * treated as a true no-op (a cost/value filter — skip a zero-value compaction).
     */
    static final int MIN_COMPACT_WINDOW_MESSAGES = 4;

    private final SessionRepository sessionRepository;
    private final CompactionEventRepository eventRepository;
    private final SessionCompactionCheckpointRepository checkpointRepository;
    private final SessionService sessionService;
    private final LightCompactStrategy lightStrategy;
    private final FullCompactStrategy fullStrategy;
    private final LlmProviderFactory llmProviderFactory;
    private final LlmProperties llmProperties;
    private final ChatEventBroadcaster broadcaster;
    private AgentRepository agentRepository;
    /** P9-5: optional — when set, full-compact emits a recovery payload row after retained messages. */
    private RecoveryPayloadBuilder recoveryPayloadBuilder;

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX (storage redesign P1): range-summary store + targeted
     * row marker. Optional (setter-injected, null in legacy unit tests) — when the range-model
     * flag is on, full compact writes a {@code t_session_summary} row instead of re-appending the
     * young-gen, and stamps {@code compacted_by_summary_id} on the covered rows.
     */
    private SessionSummaryRepository sessionSummaryRepository;
    private SessionMessageRepository sessionMessageRepository;

    /**
     * Feature flag for the range-based compaction model (storage-redesign.md §10, P1). Default
     * FALSE → existing behavior is byte-identical (the legacy boundary + summary + re-append
     * young-gen path). When TRUE the new flagged write path runs. Flag ON is not yet a valid live
     * runtime state (P2 wires the derived read); P1 only exercises it under tests.
     */
    @Value("${skillforge.compact.range-model.enabled:false}")
    private boolean rangeModelEnabled;

    private final Object[] sessionLocks;

    /** Sessions currently executing Phase 2 (LLM call) of full compact. */
    private final Set<String> fullCompactInFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Null-safe transaction template; null when no PlatformTransactionManager is provided (tests). */
    private final TransactionTemplate transactionTemplate;

    public static class CheckpointNotFoundException extends RuntimeException {
        public CheckpointNotFoundException(String message) {
            super(message);
        }
    }

    public CompactionService(SessionRepository sessionRepository,
                             CompactionEventRepository eventRepository,
                             SessionCompactionCheckpointRepository checkpointRepository,
                             SessionService sessionService,
                             LightCompactStrategy lightStrategy,
                             FullCompactStrategy fullStrategy,
                             LlmProviderFactory llmProviderFactory,
                             LlmProperties llmProperties,
                             ChatEventBroadcaster broadcaster,
                             PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.checkpointRepository = checkpointRepository;
        this.sessionService = sessionService;
        // P2b B1: hand SessionService this service as its compaction lock provider so
        // updateSessionMessages can serialize reconciliation against compaction Phase 3 using the
        // SAME per-session stripe lock. Registering from the constructor (not @Autowired setter)
        // keeps the wiring deterministic and avoids a Spring circular-dependency setter cycle.
        sessionService.setCompactionLockProvider(this);
        this.lightStrategy = lightStrategy;
        this.fullStrategy = fullStrategy;
        this.llmProviderFactory = llmProviderFactory;
        this.llmProperties = llmProperties;
        this.broadcaster = broadcaster;
        this.transactionTemplate = (transactionManager != null)
                ? new TransactionTemplate(transactionManager) : null;
        this.sessionLocks = new Object[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.sessionLocks[i] = new Object();
        }
    }

    /** Optional: 通过 setter 注入以便测试直接 new CompactionService 时不必提供. */
    @Autowired(required = false)
    public void setAgentRepository(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    /**
     * P9-5: optional setter — when wired (production path), every full compact will append a
     * recovery payload after the retained young-gen messages. null in test paths is fine; the
     * persistence loop just skips the recovery insertion.
     */
    @Autowired(required = false)
    public void setRecoveryPayloadBuilder(RecoveryPayloadBuilder recoveryPayloadBuilder) {
        this.recoveryPayloadBuilder = recoveryPayloadBuilder;
    }

    /** Optional: range-summary store for the range-model write path (storage redesign P1). */
    @Autowired(required = false)
    public void setSessionSummaryRepository(SessionSummaryRepository sessionSummaryRepository) {
        this.sessionSummaryRepository = sessionSummaryRepository;
    }

    /** Optional: message store for the targeted {@code compacted_by_summary_id} marker UPDATE (P1). */
    @Autowired(required = false)
    public void setSessionMessageRepository(SessionMessageRepository sessionMessageRepository) {
        this.sessionMessageRepository = sessionMessageRepository;
    }

    /** Test seam: toggle the range-model write path without a Spring context. */
    public void setRangeModelEnabled(boolean rangeModelEnabled) {
        this.rangeModelEnabled = rangeModelEnabled;
    }

    /**
     * 暴露 session 级 stripe 锁, 让其它组件 (如 ChatService.chatAsync) 可以和 compact 串行化.
     * 避免 C1 vs runtimeStatus=running 的 TOCTOU 竞争。
     */
    public Object lockFor(String sessionId) {
        return sessionLocks[Math.floorMod(sessionId.hashCode(), LOCK_STRIPES)];
    }

    // ================ REST 入口 ================

    /**
     * 用户或脚本主动触发一次压缩。
     * <p>会校验 runtimeStatus — running 时 user-manual 拒绝 (409 由 controller 映射).
     */
    public CompactionEventEntity compact(String sessionId, String level, String source, String reason) {
        if ("full".equalsIgnoreCase(level)) {
            FullCompactOutcome outcome = compactFullThreePhase(sessionId, source, reason, null);
            return (outcome != null) ? outcome.event() : null;
        }
        return compactLightUnderLock(sessionId, source, reason);
    }

    public List<CompactionEventEntity> listEvents(String sessionId) {
        return eventRepository.findBySessionIdOrderByIdDesc(sessionId);
    }

    @Transactional(readOnly = true)
    public List<SessionCompactionCheckpointDto> listCheckpoints(String sessionId, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        List<SessionCompactionCheckpointEntity> checkpoints = checkpointRepository
                .findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(0, safeSize))
                .getContent();
        List<SessionCompactionCheckpointDto> out = new ArrayList<>(checkpoints.size());
        for (SessionCompactionCheckpointEntity checkpoint : checkpoints) {
            out.add(toCheckpointDto(checkpoint));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public SessionCompactionCheckpointDto getCheckpoint(String sessionId, String checkpointId) {
        SessionCompactionCheckpointEntity checkpoint = getCheckpointEntity(sessionId, checkpointId);
        return toCheckpointDto(checkpoint);
    }

    public SessionEntity createBranchFromCheckpoint(String sessionId, String checkpointId, String title) {
        synchronized (lockFor(sessionId)) {
            ensureCheckpointOperationAllowed(sessionId);
            SessionCompactionCheckpointEntity checkpoint = getCheckpointEntity(sessionId, checkpointId);
            SessionEntity source = sessionService.getSession(sessionId);

            final SessionEntity[] branchRef = new SessionEntity[1];
            runInTransaction(() -> {
                List<SessionService.AppendMessage> checkpointMessages = buildCheckpointMessages(sessionId, checkpoint);
                SessionEntity branch = new SessionEntity();
                branch.setId(UUID.randomUUID().toString());
                branch.setUserId(source.getUserId());
                branch.setAgentId(source.getAgentId());
                branch.setTitle((title != null && !title.isBlank())
                        ? title : source.getTitle() + " (branch)");
                branch.setParentSessionId(source.getId());
                // branch 是“分叉快照”，深度沿用源会话，避免影响 sub-agent 深度限制。
                branch.setDepth(source.getDepth());
                // EVAL-V2 M3a §2.2: branch 复制源 origin（"eval 父"分叉出"eval 子" 而非 production，
                // 整树 origin 一致；正常 production session 默认 production）。
                branch.setOrigin(source.getOrigin());
                branch.setExecutionMode(source.getExecutionMode());
                branch.setLightContext(source.isLightContext());
                branch.setMaxLoops(source.getMaxLoops());
                branch.setMessagesJson("[]");
                branch.setLastUserMessageAt(Instant.now());
                branch.setStatus("active");
                branch.setRuntimeStatus("idle");
                branch = sessionService.saveSession(branch);
                sessionService.rewriteMessages(branch.getId(), checkpointMessages);
                // P2b B2 (§4): range-model branch — copy the source summaries that fall fully within
                // the branch's seq range to the branch session (new ids, remapped superseded_by),
                // then re-derive the branch markers. Branch rows keep the source seq_nos (rewrite
                // reassigns 0..endSeq contiguously, matching source), so summary ranges transfer 1:1.
                long branchEndSeq = resolveCheckpointEndSeq(checkpoint);
                copyRangeSummariesToBranch(sessionId, branch.getId(), branchEndSeq);
                sessionService.recomputeCompactedMarkers(branch.getId());
                branchRef[0] = branch;
            });
            String branchId = branchRef[0] != null ? branchRef[0].getId() : null;
            if (branchId == null) {
                throw new IllegalStateException("Failed to create branch session");
            }
            return sessionService.getSession(branchId);
        }
    }

    public SessionEntity restoreFromCheckpoint(String sessionId, String checkpointId) {
        synchronized (lockFor(sessionId)) {
            ensureCheckpointOperationAllowed(sessionId);
            SessionCompactionCheckpointEntity checkpoint = getCheckpointEntity(sessionId, checkpointId);
            long restoreEndSeq = resolveCheckpointEndSeq(checkpoint);
            runInTransaction(() -> {
                List<SessionService.AppendMessage> checkpointMessages = buildCheckpointMessages(sessionId, checkpoint);
                sessionService.rewriteMessages(sessionId, checkpointMessages);
                // restore 后旧 seq 空间失效，清理“位于恢复点之后”的 checkpoint，避免后续回放歧义。
                checkpointRepository.deleteBySessionIdAfterSeqNo(sessionId, restoreEndSeq);
                // P2b B2 (§4): range-model restore — drop summaries whose covered range extends past
                // the restore point (their rows no longer exist), then re-derive markers from the
                // surviving active summaries. rewriteMessages already ran one recompute, but it used
                // the pre-prune summary set; this post-prune recompute is the authoritative pass.
                // No-op when the flag is OFF / no summaries. Runs in the SAME restore transaction.
                if (rangeModelEnabled && sessionSummaryRepository != null) {
                    sessionSummaryRepository.deleteBySessionIdAndEndSeqGreaterThan(sessionId, restoreEndSeq);
                    sessionService.recomputeCompactedMarkers(sessionId);
                }
                SessionEntity updated = sessionService.getSession(sessionId);
                updated.setRuntimeStatus("idle");
                updated.setRuntimeStep("restored_checkpoint");
                updated.setRuntimeError(null);
                updated.setLastCompactedAtMessageCount(updated.getMessageCount());
                sessionService.saveSession(updated);
            });
            return sessionService.getSession(sessionId);
        }
    }

    // ================ ContextCompactorCallback ================

    @Override
    public CompactCallbackResult compactLight(String sessionId, List<Message> currentMessages,
                                               String sourceLabel, String reason) {
        return doCallbackLight(sessionId, currentMessages, sourceLabel, reason);
    }

    @Override
    public CompactCallbackResult compactFull(String sessionId, List<Message> currentMessages,
                                              String sourceLabel, String reason) {
        FullCompactOutcome outcome = compactFullThreePhase(sessionId, sourceLabel, reason, currentMessages);
        if (outcome == null) {
            return CompactCallbackResult.noOp(currentMessages, "full compact no-op or in-flight");
        }
        CompactResult r = outcome.compactResult();
        return new CompactCallbackResult(r.getMessages(), true,
                r.getTokensReclaimed(), r.getBeforeTokens(), r.getAfterTokens(),
                "applied=" + String.join(",", r.getStrategiesApplied()));
    }

    // ================ Light compact (single-phase, under stripe lock + tx) ================

    private CompactionEventEntity compactLightUnderLock(String sessionId, String source, String reason) {
        synchronized (lockFor(sessionId)) {
            final CompactionEventEntity[] saved = {null};
            runInTransaction(() -> {
                SessionEntity session = sessionRepository.findById(sessionId)
                        .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

                // EVAL-V2 M3a §2.2 R3: eval session 跳过 compact —— eval case 通常是短 turn,
                // 没必要跑 compact 流程；让 eval 流程零干扰，trace 直接保留全文用于归因分析。
                if (SessionEntity.ORIGIN_EVAL.equals(session.getOrigin())) {
                    log.debug("light compact skipped (eval origin): sessionId={}", sessionId);
                    return;
                }

                if ("user-manual".equals(source) && "running".equals(session.getRuntimeStatus())) {
                    throw new IllegalStateException("Cannot compact while session is running");
                }

                if (!isBypassGuard(source, "light")) {
                    int gap = session.getMessageCount() - session.getLastCompactedAtMessageCount();
                    if (gap < IDEMPOTENCY_MIN_GAP_MESSAGES) {
                        log.info("light compact skipped (idempotency): sessionId={} gap={}", sessionId, gap);
                        return;
                    }
                }

                if (isRangeModelSessionWithActiveSummary(sessionId)) {
                    log.info("light compact skipped (range-model derived view): sessionId={} source={}",
                            sessionId, source);
                    return;
                }

                List<Message> messages = sessionService.getContextMessages(sessionId);
                int contextWindow = resolveContextWindowForSession(session);
                CompactableToolRegistry registry = resolveToolRegistryForSession(session);
                CompactResult result = lightStrategy.apply(messages, contextWindow, registry);

                if (result == null || isTrulyNoOp(result)) {
                    log.info("light compact no-op (not persisted): sessionId={} source={}", sessionId, source);
                    return;
                }

                saved[0] = persistCompactResult(sessionId, "light", source, reason, result);
            });
            return saved[0];
        }
    }

    private CompactCallbackResult doCallbackLight(String sessionId, List<Message> current,
                                                   String source, String reason) {
        synchronized (lockFor(sessionId)) {
            final CompactCallbackResult[] out = {null};
            runInTransaction(() -> {
                SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
                if (session == null) {
                    out[0] = CompactCallbackResult.noOp(current, "session not found");
                    return;
                }
                // EVAL-V2 M3a §2.2 R3: eval session 直接 no-op，让 engine callback 拿到原 messages。
                if (SessionEntity.ORIGIN_EVAL.equals(session.getOrigin())) {
                    log.debug("callback light compact skipped (eval origin): sessionId={}", sessionId);
                    out[0] = CompactCallbackResult.noOp(current, "eval origin: compact skipped");
                    return;
                }
                if (!isBypassGuard(source, "light")) {
                    // COMPACT-IDEMPOTENCY-FIX ①: gap must compare two values in the SAME counting
                    // space. lastCompactedAtMessageCount is recorded in the persisted row-count
                    // space (see persistCompactResult), so the gap must also use the persisted
                    // count — NOT the engine's in-memory working-set size (current.size()), which
                    // starts each run from the compacted subset and would yield a permanently
                    // negative gap on any session that was previously compacted.
                    int gap = session.getMessageCount() - session.getLastCompactedAtMessageCount();
                    if (gap < IDEMPOTENCY_MIN_GAP_MESSAGES) {
                        log.debug("callback light compact skipped (idempotency): sessionId={} gap={}", sessionId, gap);
                        out[0] = CompactCallbackResult.noOp(current, "idempotency guard");
                        return;
                    }
                }

                if (isRangeModelSessionWithActiveSummary(sessionId)) {
                    log.info("callback light compact no-op (range-model derived view): sessionId={}", sessionId);
                    out[0] = CompactCallbackResult.noOp(current,
                            "range-model light compact disabled for derived view");
                    return;
                }

                int contextWindow = resolveContextWindowForSession(session);
                CompactableToolRegistry registry = resolveToolRegistryForSession(session);
                CompactResult result = lightStrategy.apply(current, contextWindow, registry);

                if (result == null || isTrulyNoOp(result)) {
                    log.info("callback light compact no-op: sessionId={}", sessionId);
                    out[0] = CompactCallbackResult.noOp(current, "strategy returned no-op");
                    return;
                }

                persistCompactResult(sessionId, "light", source, reason, result);
                out[0] = new CompactCallbackResult(result.getMessages(), true,
                        result.getTokensReclaimed(), result.getBeforeTokens(), result.getAfterTokens(),
                        "applied=" + String.join(",", result.getStrategiesApplied()));
            });
            return (out[0] != null) ? out[0] : CompactCallbackResult.noOp(current, "light compact no-op");
        }
    }

    // ================ Full compact (three-phase) ================

    /**
     * Three-phase full compact. {@code inMemoryMessages} may be null (REST path reads from DB).
     * Returns null if no-op, in-flight dedup, or provider unavailable.
     */
    private FullCompactOutcome compactFullThreePhase(String sessionId, String source, String reason,
                                                      List<Message> inMemoryMessages) {
        // ── Phase 1: guard + boundary detection, under stripe lock ──────────────
        FullCompactStrategy.PreparedCompact prep;
        LlmProvider provider;
        // INCREMENTAL-SUMMARY (storage redesign): the prior active summary text, read under the
        // stripe lock for a consistent snapshot, threaded into Phase 2 so the LLM produces an
        // EXTENDED summary (prior summary + new turns) instead of re-summarizing the whole window
        // from scratch. Only meaningful under the range model (legacy getContextMessages slices
        // post-last-boundary, so the prior summary is not present in the legacy window). Null when
        // the flag is OFF / store unwired / no prior summary exists.
        String priorSummaryText = null;

        synchronized (lockFor(sessionId)) {
            if (!fullCompactInFlight.add(sessionId)) {
                log.info("fullCompact skipped: already in-flight: sessionId={}", sessionId);
                return null;
            }
            // phase1Success guards the finally: if Phase 1 exits without completing successfully
            // (early return or exception), remove from in-flight. On success, Phase 3's finally handles it.
            boolean phase1Success = false;
            try {
                SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
                if (session == null) {
                    return null;
                }

                // EVAL-V2 M3a §2.2 R3: eval session 跳过 full compact —— phase1Success 仍设 false，
                // 让 finally 把 sessionId 从 fullCompactInFlight 摘出来，避免后续误以为还在跑。
                if (SessionEntity.ORIGIN_EVAL.equals(session.getOrigin())) {
                    log.debug("fullCompact skipped (eval origin): sessionId={}", sessionId);
                    return null;
                }

                if ("user-manual".equals(source) && "running".equals(session.getRuntimeStatus())) {
                    throw new IllegalStateException("Cannot compact while session is running");
                }

                if (!isBypassGuard(source, "full")) {
                    // COMPACT-IDEMPOTENCY-FIX ①: gap must compare two values in the SAME counting
                    // space. Always use the persisted row count (session.getMessageCount()) on both
                    // sides — same space as lastCompactedAtMessageCount (recorded in
                    // persistCompactResult). The previous callback branch used inMemoryMessages.size()
                    // (the engine working set, which restarts from the compacted subset each run) and
                    // produced a permanently negative gap on any previously-compacted session,
                    // silently suppressing in-loop compaction. Engine over-window sources
                    // (PREEMPTIVE_SOURCES) already bypass this guard via isBypassGuard, so this branch
                    // now only gates non-preemptive callers (e.g. agent-tool full) consistently with
                    // the REST path.
                    int gap = session.getMessageCount() - session.getLastCompactedAtMessageCount();
                    if (gap < IDEMPOTENCY_MIN_GAP_MESSAGES) {
                        log.info("fullCompact skipped (idempotency): sessionId={} gap={}", sessionId, gap);
                        return null;
                    }
                }

                List<Message> messages = (inMemoryMessages != null)
                        ? inMemoryMessages
                        : sessionService.getContextMessages(sessionId);

                int contextWindow = resolveContextWindowForSession(session);
                prep = fullStrategy.prepareCompact(messages, contextWindow);
                if (prep == null) {
                    log.info("fullCompact no-op (no safe boundary): sessionId={}", sessionId);
                    return null;
                }
                // COMPACT-IDEMPOTENCY-FIX ②: degenerate-split guard. On tool-heavy
                // (SubAgent/Team) sessions the safe boundary often lands only a few messages in
                // — summarising such a tiny window reclaims ≈0 tokens for ~0 value. If the
                // compactable window is below MIN_COMPACT_WINDOW_MESSAGES, return a TRUE no-op now
                // (before the LLM call) as a cost/value filter — skip the zero-value compaction.
                if (prep.window().size() < MIN_COMPACT_WINDOW_MESSAGES) {
                    log.info("fullCompact no-op (degenerate window): sessionId={} windowSize={} < {}",
                            sessionId, prep.window().size(), MIN_COMPACT_WINDOW_MESSAGES);
                    return null;
                }

                String providerName = llmProperties.getDefaultProvider();
                provider = llmProviderFactory.getProvider(providerName);
                if (provider == null) {
                    log.warn("fullCompact skipped: default provider '{}' unavailable", providerName);
                    return null;
                }
                // INCREMENTAL-SUMMARY: snapshot the current active summary text (rolling merge keeps
                // exactly one) while still under the stripe lock, so Phase 2 can feed it to the LLM
                // as the existing summary to EXTEND rather than re-summarize from scratch. Only under
                // the range model; the legacy path has no active summary row to read.
                if (rangeModelEnabled && sessionSummaryRepository != null) {
                    priorSummaryText = sessionSummaryRepository
                            .findTopBySessionIdAndSupersededByIsNullOrderByStartSeqDesc(sessionId)
                            .map(SessionSummaryEntity::getSummaryText)
                            .filter(t -> t != null && !t.isBlank())
                            .orElse(null);
                }

                // Phase 1 complete — stripe lock releases at end of synchronized block.
                // fullCompactInFlight keeps deduplication active through Phase 2.
                phase1Success = true;
            } finally {
                if (!phase1Success) {
                    fullCompactInFlight.remove(sessionId);
                }
            }
        }

        // ── Phase 2: LLM call, outside stripe lock ───────────────────────────────
        CompactResult result;
        try {
            result = fullStrategy.applyPrepared(prep, provider, null, priorSummaryText);
        } catch (Exception e) {
            fullCompactInFlight.remove(sessionId);
            log.error("fullCompact Phase 2 LLM call failed: sessionId={}", sessionId, e);
            // BUG-A prerequisite: rethrow so the engine's catch increments the breaker.
            // Returning null here would surface as performed=false (no-op), which under the
            // BUG-A fix is treated as neutral and would leave a real LLM failure invisible
            // to the circuit breaker.
            throw new RuntimeException("fullCompact Phase 2 failed for sessionId=" + sessionId, e);
        }

        if (result == null || isTrulyNoOp(result) || isIneffective(result)) {
            fullCompactInFlight.remove(sessionId);
            log.info("fullCompact no-op (LLM empty or no net reclaim): sessionId={} beforeTokens={} afterTokens={}",
                    sessionId,
                    result == null ? -1 : result.getBeforeTokens(),
                    result == null ? -1 : result.getAfterTokens());
            return null;
        }

        // ── Phase 3: persist, under stripe lock + transaction ────────────────────
        final CompactResult finalResult = result;
        final CompactionEventEntity[] savedEvt = {null};
        try {
            synchronized (lockFor(sessionId)) {
                runInTransaction(() ->
                        savedEvt[0] = persistCompactResult(sessionId, "full", source, reason, finalResult)
                );
            }
        } finally {
            fullCompactInFlight.remove(sessionId);
        }

        if (savedEvt[0] == null) {
            log.info("fullCompact no-op (range-model persistence skipped): sessionId={} source={}",
                    sessionId, source);
            return null;
        }

        log.info("fullCompact done: sessionId={} source={} reclaimed={} tokens",
                sessionId, source, result.getTokensReclaimed());
        return new FullCompactOutcome(savedEvt[0], result);
    }

    // ================ 内部辅助 ================

    /** Holds both the persisted event and the CompactResult for callers that need both. */
    private record FullCompactOutcome(CompactionEventEntity event, CompactResult compactResult) {}

    private boolean isRangeModelSessionWithActiveSummary(String sessionId) {
        return rangeModelEnabled
                && sessionSummaryRepository != null
                && sessionSummaryRepository.existsBySessionIdAndSupersededByIsNull(sessionId);
    }

    /**
     * Persist a CompactResult to DB (messages + session counters + event).
     * Must be called inside a transaction and (for thread safety) under the stripe lock.
     * Returns the saved CompactionEventEntity, or {@code null} when the range-model persistence path
     * intentionally converts the compact result into a true no-op.
     */
    private CompactionEventEntity persistCompactResult(String sessionId, String level,
                                                        String source, String reason,
                                                        CompactResult result) {
        SessionEntity fresh = sessionRepository.findById(sessionId).orElseThrow();
        if ("full".equalsIgnoreCase(level) && rangeModelEnabled) {
            // === Range-model write path (storage redesign P1, flag ON) ===
            // Writes a t_session_summary range row + marks covered rows, instead of appending a
            // boundary + summary + re-appended young-gen. No message rows are appended/deleted.
            // Falls through to the shared counter/event/broadcast tail below.
            if (!persistFullRangeModel(sessionId, source, result)) {
                return null;
            }
        } else if ("full".equalsIgnoreCase(level)) {
            String summaryText = extractSummaryText(result.getMessages());
            Message boundary = new Message();
            boundary.setRole(Message.Role.SYSTEM);
            boundary.setContent("Conversation compacted");
            Message summary = new Message();
            // BUG-F-2: SUMMARY row role MUST be USER, not SYSTEM.
            // Engine-side compacted layout puts the summary as Message.user(...),
            // so DB must mirror that — otherwise messageEquals returns false on
            // reload and triggers fallback rewrite (writing every message again
            // and dropping the SUMMARY msg_type marker into a NORMAL row).
            summary.setRole(Message.Role.USER);
            summary.setContent(summaryText);
            Map<String, Object> boundaryMeta = new HashMap<>();
            int compactedCount = result.getBeforeMessageCount() - result.getAfterMessageCount();
            boundaryMeta.put("trigger", source);
            boundaryMeta.put("tokens_before", result.getBeforeTokens());
            boundaryMeta.put("tokens_after", result.getAfterTokens());
            boundaryMeta.put("compacted_message_count", compactedCount);
            Map<String, Object> summaryMeta = new HashMap<>();
            summaryMeta.put("compacted_message_count", compactedCount);
            summaryMeta.put("trigger", source);
            List<SessionService.AppendMessage> appends = new ArrayList<>();
            appends.add(new SessionService.AppendMessage(
                    boundary, SessionService.MSG_TYPE_COMPACT_BOUNDARY, boundaryMeta));
            appends.add(new SessionService.AppendMessage(
                    summary, SessionService.MSG_TYPE_SUMMARY, summaryMeta));
            List<Message> retained = extractRetainedMessages(result.getMessages());
            if (!retained.isEmpty()) {
                // OBS-2 Q1: full compact appends a fresh retained block at new
                // seq_nos, so the auto-preserve in SessionService.rewriteMessages
                // cannot help here (this path goes through appendMessages, not
                // rewrite). Look up the original tail trace_ids — at this moment
                // appendMessages has not yet run, so the DB tail is still the
                // pre-compact source rows that retained[] mirrors — and stamp
                // them onto the retained AppendMessages via the 7-arg form.
                List<String> retainedTraceIds = sessionService.findTailTraceIds(
                        sessionId, retained.size());
                for (int i = 0; i < retained.size(); i++) {
                    String preservedTraceId = (i < retainedTraceIds.size())
                            ? retainedTraceIds.get(i) : null;
                    appends.add(new SessionService.AppendMessage(
                            retained.get(i),
                            SessionService.MSG_TYPE_NORMAL,
                            SessionService.MESSAGE_TYPE_NORMAL,
                            null,                       // controlId
                            null,                       // answeredAt
                            Collections.emptyMap(),
                            preservedTraceId));
                }
            }

            // === P9-5: post-compact recovery payload ===
            // Order: boundary → summary(USER) → retained young-gen → recoveryPayload(USER, optional).
            // Recovery payload is a plain user message (no tool_use / tool_result blocks) so it does
            // not interact with the tool_use ↔ tool_result pairing invariant.
            if (recoveryPayloadBuilder != null) {
                try {
                    Message recovery = recoveryPayloadBuilder.build(sessionId);
                    if (recovery != null) {
                        Map<String, Object> recoveryMeta = new HashMap<>();
                        recoveryMeta.put("trigger", source);
                        appends.add(new SessionService.AppendMessage(
                                recovery, SessionService.MSG_TYPE_RECOVERY_PAYLOAD, recoveryMeta));
                    }
                } catch (Exception ex) {
                    // Recovery is best-effort; never block compact persistence on it.
                    log.warn("recovery payload build failed; continuing compact: sessionId={}", sessionId, ex);
                }
            }

            long lastSeqNo = sessionService.appendMessages(sessionId, appends);
            long firstSeqNo = lastSeqNo - appends.size() + 1;
            long boundarySeqNo = firstSeqNo;
            long summarySeqNo = firstSeqNo + 1;
            SessionCompactionCheckpointEntity checkpoint = new SessionCompactionCheckpointEntity();
            checkpoint.setId(UUID.randomUUID().toString());
            checkpoint.setSessionId(sessionId);
            checkpoint.setBoundarySeqNo(boundarySeqNo);
            checkpoint.setSummarySeqNo(summarySeqNo);
            checkpoint.setReason(trimReason(source));
            checkpoint.setPreRangeStartSeqNo(0L);
            checkpoint.setPreRangeEndSeqNo(Math.max(0, boundarySeqNo - 1));
            checkpoint.setPostRangeStartSeqNo(summarySeqNo);
            checkpoint.setPostRangeEndSeqNo(lastSeqNo);
            checkpointRepository.save(checkpoint);
        } else {
            List<SessionService.StoredMessage> all = sessionService.getFullHistoryRecords(sessionId);
            int lastBoundary = -1;
            for (int i = all.size() - 1; i >= 0; i--) {
                if (SessionService.MSG_TYPE_COMPACT_BOUNDARY.equals(all.get(i).msgType())) {
                    lastBoundary = i;
                    break;
                }
            }
            if (lastBoundary >= 0) {
                List<SessionService.AppendMessage> rewritten = new ArrayList<>();
                // OBS-2 M3 W1: preserve historical trace_id when rewriting rows during a
                // full-compact boundary preservation pass. Compaction has no traceId of its
                // own (background orchestration), so post-boundary new messages keep null.
                for (int i = 0; i <= lastBoundary; i++) {
                    SessionService.StoredMessage item = all.get(i);
                    rewritten.add(new SessionService.AppendMessage(
                            item.message(), item.msgType(), item.messageType(),
                            item.controlId(), item.answeredAt(),
                            item.metadata(), item.traceId()));
                }
                for (Message msg : result.getMessages()) {
                    rewritten.add(new SessionService.AppendMessage(
                            msg, SessionService.MSG_TYPE_NORMAL, Collections.emptyMap()));
                }
                sessionService.rewriteMessages(sessionId, rewritten);
            } else {
                sessionService.saveSessionMessages(sessionId, result.getMessages());
            }
        }
        fresh.setMessageCount((int) sessionService.countMessageRows(sessionId));
        fresh.setLastCompactedAt(Instant.now());
        fresh.setLastCompactedAtMessageCount(fresh.getMessageCount());
        fresh.setTotalTokensReclaimed(fresh.getTotalTokensReclaimed() + result.getTokensReclaimed());
        if ("light".equalsIgnoreCase(level)) {
            fresh.setLightCompactCount(fresh.getLightCompactCount() + 1);
        } else {
            fresh.setFullCompactCount(fresh.getFullCompactCount() + 1);
        }
        sessionRepository.save(fresh);

        CompactionEventEntity evt = buildEvent(sessionId, level, source, reason, result);
        CompactionEventEntity saved = eventRepository.save(evt);
        broadcastUpdated(fresh);
        return saved;
    }

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX (storage redesign P1, §2 write + §2.6 Q3 merge + §8.7 Q4):
     * range-model full-compact persistence. Writes ONE {@code t_session_summary} row covering the
     * real seq range, marks the covered rows' {@code compacted_by_summary_id}, supersedes the prior
     * active summary (Q3 rolling merge), and writes a checkpoint pointing at the summary id — without
     * appending / deleting / re-appending any message rows.
     *
     * <p><b>Range seq mapping</b>: {@link CompactResult} only exposes before/after model-message
     * counts, not the prepared right edge, so we rebuild the same model-view frame sequence from the
     * stored rows and active summary ranges. Summary frames contribute their {@code end_seq}; real-row
     * frames contribute their {@code seq_no}. This keeps the rolling frontier monotonic even when old
     * rows still carry stale markers pointing at superseded summaries:
     * <ul>
     *   <li>{@code windowSize} = the number of real window rows folded into the summary. The
     *       compacted layout is {@code [1 summary] + youngGen}, so
     *       {@code afterMessageCount = 1 + youngGen.size()} and the window row count is
     *       {@code beforeMessageCount - youngGen.size() = (beforeMessageCount - afterMessageCount) + 1}.
     *       Deriving the window size from counts is still an approximation, but the model-frame to
     *       seq mapping is range-aware.</li>
     *   <li>{@code endSeq} = the real seq_no of the last covered model-view row
     *       (index {@code windowSize - 1}).</li>
     *   <li>Per §2.6 Q3 the new summary covers {@code [0, endSeq]} (rolling merge subsumes everything
     *       up to endSeq); {@code startSeq} is therefore 0.</li>
     * </ul>
     */
    private boolean persistFullRangeModel(String sessionId, String source, CompactResult result) {
        if (sessionSummaryRepository == null) {
            throw new IllegalStateException(
                    "range-model compaction enabled but SessionSummaryRepository not wired");
        }

        // Window row count folded into the summary: the compacted shape is [summary] + youngGen,
        // so the delta (before - after) undercounts the window by 1 (the summary row replaces the
        // whole window). The true window size adds that 1 back.
        int compactedCount = (result.getBeforeMessageCount() - result.getAfterMessageCount()) + 1;

        // Load the full real history ONCE; both the model-view mapping and the post-range end
        // (highest real seq) are derived from this single read (perf: avoid a second DB round-trip).
        List<SessionService.StoredMessage> allRecords = sessionService.getFullHistoryRecords(sessionId);
        List<SessionSummaryEntity> priorActive =
                sessionSummaryRepository.findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(sessionId);

        // Map the compacted model-view window onto real seq_nos. A summary frame contributes its
        // covered end_seq; a real-row frame contributes that row's seq_no. This mirrors
        // SessionService.getContextMessagesWithProvenance and prevents stale markers from moving the
        // rolling frontier backwards after a prior active summary.
        List<ModelViewFrame> modelViewFrames = sliceModelViewFrames(allRecords, priorActive);
        if (compactedCount <= 0 || modelViewFrames.isEmpty()) {
            log.info("range-model fullCompact no-op mapping (compactedCount={} modelViewFrames={}): sessionId={}",
                    compactedCount, modelViewFrames.size(), sessionId);
            return false;
        }
        int lastWindowIdx = Math.min(compactedCount, modelViewFrames.size()) - 1;
        long endSeq = modelViewFrames.get(lastWindowIdx).endSeq();
        long startSeq = 0L; // §2.6 Q3: rolling merge — new summary covers [0, endSeq].

        long priorMaxEndSeq = priorActive.stream()
                .mapToLong(SessionSummaryEntity::getEndSeq)
                .max()
                .orElse(-1L);
        if (endSeq < priorMaxEndSeq) {
            log.warn("range-model fullCompact no-op (non-monotonic frontier): sessionId={} "
                            + "newEndSeq={} priorActiveMaxEndSeq={} compactedCount={} modelViewFrames={}",
                    sessionId, endSeq, priorMaxEndSeq, compactedCount, modelViewFrames.size());
            return false;
        }

        String summaryText = extractSummaryText(result.getMessages());
        String recoveryText = buildRecoveryPayloadText(sessionId);

        SessionSummaryEntity summary = new SessionSummaryEntity();
        summary.setSessionId(sessionId);
        summary.setStartSeq(startSeq);
        summary.setEndSeq(endSeq);
        summary.setSummaryText(summaryText);
        summary.setLevel("full");
        summary.setSource(source);
        summary.setTokensBefore(result.getBeforeTokens());
        summary.setTokensAfter(result.getAfterTokens());
        summary.setCompactedMessageCount(compactedCount);
        summary.setRecoveryPayload(recoveryText);
        SessionSummaryEntity savedSummary = sessionSummaryRepository.save(summary);

        // §2.6 Q3 merge: only active summaries fully contained by the new rolling range are subsumed.
        // Do not blanket-supersede unrelated or wider active ranges; that is the frontier-regression
        // bug that can re-expose previously compacted history.
        for (SessionSummaryEntity prior : priorActive) {
            if (prior.getStartSeq() >= startSeq && prior.getEndSeq() <= endSeq) {
                sessionSummaryRepository.markSuperseded(prior.getId(), savedSummary.getId());
            }
        }

        restampActiveSummaryMarkers(sessionId);

        // Checkpoint points at the summary id + range (same transaction). No message rows written,
        // so boundary_seq_no is the next-real-seq sentinel (endSeq+1) per the design note.
        SessionCompactionCheckpointEntity checkpoint = new SessionCompactionCheckpointEntity();
        checkpoint.setId(UUID.randomUUID().toString());
        checkpoint.setSessionId(sessionId);
        checkpoint.setBoundarySeqNo(endSeq + 1);
        checkpoint.setSummarySeqNo(savedSummary.getId());
        checkpoint.setReason(trimReason(source));
        checkpoint.setPreRangeStartSeqNo(startSeq);
        checkpoint.setPreRangeEndSeqNo(endSeq);
        checkpoint.setPostRangeStartSeqNo(endSeq + 1);
        // Highest real seq from the already-loaded list (no extra DB read).
        long lastRealSeq = allRecords.isEmpty()
                ? endSeq
                : allRecords.get(allRecords.size() - 1).seqNo();
        checkpoint.setPostRangeEndSeqNo(lastRealSeq);
        checkpointRepository.save(checkpoint);
        return true;
    }

    private record ModelViewFrame(long endSeq) {}

    /**
     * Slice the model view out of an already-loaded full history, keeping the real seq frontier each
     * emitted model message covers. With active summaries, this mirrors
     * {@link SessionService#getContextMessagesWithProvenance}: active summary ranges collapse to one
     * frame whose frontier is {@code summary.end_seq}; uncovered real rows become one frame whose
     * frontier is {@code row.seq_no}. With no active summaries, this mirrors the legacy post-boundary
     * slice used by {@link SessionService#getContextMessages} so first range-model compact of an old
     * boundary session maps the same model-view window it summarized. This is count-only mapping, so it
     * intentionally does not apply archive substitution.
     */
    private List<ModelViewFrame> sliceModelViewFrames(List<SessionService.StoredMessage> all,
                                                      List<SessionSummaryEntity> activeSummaries) {
        int start = 0;
        if (activeSummaries.isEmpty()) {
            int lastBoundary = -1;
            for (int i = all.size() - 1; i >= 0; i--) {
                if (SessionService.MSG_TYPE_COMPACT_BOUNDARY.equals(all.get(i).msgType())) {
                    lastBoundary = i;
                    break;
                }
            }
            start = (lastBoundary >= 0) ? lastBoundary + 1 : 0;
        }
        List<ModelViewFrame> out = new ArrayList<>();
        int summaryIdx = 0;
        for (int i = start; i < all.size(); i++) {
            SessionService.StoredMessage rec = all.get(i);
            if (SessionService.MSG_TYPE_SYSTEM_EVENT.equals(rec.msgType())) {
                continue;
            }
            long seq = rec.seqNo();
            while (summaryIdx < activeSummaries.size()
                    && activeSummaries.get(summaryIdx).getEndSeq() < seq) {
                summaryIdx++;
            }
            if (summaryIdx < activeSummaries.size()) {
                SessionSummaryEntity summary = activeSummaries.get(summaryIdx);
                if (seq >= summary.getStartSeq() && seq <= summary.getEndSeq()) {
                    long endSeq = summary.getEndSeq();
                    out.add(new ModelViewFrame(endSeq));
                    while (i + 1 < all.size() && all.get(i + 1).seqNo() <= endSeq) {
                        i++;
                    }
                    summaryIdx++;
                    continue;
                }
            }
            out.add(new ModelViewFrame(seq));
        }
        return out;
    }

    private void restampActiveSummaryMarkers(String sessionId) {
        if (sessionMessageRepository == null || sessionSummaryRepository == null) {
            return;
        }
        List<SessionSummaryEntity> active =
                sessionSummaryRepository.findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(sessionId);
        int cleared = sessionMessageRepository.clearCompactedMarkers(sessionId);
        int marked = 0;
        for (SessionSummaryEntity s : active) {
            marked += sessionMessageRepository.markCompactedBySummary(
                    sessionId, s.getStartSeq(), s.getEndSeq(), s.getId());
        }
        log.debug("range-model fullCompact restamped markers: cleared={} marked={} activeSummaries={} sessionId={}",
                cleared, marked, active.size(), sessionId);
    }

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2b (§4, B2): copy the source session's
     * summaries that lie fully within {@code [0, branchEndSeq]} onto {@code branchId} as fresh rows
     * (new auto-generated ids), preserving the {@code superseded_by} chain by remapping old→new ids.
     *
     * <p>Branch rows carry the same seq_nos as the source rows up to {@code branchEndSeq} (the rewrite
     * reassigns 0..N contiguously), so a summary's {@code [start_seq,end_seq]} transfers verbatim.
     * Summaries whose {@code end_seq > branchEndSeq} are skipped (their rows are not in the branch).
     * No-op when the flag is OFF / store unwired. Runs inside the branch-creation transaction.
     */
    private void copyRangeSummariesToBranch(String sourceSessionId, String branchId, long branchEndSeq) {
        if (!rangeModelEnabled || sessionSummaryRepository == null) {
            return;
        }
        List<SessionSummaryEntity> sourceSummaries =
                sessionSummaryRepository.findBySessionIdOrderByStartSeqAsc(sourceSessionId);
        if (sourceSummaries.isEmpty()) {
            return;
        }
        // Pass 1: copy each in-range summary, recording old-id → new-entity for superseded_by remap.
        Map<Long, SessionSummaryEntity> oldIdToNew = new HashMap<>();
        for (SessionSummaryEntity src : sourceSummaries) {
            if (src.getEndSeq() > branchEndSeq) {
                continue; // covered rows not present in the branch
            }
            SessionSummaryEntity copy = new SessionSummaryEntity();
            copy.setSessionId(branchId);
            copy.setStartSeq(src.getStartSeq());
            copy.setEndSeq(src.getEndSeq());
            copy.setSummaryText(src.getSummaryText());
            copy.setLevel(src.getLevel());
            copy.setSource(src.getSource());
            copy.setTokensBefore(src.getTokensBefore());
            copy.setTokensAfter(src.getTokensAfter());
            copy.setCompactedMessageCount(src.getCompactedMessageCount());
            copy.setRecoveryPayload(src.getRecoveryPayload());
            // superseded_by remapped in pass 2 (target may not be saved yet).
            SessionSummaryEntity saved = sessionSummaryRepository.save(copy);
            oldIdToNew.put(src.getId(), saved);
        }
        // Pass 2: remap superseded_by to the copied targets. A superseded summary whose superseding
        // target was out of range (skipped) is left active in the branch — harmless: the derived read
        // collapses by active marker, and recompute restamps from active ranges.
        for (SessionSummaryEntity src : sourceSummaries) {
            if (src.getSupersededBy() == null) {
                continue;
            }
            SessionSummaryEntity copy = oldIdToNew.get(src.getId());
            SessionSummaryEntity newTarget = oldIdToNew.get(src.getSupersededBy());
            if (copy != null && newTarget != null) {
                sessionSummaryRepository.markSuperseded(copy.getId(), newTarget.getId());
            }
        }
    }

    /**
     * §8.7 Q4: build the recovery payload text to persist on the summary row (no message row).
     * Best-effort — returns null when no builder is wired or it yields nothing.
     *
     * <p>RecoveryPayloadBuilder currently returns String content, but to avoid silently dropping
     * the recovery payload if it ever returns array / ContentBlock-list content, non-String content
     * is JSON-serialized (with a toString fallback) rather than discarded.
     */
    private String buildRecoveryPayloadText(String sessionId) {
        if (recoveryPayloadBuilder == null) {
            return null;
        }
        try {
            Message recovery = recoveryPayloadBuilder.build(sessionId);
            if (recovery == null) {
                return null;
            }
            Object content = recovery.getContent();
            if (content instanceof String s) {
                return s.isBlank() ? null : s;
            }
            if (content != null) {
                // Non-String (e.g. ContentBlock list) — serialize rather than silently drop.
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(content);
                } catch (Exception serEx) {
                    log.warn("range-model recovery payload non-String content; falling back to toString: sessionId={}",
                            sessionId, serEx);
                    return content.toString();
                }
            }
        } catch (Exception ex) {
            log.warn("range-model recovery payload build failed; continuing: sessionId={}", sessionId, ex);
        }
        return null;
    }

    private String extractSummaryText(List<Message> compactedMessages) {
        if (compactedMessages == null || compactedMessages.isEmpty()) {
            return "Summary unavailable";
        }
        Message first = compactedMessages.get(0);
        Object content = first.getContent();
        // After BUG-F-1, the first compacted message is always Message.user(summaryPrefix)
        // — content is a String. The legacy `\n\n---\n\n` split branch is removed:
        // mergeSummaryIntoUser is gone, so no merged String form can exist. Keeping
        // the indexOf split would silently corrupt LLM-produced summaries that happen
        // to contain a markdown horizontal rule, breaking DB messageEquals on reload.
        if (content instanceof String s) {
            return s;
        }
        // Defensive: if some upstream regression sends a List form, extract any
        // text-form content rather than throwing. We do not aim for perfect recovery
        // here — the post-BUG-F-1 invariant is String content.
        if (content instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object o : blocks) {
                if (o instanceof com.skillforge.core.model.ContentBlock cb
                        && "text".equals(cb.getType()) && cb.getText() != null) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(cb.getText());
                } else if (o instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                    Object t = map.get("text");
                    if (t != null) {
                        if (!sb.isEmpty()) sb.append('\n');
                        sb.append(t);
                    }
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }
        return "Summary unavailable";
    }

    private String trimReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return reason.length() <= 32 ? reason : reason.substring(0, 32);
    }

    private List<Message> extractRetainedMessages(List<Message> compactedMessages) {
        if (compactedMessages == null || compactedMessages.size() <= 1) {
            return Collections.emptyList();
        }
        return new ArrayList<>(compactedMessages.subList(1, compactedMessages.size()));
    }

    private SessionCompactionCheckpointDto toCheckpointDto(SessionCompactionCheckpointEntity checkpoint) {
        return new SessionCompactionCheckpointDto(
                checkpoint.getId(),
                checkpoint.getSessionId(),
                checkpoint.getBoundarySeqNo(),
                checkpoint.getSummarySeqNo(),
                checkpoint.getReason(),
                checkpoint.getPreRangeStartSeqNo(),
                checkpoint.getPreRangeEndSeqNo(),
                checkpoint.getPostRangeStartSeqNo(),
                checkpoint.getPostRangeEndSeqNo(),
                checkpoint.getSnapshotRef(),
                checkpoint.getCreatedAt()
        );
    }

    private SessionCompactionCheckpointEntity getCheckpointEntity(String sessionId, String checkpointId) {
        SessionCompactionCheckpointEntity checkpoint = checkpointRepository.findById(checkpointId)
                .orElseThrow(() -> new CheckpointNotFoundException("Checkpoint not found"));
        if (!sessionId.equals(checkpoint.getSessionId())) {
            throw new CheckpointNotFoundException("Checkpoint not found");
        }
        return checkpoint;
    }

    private List<SessionService.AppendMessage> buildCheckpointMessages(String sessionId,
                                                                       SessionCompactionCheckpointEntity checkpoint) {
        long endSeq = resolveCheckpointEndSeq(checkpoint);
        List<SessionService.StoredMessage> records = sessionService.getFullHistoryRecords(sessionId);
        List<SessionService.AppendMessage> out = new ArrayList<>();
        // OBS-2 M3 W1: checkpoint restore replays historical rows; preserve original trace_id
        // so per-trace queries remain consistent after a restore.
        for (SessionService.StoredMessage record : records) {
            if (record.seqNo() > endSeq) {
                break;
            }
            out.add(new SessionService.AppendMessage(
                    record.message(), record.msgType(), record.messageType(),
                    record.controlId(), record.answeredAt(),
                    record.metadata(), record.traceId()));
        }
        return out;
    }

    private long resolveCheckpointEndSeq(SessionCompactionCheckpointEntity checkpoint) {
        return checkpoint.getPostRangeEndSeqNo() != null
                ? checkpoint.getPostRangeEndSeqNo()
                : checkpoint.getSummarySeqNo() != null
                ? checkpoint.getSummarySeqNo()
                : checkpoint.getBoundarySeqNo();
    }

    private void ensureCheckpointOperationAllowed(String sessionId) {
        if (fullCompactInFlight.contains(sessionId)) {
            throw new IllegalStateException("Cannot mutate checkpoint state while full compact is in progress");
        }
        SessionEntity session = sessionService.getSession(sessionId);
        if ("running".equals(session.getRuntimeStatus())) {
            throw new IllegalStateException("Cannot operate on checkpoint while session is running");
        }
    }

    /** Runs action in a Spring transaction if a PlatformTransactionManager was provided; otherwise runs directly. */
    private void runInTransaction(Runnable action) {
        if (transactionTemplate != null) {
            transactionTemplate.execute(status -> {
                action.run();
                return null;
            });
        } else {
            action.run();
        }
    }

    /**
     * 判断 source + level 组合是否绕过 idempotency guard.
     *
     * <p>(source × level → bypass?) matrix:
     * <pre>
     *   source              | light  | full
     *   --------------------+--------+--------
     *   user-manual         | bypass | bypass   (explicit user action — always honored)
     *   agent-tool          | bypass | GATED    (light is cheap/idempotent; full is gated)
     *   engine-soft         | bypass | bypass   ┐ PREEMPTIVE_SOURCES: engine measured ratio
     *   engine-hard         | bypass | bypass   │ &gt; threshold (or overflow) → the compaction is
     *   engine-preemptive   | bypass | bypass   │ REQUIRED; never suppress it. When ② finds a
     *   post-overflow       | bypass | bypass   ┘ degenerate window it just cheap-no-ops (no thrash).
     *   other (e.g. engine-gap) | GATED | GATED
     * </pre>
     * engine-soft stays in PREEMPTIVE_SOURCES intentionally: engine-triggered ⇒ should compact;
     * combined with ②'s degenerate guard it merely cheap-no-ops when the boundary is degenerate.
     */
    private boolean isBypassGuard(String source, String level) {
        return "user-manual".equals(source)
                || ("agent-tool".equals(source) && "light".equalsIgnoreCase(level))
                // COMPACT-IDEMPOTENCY-FIX ①: engine-measured over-window compactions are
                // required; never let the idempotency guard suppress them.
                || PREEMPTIVE_SOURCES.contains(source);
    }

    /** 判断一次压缩是否真的没产出 —— 用于 #12 跳过 junk event. */
    private boolean isTrulyNoOp(CompactResult result) {
        return result.getTokensReclaimed() == 0
                && (result.getStrategiesApplied() == null || result.getStrategiesApplied().isEmpty())
                && result.getBeforeMessageCount() == result.getAfterMessageCount();
    }

    /**
     * COMPACT-IDEMPOTENCY-FIX ②: a compaction that did not actually shrink the token footprint
     * (afterTokens &gt;= beforeTokens) is a no-net-reclaim result — zero (or negative) value — so it
     * is treated as a true no-op (no boundary, no event) as a cost/value filter. Complements
     * {@link #isTrulyNoOp}, which only catches strategy-level emptiness.
     */
    private boolean isIneffective(CompactResult result) {
        return result.getAfterTokens() >= result.getBeforeTokens();
    }

    /**
     * 解析 session 对应的 context window. 优先级:
     *   1. llmProperties.providers[providerName].contextWindowTokens  (YAML 显式配置)
     *   2. ModelConfig.lookupKnownContextWindow(modelName)            (静态已知模型表)
     *   3. ModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS = 32000          (最终兜底)
     */
    int resolveContextWindowForSession(SessionEntity session) {
        try {
            if (agentRepository != null && session.getAgentId() != null) {
                AgentEntity agent = agentRepository.findById(session.getAgentId()).orElse(null);
                if (agent != null) {
                    // Step 0: explicit per-agent override (highest priority) —
                    // agent.config.context_window_tokens. ChatService injects THIS method's result
                    // into agentDef.config (overwriting whatever toAgentDefinition put there), so
                    // unless the override is honored here it is silently dead and every
                    // unknown-window model (e.g. glm-5.2) falls to the flat 64K default — that is why
                    // session 9d3eff0f sat at 64K despite a configured 400000.
                    if (agent.getConfig() != null && !agent.getConfig().isBlank()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> cfg = CONFIG_MAPPER
                                    .readValue(agent.getConfig(), Map.class);
                            Object override = cfg.get("context_window_tokens");
                            if (override instanceof Number n && n.intValue() > 0) {
                                return n.intValue();
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse agent config context_window_tokens for session "
                                    + "{}, falling through to model resolution", session.getId(), e);
                        }
                    }
                }
                if (agent != null && agent.getModelId() != null) {
                    String modelId = agent.getModelId();
                    String providerName;
                    if (modelId.contains(":")) {
                        providerName = modelId.substring(0, modelId.indexOf(':'));
                    } else {
                        providerName = llmProperties.getDefaultProvider();
                    }
                    if (providerName != null) {
                        LlmProperties.ProviderConfig pc = llmProperties.getProviders().get(providerName);
                        if (pc != null && pc.getContextWindowTokens() != null) {
                            return pc.getContextWindowTokens();  // YAML explicit config — highest priority
                        }
                        // Step 2: static known-model map lookup using the provider's configured model name
                        String modelName = (pc != null) ? pc.getModel() : null;
                        if (modelName == null) {
                            modelName = modelId.contains(":") ? modelId.substring(modelId.indexOf(':') + 1) : modelId;
                        }
                        java.util.Optional<Integer> known = ModelConfig.lookupKnownContextWindow(modelName);
                        if (known.isPresent()) {
                            log.debug("Resolved context window via known-model map: session={} model={} window={}",
                                    session.getId(), modelName, known.get());
                            return known.get();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve contextWindowTokens for session {}, falling back to default",
                    session.getId(), e);
        }
        return ModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS;
    }

    /**
     * Resolve per-agent CompactableToolRegistry from agent config JSON.
     * Falls back to default whitelist if agent has no override.
     */
    CompactableToolRegistry resolveToolRegistryForSession(SessionEntity session) {
        try {
            if (agentRepository != null && session.getAgentId() != null) {
                AgentEntity agent = agentRepository.findById(session.getAgentId()).orElse(null);
                if (agent != null && agent.getConfig() != null && !agent.getConfig().isBlank()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> configMap = CONFIG_MAPPER
                            .readValue(agent.getConfig(), Map.class);
                    return CompactableToolRegistry.fromAgentConfig(configMap);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve compactable tools for session {}, using defaults",
                    session.getId(), e);
        }
        return new CompactableToolRegistry();
    }

    private CompactionEventEntity buildEvent(String sessionId, String level, String source,
                                             String reason, CompactResult result) {
        CompactionEventEntity evt = new CompactionEventEntity();
        evt.setSessionId(sessionId);
        evt.setLevel(level);
        evt.setSource(source);
        evt.setReason(reason);
        evt.setTriggeredAt(Instant.now());
        evt.setBeforeTokens(result.getBeforeTokens());
        evt.setAfterTokens(result.getAfterTokens());
        evt.setTokensReclaimed(result.getTokensReclaimed());
        evt.setBeforeMessageCount(result.getBeforeMessageCount());
        evt.setAfterMessageCount(result.getAfterMessageCount());
        if ("full".equalsIgnoreCase(level)) {
            evt.setStrategiesApplied("llm-summary");
        } else {
            evt.setStrategiesApplied(String.join(",", result.getStrategiesApplied()));
        }
        return evt;
    }

    private void broadcastUpdated(SessionEntity s) {
        if (broadcaster == null) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "session_updated");
            payload.put("sessionId", s.getId());
            payload.put("messageCount", s.getMessageCount());
            payload.put("lightCompactCount", s.getLightCompactCount());
            payload.put("fullCompactCount", s.getFullCompactCount());
            payload.put("totalTokensReclaimed", s.getTotalTokensReclaimed());
            payload.put("updatedAt", s.getUpdatedAt());
            broadcaster.userEvent(s.getUserId(), payload);
        } catch (Exception t) {
            // Broadcast failure is non-fatal; keep stacktrace for debugging.
            log.debug("compact broadcastUpdated skipped", t);
        }
    }

    // Suppressed unused reference: keep TokenEstimator import to not dead-drop it from API surface.
    @SuppressWarnings("unused")
    private static int estimate(List<Message> msgs) {
        return TokenEstimator.estimate(msgs);
    }
}
