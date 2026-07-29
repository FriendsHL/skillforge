package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.runtime.ContextRuntimeSnapshot;
import com.skillforge.core.context.runtime.ContextRuntimeStore;
import com.skillforge.server.repository.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Session-scoped durable store for content-free context runtime checkpoints.
 */
@Service
public class JpaContextRuntimeStore implements ContextRuntimeStore {

    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    public JpaContextRuntimeStore(
            SessionRepository sessionRepository,
            ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContextRuntimeSnapshot> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        return sessionRepository.findContextRuntimeJsonById(sessionId)
                .filter(json -> !json.isBlank())
                .map(this::readSnapshot);
    }

    @Override
    @Transactional
    public void save(String sessionId, ContextRuntimeSnapshot snapshot) {
        if (sessionId == null || sessionId.isBlank() || snapshot == null) return;
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            int updated = sessionRepository.updateContextRuntimeJson(sessionId, json);
            if (updated != 1) {
                throw new IllegalStateException(
                        "Session not found while saving context runtime: " + sessionId);
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize context runtime snapshot", ex);
        }
    }

    private ContextRuntimeSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, ContextRuntimeSnapshot.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot deserialize context runtime snapshot", ex);
        }
    }
}
