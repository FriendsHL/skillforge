package com.skillforge.observability.repository;

import com.skillforge.observability.entity.LlmTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface LlmTraceRepository extends JpaRepository<LlmTraceEntity, String> {

    List<LlmTraceEntity> findBySessionIdOrderByStartedAtDesc(String sessionId);

    List<LlmTraceEntity> findByStartedAtBefore(Instant cutoff);

    /**
     * OBS-4 M2: lookup all traces sharing a root_trace_id, ordered by startedAt ascending.
     * Drives the {@code GET /api/traces/{rootTraceId}/tree} endpoint that re-assembles a
     * cross-session investigation timeline (parent agent + all spawned subagent traces).
     */
    List<LlmTraceEntity> findByRootTraceIdOrderByStartedAtAsc(String rootTraceId);
}
