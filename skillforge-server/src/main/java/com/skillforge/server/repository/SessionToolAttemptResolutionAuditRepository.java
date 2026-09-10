package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionToolAttemptResolutionAuditEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Insert/read only application repository; no mutating query is exposed. */
public interface SessionToolAttemptResolutionAuditRepository
        extends Repository<SessionToolAttemptResolutionAuditEntity, Long> {

    <S extends SessionToolAttemptResolutionAuditEntity> S save(S entity);

    Optional<SessionToolAttemptResolutionAuditEntity> findById(Long id);

    Optional<SessionToolAttemptResolutionAuditEntity> findByResolutionRequestId(
            UUID resolutionRequestId);

    List<SessionToolAttemptResolutionAuditEntity> findBySessionIdOrderByCreatedAtAsc(
            String sessionId, Pageable pageable);
}
