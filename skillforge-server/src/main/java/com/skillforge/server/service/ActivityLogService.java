package com.skillforge.server.service;

import com.skillforge.server.entity.ActivityLogEntity;
import com.skillforge.server.repository.ActivityLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ActivityLogService {

    private final ActivityLogRepository repository;

    public ActivityLogService(ActivityLogRepository repository) {
        this.repository = repository;
    }

    public void log(Long userId, String sessionId, String toolName,
                    String inputSummary, String outputSummary, long durationMs, boolean success) {
        ActivityLogEntity entity = new ActivityLogEntity();
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setToolName(toolName);
        entity.setInputSummary(truncate(inputSummary, 500));
        entity.setOutputSummary(truncate(outputSummary, 500));
        entity.setDurationMs(durationMs);
        entity.setSuccess(success);
        repository.save(entity);
    }

    public List<ActivityLogEntity> getSessionLogs(Long userId, String sessionId) {
        return repository.findByUserIdAndSessionIdOrderByCreatedAtAsc(userId, sessionId);
    }

    public List<ActivityLogEntity> getRecentLogs(Long userId, LocalDateTime since) {
        return repository.findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(userId, since);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
