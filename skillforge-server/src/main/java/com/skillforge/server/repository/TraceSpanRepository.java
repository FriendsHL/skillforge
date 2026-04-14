package com.skillforge.server.repository;

import com.skillforge.server.entity.TraceSpanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TraceSpanRepository extends JpaRepository<TraceSpanEntity, String> {

    /** 查询某个 session 的所有 root span（AGENT_LOOP），按 startTime 降序。 */
    List<TraceSpanEntity> findBySessionIdAndSpanTypeOrderByStartTimeDesc(String sessionId, String spanType);

    /** 查询某个 session 的所有 span，按 startTime 正序（用于构建完整 trace 树）。 */
    List<TraceSpanEntity> findBySessionIdOrderByStartTimeAsc(String sessionId);

    /** 查询某个 parent span 下的所有子 span，按 startTime 正序。 */
    List<TraceSpanEntity> findByParentSpanIdOrderByStartTimeAsc(String parentSpanId);

    /** 查询所有 session 的 AGENT_LOOP root span，按 startTime 降序（用于 trace 列表页）。 */
    List<TraceSpanEntity> findBySpanTypeOrderByStartTimeDesc(String spanType);

    /** 查询多个 session 的所有 span，按 startTime 正序（用于 collab run 跨 session 追踪）。 */
    @Query("SELECT s FROM TraceSpanEntity s WHERE s.sessionId IN :ids ORDER BY s.startTime ASC")
    List<TraceSpanEntity> findBySessionIdInOrderByStartTimeAsc(@Param("ids") Collection<String> sessionIds);
}
