package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.dto.SessionMessageDto;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
        AgentEntity agent = agentRepository.findById(childAgentId).orElse(null);
        if (agent != null && agent.getExecutionMode() != null) {
            session.setExecutionMode(agent.getExecutionMode());
        }
        return sessionRepository.save(session);
    }

    public SessionEntity createSession(Long userId, Long agentId) {
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setAgentId(agentId);
        session.setTitle("New Session");
        session.setMessagesJson("[]");
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
        // 只返回顶层 session,SubAgent 派发的子 session 不污染主列表
        return sessionRepository.findByUserIdAndParentSessionIdIsNullOrderByUpdatedAtDesc(userId);
    }

    /** 列出某个 parent session 下的所有子 session(SubAgent 派发)。 */
    public List<SessionEntity> listChildSessions(String parentSessionId) {
        return sessionRepository.findByParentSessionId(parentSessionId);
    }

    public record AppendMessage(Message message, String msgType, Map<String, Object> metadata) {
        public AppendMessage {
            Objects.requireNonNull(message, "message");
            msgType = (msgType == null || msgType.isBlank()) ? MSG_TYPE_NORMAL : msgType;
            metadata = (metadata == null) ? Collections.emptyMap() : metadata;
        }
    }

    public record StoredMessage(long seqNo, String msgType, Map<String, Object> metadata, Message message) {
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
                    stored.metadata()
            ));
        }
        return out;
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
     */
    @Transactional(readOnly = true)
    public List<Message> getContextMessages(String id) {
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
            out.add(records.get(i).message());
        }
        return out;
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
        if (messages == null || messages.isEmpty()) {
            return getLastSeqNo(id);
        }
        List<AppendMessage> wraps = new ArrayList<>(messages.size());
        for (Message message : messages) {
            wraps.add(new AppendMessage(message, msgType, Collections.emptyMap()));
        }
        return appendMessages(id, wraps);
    }

    public long appendNormalMessages(String id, List<Message> messages) {
        return appendMessages(id, messages, MSG_TYPE_NORMAL);
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
        synchronized (lockForAppend(id)) {
            requiredTxTemplate.execute(status -> {
                List<Message> persistedContext = getContextMessages(id);
                int prefixLen = commonPrefixSize(persistedContext, messages);
                if (prefixLen == 0 && !persistedContext.isEmpty() && messages != null && !messages.isEmpty()) {
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
                        for (int i = 0; i <= lastBoundary; i++) {
                            StoredMessage stored = fullRecords.get(i);
                            wraps.add(new AppendMessage(stored.message(), stored.msgType(), stored.metadata()));
                        }
                        for (Message message : messages) {
                            wraps.add(new AppendMessage(message, MSG_TYPE_NORMAL, Collections.emptyMap()));
                        }
                        rewriteRowsInNewTransaction(id, wraps);
                    } else {
                        log.warn("updateSessionMessages prefix mismatch, fallback to full rewrite: sessionId={}", id);
                        List<AppendMessage> wraps = new ArrayList<>(messages.size());
                        for (Message message : messages) {
                            wraps.add(new AppendMessage(message, MSG_TYPE_NORMAL, Collections.emptyMap()));
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
                            wraps.add(new AppendMessage(message, MSG_TYPE_NORMAL, Collections.emptyMap()));
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

    public SessionEntity saveSession(SessionEntity session) {
        return sessionRepository.save(session);
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
                if (storeProperties.isRowWriteEnabled()) {
                    rewriteRowsInNewTransaction(id, messages);
                }
                SessionEntity session = getSession(id);
                if (messages == null || messages.isEmpty()) {
                    session.setMessagesJson("[]");
                } else {
                    List<Message> plainMessages = new ArrayList<>(messages.size());
                    for (AppendMessage appendMessage : messages) {
                        plainMessages.add(appendMessage.message());
                    }
                    session.setMessagesJson(writeJsonSafely(plainMessages));
                }
                session.setMessageCount(storeProperties.isRowWriteEnabled()
                        ? (int) sessionMessageRepository.countBySessionId(id)
                        : (messages == null ? 0 : messages.size()));
                sessionRepository.save(session);
                return null;
            });
        }
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
            Map<String, Object> metadata = new HashMap<>(readMapJsonSafely(e.getMetadataJson()));
            if (e.getPrunedAt() != null) {
                metadata.put("pruned", true);
                metadata.put("pruned_at", e.getPrunedAt().toString());
            }
            out.add(new StoredMessage(e.getSeqNo(), e.getMsgType(), metadata, message));
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
            e.setContentJson(writeJsonSafely(append.message().getContent()));
            e.setMetadataJson(writeJsonSafely(append.metadata()));
            e.setCreatedAt(now);
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
