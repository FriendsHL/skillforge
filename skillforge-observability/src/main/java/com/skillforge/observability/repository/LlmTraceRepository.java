package com.skillforge.observability.repository;

import com.skillforge.observability.entity.LlmTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface LlmTraceRepository extends JpaRepository<LlmTraceEntity, String> {

    List<LlmTraceEntity> findBySessionIdOrderByStartedAtDesc(String sessionId);

    /**
     * EVAL-V2 M3a §2.2 R3: traces by sessionId 但额外按 origin 过滤。
     * Drives {@code GET /api/traces?sessionId=X&origin=Y} (默认 production，避免 eval 流量
     * 污染常规 OBS 视图)。
     */
    List<LlmTraceEntity> findBySessionIdAndOriginOrderByStartedAtDesc(String sessionId, String origin);

    /**
     * EVAL-V2 M3a §2.2 R3: 全量 traces 按 origin 过滤（无 sessionId 时使用）。
     * 注意：不带 sessionId 的全量查询历史上仅供 admin / cron 使用，本方法保留同样的限定，
     * 默认 production 调用方。
     */
    List<LlmTraceEntity> findByOriginOrderByStartedAtDesc(String origin);

    List<LlmTraceEntity> findByStartedAtBefore(Instant cutoff);

    /**
     * OBS-4 M2: lookup all traces sharing a root_trace_id, ordered by startedAt ascending.
     * Drives the {@code GET /api/traces/{rootTraceId}/tree} endpoint that re-assembles a
     * cross-session investigation timeline (parent agent + all spawned subagent traces).
     */
    List<LlmTraceEntity> findByRootTraceIdOrderByStartedAtAsc(String rootTraceId);
}
