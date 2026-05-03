package com.skillforge.observability.repository;

import com.skillforge.observability.entity.LlmTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface LlmTraceRepository extends JpaRepository<LlmTraceEntity, String> {

    List<LlmTraceEntity> findBySessionIdOrderByStartedAtDesc(String sessionId);

    List<LlmTraceEntity> findByStartedAtBefore(Instant cutoff);
}
