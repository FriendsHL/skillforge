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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>事务: compact / 回调两个主入口都加了 {@code @Transactional}, 保证 messagesJson 更新 +
 * session 计数器 + event 入表要么都成功要么都回滚. 广播在事务外部执行 (best-effort).
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

    public CompactionService(SessionRepository sessionRepository,
                             CompactionEventRepository eventRepository,
                             SessionService sessionService,
                             LightCompactStrategy lightStrategy,
                             FullCompactStrategy fullStrategy,
                             LlmProviderFactory llmProviderFactory,
                             LlmProperties llmProperties,
                             ChatEventBroadcaster broadcaster) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.sessionService = sessionService;
        this.lightStrategy = lightStrategy;
        this.fullStrategy = fullStrategy;
        this.llmProviderFactory = llmProviderFactory;
        this.llmProperties = llmProperties;
        this.broadcaster = broadcaster;
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
     * <p>上调用方必须在 {@link #lockFor(String)} 锁内 — 这样才能和 ChatService.chatAsync
     * 的 runtimeStatus 检查互斥.
     */
    @Transactional
    public CompactionEventEntity compact(String sessionId, String level, String source, String reason) {
        synchronized (lockFor(sessionId)) {
            SessionEntity session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

            // user-manual 不允许在 running 时触发
            if ("user-manual".equals(source) && "running".equals(session.getRuntimeStatus())) {
                throw new IllegalStateException("Cannot compact while session is running");
            }

            // idempotency guard (user-manual 和 A1 light bypass)
            boolean bypassGuard = isBypassGuard(source, level);
            if (!bypassGuard) {
                int gap = session.getMessageCount() - session.getLastCompactedAtMessageCount();
                if (gap < IDEMPOTENCY_MIN_GAP_MESSAGES) {
                    log.info("compact skipped by idempotency guard: sessionId={} gap={}", sessionId, gap);
                    return null;
                }
            }

            List<Message> messages = sessionService.getSessionMessages(sessionId);
            int contextWindow = resolveContextWindowForSession(session);
            CompactResult result = runStrategy(level, messages, contextWindow);

            // #12: 真正的 no-op 不入表
            if (result == null || isTrulyNoOp(result)) {
                log.info("compact no-op (not persisted): sessionId={} level={} source={}",
                        sessionId, level, source);
                return null;
            }

            // 持久化新的 messages
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

            // 广播在事务提交后也 OK, 这里先调用 (best-effort)
            broadcastUpdated(fresh);
            log.info("compact done: sessionId={} level={} source={} reclaimed={} tokens",
                    sessionId, level, source, result.getTokensReclaimed());
            return saved;
        }
    }

    public List<CompactionEventEntity> listEvents(String sessionId) {
        return eventRepository.findBySessionIdOrderByIdDesc(sessionId);
    }

    // ================ ContextCompactorCallback ================

    @Override
    public CompactCallbackResult compactLight(String sessionId, List<Message> currentMessages,
                                               String sourceLabel, String reason) {
        return doCallback(sessionId, currentMessages, "light", sourceLabel, reason);
    }

    @Override
    public CompactCallbackResult compactFull(String sessionId, List<Message> currentMessages,
                                              String sourceLabel, String reason) {
        return doCallback(sessionId, currentMessages, "full", sourceLabel, reason);
    }

    /**
     * Engine 回调路径: 直接用 engine 传过来的 in-memory 列表压缩. 仍然会持久化结果.
     */
    @Transactional
    public CompactCallbackResult doCallback(String sessionId, List<Message> current,
                                             String level, String source, String reason) {
        synchronized (lockFor(sessionId)) {
            SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
                return CompactCallbackResult.noOp(current, "session not found");
            }
            // idempotency guard (A1 light 和 user-manual bypass)
            boolean bypassGuard = isBypassGuard(source, level);
            if (!bypassGuard) {
                int gap = current.size() - session.getLastCompactedAtMessageCount();
                if (gap < IDEMPOTENCY_MIN_GAP_MESSAGES) {
                    log.debug("callback compact skipped (idempotency): sessionId={} gap={}", sessionId, gap);
                    return CompactCallbackResult.noOp(current, "idempotency guard");
                }
            }

            int contextWindow = resolveContextWindowForSession(session);
            CompactResult result = runStrategy(level, current, contextWindow);

            // #12: 真正的 no-op 不入表
            if (result == null || isTrulyNoOp(result)) {
                log.info("callback compact no-op (not persisted): sessionId={} level={} source={}",
                        sessionId, level, source);
                return CompactCallbackResult.noOp(current, "strategy returned no-op");
            }

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
            eventRepository.save(evt);
            broadcastUpdated(fresh);

            return new CompactCallbackResult(result.getMessages(), true,
                    result.getTokensReclaimed(), result.getBeforeTokens(), result.getAfterTokens(),
                    "applied=" + String.join(",", result.getStrategiesApplied()));
        }
    }

    // ================ 内部辅助 ================

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
     *   agent.modelId → ModelConfig.contextWindowTokens → llmProperties default → 32000 fallback.
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
                            return pc.getContextWindowTokens();
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

    private CompactResult runStrategy(String level, List<Message> messages, int contextWindow) {
        if ("full".equalsIgnoreCase(level)) {
            String providerName = llmProperties.getDefaultProvider();
            LlmProvider provider = llmProviderFactory.getProvider(providerName);
            if (provider == null) {
                log.warn("Full compact skipped: default provider '{}' unavailable", providerName);
                return null;
            }
            return fullStrategy.apply(messages, contextWindow, provider, null);
        }
        return lightStrategy.apply(messages, contextWindow);
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
