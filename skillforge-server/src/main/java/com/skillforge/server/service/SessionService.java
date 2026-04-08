package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessionRepository;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;

    public SessionService(SessionRepository sessionRepository, AgentRepository agentRepository) {
        this.sessionRepository = sessionRepository;
        this.agentRepository = agentRepository;
        this.objectMapper = new ObjectMapper();
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
        // 创建时从 Agent 拷贝默认 executionMode
        AgentEntity agent = agentRepository.findById(agentId).orElse(null);
        if (agent != null && agent.getExecutionMode() != null) {
            session.setExecutionMode(agent.getExecutionMode());
        }
        return sessionRepository.save(session);
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

    public void updateSessionMessages(String id, List<Message> messages,
                                       long inputTokens, long outputTokens) {
        SessionEntity session = getSession(id);
        saveSessionMessages(id, messages);
        session = getSession(id);
        session.setMessageCount(messages.size());
        session.setTotalInputTokens(session.getTotalInputTokens() + inputTokens);
        session.setTotalOutputTokens(session.getTotalOutputTokens() + outputTokens);
        sessionRepository.save(session);
    }

    public SessionEntity saveSession(SessionEntity session) {
        return sessionRepository.save(session);
    }

    public void archiveSession(String id) {
        SessionEntity session = getSession(id);
        session.setStatus("archived");
        sessionRepository.save(session);
    }

    public List<Message> getSessionMessages(String id) {
        SessionEntity session = getSession(id);
        String json = session.getMessagesJson();
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Message>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize messages for session {}: {}", id, e.getMessage());
            return new ArrayList<>();
        }
    }

    public void saveSessionMessages(String id, List<Message> messages) {
        SessionEntity session = getSession(id);
        try {
            String json = objectMapper.writeValueAsString(messages);
            session.setMessagesJson(json);
            sessionRepository.save(session);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize messages for session {}: {}", id, e.getMessage());
            throw new RuntimeException("Failed to serialize session messages", e);
        }
    }
}
