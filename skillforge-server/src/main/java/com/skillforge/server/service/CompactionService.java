package com.skillforge.server.service;

import com.skillforge.core.compact.CompactableToolRegistry;
import com.skillforge.core.compact.CompactResult;
import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.compact.SessionMemoryCompactStrategy;
import com.skillforge.core.compact.TokenEstimator;
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
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.CompactionEventRepository;
import com.skillforge.server.repository.SessionCompactionCheckpointRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
public class CompactionService implements ContextCompactorCallback {

    private static final Logger log = LoggerFactory.getLogger(CompactionService.class);

    private static final int LOCK_STRIPES = 64;
    private static final int IDEMPOTENCY_MIN_GAP_MESSAGES = 5;

    private final SessionRepository sessionRepository;
    private final CompactionEventRepository eventRepository;
    private final SessionCompactionCheckpointRepository checkpointRepository;
    private final SessionService sessionService;
    private final LightCompactStrategy lightStrategy;
    private final FullCompactStrategy fullStrategy;
    private final LlmProviderFactory llmProviderFactory;
    private final LlmProperties llmProperties;
    private final ChatEventBroadcaster broadcaster;
    private final SessionMemoryCompactStrategy sessionMemoryCompactStrategy = new SessionMemoryCompactStrategy();
    private AgentRepository agentRepository;
    private MemoryService memoryService;

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

