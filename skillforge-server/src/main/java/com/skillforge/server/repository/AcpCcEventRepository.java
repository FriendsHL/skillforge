package com.skillforge.server.repository;

import com.skillforge.server.entity.AcpCcEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link AcpCcEventEntity} (ACP-EXTERNAL-AGENT P2-1) — normalized
 * OTLP cc events bound to a SkillForge cc sub-session.
 */
public interface AcpCcEventRepository extends JpaRepository<AcpCcEventEntity, Long> {

    /** All events for a cc sub-session in arrival order (id is monotonic). */
    List<AcpCcEventEntity> findBySessionIdOrderByIdAsc(String sessionId);

    long countBySessionId(String sessionId);
}
