package com.skillforge.server.repository;

import com.skillforge.server.entity.AgentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<AgentEntity, Long> {

    List<AgentEntity> findByOwnerId(Long ownerId);

    List<AgentEntity> findByStatus(String status);

    List<AgentEntity> findByIsPublicTrue();

    boolean existsByName(String name);

    /**
     * MEMORY-LLM-SYNTHESIS (V69 dogfood) + KILL-BOOTSTRAP-PROMPT-TO-DB
     * (V95 2026-05-22): used by {@code AdminMemoryLlmSynthesisController}
     * and {@code FlywheelController} to look up system agents by name
     * (e.g. {@code SystemAgentNames.MEMORY_CURATOR}) for manual triggers
     * and per-agent flywheel chain. Lookup by name is safe here because
     * system agents (owner_id=NULL) carry deterministic names.
     */
    Optional<AgentEntity> findFirstByName(String name);

    /**
     * SYSTEM-AGENT-TYPING Phase 1 (V89): list all agents of a given type.
     * {@code agentType} is the {@code agent_type} column constrained by
     * {@code chk_agent_type} to {@code 'user'} or {@code 'system'}. Used by
     * F7 (Phase 1.2) session-annotator pipeline to bias LLM-queue toward
     * user-agent sessions, and by Phase 2 admin / observability surfaces.
     */
    List<AgentEntity> findByAgentType(String agentType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AgentEntity a WHERE a.id = :id")
    Optional<AgentEntity> findByIdForUpdate(@Param("id") Long id);
}
