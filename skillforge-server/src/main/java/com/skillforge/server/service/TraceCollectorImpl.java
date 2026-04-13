package com.skillforge.server.service;

import com.skillforge.core.engine.TraceCollector;
import com.skillforge.core.engine.TraceSpan;
import com.skillforge.server.entity.TraceSpanEntity;
import com.skillforge.server.repository.TraceSpanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * TraceCollector 的持久化实现，将 span 异步写入 t_trace_span 表。
 */
@Service
public class TraceCollectorImpl implements TraceCollector {

    private static final Logger log = LoggerFactory.getLogger(TraceCollectorImpl.class);

    private final TraceSpanRepository repository;

    public TraceCollectorImpl(TraceSpanRepository repository) {
        this.repository = repository;
    }

    @Override
    @Async
    public void record(TraceSpan span) {
        try {
            TraceSpanEntity entity = new TraceSpanEntity();
            entity.setId(span.getId());
            entity.setSessionId(span.getSessionId());
            entity.setParentSpanId(span.getParentSpanId());
            entity.setSpanType(span.getSpanType());
            entity.setName(span.getName());
            entity.setInput(truncate(span.getInput(), 65000));
            entity.setOutput(truncate(span.getOutput(), 65000));
            entity.setStartTime(Instant.ofEpochMilli(span.getStartTimeMs()));
            entity.setEndTime(span.getEndTimeMs() > 0 ? Instant.ofEpochMilli(span.getEndTimeMs()) : null);
            entity.setDurationMs(span.getDurationMs());
            entity.setIterationIndex(span.getIterationIndex());
            entity.setInputTokens(span.getInputTokens());
            entity.setOutputTokens(span.getOutputTokens());
            entity.setModelId(span.getModelId());
            entity.setSuccess(span.isSuccess());
            entity.setError(span.getError());
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist trace span: {}", e.getMessage());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...[truncated]";
    }
}
