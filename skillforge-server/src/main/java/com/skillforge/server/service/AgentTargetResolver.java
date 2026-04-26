package com.skillforge.server.service;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
        AgentEntity target = resolveTarget(author, targetAgentId, targetAgentName);
        if (!isVisible(author, target)) {
            throw new IllegalArgumentException("target agent is not visible: id=" + target.getId());
        }
        return target;
    }

    @Transactional(readOnly = true)
    public List<AgentEntity> listVisibleTargets(String sessionId, String query) {
        Long authorAgentId = authorAgentIdForSession(sessionId);
        AgentEntity author = agentRepository.findById(authorAgentId)
                .orElseThrow(() -> new IllegalArgumentException("author agent not found: id=" + authorAgentId));
        String q = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        return agentRepository.findAll().stream()
                .filter(target -> isVisible(author, target))
                .filter(target -> q.isBlank() || matchesQuery(target, q))
                .sorted(Comparator
                        .comparing((AgentEntity a) -> !authorAgentId.equals(a.getId()))
                        .thenComparing(a -> safeLower(a.getName())))
                .toList();
    }

    private AgentEntity resolveTarget(AgentEntity author, Object targetAgentId, Object targetAgentName) {
        if (targetAgentId != null) {
            Long id = toLong(targetAgentId, "targetAgentId");
            return agentRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("target agent not found: id=" + id));
        }
        String name = targetAgentName != null ? targetAgentName.toString().trim() : null;
        if (name != null && !name.isBlank()) {
            List<AgentEntity> visible = agentRepository.findAll().stream()
                    .filter(a -> isVisible(author, a))
                    .toList();
            List<AgentEntity> matches = visible.stream()
                    .filter(a -> a.getName() != null && a.getName().equalsIgnoreCase(name))
                    .toList();
            if (matches.isEmpty()) {
                String needle = name.toLowerCase(Locale.ROOT);
                matches = visible.stream()
                        .filter(a -> matchesQuery(a, needle))
                        .toList();
            }
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("target agent not found: name=" + name);
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException("targetAgentName is ambiguous: " + name);
            }
            return matches.get(0);
        }
        return author;
    }

    public static boolean isVisible(AgentEntity author, AgentEntity target) {
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

    private static boolean matchesQuery(AgentEntity target, String q) {
        if (target == null || q == null || q.isBlank()) {
            return true;
        }
        return safeLower(target.getName()).contains(q)
                || safeLower(target.getDescription()).contains(q)
                || String.valueOf(target.getId()).equals(q);
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
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
