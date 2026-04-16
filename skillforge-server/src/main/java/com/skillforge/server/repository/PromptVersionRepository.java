package com.skillforge.server.repository;

import com.skillforge.server.entity.PromptVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PromptVersionRepository extends JpaRepository<PromptVersionEntity, String> {

    List<PromptVersionEntity> findByAgentIdAndStatus(String agentId, String status);

    Optional<PromptVersionEntity> findTopByAgentIdAndStatusOrderByCreatedAtDesc(String agentId, String status);

    @Query("SELECT MAX(v.versionNumber) FROM PromptVersionEntity v WHERE v.agentId = :agentId")
    Optional<Integer> findMaxVersionNumber(@Param("agentId") String agentId);

    List<PromptVersionEntity> findByAgentIdOrderByVersionNumberDesc(String agentId);
}
