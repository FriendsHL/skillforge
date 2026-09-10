package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionMessageInboxEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionMessageInboxRepository extends JpaRepository<SessionMessageInboxEntity, Long> {

    Optional<SessionMessageInboxEntity> findBySessionIdAndInboxId(String sessionId, UUID inboxId);

    List<SessionMessageInboxEntity> findBySessionIdOrderByIdAsc(String sessionId, Pageable pageable);

    long countBySessionId(String sessionId);
}
