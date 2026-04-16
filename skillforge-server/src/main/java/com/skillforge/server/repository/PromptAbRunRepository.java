package com.skillforge.server.repository;

import com.skillforge.server.entity.PromptAbRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PromptAbRunRepository extends JpaRepository<PromptAbRunEntity, String> {

    List<PromptAbRunEntity> findByAgentIdAndStatus(String agentId, String status);

    @Query("SELECT COUNT(r) FROM PromptAbRunEntity r WHERE r.agentId = :agentId " +
           "AND r.promoted = true AND r.completedAt >= :startOfDay")
    long countPromotedSince(@Param("agentId") String agentId, @Param("startOfDay") Instant startOfDay);

    Optional<PromptAbRunEntity> findTopByAgentIdAndStatusInOrderByCompletedAtDesc(
            String agentId, List<String> statuses);
}
