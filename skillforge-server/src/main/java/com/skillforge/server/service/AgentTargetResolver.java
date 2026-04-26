package com.skillforge.server.service;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AgentTargetResolver {

    private final AgentRepository agentRepository;
    private final SessionRepository sessionRepository;

    public AgentTargetResolver(AgentRepository agentRepository, SessionRepository sessionRepository) {
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
    }

    @Transactional(readOnly = true)
    public Long authorAgentIdForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
        return session.getAgentId();
    }

    @Transactional(readOnly = true)
    public AgentEntity resolveVisibleTarget(String sessionId, Object targetAgentId, Object targetAgentName) {
        Long authorAgentId = authorAgentIdForSession(sessionId);
        AgentEntity author = agentRepository.findById(authorAgentId)
                .orElseThrow(() -> new IllegalArgumentException("author agent not found: id=" + authorAgentId));
        AgentEntity target = resolveTarget(authorAgentId, targetAgentId, targetAgentName);
        if (!isVisible(author, target)) {
            throw new IllegalArgumentException("target agent is not visible: id=" + target.getId());
        }
        return target;
    }

    private AgentEntity resolveTarget(Long defaultAgentId, Object targetAgentId, Object targetAgentName) {
        if (targetAgentId != null) {
            Long id = toLong(targetAgentId, "targetAgentId");
            return agentRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("target agent not found: id=" + id));
        }
        String name = targetAgentName != null ? targetAgentName.toString().trim() : null;
        if (name != null && !name.isBlank()) {
            List<AgentEntity> matches = agentRepository.findAll().stream()
                    .filter(a -> a.getName() != null && a.getName().equalsIgnoreCase(name))
                    .toList();
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("target agent not found: name=" + name);
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException("targetAgentName is ambiguous: " + name);
            }
            return matches.get(0);
        }
        return agentRepository.findById(defaultAgentId)
                .orElseThrow(() -> new IllegalArgumentException("author agent not found: id=" + defaultAgentId));
    }

    private static boolean isVisible(AgentEntity author, AgentEntity target) {
        if (author == null || target == null) {
            return false;
        }
        if (!"active".equalsIgnoreCase(target.getStatus())) {
            return false;
        }
        if (author.getId() != null && author.getId().equals(target.getId())) {
            return true;
        }
        if (Boolean.TRUE.equals(target.isPublic())) {
            return true;
        }
        Long authorOwner = author.getOwnerId();
        Long targetOwner = target.getOwnerId();
        return authorOwner != null && authorOwner.equals(targetOwner);
    }

    private static Long toLong(Object value, String label) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid " + label + ": " + value);
        }
    }
}
