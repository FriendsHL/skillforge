package com.skillforge.server.service;

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
    private final ContextRuntimeSnapshotCodec codec;

    public JpaContextRuntimeStore(
            SessionRepository sessionRepository,
            ObjectMapper objectMapper) {
        this(sessionRepository, new ContextRuntimeSnapshotCodec(objectMapper));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JpaContextRuntimeStore(
            SessionRepository sessionRepository,
            ContextRuntimeSnapshotCodec codec) {
        this.sessionRepository = sessionRepository;
        this.codec = codec;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContextRuntimeSnapshot> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        ContextRuntimeSnapshot snapshot = sessionRepository.findContextRuntimeJsonById(sessionId)
                .map(codec::decodeOrEmpty)
                .orElseGet(ContextRuntimeSnapshot::empty);
        return Optional.of(snapshot);
    }

    @Override
    @Transactional
    public void save(String sessionId, ContextRuntimeSnapshot snapshot) {
        if (sessionId == null || sessionId.isBlank() || snapshot == null) return;
        String json = codec.encode(snapshot);
        int updated = sessionRepository.updateContextRuntimeJson(sessionId, json);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Session not found while saving context runtime: " + sessionId);
        }
    }
}
