package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.reminder.ReminderBuilder;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.dto.SessionMessageDto;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionSummaryEntity;
import com.skillforge.server.exception.SessionNotFoundException;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final int APPEND_LOCK_STRIPES = 64;
    /** Channel 会话在缺少用户映射且 Agent 也无 ownerId 时的最终兜底用户。 */
    public static final long DEFAULT_CHANNEL_USER_ID = 1L;
    public static final String MSG_TYPE_NORMAL = "NORMAL";
    public static final String MSG_TYPE_COMPACT_BOUNDARY = "COMPACT_BOUNDARY";
    public static final String MSG_TYPE_SUMMARY = "SUMMARY";
    public static final String MSG_TYPE_SYSTEM_EVENT = "SYSTEM_EVENT";
    /** P9-5: post-compact recovery payload (recently-read files snapshot). */
    public static final String MSG_TYPE_RECOVERY_PAYLOAD = "RECOVERY_PAYLOAD";
    public static final String MESSAGE_TYPE_NORMAL = "normal";
    public static final String MESSAGE_TYPE_ASK_USER = "ask_user";
    public static final String MESSAGE_TYPE_CONFIRMATION = "confirmation";

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2a (§3 provenance): provenance value for
     * an injected range summary in the derived model view. The summary is NOT a real message row,
     * so it has no seq_no; {@code -1} marks it so P2b's reconciliation never tries to persist it.
     * Real rows carry their actual (non-negative) {@code seq_no} as provenance.
     */
    public static final long PROVENANCE_SUMMARY = -1L;

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final AgentRepository agentRepository;
    private final SessionMessageStoreProperties storeProperties;
    private final ObjectMapper objectMapper;
    private final Object[] appendLockStripes;
    private final TransactionTemplate requiredTxTemplate;
    private final TransactionTemplate requiresNewTxTemplate;
    private final TransactionTemplate readOnlyTxTemplate;

    /**
     * Optional:WebSocket 广播器。构造时可能不可用(测试 / 启动顺序),
     * 通过 setter 注入以避免循环依赖。为 null 时静默跳过广播。
     */
    private ChatEventBroadcaster broadcaster;

    /**
     * P9-2: tool_result 归档服务。setter 注入避免引入循环依赖（ToolResultArchiveService
     * 只依赖 archive repository，但保持 setter 注入与 broadcaster 一致，方便单元测试用 null
     * 启用 SessionService 而不强制注入归档依赖）。
     */
    private ToolResultArchiveService toolResultArchiveService;

    /**
     * Q2 BE-W1: ReminderBuilder per-session debounce map needs to be cleared on session
     * deletion, otherwise the Spring singleton accumulates dead entries (≈20MB / 100K
     * deletions). Setter injection keeps SessionService unit tests free of this dependency.
     * Null at unit-test time → no-op.
     */
    private ReminderBuilder reminderBuilder;

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2a: range-summary store for the derived
     * model view. Optional (setter-injected, null in legacy unit tests). When the range-model flag
     * is OFF this is never consulted; when ON, {@link #getContextMessages(String)} derives the model
     * view from active summaries + uncovered rows instead of the boundary slice.
     */
    private SessionSummaryRepository sessionSummaryRepository;

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2a: routes {@link #getContextMessages}
     * between the legacy boundary-slice read (flag OFF) and the derived range-model read (flag ON).
     * Mirrors {@code CompactionService}'s {@code skillforge.compact.range-model.enabled} so the read
     * and write paths flip together. Default false → no behavior change until explicitly enabled.
     */
    @Value("${skillforge.compact.range-model.enabled:false}")
    private boolean rangeModelEnabled;

    public SessionService(SessionRepository sessionRepository,
                          SessionMessageRepository sessionMessageRepository,
                          AgentRepository agentRepository,
                          SessionMessageStoreProperties storeProperties,
                          ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.agentRepository = agentRepository;
        this.storeProperties = storeProperties;
        this.objectMapper = objectMapper;
        this.appendLockStripes = new Object[APPEND_LOCK_STRIPES];
        for (int i = 0; i < APPEND_LOCK_STRIPES; i++) {
            this.appendLockStripes[i] = new Object();
        }
        this.requiredTxTemplate = new TransactionTemplate(transactionManager);
        this.requiredTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.requiresNewTxTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnlyTxTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readOnlyTxTemplate.setReadOnly(true);
    }

    @Autowired(required = false)
    public void setBroadcaster(ChatEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Autowired(required = false)
    public void setToolResultArchiveService(ToolResultArchiveService toolResultArchiveService) {
        this.toolResultArchiveService = toolResultArchiveService;
    }

    /** Q2 BE-W1: optional setter for the singleton ReminderBuilder; clears per-session
     *  debounce state on session deletion so the singleton's map doesn't leak entries. */
    @Autowired(required = false)
    public void setReminderBuilder(ReminderBuilder reminderBuilder) {
        this.reminderBuilder = reminderBuilder;
    }

    /** P2a: optional setter for the range-summary store backing the derived model view. */
    @Autowired(required = false)
    public void setSessionSummaryRepository(SessionSummaryRepository sessionSummaryRepository) {
        this.sessionSummaryRepository = sessionSummaryRepository;
    }

    /** Test seam: toggle the range-model derived read without a Spring context. */
    public void setRangeModelEnabled(boolean rangeModelEnabled) {
        this.rangeModelEnabled = rangeModelEnabled;
    }

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2b (B1): supplies the per-session
     * compaction stripe lock so {@link #updateSessionMessages} can make its read-derive-compare-write
     * body mutually exclusive with a concurrent compaction Phase 3 (insert summary + supersede +
     * mark). {@code CompactionService} implements this. Setter-injected (optional) to avoid a
     * constructor cycle (CompactionService already constructor-depends on SessionService); null in
     * legacy unit tests → no extra lock (flag-OFF behavior unchanged).
     */
    public interface CompactionLockProvider {
        Object lockFor(String sessionId);
    }

    /**
     * P2b B1: the compaction lock provider. When wired AND the range-model flag is ON,
     * {@link #updateSessionMessages} acquires this lock OUTSIDE its append lock so reconciliation and
     * compaction Phase 3 cannot interleave (the active summary can no longer change mid-reconcile).
     */
    private CompactionLockProvider compactionLockProvider;

    /**
     * Wired manually by {@code CompactionService}'s constructor ({@code setCompactionLockProvider(this)})
     * rather than via {@code @Autowired}: SessionService is constructor-injected INTO CompactionService,
     * so an autowired setter back-edge here makes Spring see a sessionService ↔ compactionService bean
     * cycle and refuse to start. The manual call keeps the optional semantics (null when no
     * CompactionService bean exists — {@code updateSessionMessages} null-checks before use) without the
     * cycle. Do NOT re-add {@code @Autowired} here.
     */
    public void setCompactionLockProvider(CompactionLockProvider compactionLockProvider) {
        this.compactionLockProvider = compactionLockProvider;
    }

    private Map<String, Object> toListProjection(SessionEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("userId", s.getUserId());
        m.put("agentId", s.getAgentId());
        m.put("title", s.getTitle());
        m.put("status", s.getStatus());
        m.put("runtimeStatus", s.getRuntimeStatus());
        m.put("runtimeStep", s.getRuntimeStep());
        m.put("runtimeError", s.getRuntimeError());
        m.put("messageCount", s.getMessageCount());
        m.put("totalInputTokens", s.getTotalInputTokens());
        m.put("totalOutputTokens", s.getTotalOutputTokens());
        m.put("executionMode", s.getExecutionMode());
        m.put("parentSessionId", s.getParentSessionId());
        m.put("depth", s.getDepth());
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }

    /**
     * 创建子 session,继承父的 userId,记录 parentSessionId + depth + subAgentRunId。
     */
    public SessionEntity createSubSession(SessionEntity parent, Long childAgentId, String runId) {
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(parent.getUserId());
        session.setAgentId(childAgentId);
        session.setTitle("SubAgent run " + runId.substring(0, 8));
        session.setMessagesJson("[]");
        session.setParentSessionId(parent.getId());
        session.setDepth(parent.getDepth() + 1);
        session.setSubAgentRunId(runId);
        // 子 session 默认继承父的 executionMode
        session.setExecutionMode(parent.getExecutionMode());
        // EVAL-V2 M3a §2.2: 子 session 复制父 origin。eval 父派 eval 子，整树同 origin，
        // 不会有 eval 父 → production 子的"窜流"导致 eval cost 进 production dashboard。
        session.setOrigin(parent.getOrigin());
        AgentEntity agent = agentRepository.findById(childAgentId).orElse(null);
        if (agent != null && agent.getExecutionMode() != null) {
            session.setExecutionMode(agent.getExecutionMode());
        }
        return sessionRepository.save(session);
    }

    public SessionEntity createSession(Long userId, Long agentId) {
        return createSession(userId, agentId, null);
    }

    /**
     * Legacy compatibility overload from EVAL-V2 Q1.
     *
     * <p>M3c introduces {@code t_eval_analysis_session}; new analysis flows
     * should create ordinary sessions and persist their analysis linkage in the
     * dedicated table instead of writing {@code sourceScenarioId} here. This
     * overload remains for backward compatibility with older callers.
     */
    public SessionEntity createSession(Long userId, Long agentId, String sourceScenarioId) {
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setAgentId(agentId);
        session.setTitle("New Session");
        session.setMessagesJson("[]");
        if (sourceScenarioId != null && !sourceScenarioId.isBlank()) {
            session.setSourceScenarioId(sourceScenarioId);
        }
        // 初始化 lastUserMessageAt 以免 B3 在空会话上计算出巨大 gap
        session.setLastUserMessageAt(java.time.Instant.now());
        // 创建时从 Agent 拷贝默认 executionMode
        AgentEntity agent = agentRepository.findById(agentId).orElse(null);
        if (agent != null && agent.getExecutionMode() != null) {
            session.setExecutionMode(agent.getExecutionMode());
        }
        SessionEntity saved = sessionRepository.save(session);
        // 广播 per-user session_created,列表页据此立即插入新行
        if (broadcaster != null && saved.getParentSessionId() == null) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", "session_created");
                payload.put("session", toListProjection(saved));
                broadcaster.userEvent(saved.getUserId(), payload);
            } catch (Throwable t) {
                log.debug("session_created broadcast skipped: {}", t.getMessage());
            }
        }
        return saved;
    }

    public SessionEntity getSession(String id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Session not found: " + id));
    }

    public List<SessionEntity> listUserSessions(Long userId) {
        // EVAL-V2 M3a R3: 默认 origin='production'，避免 eval 派生 session 污染常规列表。
        return listUserSessions(userId, SessionEntity.ORIGIN_PRODUCTION);
    }

    /**
     * EVAL-V2 M3a §2.2 R3: 显式 origin 过滤的 overload，eval 任务 UI 用 origin='eval' 拉
     * 自己的 session。不允许传 null —— null 等价于 "返回所有 origin"，会破坏过滤的初衷；
     * 调用方需要这种语义请显式传两条 list 合并。
     */
    public List<SessionEntity> listUserSessions(Long userId, String origin) {
        Objects.requireNonNull(origin, "origin");
        // 只返回顶层 session,SubAgent 派发的子 session 不污染主列表
        return sessionRepository
                .findByUserIdAndParentSessionIdIsNullAndOriginOrderByUpdatedAtDesc(userId, origin);
    }

    /**
     * SYSTEM-AGENT-TYPING Phase 2 visibility (2026-05-18) — list top-level sessions
     * by owning agent's {@code agent_type}. {@code userId} is NOT a filter: system
     * sessions are cron-owned (typically userId=0) but the dashboard operator
     * (userId=1=admin in single-user dev) is expected to see them.
     *
     * <p>Origin defaults to {@link SessionEntity#ORIGIN_PRODUCTION} so eval-spawned
     * runs don't pollute the system view (mirrors {@link #listUserSessions(Long)}).
     *
     * @param agentType {@code "user"} or {@code "system"}; must not be null/blank
     */
    public List<SessionEntity> listSessionsByAgentType(String agentType) {
        return listSessionsByAgentType(agentType, SessionEntity.ORIGIN_PRODUCTION);
    }

    /** Origin-explicit overload — eval flows pass {@code 'eval'}. */
    public List<SessionEntity> listSessionsByAgentType(String agentType, String origin) {
        Objects.requireNonNull(agentType, "agentType");
        Objects.requireNonNull(origin, "origin");
        if (agentType.isBlank()) {
            throw new IllegalArgumentException("agentType must not be blank");
        }
        return sessionRepository.findByAgentTypeAndOriginOrderByUpdatedAtDesc(agentType, origin);
    }

    /** 列出某个 parent session 下的所有子 session(SubAgent 派发)。 */
    public List<SessionEntity> listChildSessions(String parentSessionId) {
        return sessionRepository.findByParentSessionId(parentSessionId);
    }

    public record AppendMessage(Message message, String msgType, String messageType, String controlId,
                                Instant answeredAt, Map<String, Object> metadata, String traceId) {
        public AppendMessage {
            Objects.requireNonNull(message, "message");
            msgType = (msgType == null || msgType.isBlank()) ? MSG_TYPE_NORMAL : msgType;
            messageType = (messageType == null || messageType.isBlank()) ? MESSAGE_TYPE_NORMAL : messageType;
            metadata = (metadata == null) ? Collections.emptyMap() : metadata;
        }

        /** Backward-compat — defaults traceId to null. */
        public AppendMessage(Message message, String msgType, String messageType, String controlId,
                             Instant answeredAt, Map<String, Object> metadata) {
            this(message, msgType, messageType, controlId, answeredAt, metadata, null);
        }

        public AppendMessage(Message message, String msgType, Map<String, Object> metadata) {
            this(message, msgType, MESSAGE_TYPE_NORMAL, null, null, metadata, null);
        }
    }

    /**
     * StoredMessage carries the row-store representation back into the service layer.
     *
     * <p>OBS-2 M3 W1: {@code traceId} now travels with the row so boundary preservation /
     * full rewrite paths in {@link #updateSessionMessages(String, List, long, long, String)}
     * can restore the original {@code trace_id} on each historical row instead of clearing
     * it to NULL. Only delta rows (newly written by the current loop) get stamped with the
     * {@code traceId} parameter passed to {@code updateSessionMessages}.
     */
    public record StoredMessage(long seqNo, String msgType, String messageType, String controlId,
                                Instant answeredAt, Map<String, Object> metadata, Message message,
                                String traceId, Instant createdAt, Long compactedBySummaryId) {
        /** Backward-compat — defaults compactedBySummaryId to null. */
        public StoredMessage(long seqNo, String msgType, String messageType, String controlId,
                             Instant answeredAt, Map<String, Object> metadata, Message message,
                             String traceId, Instant createdAt) {
            this(seqNo, msgType, messageType, controlId, answeredAt, metadata, message, traceId, createdAt, null);
        }

        /** Backward-compat — defaults createdAt + compactedBySummaryId to null. */
        public StoredMessage(long seqNo, String msgType, String messageType, String controlId,
                             Instant answeredAt, Map<String, Object> metadata, Message message,
                             String traceId) {
            this(seqNo, msgType, messageType, controlId, answeredAt, metadata, message, traceId, null, null);
        }

        /** Backward-compat — defaults traceId + createdAt + compactedBySummaryId to null. */
        public StoredMessage(long seqNo, String msgType, String messageType, String controlId,
                             Instant answeredAt, Map<String, Object> metadata, Message message) {
            this(seqNo, msgType, messageType, controlId, answeredAt, metadata, message, null, null, null);
        }

        public StoredMessage(long seqNo, String msgType, Map<String, Object> metadata, Message message) {
            this(seqNo, msgType, MESSAGE_TYPE_NORMAL, null, null, metadata, message, null, null, null);
        }
    }

    @Transactional(readOnly = true)
    public List<Message> getFullHistory(String id) {
        List<StoredMessage> storedMessages = getFullHistoryRecords(id);
        List<Message> out = new ArrayList<>(storedMessages.size());
        for (StoredMessage stored : storedMessages) {
            out.add(stored.message());
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<SessionMessageDto> getFullHistoryDtos(String id) {
        List<StoredMessage> storedMessages = getFullHistoryRecords(id);
        List<SessionMessageDto> out = new ArrayList<>(storedMessages.size());
        for (StoredMessage stored : storedMessages) {
            Message m = stored.message();
            out.add(new SessionMessageDto(
                    stored.seqNo(),
                    toRoleString(m),
                    m.getContent(),
                    stored.msgType(),
                    stored.messageType(),
                    stored.controlId(),
                    stored.answeredAt(),
                    stored.metadata(),
                    stored.traceId(),
                    stored.createdAt(),
                    m.getReasoningContent()
            ));
        }
        return out;
    }

    /**
     * Memory v2 PR-3: load NORMAL, unpruned row-store messages after an extraction cursor.
     * The cursor is exclusive and uses {@code t_session_message.seq_no}. Legacy CLOB
     * fallback treats list indexes as seq numbers for dev/test compatibility.
     */
    @Transactional(readOnly = true)
    public List<StoredMessage> getNormalHistoryRecordsAfterSeq(String id, long seqNoExclusive) {
        if (storeProperties != null && storeProperties.isRowReadEnabled() && sessionMessageRepository != null) {
            List<SessionMessageEntity> rows = new ArrayList<>();
            int page = 0;
            while (true) {
                Page<SessionMessageEntity> p = sessionMessageRepository
                        .findBySessionIdAndMsgTypeAndPrunedAtIsNullAndSeqNoGreaterThanOrderBySeqNoAsc(
                                id, MSG_TYPE_NORMAL, seqNoExclusive, PageRequest.of(page, 500));
                if (p.isEmpty()) {
                    break;
                }
                rows.addAll(p.getContent());
                if (!p.hasNext()) {
                    break;
                }
                page++;
            }
            return toStoredMessages(rows);
        }

        List<Message> legacy = readLegacyMessagesJson(id);
        List<StoredMessage> out = new ArrayList<>();
        for (int i = 0; i < legacy.size(); i++) {
            if (i > seqNoExclusive) {
                out.add(new StoredMessage(i, MSG_TYPE_NORMAL, Collections.emptyMap(), legacy.get(i)));
            }
        }
        return out;
    }

    /**
     * Latest NORMAL, unpruned message seq for Memory v2 incremental extraction.
     * Returns {@code -1} when no extractable row exists.
     */
    @Transactional(readOnly = true)
    public long getLatestNormalSeqNo(String id) {
        if (storeProperties != null && storeProperties.isRowReadEnabled() && sessionMessageRepository != null) {
            return sessionMessageRepository
                    .findTopBySessionIdAndMsgTypeAndPrunedAtIsNullOrderBySeqNoDesc(id, MSG_TYPE_NORMAL)
                    .map(SessionMessageEntity::getSeqNo)
                    .orElse(-1L);
        }
        return readLegacyMessagesJson(id).size() - 1L;
    }

    /**
     * Count new user turns after the extraction cursor. Used by PR-3 to avoid
     * locking a session forever after a too-short early attempt, and to trigger
     * long-running sessions once enough unextracted user turns accumulate.
     */
    @Transactional(readOnly = true)
    public long countUserNormalMessagesAfterSeq(String id, long seqNoExclusive) {
        if (storeProperties != null && storeProperties.isRowReadEnabled() && sessionMessageRepository != null) {
            return sessionMessageRepository
                    .countBySessionIdAndRoleAndMsgTypeAndPrunedAtIsNullAndSeqNoGreaterThan(
                            id, "user", MSG_TYPE_NORMAL, seqNoExclusive);
        }
        long count = 0;
        List<Message> legacy = readLegacyMessagesJson(id);
        for (int i = 0; i < legacy.size(); i++) {
            if (i > seqNoExclusive && legacy.get(i).getRole() == Message.Role.USER) {
                count++;
            }
        }
        return count;
    }

    /**
     * 用于 UI：返回结构化历史（含 msgType / metadata / seqNo）。
     */
    @Transactional(readOnly = true)
    public List<StoredMessage> getFullHistoryRecords(String id) {
        List<StoredMessage> rowRecords = new ArrayList<>();
        if (storeProperties.isRowReadEnabled()) {
            List<SessionMessageEntity> entities = loadAllMessageRowsInReadTx(id);
            if (!entities.isEmpty()) {
                rowRecords = toStoredMessages(entities);
            }
        }

        List<Message> legacy = readLegacyMessagesJson(id);
        List<StoredMessage> legacyRecords = legacyToStoredMessages(legacy);

        if (storeProperties.isDualReadVerifyEnabled() && !legacyRecords.isEmpty()) {
            verifyDualReadConsistency(id, rowRecords, legacyRecords);
        }

        if (!rowRecords.isEmpty()) {
            return rowRecords;
        }
        return legacyRecords;
    }

    /**
     * 用于 LLM：如果存在最近一个 COMPACT_BOUNDARY，则仅返回边界后的上下文（不含 boundary 本身）。
     *
     * <p>P9-2: 在返回前应用 tool_result 归档替换（lookup-then-substitute + 必要时 idempotent
     * upsert）。归档决策对同一 (session_id, tool_use_id) 幂等不翻转：已归档过的 tool_result
     * 永远以 preview 形式呈现给 LLM；未归档但本次聚合超预算的会按 size 降序归档大块到表，
     * 后续 turn 再读取时自动命中 preview 路径。失败 / 未启用时回退到原逻辑（保留原文）。
     */
    @Transactional(readOnly = true)
    public List<Message> getContextMessages(String id) {
        // P2b-2a (§5(A) dual-read, §10 risk ③): derive the model view ONLY for sessions that were
        // actually compacted under the range model — i.e. that have at least one ACTIVE range
        // summary row. This is the migration-safety gate: flipping the flag ON globally must NOT
        // re-derive EXISTING old-model sessions (whose compacted view is physical
        // COMPACT_BOUNDARY + SUMMARY rows, with NO t_session_summary rows). For those,
        // getContextMessagesWithProvenance would find no active summaries and emit the ENTIRE
        // history (incl. old boundary/summary rows + re-appended young-gen) → context blow-up.
        // Old-model boundary sessions AND fresh/never-compacted sessions fall through to the
        // legacy boundary-slice path below (which correctly post-boundary-slices the former and
        // returns all rows for the latter). The existence check is ONE indexed boolean query on
        // the partial index idx_ss_session_active — acceptable on this per-turn hot path.
        //
        // WHY a genuine new-model session can never be wrongly routed to the legacy slice:
        //   - Q3 rolling merge (CompactionService.persistCompactResult) saves the NEW summary
        //     FIRST then supersedes priors in the SAME transaction, so a committed full-compact
        //     leaves the session with exactly one active summary — no production path lands a
        //     compacted new-model session at zero active summaries.
        //   - The restore/checkpoint prune (deleteBySessionIdAndEndSeqGreaterThan) can drop
        //     summaries, but it REWRITES the message rows back to the checkpoint state in the
        //     same operation, so afterwards the legacy slice returns the restored row set
        //     (correct) rather than a blown-up full history.
        if (rangeModelEnabled && sessionSummaryRepository != null
                && sessionSummaryRepository.existsBySessionIdAndSupersededByIsNull(id)) {
            return getContextMessagesWithProvenance(id).messages();
        }
        List<StoredMessage> records = getFullHistoryRecords(id);
        int lastBoundary = -1;
        for (int i = records.size() - 1; i >= 0; i--) {
            if (MSG_TYPE_COMPACT_BOUNDARY.equals(records.get(i).msgType())) {
                lastBoundary = i;
                break;
            }
        }
        List<Message> out = new ArrayList<>();
        int start = (lastBoundary >= 0) ? lastBoundary + 1 : 0;
        for (int i = start; i < records.size(); i++) {
            StoredMessage record = records.get(i);
            if (MSG_TYPE_SYSTEM_EVENT.equals(record.msgType())) {
                continue;
            }
            out.add(record.message());
        }
        return applyArchiveSafely(id, out);
    }

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2a (§2.4 derive + §3 provenance): the
     * derived model view that feeds the LLM, paired with a provenance array so P2b's reconciliation
     * can tell injected summaries (never persisted) from real rows (already persisted) and only
     * write back genuinely new turns.
     *
     * <p>Construction (single linear pass over the full real history ASC by seq):
     * <ul>
     *   <li>A maximal run of consecutive rows whose {@code compacted_by_summary_id} == the same
     *       <b>active</b> summary S is collapsed into ONE {@code Message.user(S.summaryText)} with
     *       provenance {@link #PROVENANCE_SUMMARY} ({@code -1}). The whole run is skipped.</li>
     *   <li>An uncovered row (no marker, or marker pointing at a non-active / superseded summary)
     *       is emitted as-is with provenance = its real {@code seq_no}. SYSTEM_EVENT rows are
     *       skipped (mirrors the legacy slice).</li>
     * </ul>
     *
     * <p>Per §2.6 Q3 (rolling merge) there is normally exactly ONE active summary covering
     * {@code [0, endSeq]}, so the typical result is {@code [summary] + uncovered tail}. The general
     * multiple-adjacent-active-summary case is handled by run-detection keyed on the marker id.
     *
     * <p><b>Superseded-marker defense</b>: if a row's {@code compacted_by_summary_id} points at a
     * summary that is NOT in the active set (it was superseded but the marker was not re-pointed —
     * the P1 {@code markCompactedBySummary} only stamps unmarked rows, so after a Q3 merge the
     * earliest rows still carry the OLD, now-superseded summary id), the row is treated as
     * UNCOVERED and emitted as a real row. This is the safe choice: emitting the real row can never
     * drop information (the rolling active summary already re-summarizes that span, so the LLM sees
     * the content twice at worst — verbose, not lossy), whereas mapping it to the superseding
     * summary would risk collapsing a row that the active summary's range might not actually cover.
     * INV-1 still holds: P1 ranges end pair-complete (findSafeBoundary), and emitting extra real
     * rows verbatim cannot split a tool_use↔tool_result pair.
     *
     * <p>INV-4: the injected summary is a transient {@code Message.user(...)} built here, never a
     * persisted message row; real rows are emitted by object identity from {@code StoredMessage}.
     */
    @Transactional(readOnly = true)
    public ContextWithProvenance getContextMessagesWithProvenance(String id) {
        List<StoredMessage> records = getFullHistoryRecords(id);

        // Active summary ids (superseded_by IS NULL) → summaryText, for run collapsing. Empty when
        // the session never compacted under the range model (all rows uncovered).
        Map<Long, String> activeSummaryText = new HashMap<>();
        if (sessionSummaryRepository != null) {
            List<SessionSummaryEntity> active = sessionSummaryRepository
                    .findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(id);
            for (SessionSummaryEntity s : active) {
                activeSummaryText.put(s.getId(), s.getSummaryText());
            }
        }

        List<Message> out = new ArrayList<>();
        List<Long> provenance = new ArrayList<>();
        int i = 0;
        while (i < records.size()) {
            StoredMessage record = records.get(i);
            if (MSG_TYPE_SYSTEM_EVENT.equals(record.msgType())) {
                i++;
                continue;
            }
            Long marker = record.compactedBySummaryId();
            String summaryText = (marker != null) ? activeSummaryText.get(marker) : null;
            if (summaryText != null) {
                // Start of a covered run for active summary `marker`. Consume the maximal run of
                // consecutive rows carrying the SAME active marker (SYSTEM_EVENT rows inside the run
                // are skipped without breaking it — they are invisible to the model view anyway).
                emitSummary(out, provenance, summaryText);
                while (i < records.size()) {
                    StoredMessage r = records.get(i);
                    if (MSG_TYPE_SYSTEM_EVENT.equals(r.msgType())) {
                        i++;
                        continue;
                    }
                    if (marker.equals(r.compactedBySummaryId())) {
                        i++;
                    } else {
                        break;
                    }
                }
                continue;
            }
            // Uncovered row (no marker, or marker pointing at a superseded / unknown summary).
            out.add(record.message());
            provenance.add(record.seqNo());
            i++;
        }
        List<Message> archived = applyArchiveSafely(id, out);
        // applyArchiveSafely is documented to substitute tool_result content IN PLACE and never change
        // the list size, so provenance stays index-aligned with the returned messages. Defensive
        // guard (P2a nit): if a future archive implementation ever returns a differently-sized list,
        // the provenance array would silently misalign — detect and fall back to the un-archived
        // (size-correct) list rather than hand out a misaligned provenance pairing.
        if (archived == null || archived.size() != out.size()) {
            log.warn("getContextMessagesWithProvenance: applyArchiveSafely changed list size ({}→{}); "
                    + "falling back to un-archived messages to keep provenance aligned. sessionId={}",
                    out.size(), archived == null ? "null" : archived.size(), id);
            return new ContextWithProvenance(out, toLongArray(provenance));
        }
        return new ContextWithProvenance(archived, toLongArray(provenance));
    }

    private void emitSummary(List<Message> out, List<Long> provenance, String summaryText) {
        out.add(Message.user(summaryText));
        provenance.add(PROVENANCE_SUMMARY);
    }

    private long[] toLongArray(List<Long> values) {
        long[] arr = new long[values.size()];
        for (int i = 0; i < values.size(); i++) {
            arr[i] = values.get(i);
        }
        return arr;
    }

    /**
     * P2a (§3): derived model-view messages paired with a per-message provenance array.
     * {@code provenance[i]} is {@link #PROVENANCE_SUMMARY} for an injected summary, otherwise the
     * real {@code seq_no} of the row that produced {@code messages.get(i)}. Always index-aligned
     * with {@code messages}.
     */
    public record ContextWithProvenance(List<Message> messages, long[] provenance) {}

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2b (§7, B2): recompute the denormalized
     * {@code compacted_by_summary_id} markers from the active range summaries.
     *
     * <p><b>Why</b>: a marker is a denormalization of "this row is covered by the active summary
     * whose [start_seq,end_seq] contains my seq_no". Any path that re-writes message rows
     * (DELETE+INSERT) — the divergence-guard rewrite in {@link #updateSessionMessages}, and
     * {@code CompactionService.restoreFromCheckpoint} / {@code createBranchFromCheckpoint} — re-inserts
     * rows via {@code AppendMessage} which carries NO {@code compactedBySummaryId} (the 7-arg
     * constructor only preserves business/identity columns). Without recompute the markers would be
     * wiped (B2): the derived model view would stop collapsing those rows AND a future summary could
     * re-cover them → double-show / model-view corruption.
     *
     * <p><b>Algorithm</b>: clear all markers, then for each ACTIVE summary (superseded_by IS NULL)
     * re-stamp its covered seq range via {@link SessionMessageRepository#markCompactedBySummary}
     * (which only stamps still-unmarked rows — after the clear all rows are unmarked, so the first
     * active summary covering a seq wins; ranges of distinct active summaries are non-overlapping by
     * construction — P1 ranges are pair-complete and the Q3 rolling merge supersedes prior summaries).
     *
     * <p><b>No-op safety</b>: when the range-model flag is OFF, or the summary store is not wired
     * (legacy unit tests), or the session has no active summaries, this method is a cheap no-op — it
     * never touches markers a legacy session never had. Callers may invoke it unconditionally on a
     * rewrite path; it self-guards.
     *
     * <p>This method assumes the caller already holds the session append lock + an active transaction
     * (it is invoked from inside the rewrite paths, which run under {@code lockForAppend} + a REQUIRED
     * tx). It does not open its own lock/tx.
     */
    void recomputeCompactedMarkers(String sessionId) {
        if (!rangeModelEnabled || sessionSummaryRepository == null
                || sessionMessageRepository == null
                || storeProperties == null || !storeProperties.isRowWriteEnabled()) {
            return;
        }
        List<SessionSummaryEntity> active = sessionSummaryRepository
                .findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(sessionId);
        // Clear unconditionally so a rewrite that drops the last summary (e.g. restore before any
        // summary) also drops stale markers; restamp only when active summaries remain.
        sessionMessageRepository.clearCompactedMarkers(sessionId);
        for (SessionSummaryEntity s : active) {
            sessionMessageRepository.markCompactedBySummary(
                    sessionId, s.getStartSeq(), s.getEndSeq(), s.getId());
        }
    }

    public long appendInteractiveControlMessage(String id, String messageType, String controlId,
                                                Message message, Map<String, Object> metadata) {
        return appendMessages(id, List.of(new AppendMessage(
                message,
                MSG_TYPE_SYSTEM_EVENT,
                messageType,
                controlId,
                null,
                metadata)));
    }

    @Transactional
    public SessionMessageEntity markControlAnswered(String sessionId, String messageType, String controlId,
                                                    String state, String answer, String answerMode) {
        SessionMessageEntity entity = sessionMessageRepository
                .findBySessionIdAndMessageTypeAndControlId(sessionId, messageType, controlId)
                .orElseThrow(() -> new IllegalArgumentException("control not found"));
        if (entity.getAnsweredAt() != null) {
            throw new IllegalStateException("control already answered");
        }
        Map<String, Object> metadata = new LinkedHashMap<>(readMapJsonSafely(entity.getMetadataJson()));
        metadata.put("state", state);
        if (answer != null) {
            metadata.put("answer", answer);
        }
        metadata.put("answerMode", answerMode);
        Instant now = Instant.now();
        entity.setAnsweredAt(now);
        metadata.put("answeredAt", now.toString());
        entity.setMetadataJson(writeJsonSafely(metadata));
        return sessionMessageRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public SessionMessageEntity getControlMessage(String sessionId, String messageType, String controlId) {
        return sessionMessageRepository
                .findBySessionIdAndMessageTypeAndControlId(sessionId, messageType, controlId)
                .orElseThrow(() -> new IllegalArgumentException("control not found"));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<SessionMessageEntity> findPendingAsk(String sessionId) {
        return sessionMessageRepository
                .findTopBySessionIdAndMessageTypeAndAnsweredAtIsNullOrderBySeqNoDesc(
                        sessionId, MESSAGE_TYPE_ASK_USER);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<SessionMessageEntity> findPendingConfirmation(String sessionId) {
        return sessionMessageRepository
                .findTopBySessionIdAndMessageTypeAndAnsweredAtIsNullOrderBySeqNoDesc(
                        sessionId, MESSAGE_TYPE_CONFIRMATION);
    }

    /**
     * P9-2: 调用 ToolResultArchiveService 应用归档替换。归档服务标注 REQUIRES_NEW 事务，
     * 即便从 readOnly 上下文调用也会启用独立写事务。失败 fallback 到原 messages，
     * 保证 P9-2 故障时不破坏现有 chat loop（明确失败由 service 内部 log.warn）。
     */
    private List<Message> applyArchiveSafely(String sessionId, List<Message> messages) {
        if (toolResultArchiveService == null || messages == null || messages.isEmpty()) {
            return messages;
        }
        try {
            return toolResultArchiveService.applyArchive(sessionId, messages);
        } catch (Exception e) {
            log.warn("Tool result archive apply failed, returning original messages: sessionId={}", sessionId, e);
            return messages;
        }
    }

    public long appendMessages(String id, List<AppendMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return getLastSeqNo(id);
        }
        boolean inExistingTx = TransactionSynchronizationManager.isActualTransactionActive();
        if (!storeProperties.isRowWriteEnabled()) {
            synchronized (lockForAppend(id)) {
                Long lastSeq = requiredTxTemplate.execute(status -> {
                    List<Message> merged = readLegacyMessagesJson(id);
                    for (AppendMessage appendMessage : messages) {
                        merged.add(appendMessage.message());
                    }
                    writeLegacyOnly(id, merged);
                    return (long) merged.size() - 1L;
                });
                return lastSeq != null ? lastSeq : -1L;
            }
        }
        int maxAttempts = inExistingTx ? 1 : 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                synchronized (lockForAppend(id)) {
                    return appendInNewTransaction(id, messages);
                }
            } catch (DataIntegrityViolationException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("appendMessages seq conflict retrying: sessionId={} attempt={}", id, attempt);
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(5, 30));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        return getLastSeqNo(id);
    }

    public long appendMessages(String id, List<Message> messages, String msgType) {
        return appendMessages(id, messages, msgType, null);
    }

    /** OBS-2 M1: overload that persists {@code traceId} on every appended row. */
    public long appendMessages(String id, List<Message> messages, String msgType, String traceId) {
        if (messages == null || messages.isEmpty()) {
            return getLastSeqNo(id);
        }
        List<AppendMessage> wraps = new ArrayList<>(messages.size());
        for (Message message : messages) {
            wraps.add(new AppendMessage(message, msgType, MESSAGE_TYPE_NORMAL, null, null,
                    Collections.emptyMap(), traceId));
        }
        return appendMessages(id, wraps);
    }

    public long appendNormalMessages(String id, List<Message> messages) {
        return appendMessages(id, messages, MSG_TYPE_NORMAL, null);
    }

    /** OBS-2 M1: overload that persists {@code traceId} on every appended row. */
    public long appendNormalMessages(String id, List<Message> messages, String traceId) {
        return appendMessages(id, messages, MSG_TYPE_NORMAL, traceId);
    }

    public long countMessageRows(String sessionId) {
        if (storeProperties.isRowWriteEnabled()) {
            return sessionMessageRepository.countBySessionId(sessionId);
        }
        return readLegacyMessagesJson(sessionId).size();
    }

    /**
     * 首版工具输出裁剪：将包含 tool_result 的 NORMAL 消息标记为 pruned，并在读取时脱敏输出。
     */
    public int pruneToolOutputs(String sessionId, int limit) {
        if (!storeProperties.isRowWriteEnabled()) {
            log.info("pruneToolOutputs skipped because row-write-enabled=false, sessionId={}", sessionId);
            return 0;
        }
        synchronized (lockForAppend(sessionId)) {
            SessionEntity session = getSession(sessionId);
            if ("running".equals(session.getRuntimeStatus())) {
                throw new IllegalStateException("Cannot prune tool outputs while session is running");
            }
            Integer total = requiredTxTemplate.execute(status -> {
                int remaining = limit > 0 ? limit : Integer.MAX_VALUE;
                int totalPruned = 0;
                long cursorSeqNo = -1L;
                while (remaining > 0) {
                    Page<SessionMessageEntity> page = sessionMessageRepository
                            .findBySessionIdAndPrunedAtIsNullAndSeqNoGreaterThanOrderBySeqNoAsc(
                                    sessionId, cursorSeqNo, PageRequest.of(0, 200));
                    if (page.isEmpty()) {
                        break;
                    }
                    List<SessionMessageEntity> toUpdate = new ArrayList<>();
                    for (SessionMessageEntity entity : page.getContent()) {
                        cursorSeqNo = entity.getSeqNo();
                        if (remaining <= 0) {
                            break;
                        }
                        if (shouldPruneToolOutput(entity)) {
                            entity.setPrunedAt(Instant.now());
                            entity.setMetadataJson(mergePrunedMetadata(entity.getMetadataJson()));
                            toUpdate.add(entity);
                            totalPruned++;
                            remaining--;
                        }
                    }
                    if (!toUpdate.isEmpty()) {
                        sessionMessageRepository.saveAll(toUpdate);
                    }
                    if (!page.hasNext()) {
                        break;
                    }
                }
                return totalPruned;
            });
            return total != null ? total : 0;
        }
    }

    public void updateSessionMessages(String id, List<Message> messages,
                                       long inputTokens, long outputTokens) {
        updateSessionMessages(id, messages, inputTokens, outputTokens, null);
    }

    /**
     * OBS-2 M1: overload that stamps {@code traceId} on every newly appended row from the engine.
     * Existing rows (re-written via boundary preservation / full rewrite) keep their original
     * trace_id (they are restored from {@link StoredMessage}) — the new traceId only applies to
     * delta messages produced by the current loop.
     */
    public void updateSessionMessages(String id, List<Message> messages,
                                       long inputTokens, long outputTokens, String traceId) {
        // P2b B1: under the range model, the post-loop reconciliation (read active summary → derive →
        // compare → write) must be mutually exclusive with a concurrent compaction Phase 3 (insert
        // summary + supersede + mark covered rows). Otherwise the active summary can change BETWEEN
        // getContextMessages's two derived reads vs the engine view → prefix breaks → the divergence
        // guard rewrites the injected summary as a NORMAL row (INV-4 leak). We acquire the SAME stripe
        // lock compaction uses, OUTSIDE the append lock.
        //
        // Lock ORDERING (deadlock-safety): compaction always takes lockFor (compaction stripe) FIRST,
        // then — via appendMessages/rewriteMessages — lockForAppend (append stripe). We mirror that
        // exact order here (compaction lock → append lock), so no opposing-order acquisition exists.
        // The two are DIFFERENT monitors (separate stripe arrays), so this nesting is required; both
        // are plain Object monitors (reentrant), so a same-thread re-entry is safe.
        Object compactionLock = (rangeModelEnabled && compactionLockProvider != null)
                ? compactionLockProvider.lockFor(id) : null;
        if (compactionLock != null) {
            synchronized (compactionLock) {
                updateSessionMessagesUnderCompactionLock(id, messages, inputTokens, outputTokens, traceId);
            }
        } else {
            updateSessionMessagesUnderCompactionLock(id, messages, inputTokens, outputTokens, traceId);
        }
    }

    private void updateSessionMessagesUnderCompactionLock(String id, List<Message> messages,
                                                          long inputTokens, long outputTokens, String traceId) {
        synchronized (lockForAppend(id)) {
            requiredTxTemplate.execute(status -> {
                List<Message> persistedContext = getContextMessages(id);
                int prefixLen = commonPrefixSize(persistedContext, messages);
                // Divergence guard (2026-05-10, post-Q2/Q3 hardening):
                // Trigger rewrite whenever ANY persistedContext row didn't match engine's
                // view, not just the all-or-nothing prefixLen==0 case. The original
                // prefixLen==0 check missed mid-prefix divergence — engine's state diverged
                // from DB at some inner index but matched at index 0, so the else-branch
                // silently appended `messages.subList(prefixLen, ...)` as delta. Combined
                // with row-store dedup-by-id, this materialised as DUP user-msg rows
                // (Q2 commit bdb0453 hit it: ChatService persisted array-shape userMsg with
                // <system-reminder>, engine rebuilt String-shape Message.user(text), prefix
                // diverged at the user-msg index, append-delta dup-wrote the engine version
                // as seq N+1 — visible as two identical user bubbles in chat).
                //
                // Q3 commit cc87776 fixed the specific Q2 path by threading the same Message
                // object through engine.run; this guard is the structural backstop catching
                // the same shape-divergence pattern from any future code path. Compact paths
                // (light/full via CompactionService callback) sync DB BEFORE this is called,
                // so they remain prefix-matching and don't trip the guard.
                //
                // P2b B1: under the range model the "existing reconciliation suffices" property is
                // CONDITIONAL on this method holding the compaction stripe lock (acquired in the
                // public updateSessionMessages wrapper above) — without it, a concurrent /compact
                // Phase 3 could change the active summary between getContextMessages's reads and the
                // engine view, breaking the prefix and tripping this guard. STEP 3's range-model
                // branch below is the summary-safe backstop if that ever happens anyway.
                // prefixLen<size already implies persistedContext non-empty
                // (prefixLen>=0 cannot be < 0).
                boolean prefixMismatch = prefixLen < persistedContext.size()
                        && messages != null && !messages.isEmpty();
                if (prefixMismatch && rangeModelEnabled && sessionSummaryRepository != null) {
                    // ===== P2b B1 STEP 3 (belt-and-suspenders) =====
                    // Under the range model, `messages` (the engine view) is the DERIVED view: a
                    // covered run is represented by a single injected Message.user(summaryText) with
                    // provenance -1 — it is NOT a real row and must NEVER be persisted as a NORMAL
                    // row (INV-4). The B1 lock should prevent a mid-reconcile summary change, but if
                    // a mismatch still surfaces here, the legacy rewrite below would write the
                    // injected summary as a NORMAL row → summary leak. So under the flag we take a
                    // summary-safe rewrite: rebuild the row set as [existing REAL rows verbatim] +
                    // [engine tail with injected summaries filtered out]. Real rows already in the DB
                    // keep their identity/markers; only genuinely-new real turns are appended.
                    log.warn("updateSessionMessages range-model prefix mismatch (B1 backstop), summary-safe rewrite: sessionId={}, divergeAt={}, persistedSize={}, engineSize={}",
                            id, prefixLen, persistedContext.size(), messages.size());
                    rangeModelSummarySafeReconcile(id, messages, prefixLen, traceId);
                } else if (prefixMismatch) {
                    if (prefixLen > 0) {
                        // Mid-prefix divergence — strong indicator of a shape/content
                        // mismatch bug between ChatService persistence and engine output.
                        // Log diagnostic context (incl. content shape — ArrayList vs String —
                        // so Q2-style shape divergence is one-glance diagnosable). Only the
                        // class simple name is logged; raw content is intentionally omitted
                        // to avoid leaking PII / sensitive payloads.
                        Message persistRow = persistedContext.get(prefixLen);
                        Message engineRow = messages.size() > prefixLen ? messages.get(prefixLen) : null;
                        Object persistContent = persistRow.getContent();
                        Object engineContent = engineRow != null ? engineRow.getContent() : null;
                        String persistContentType = persistContent == null ? "null" : persistContent.getClass().getSimpleName();
                        String engineContentType = engineContent == null ? "null" : engineContent.getClass().getSimpleName();
                        log.warn("updateSessionMessages mid-prefix divergence: sessionId={}, divergeAt={}, persistedSize={}, engineSize={}, persistRole={}, engineRole={}, persistContentType={}, engineContentType={}",
                                id, prefixLen, persistedContext.size(), messages.size(),
                                persistRow.getRole(),
                                engineRow == null ? "<missing>" : engineRow.getRole(),
                                persistContentType, engineContentType);
                    }
                    // 兼容兜底：优先保留最后一个 boundary 之前的完整历史，避免破坏 full-compact 断点。
                    List<StoredMessage> fullRecords = getFullHistoryRecords(id);
                    int lastBoundary = -1;
                    for (int i = fullRecords.size() - 1; i >= 0; i--) {
                        if (MSG_TYPE_COMPACT_BOUNDARY.equals(fullRecords.get(i).msgType())) {
                            lastBoundary = i;
                            break;
                        }
                    }
                    if (lastBoundary >= 0) {
                        log.warn("updateSessionMessages prefix mismatch, preserve boundary rewrite: sessionId={}", id);
                        List<AppendMessage> wraps = new ArrayList<>(lastBoundary + 1 + messages.size());
                        // OBS-2 M3 W1: preserve each historical row's original trace_id (read back
                        // via StoredMessage.traceId, populated by toStoredMessages from
                        // SessionMessageEntity.traceId). Without this, boundary preservation
                        // would clear trace_id on every kept row.
                        for (int i = 0; i <= lastBoundary; i++) {
                            StoredMessage stored = fullRecords.get(i);
                            wraps.add(new AppendMessage(stored.message(), stored.msgType(),
                                    stored.messageType(), stored.controlId(), stored.answeredAt(),
                                    stored.metadata(), stored.traceId()));
                        }
                        for (Message message : messages) {
                            wraps.add(new AppendMessage(message, MSG_TYPE_NORMAL, MESSAGE_TYPE_NORMAL,
                                    null, null, Collections.emptyMap(), traceId));
                        }
                        rewriteRowsInNewTransaction(id, wraps);
                    } else {
                        log.warn("updateSessionMessages prefix mismatch, fallback to full rewrite: sessionId={}", id);
                        // OBS-2 M3 W1: full-rewrite path — no historical rows kept since lastBoundary<0,
                        // so the new traceId on delta is the only stamp; no historical row to preserve.
                        List<AppendMessage> wraps = new ArrayList<>(messages.size());
                        for (Message message : messages) {
                            wraps.add(new AppendMessage(message, MSG_TYPE_NORMAL, MESSAGE_TYPE_NORMAL,
                                    null, null, Collections.emptyMap(), traceId));
                        }
                        rewriteRowsInNewTransaction(id, wraps);
                    }
                } else {
                    List<Message> delta = (messages == null || messages.size() <= prefixLen)
                            ? Collections.emptyList()
                            : messages.subList(prefixLen, messages.size());
                    if (!delta.isEmpty()) {
                        List<AppendMessage> wraps = new ArrayList<>(delta.size());
                        for (Message message : delta) {
                            wraps.add(new AppendMessage(message, MSG_TYPE_NORMAL, MESSAGE_TYPE_NORMAL,
                                    null, null, Collections.emptyMap(), traceId));
                        }
                        appendRowsOnce(id, wraps);
                    }
                }

                SessionEntity session = getSession(id);
                session.setMessageCount(storeProperties.isRowWriteEnabled()
                        ? (int) sessionMessageRepository.countBySessionId(id)
                        : (messages == null ? 0 : messages.size()));
                session.setTotalInputTokens(session.getTotalInputTokens() + inputTokens);
                session.setTotalOutputTokens(session.getTotalOutputTokens() + outputTokens);
                sessionRepository.save(session);
                return null;
            });
        }
    }

    /**
     * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2b (B1 STEP 3): summary-safe reconciliation
     * for the range model when the divergence guard would otherwise fire. Guarantees INV-4 (no
     * injected summary ever lands as a NORMAL message row) even if the B1 lock reasoning has a hole.
     *
     * <p>Real history under the range model is append-only — existing real rows are correct and must
     * NOT be deleted/rewritten. The engine view {@code messages} is the DERIVED view: a covered run
     * shows up as one injected {@code Message.user(activeSummaryText)} (provenance -1). We append ONLY
     * the engine-view tail BEYOND the matched prefix ({@code prefixLen}, derived-view index space),
     * minus any injected summary in that tail — never touching existing rows. This preserves the
     * legacy "append the divergent delta" behavior while structurally refusing to persist a summary
     * string as a NORMAL row.
     *
     * <p>This is a conservative backstop: it never deletes a real row and never writes a summary row.
     * In the (lock-prevented) mismatch case the appended delta may, in the worst case, duplicate a
     * real turn that was already stored — strictly preferable to leaking a summary into the
     * user-visible history.
     *
     * <p><b>Accepted limitation (content-string match)</b>: {@link #isInjectedSummary} identifies an
     * injected summary by exact text match against the active summary texts. A real USER turn whose
     * text happens to equal an active summary's text would be filtered (silently dropped) here. This
     * needs BOTH the B1-lock-miss race AND a USER-role text collision with a (typically long,
     * LLM-generated) summary — double-rare. When a filter happens we {@code log.warn} so a real-message
     * drop is observable rather than silent. The full fix is the §3 provenance array (tag each engine
     * message with its origin so no content heuristic is needed); not done at P2b-1.
     */
    private void rangeModelSummarySafeReconcile(String id, List<Message> messages, int prefixLen,
                                                String traceId) {
        java.util.Set<String> activeSummaryTexts = new java.util.HashSet<>();
        List<SessionSummaryEntity> active = sessionSummaryRepository
                .findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(id);
        for (SessionSummaryEntity s : active) {
            if (s.getSummaryText() != null) {
                activeSummaryTexts.add(s.getSummaryText());
            }
        }
        int from = Math.max(0, prefixLen);
        if (messages == null || from >= messages.size()) {
            return; // nothing beyond the prefix to append
        }
        List<AppendMessage> wraps = new ArrayList<>(messages.size() - from);
        for (int i = from; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (isInjectedSummary(m, activeSummaryTexts)) {
                // INV-4: never persist an injected summary as a NORMAL row. Log the filter so a
                // possible false-positive (a real user turn whose text collides with a summary) is
                // observable, not a silent drop. See "Accepted limitation" in the javadoc above.
                log.warn("rangeModelSummarySafeReconcile: filtered a USER message matching an active "
                        + "summary text (possible false-positive if a real user turn); sessionId={}", id);
                continue;
            }
            wraps.add(new AppendMessage(m, MSG_TYPE_NORMAL, MESSAGE_TYPE_NORMAL,
                    null, null, Collections.emptyMap(), traceId));
        }
        if (!wraps.isEmpty()) {
            appendRowsOnce(id, wraps);
        }
    }

    /** True when {@code m} is a String-content user message whose text equals an active summary. */
    private boolean isInjectedSummary(Message m, java.util.Set<String> activeSummaryTexts) {
        if (m == null || m.getRole() != Message.Role.USER || activeSummaryTexts.isEmpty()) {
            return false;
        }
        Object content = m.getContent();
        return content instanceof String s && activeSummaryTexts.contains(s);
    }

    public SessionEntity saveSession(SessionEntity session) {
        return sessionRepository.save(session);
    }

    // ============ OBS-4: active_root_trace_id 读写接口 ============

    /**
     * OBS-4 §2.5: 读 session.active_root_trace_id (跨 agent / 跨 session trace 串联根)。
     * <p>NULL = 老 session 或当前无 active 处理流程。ChatService 在每次 user message 边界
     * 处由 chatAsync 清空，主 agent 首个 trace 创建时回填为自身 trace_id；spawn child
     * 时由 spawnMember 复制父 session 的当前值给 child。
     */
    @Transactional(readOnly = true)
    public String getActiveRootTraceId(String sessionId) {
        return sessionRepository.findById(sessionId)
                .map(SessionEntity::getActiveRootTraceId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    /**
     * OBS-4 §2.5: 写 session.active_root_trace_id。
     * <p>由 ChatService 4-arg preserveActiveRoot=true 路径在 active_root 缺失时 defensive
     * 回填使用（正常路径已通过 spawnMember 或 allocateNewRootTraceId 设好）。
     * 失败抛 {@link SessionNotFoundException}，调用方负责回滚整个 user message 处理。
     */
    @Transactional
    public void setActiveRootTraceId(String sessionId, String rootTraceId) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        session.setActiveRootTraceId(rootTraceId);
        sessionRepository.save(session);
    }

    /**
     * OBS-4 §2.5: 清空 session.active_root_trace_id。
     * <p>常规 user message 边界**不**调本方法 — 走 {@link #allocateNewRootTraceId(String, String)}
     * 单事务原子重置 (W2/W3)。本方法保留供未来 admin / cleanup / test 场景调用。
     */
    @Transactional
    public void clearActiveRootTraceId(String sessionId) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        session.setActiveRootTraceId(null);
        sessionRepository.save(session);
    }

    /**
     * OBS-4 §2.5 (W2/W3 r1 fix): user-message-boundary 原子重置 + 设新 root。
     *
     * <p>单 {@code @Transactional} 内 load → set activeRootTraceId = newRootTraceId → save，
     * 消除原 clear-then-set 三事务模式（reviewer r1 W2/W3）：
     * <ul>
     *   <li>原子性：tech-design §2.1 的"清空 + 设新值放在同一事务内"声明现在与实现一致</li>
     *   <li>消除冗余 DB read：clear 后立即 get 必然 null 的多余查询不再发生</li>
     *   <li>窗口期消除：clear 与 set 之间的 active_root=NULL 可见窗口不再存在</li>
     * </ul>
     *
     * <p>由 {@link com.skillforge.server.service.ChatService#chatAsync(String, String, Long)}
     * 3-arg 入口（真实 user message 边界，{@code preserveActiveRoot=false}）调用。
     * 4-arg {@code preserveActiveRoot=true} 路径不调本方法（直接 read 已有 active_root 继承）。
     *
     * <p>幂等性：重复用同一 newRootTraceId 调用是 effective no-op（最终值相同）。
     *
     * @param sessionId        session id
     * @param newRootTraceId   即将作为本 user message 主 agent 第一个 trace 的 root_trace_id
     *                         （= 该 trace 自身的 trace_id）
     */
    @Transactional
    public void allocateNewRootTraceId(String sessionId, String newRootTraceId) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        session.setActiveRootTraceId(newRootTraceId);
        sessionRepository.save(session);
    }

    public record DeleteSkipped(String id, String reason) {}

    public record DeleteResult(int deleted, List<DeleteSkipped> skipped) {}

    /**
     * 单删 session。悲观锁防 TOCTOU：SELECT FOR UPDATE 后检查状态再删。
     * 依赖 V18 的 ON DELETE CASCADE 级联清理 t_session_message / t_session_compaction_checkpoint。
     */
    @Transactional
    public void deleteSession(String id) {
        SessionEntity session = sessionRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        if ("running".equals(session.getRuntimeStatus())) {
            throw new IllegalStateException("Cannot delete a running session");
        }
        Long userId = session.getUserId();
        // 子 session 的 parent_session_id 无 FK CASCADE，先置 null 避免悬空引用
        List<SessionEntity> children = sessionRepository.findByParentSessionId(id);
        for (SessionEntity child : children) {
            child.setParentSessionId(null);
        }
        sessionRepository.saveAll(children);
        sessionRepository.deleteById(id);
        // Q2 BE-W1: drop singleton-scoped reminder debounce state for this session.
        if (reminderBuilder != null) {
            reminderBuilder.clearSession(id);
        }
        scheduleDeletedBroadcast(userId, id);
    }

    /**
     * 批量删除 session：能删的删，running 或非本用户的跳过，永不抛错。
     * 若 ownerUserId 非 null，只删归属该用户的 session，其它视为 not_found（避免信息泄漏）。
     */
    @Transactional
    public DeleteResult deleteSessions(List<String> ids, Long ownerUserId) {
        if (ids == null || ids.isEmpty()) {
            return new DeleteResult(0, Collections.emptyList());
        }
        // 批量加载，避免 N+1
        Map<String, SessionEntity> sessionMap = new HashMap<>();
        for (SessionEntity s : sessionRepository.findAllById(ids)) {
            sessionMap.put(s.getId(), s);
        }

        List<DeleteSkipped> skipped = new ArrayList<>();
        Map<String, Long> toDelete = new LinkedHashMap<>(); // sessionId → userId
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                skipped.add(new DeleteSkipped(id != null ? id : "", "invalid_id"));
                continue;
            }
            SessionEntity session = sessionMap.get(id);
            if (session == null) {
                skipped.add(new DeleteSkipped(id, "not_found"));
                continue;
            }
            if (ownerUserId != null && !ownerUserId.equals(session.getUserId())) {
                skipped.add(new DeleteSkipped(id, "not_found"));
                continue;
            }
            if ("running".equals(session.getRuntimeStatus())) {
                skipped.add(new DeleteSkipped(id, "running"));
                continue;
            }
            toDelete.put(id, session.getUserId());
        }
        if (!toDelete.isEmpty()) {
            // 子 session 置 null，避免悬空引用
            for (String id : toDelete.keySet()) {
                List<SessionEntity> children = sessionRepository.findByParentSessionId(id);
                for (SessionEntity child : children) {
                    child.setParentSessionId(null);
                }
                sessionRepository.saveAll(children);
            }
            sessionRepository.deleteAllById(toDelete.keySet());
            // Q2 BE-W1: drop singleton-scoped reminder debounce state for each deleted session.
            if (reminderBuilder != null) {
                for (String deletedId : toDelete.keySet()) {
                    reminderBuilder.clearSession(deletedId);
                }
            }
            toDelete.forEach((sessionId, userId) -> scheduleDeletedBroadcast(userId, sessionId));
        }
        return new DeleteResult(toDelete.size(), skipped);
    }

    /** 在事务提交后广播 session_deleted，防止事务回滚时幽灵通知。 */
    private void scheduleDeletedBroadcast(Long userId, String sessionId) {
        if (broadcaster == null || userId == null) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcastSessionDeleted(userId, sessionId);
                    }
                });
    }

    private void broadcastSessionDeleted(Long userId, String sessionId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "session_deleted");
            payload.put("sessionId", sessionId);
            broadcaster.userEvent(userId, payload);
        } catch (Exception e) {
            log.warn("session_deleted broadcast failed: sessionId={} err={}", sessionId, e.getMessage());
        }
    }

    public void archiveSession(String id) {
        SessionEntity session = getSession(id);
        session.setStatus("archived");
        sessionRepository.save(session);
        if (broadcaster != null) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", "session_deleted");
                payload.put("sessionId", id);
                broadcaster.userEvent(session.getUserId(), payload);
            } catch (Throwable t) {
                log.debug("session_deleted broadcast skipped: {}", t.getMessage());
            }
        }
    }

    public List<Message> getSessionMessages(String id) {
        return getFullHistory(id);
    }

    public void saveSessionMessages(String id, List<Message> messages) {
        List<AppendMessage> wraps = new ArrayList<>();
        if (messages != null && !messages.isEmpty()) {
            wraps = new ArrayList<>(messages.size());
            for (Message message : messages) {
                wraps.add(new AppendMessage(message, MSG_TYPE_NORMAL, Collections.emptyMap()));
            }
        }
        rewriteMessages(id, wraps);
    }

    public void rewriteMessages(String id, List<AppendMessage> messages) {
        // 兼容旧调用：保留“覆盖写”语义（迁移完成后应逐步移除该入口）
        synchronized (lockForAppend(id)) {
            requiredTxTemplate.execute(status -> {
                List<AppendMessage> patched = messages;
                if (storeProperties.isRowWriteEnabled()) {
                    // OBS-2 Q1: snapshot existing trace_ids by seq_no BEFORE the
                    // DELETE+INSERT rewrite, then patch them onto rewritten rows
                    // whose AppendMessage.traceId is null. Index alignment:
                    // appendRowsOnce after deleteBySessionId starts base = -1, so
                    // the new row at list index i lands at seq_no = i — matching
                    // the old row at seq_no = i (project invariant: seq_no starts
                    // at 0, contiguous). Caller-provided non-null traceIds win.
                    //
                    // Concurrency: this snapshot must stay inside the
                    // synchronized(lockForAppend(id)) + outer REQUIRED tx. The
                    // stripe lock excludes other writers on the same session,
                    // and the inner REQUIRES_NEW used by rewriteRowsInNewTransaction
                    // is safe because the lock is already held when it runs.
                    // Moving snapshot outside the lock would open a race window
                    // where a parallel append/rewrite could change trace_ids
                    // between snapshot and the DELETE.
                    Map<Long, String> oldTraceIds = snapshotTraceIds(id);
                    patched = patchTraceIds(messages, oldTraceIds);
                    rewriteRowsInNewTransaction(id, patched);
                    // P2b B2: DELETE+INSERT drops compacted_by_summary_id on every re-inserted row
                    // (AppendMessage carries no marker). Re-derive markers from the active summary
                    // ranges so the derived model view keeps collapsing covered rows. No-op when the
                    // range-model flag is OFF / no summaries exist.
                    //
                    // NOTE: restoreFromCheckpoint / createBranchFromCheckpoint must adjust their
                    // summaries (prune post-checkpoint summaries on restore; copy/remap on branch)
                    // BEFORE the markers are correct. They run their summary-fixup + an explicit
                    // recomputeCompactedMarkers INSIDE the same transaction as this rewrite (the
                    // checkpoint flow wraps rewriteMessages in runInTransaction), so the recompute
                    // here uses the active-summary set as it stands at rewrite time. For restore the
                    // post-checkpoint summary prune happens after rewrite in the same tx, so
                    // CompactionService calls recomputeCompactedMarkers again post-prune; the extra
                    // call here is a harmless (idempotent) first pass. For branch the summaries are
                    // copied post-rewrite, so this first pass is a no-op (no branch summaries yet)
                    // and CompactionService's post-copy recompute does the real work.
                    recomputeCompactedMarkers(id);
                }
                SessionEntity session = getSession(id);
                if (patched == null || patched.isEmpty()) {
                    session.setMessagesJson("[]");
                } else {
                    List<Message> plainMessages = new ArrayList<>(patched.size());
                    for (AppendMessage appendMessage : patched) {
                        plainMessages.add(appendMessage.message());
                    }
                    session.setMessagesJson(writeJsonSafely(plainMessages));
                }
                session.setMessageCount(storeProperties.isRowWriteEnabled()
                        ? (int) sessionMessageRepository.countBySessionId(id)
                        : (patched == null ? 0 : patched.size()));
                sessionRepository.save(session);
                return null;
            });
        }
    }

    /**
     * OBS-2 Q1: snapshot existing (seq_no → trace_id) for rows whose trace_id is
     * non-null. Used by {@link #rewriteMessages} to preserve trace_ids across the
     * DELETE+INSERT rewrite that previously wiped the column.
     */
    private Map<Long, String> snapshotTraceIds(String sessionId) {
        if (sessionMessageRepository == null) {
            return Collections.emptyMap();
        }
        List<SessionMessageRepository.TraceIdView> rows =
                sessionMessageRepository.findNonNullTraceIdProjections(sessionId);
        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> map = new HashMap<>(rows.size() * 2);
        for (SessionMessageRepository.TraceIdView v : rows) {
            map.put(v.getSeqNo(), v.getTraceId());
        }
        return map;
    }

    /**
     * OBS-2 Q1: copy each {@link AppendMessage} forward, replacing only those
     * whose {@code traceId} is null with the snapshot value at index = seq_no.
     * Caller-provided non-null traceIds (e.g. boundary-preservation 7-arg path)
     * are never overwritten. Returns the original list reference unchanged when
     * no patch applies (avoids extra allocation in the common no-trace case).
     */
    private List<AppendMessage> patchTraceIds(List<AppendMessage> messages,
                                              Map<Long, String> oldTraceIds) {
        // Known limitation (OBS-2 Q1): index-aligned patching is only precise
        // when the rewrite preserves message count (e.g. light Rule 1
        // truncate-large-tool-output mutates blocks in place). Light Rule 2/3/4
        // (dedupConsecutiveTools / foldFailedRetries / dropEmptyNarration) call
        // working.remove() and shrink the list — newList[i] can then come from
        // oldSeqNo>i, so the patched trace_id may bind to the "wrong" turn.
        // Accepted trade-off: still strictly better than the prior 100% NULL
        // wipe. A precise fix needs CompactResult to carry seq_no identity per
        // surviving message instead of relying on list index.
        if (messages == null || messages.isEmpty()
                || oldTraceIds == null || oldTraceIds.isEmpty()) {
            return messages;
        }
        List<AppendMessage> out = null;
        for (int i = 0; i < messages.size(); i++) {
            AppendMessage am = messages.get(i);
            String preserved = (am.traceId() == null) ? oldTraceIds.get((long) i) : null;
            if (preserved != null) {
                if (out == null) {
                    out = new ArrayList<>(messages);
                }
                out.set(i, new AppendMessage(am.message(), am.msgType(), am.messageType(),
                        am.controlId(), am.answeredAt(), am.metadata(), preserved));
            }
        }
        return out != null ? out : messages;
    }

    /**
     * OBS-2 Q1: return the trace_ids of the last {@code n} rows of {@code
     * sessionId}, ordered by seq_no ASC. Slots whose row had a null trace_id are
     * preserved as null in the result so the list size matches {@code n} (or
     * fewer when the session has &lt; n rows). Returns an empty list when
     * {@code n &lt;= 0} or row-store is disabled.
     *
     * <p>Used by {@code CompactionService.persistCompactResult} to copy the
     * original tail trace_ids onto the freshly-appended retained block during a
     * full compact. Must be called BEFORE the new compact rows are appended,
     * because once appended the "last N" no longer points at the source rows.
     */
    public List<String> findTailTraceIds(String sessionId, int n) {
        if (n <= 0 || sessionMessageRepository == null
                || storeProperties == null || !storeProperties.isRowWriteEnabled()) {
            return Collections.emptyList();
        }
        List<SessionMessageRepository.TraceIdView> rows = sessionMessageRepository
                .findTailTraceIdProjections(sessionId, PageRequest.of(0, n));
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        // rows are seq_no DESC; reverse to ASC for caller (matches retained[i] ↔ tail[i]).
        List<String> out = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            out.add(rows.get(i).getTraceId());
        }
        return out;
    }

    public List<SessionEntity> listByCollabRunId(String collabRunId) {
        return sessionRepository.findByCollabRunId(collabRunId);
    }

    /**
     * Creates a channel-driven session and applies default user fallback.
     */
    public String createChannelSession(Long agentId, String title) {
        return createChannelSession(agentId, title, null);
    }

    /**
     * Creates a channel-driven session with an explicit SkillForge user owner.
     * Unknown identity mapping falls back to {@link #DEFAULT_CHANNEL_USER_ID}.
     */
    public String createChannelSession(Long agentId, String title, Long mappedUserId) {
        Long effectiveUserId = resolveChannelSessionUserId(agentId, mappedUserId);
        if (mappedUserId == null) {
            log.info("Channel user mapping missing, fallback user resolved. agentId={}, fallbackUserId={}",
                    agentId, effectiveUserId);
        }
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(effectiveUserId);
        session.setAgentId(agentId);
        session.setTitle(title != null ? title : "Channel session");
        session.setMessagesJson("[]");
        session.setLastUserMessageAt(java.time.Instant.now());
        AgentEntity agent = agentRepository.findById(agentId).orElse(null);
        if (agent != null && agent.getExecutionMode() != null) {
            session.setExecutionMode(agent.getExecutionMode());
        }
        SessionEntity saved = sessionRepository.save(session);
        return saved.getId();
    }

    private Long resolveChannelSessionUserId(Long agentId, Long mappedUserId) {
        if (mappedUserId != null) {
            return mappedUserId;
        }
        AgentEntity agent = agentRepository.findById(agentId).orElse(null);
        if (agent != null && agent.getOwnerId() != null) {
            return agent.getOwnerId();
        }
        return DEFAULT_CHANNEL_USER_ID;
    }

    /**
     * True when a session is active and idle/waiting — i.e. safe to enqueue a new
     * channel turn. Returns false for archived sessions, running sessions, or
     * unknown ids.
     */
    public boolean isChannelSessionActive(String sessionId) {
        return sessionRepository.findById(sessionId)
                .map(s -> "active".equals(s.getStatus())
                        && (s.getRuntimeStatus() == null
                            || "idle".equals(s.getRuntimeStatus())
                            || "waiting_user".equals(s.getRuntimeStatus())))
                .orElse(false);
    }

    private List<SessionMessageEntity> loadAllMessageRows(String sessionId) {
        List<SessionMessageEntity> all = new ArrayList<>();
        int page = 0;
        while (true) {
            Page<SessionMessageEntity> p = sessionMessageRepository
                    .findBySessionIdOrderBySeqNoAsc(sessionId, PageRequest.of(page, 500));
            if (p.isEmpty()) {
                break;
            }
            all.addAll(p.getContent());
            if (!p.hasNext()) {
                break;
            }
            page++;
        }
        return all;
    }

    private List<SessionMessageEntity> loadAllMessageRowsInReadTx(String sessionId) {
        List<SessionMessageEntity> rows = readOnlyTxTemplate.execute(status -> loadAllMessageRows(sessionId));
        return rows != null ? rows : Collections.emptyList();
    }

    private List<StoredMessage> toStoredMessages(List<SessionMessageEntity> entities) {
        List<StoredMessage> out = new ArrayList<>(entities.size());
        for (SessionMessageEntity e : entities) {
            Message message = new Message();
            message.setRole(parseRole(e.getRole()));
            Object content = readJsonSafely(e.getContentJson());
            if (e.getPrunedAt() != null) {
                content = sanitizePrunedContent(content);
            }
            message.setContent(content);
            message.setReasoningContent(e.getReasoningContent());
            Map<String, Object> metadata = new HashMap<>(readMapJsonSafely(e.getMetadataJson()));
            if (e.getPrunedAt() != null) {
                metadata.put("pruned", true);
                metadata.put("pruned_at", e.getPrunedAt().toString());
            }
            String messageType = e.getMessageType() == null || e.getMessageType().isBlank()
                    ? MESSAGE_TYPE_NORMAL : e.getMessageType();
            out.add(new StoredMessage(
                    e.getSeqNo(),
                    e.getMsgType(),
                    messageType,
                    e.getControlId(),
                    e.getAnsweredAt(),
                    metadata,
                    message,
                    e.getTraceId(),
                    e.getCreatedAt(),
                    e.getCompactedBySummaryId()));
        }
        return out;
    }

    private long getLastSeqNo(String sessionId) {
        return sessionMessageRepository.findTopBySessionIdOrderBySeqNoDesc(sessionId)
                .map(SessionMessageEntity::getSeqNo)
                .orElse(-1L);
    }

    private String toRoleString(Message message) {
        if (message.getRole() == null) {
            return "user";
        }
        return switch (message.getRole()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
        };
    }

    private Message.Role parseRole(String rawRole) {
        if (rawRole == null) {
            return Message.Role.USER;
        }
        return switch (rawRole.toLowerCase()) {
            case "assistant" -> Message.Role.ASSISTANT;
            case "system" -> Message.Role.SYSTEM;
            default -> Message.Role.USER;
        };
    }

    private Object readJsonSafely(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize message content JSON: {}", e.getMessage());
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMapJsonSafely(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize metadata JSON: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    private String writeJsonSafely(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize json payload", e);
        }
    }

    private Object sanitizePrunedContent(Object content) {
        if (!(content instanceof List<?> blocks)) {
            return content;
        }
        List<Object> sanitized = new ArrayList<>(blocks.size());
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> map) {
                String type = map.get("type") != null ? map.get("type").toString() : "";
                if ("tool_result".equals(type)) {
                    Map<String, Object> cloned = new HashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() != null) {
                            cloned.put(entry.getKey().toString(), entry.getValue());
                        }
                    }
                    cloned.put("content", "[TOOL OUTPUT PRUNED]");
                    sanitized.add(cloned);
                } else {
                    sanitized.add(map);
                }
                continue;
            }
            if (block instanceof ContentBlock contentBlock
                    && "tool_result".equals(contentBlock.getType())) {
                sanitized.add(ContentBlock.toolResult(
                        contentBlock.getToolUseId(),
                        "[TOOL OUTPUT PRUNED]",
                        Boolean.TRUE.equals(contentBlock.getIsError())));
                continue;
            }
            sanitized.add(block);
        }
        return sanitized;
    }

    private void writeLegacyOnly(String id, List<Message> messages) {
        SessionEntity session = getSession(id);
        session.setMessagesJson(writeJsonSafely(messages));
        session.setMessageCount(messages.size());
        sessionRepository.save(session);
    }

    private List<Message> readLegacyMessagesJson(String id) {
        SessionEntity session = getSession(id);
        String json = session.getMessagesJson();
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Message>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize legacy messagesJson for session {}: {}", id, e.getMessage());
            return new ArrayList<>();
        }
    }

    private boolean shouldPruneToolOutput(SessionMessageEntity entity) {
        if (!MSG_TYPE_NORMAL.equals(entity.getMsgType())) {
            return false;
        }
        Object content = readJsonSafely(entity.getContentJson());
        if (!(content instanceof List<?> blocks)) {
            return false;
        }
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> map) {
                if ("tool_result".equals(map.get("type"))) {
                    return true;
                }
            } else if (block instanceof ContentBlock contentBlock
                    && "tool_result".equals(contentBlock.getType())) {
                return true;
            }
        }
        return false;
    }

    private String mergePrunedMetadata(String metadataJson) {
        Map<String, Object> metadata = new HashMap<>(readMapJsonSafely(metadataJson));
        metadata.put("pruned", true);
        metadata.put("prune_reason", "tool_output");
        metadata.put("pruned_at", Instant.now().toString());
        return writeJsonSafely(metadata);
    }

    private Object lockForAppend(String sessionId) {
        return appendLockStripes[Math.floorMod(sessionId.hashCode(), APPEND_LOCK_STRIPES)];
    }

    private long appendInNewTransaction(String id, List<AppendMessage> messages) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return appendRowsOnce(id, messages);
        }
        Long seq = requiresNewTxTemplate.execute(status -> appendRowsOnce(id, messages));
        return seq != null ? seq : getLastSeqNo(id);
    }

    private void rewriteRowsInNewTransaction(String id, List<AppendMessage> messages) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            sessionMessageRepository.deleteBySessionId(id);
            if (messages != null && !messages.isEmpty()) {
                appendRowsOnce(id, messages);
            }
            return;
        }
        requiresNewTxTemplate.execute(status -> {
            sessionMessageRepository.deleteBySessionId(id);
            if (messages != null && !messages.isEmpty()) {
                appendRowsOnce(id, messages);
            }
            return null;
        });
    }

    private long appendRowsOnce(String id, List<AppendMessage> messages) {
        long base = getLastSeqNo(id);
        Instant now = Instant.now();
        List<SessionMessageEntity> entities = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            AppendMessage append = messages.get(i);
            SessionMessageEntity e = new SessionMessageEntity();
            e.setSessionId(id);
            e.setSeqNo(base + i + 1);
            e.setRole(toRoleString(append.message()));
            e.setMsgType(append.msgType());
            e.setMessageType(append.messageType());
            e.setControlId(append.controlId());
            e.setAnsweredAt(append.answeredAt());
            e.setContentJson(writeJsonSafely(append.message().getContent()));
            e.setReasoningContent(append.message().getReasoningContent());
            e.setMetadataJson(writeJsonSafely(append.metadata()));
            e.setCreatedAt(now);
            // OBS-2 M1: persist trace_id (may be null for legacy / pre-trace paths)
            e.setTraceId(append.traceId());
            entities.add(e);
        }
        sessionMessageRepository.saveAll(entities);
        return base + messages.size();
    }

    private List<StoredMessage> legacyToStoredMessages(List<Message> legacy) {
        List<StoredMessage> out = new ArrayList<>(legacy.size());
        for (int i = 0; i < legacy.size(); i++) {
            out.add(new StoredMessage(i, MSG_TYPE_NORMAL, Collections.emptyMap(), legacy.get(i)));
        }
        return out;
    }

    private void verifyDualReadConsistency(String sessionId,
                                           List<StoredMessage> rowRecords,
                                           List<StoredMessage> legacyRecords) {
        if (rowRecords.isEmpty()) {
            log.warn("Dual-read mismatch(session={}): row store empty but legacy has {} messages",
                    sessionId, legacyRecords.size());
            return;
        }
        boolean hasNonNormalType = rowRecords.stream()
                .anyMatch(m -> !MSG_TYPE_NORMAL.equals(m.msgType()));
        if (hasNonNormalType) {
            // 行存储已经包含边界/摘要语义，legacy CLOB 不再等价，跳过校验避免噪声。
            return;
        }
        if (rowRecords.size() != legacyRecords.size()) {
            log.warn("Dual-read mismatch(session={}): row={} legacy={}",
                    sessionId, rowRecords.size(), legacyRecords.size());
            return;
        }
        int mismatchAt = -1;
        for (int i = 0; i < legacyRecords.size(); i++) {
            Message r = rowRecords.get(i).message();
            Message l = legacyRecords.get(i).message();
            if (!messageEquals(r, l)) {
                mismatchAt = i;
                break;
            }
        }
        if (mismatchAt >= 0) {
            log.warn("Dual-read mismatch(session={}): first mismatch index={}", sessionId, mismatchAt);
        }
    }

    private int commonPrefixSize(List<Message> a, List<Message> b) {
        if (a == null || b == null) {
            return 0;
        }
        int n = Math.min(a.size(), b.size());
        int i = 0;
        while (i < n && messageEquals(a.get(i), b.get(i))) {
            i++;
        }
        return i;
    }

    private boolean messageEquals(Message m1, Message m2) {
        if (m1 == null || m2 == null) {
            return m1 == m2;
        }
        if (m1.getRole() != m2.getRole()) {
            return false;
        }
        try {
            String c1 = objectMapper.writeValueAsString(m1.getContent());
            String c2 = objectMapper.writeValueAsString(m2.getContent());
            return Objects.equals(c1, c2);
        } catch (JsonProcessingException e) {
            return Objects.equals(m1.getTextContent(), m2.getTextContent());
        }
    }
}
