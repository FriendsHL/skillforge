package com.skillforge.server.service;

import com.skillforge.core.compact.CompactResult;
import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.ModelConfig;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.CompactionEventEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.CompactionEventRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final SessionService sessionService;
    private final LightCompactStrategy lightStrategy;
    private final FullCompactStrategy fullStrategy;
    private final LlmProviderFactory llmProviderFactory;
    private final LlmProperties llmProperties;
    private final ChatEventBroadcaster broadcaster;
    private AgentRepository agentRepository;

    private final Object[] sessionLocks;

    /** Sessions currently executing Phase 2 (LLM call) of full compact. */
    private final Set<String> fullCompactInFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Null-safe transaction template; null when no PlatformTransactionManager is provided (tests). */
    private final TransactionTemplate transactionTemplate;

    public CompactionService(SessionRepository sessionRepository,
                             CompactionEventRepository eventRepository,
                             SessionService sessionService,
                             LightCompactStrategy lightStrategy,
                             FullCompactStrategy fullStrategy,
                             LlmProviderFactory llmProviderFactory,
                             LlmProperties llmProperties,
                             ChatEventBroadcaster broadcaster,
                             PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
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

                List<Message> messages = sessionService.getSessionMessages(sessionId);
                int contextWindow = resolveContextWindowForSession(session);
                CompactResult result = lightStrategy.apply(messages, contextWindow);

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
                CompactResult result = lightStrategy.apply(current, contextWindow);

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
                        : sessionService.getSessionMessages(sessionId);

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
            result = fullStrategy.applyPrepared(prep, provider, null);
        } catch (Exception e) {
            fullCompactInFlight.remove(sessionId);
            log.error("fullCompact Phase 2 LLM call failed: sessionId={}", sessionId, e);
            return null;
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
        sessionService.saveSessionMessages(sessionId, result.getMessages());
        SessionEntity fresh = sessionRepository.findById(sessionId).orElseThrow();
        fresh.setMessageCount(result.getMessages().size());
        fresh.setLastCompactedAt(Instant.now());
        fresh.setLastCompactedAtMessageCount(result.getMessages().size());
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
            log.debug("compact broadcastUpdated skipped: {}", t.getMessage());
        }
    }

    // Suppressed unused reference: keep TokenEstimator import to not dead-drop it from API surface.
    @SuppressWarnings("unused")
    private static int estimate(List<Message> msgs) {
        return TokenEstimator.estimate(msgs);
    }
}
