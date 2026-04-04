package com.skillforge.server.repository;

import com.skillforge.server.entity.ModelUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ModelUsageRepository extends JpaRepository<ModelUsageEntity, Long> {

    List<ModelUsageEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<ModelUsageEntity> findBySessionId(String sessionId);

    @Query("SELECT CAST(u.createdAt AS LocalDate) AS date, " +
           "SUM(u.inputTokens) AS inputTokens, " +
           "SUM(u.outputTokens) AS outputTokens " +
           "FROM ModelUsageEntity u " +
           "WHERE u.createdAt >= :since " +
           "GROUP BY CAST(u.createdAt AS LocalDate) " +
           "ORDER BY CAST(u.createdAt AS LocalDate)")
    List<Object[]> findDailyUsage(@Param("since") LocalDateTime since);

    @Query("SELECT u.modelId, SUM(u.inputTokens + u.outputTokens) " +
           "FROM ModelUsageEntity u " +
           "GROUP BY u.modelId " +
           "ORDER BY SUM(u.inputTokens + u.outputTokens) DESC")
    List<Object[]> findUsageByModel();

    @Query("SELECT a.name, SUM(u.inputTokens + u.outputTokens) " +
           "FROM ModelUsageEntity u " +
           "JOIN AgentEntity a ON a.id = u.agentId " +
           "GROUP BY a.name " +
           "ORDER BY SUM(u.inputTokens + u.outputTokens) DESC")
    List<Object[]> findUsageByAgent();
}
