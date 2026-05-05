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

    List<ModelUsageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * EVAL-V2 M3a §2.2 R3 (r2 fix): 按 origin 过滤的日用量聚合 — LEFT JOIN SessionEntity。
     *
     * <p>R3 spec：「eval token 不计入 dashboard」 ≠ 「channel-only token 不计入」。早期
     * channel 调用路径上的 ModelUsageEntity 行 sessionId 可能为 null 或指向已删除 session
     * （清理 cron 后），它们历史上一直进 production dashboard。新过滤逻辑：
     * <ul>
     *   <li>LEFT JOIN：保留 u.sessionId IS NULL 的行</li>
     *   <li>WHERE s.origin = :origin OR s.id IS NULL：仅排除明确 origin='eval' 的；
     *       未匹配到 session 行（s.id IS NULL）默认计入 production 聚合，保持历史行为</li>
     * </ul>
     */
    @Query("SELECT CAST(u.createdAt AS LocalDate) AS date, " +
           "SUM(u.inputTokens) AS inputTokens, " +
           "SUM(u.outputTokens) AS outputTokens " +
           "FROM ModelUsageEntity u " +
           "LEFT JOIN SessionEntity s ON s.id = u.sessionId " +
           "WHERE u.createdAt >= :since AND (s.origin = :origin OR s.id IS NULL) " +
           "GROUP BY CAST(u.createdAt AS LocalDate) " +
           "ORDER BY CAST(u.createdAt AS LocalDate)")
    List<Object[]> findDailyUsage(@Param("since") LocalDateTime since,
                                  @Param("origin") String origin);

    /**
     * EVAL-V2 M3a §2.2 R3 (r2 fix): 同 {@link #findDailyUsage} 的 LEFT JOIN 语义 ——
     * channel-only path（u.sessionId NULL / 已清理 session）默认计入 production。
     */
    @Query("SELECT u.modelId, SUM(u.inputTokens + u.outputTokens) " +
           "FROM ModelUsageEntity u " +
           "LEFT JOIN SessionEntity s ON s.id = u.sessionId " +
           "WHERE s.origin = :origin OR s.id IS NULL " +
           "GROUP BY u.modelId " +
           "ORDER BY SUM(u.inputTokens + u.outputTokens) DESC")
    List<Object[]> findUsageByModel(@Param("origin") String origin);

    /**
     * EVAL-V2 M3a §2.2 R3 (r2 fix): 同 {@link #findDailyUsage} 的 LEFT JOIN 语义。
     * AgentEntity JOIN 仍是 INNER（usage 行没有有效 agentId 时本就不该出现在 by-agent
     * 聚合里）；SessionEntity 是 LEFT JOIN 以容纳 channel-only 历史行。
     */
    @Query("SELECT a.name, SUM(u.inputTokens + u.outputTokens) " +
           "FROM ModelUsageEntity u " +
           "JOIN AgentEntity a ON a.id = u.agentId " +
           "LEFT JOIN SessionEntity s ON s.id = u.sessionId " +
           "WHERE s.origin = :origin OR s.id IS NULL " +
           "GROUP BY a.name " +
           "ORDER BY SUM(u.inputTokens + u.outputTokens) DESC")
    List<Object[]> findUsageByAgent(@Param("origin") String origin);

    /**
     * 求和指定时间之后的 input/output token。返回单行 List,行内 [inputSum, outputSum];
     * 区间内无数据时也会因 COALESCE 返回 [0, 0]。
     */
    @Query("SELECT COALESCE(SUM(u.inputTokens), 0), COALESCE(SUM(u.outputTokens), 0) " +
           "FROM ModelUsageEntity u " +
           "WHERE u.createdAt >= :since")
    List<Object[]> sumTokensSince(@Param("since") LocalDateTime since);
}
