package com.skillforge.observability.repository;

import com.skillforge.observability.entity.LlmSpanEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface LlmSpanRepository extends JpaRepository<LlmSpanEntity, String> {

    List<LlmSpanEntity> findBySessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
            String sessionId, Instant since);

    List<LlmSpanEntity> findBySessionIdOrderByStartedAtAsc(String sessionId);

    List<LlmSpanEntity> findByTraceIdOrderByStartedAtAsc(String traceId);

    List<LlmSpanEntity> findByStartedAtBefore(Instant cutoff);
}