    /** Optional: for session memory compact (P9-6). */
    @Autowired(required = false)
    public void setMemoryService(MemoryService memoryService) {
        this.memoryService = memoryService;
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
                branch.setExecutionMode(source.getExecutionMode());
                branch.setLightContext(source.isLightContext());
                branch.setMaxLoops(source.getMaxLoops());
                branch.setMessagesJson("[]");
                branch.setLastUserMessageAt(Instant.now());
                branch.setStatus("active");
                branch.setRuntimeStatus("idle");
                branch = sessionService.saveSession(branch);
                sessionService.rewriteMessages(branch.getId(), checkpointMessages);
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
                if (!isBypassGuard(source, "light")) {
                    int gap = current.size() - session.getLastCompactedAtMessageCount();
                    if (gap < IDEMPOTENCY_MIN_GAP_MESSAGES) {
                        log.debug("callback light compact skipped (idempotency): sessionId={} gap={}", sessionId, gap);
                        out[0] = CompactCallbackResult.noOp(current, "idempotency guard");
                        return;
                    }
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
        SessionEntity sessionForMemoryCompact = null; // hoisted for Phase 1.5

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

                if ("user-manual".equals(source) && "running".equals(session.getRuntimeStatus())) {
                    throw new IllegalStateException("Cannot compact while session is running");
                }

                if (!isBypassGuard(source, "full")) {
                    // For REST path: use the session's stored messageCount (consistent with old behavior).
                    // For callback path: use current.size() (the engine has an accurate live count).
                    int effectiveCount = (inMemoryMessages != null)
                            ? inMemoryMessages.size() : session.getMessageCount();
                    int gap = effectiveCount - session.getLastCompactedAtMessageCount();
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

                String providerName = llmProperties.getDefaultProvider();
                provider = llmProviderFactory.getProvider(providerName);
                if (provider == null) {
                    log.warn("fullCompact skipped: default provider '{}' unavailable", providerName);
                    return null;
                }
                // Phase 1 complete — stripe lock releases at end of synchronized block.
                // fullCompactInFlight keeps deduplication active through Phase 2.
                sessionForMemoryCompact = session;
                phase1Success = true;
            } finally {
                if (!phase1Success) {
                    fullCompactInFlight.remove(sessionId);
                }
            }
        }

        // ── Phase 1.5: attempt session memory compact (zero-LLM) ────────────────
        if (memoryService != null && sessionForMemoryCompact != null
                && sessionForMemoryCompact.getUserId() != null) {
            try {
                String memorySummary = memoryService.previewMemoriesForPrompt(
                        sessionForMemoryCompact.getUserId(), null);
                if (memorySummary != null && !memorySummary.isBlank()) {
                    CompactResult memoryResult = sessionMemoryCompactStrategy.tryCompact(
                            prep, memorySummary,
                            SessionMemoryCompactStrategy.DEFAULT_MAX_TOKENS,
                            SessionMemoryCompactStrategy.DEFAULT_MIN_MESSAGES);
                    if (memoryResult != null && !isTrulyNoOp(memoryResult)) {
                        // Memory compact succeeded — skip Phase 2 (LLM), go to Phase 3
                        final CompactResult finalMemResult = memoryResult;
                        final CompactionEventEntity[] savedEvt = {null};
                        try {
                            synchronized (lockFor(sessionId)) {
                                runInTransaction(() ->
                                        savedEvt[0] = persistCompactResult(sessionId, "full", source, reason, finalMemResult)
                                );
                            }
                        } finally {
                            fullCompactInFlight.remove(sessionId);
                        }
                        log.info("sessionMemoryCompact succeeded: sessionId={} source={} reclaimed={} tokens",
                                sessionId, source, memoryResult.getTokensReclaimed());
                        return new FullCompactOutcome(savedEvt[0], memoryResult);
                    }
                }
            } catch (Exception e) {
                log.warn("sessionMemoryCompact failed, falling back to LLM: sessionId={}", sessionId, e);
            }
        }

        // ── Phase 2: LLM call, outside stripe lock ───────────────────────────────
        CompactResult result;
        try {
            result = fullStrategy.applyPrepared(prep, provider, null);
        } catch (Exception e) {
            fullCompactInFlight.remove(sessionId);
            log.error("fullCompact Phase 2 LLM call failed: sessionId={}", sessionId, e);
            // BUG-A prerequisite: rethrow so the engine's catch increments the breaker.
            // Returning null here would surface as performed=false (no-op), which under the
            // BUG-A fix is treated as neutral and would leave a real LLM failure invisible
            // to the circuit breaker.
            throw new RuntimeException("fullCompact Phase 2 failed for sessionId=" + sessionId, e);
        }

        if (result == null || isTrulyNoOp(result)) {
            fullCompactInFlight.remove(sessionId);
            log.info("fullCompact no-op (LLM returned empty): sessionId={}", sessionId);
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

        log.info("fullCompact done: sessionId={} source={} reclaimed={} tokens",
                sessionId, source, result.getTokensReclaimed());
        return new FullCompactOutcome(savedEvt[0], result);
    }

    // ================ 内部辅助 ================

    /** Holds both the persisted event and the CompactResult for callers that need both. */
    private record FullCompactOutcome(CompactionEventEntity event, CompactResult compactResult) {}

    /**
     * Persist a CompactResult to DB (messages + session counters + event).
     * Must be called inside a transaction and (for thread safety) under the stripe lock.
     * Returns the saved CompactionEventEntity.
     */
    private CompactionEventEntity persistCompactResult(String sessionId, String level,
                                                        String source, String reason,
                                                        CompactResult result) {
        SessionEntity fresh = sessionRepository.findById(sessionId).orElseThrow();
        if ("full".equalsIgnoreCase(level)) {
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
                for (Message message : retained) {
                    appends.add(new SessionService.AppendMessage(
                            message, SessionService.MSG_TYPE_NORMAL, Collections.emptyMap()));
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

    /** 判断 source + level 组合是否绕过 idempotency guard. */
    private boolean isBypassGuard(String source, String level) {
        return "user-manual".equals(source)
                || ("agent-tool".equals(source) && "light".equalsIgnoreCase(level));
    }

    /** 判断一次压缩是否真的没产出 —— 用于 #12 跳过 junk event. */
    private boolean isTrulyNoOp(CompactResult result) {
        return result.getTokensReclaimed() == 0
                && (result.getStrategiesApplied() == null || result.getStrategiesApplied().isEmpty())
                && result.getBeforeMessageCount() == result.getAfterMessageCount();
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
                    Map<String, Object> configMap = new com.fasterxml.jackson.databind.ObjectMapper()
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
