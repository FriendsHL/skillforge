package com.skillforge.server.memory;

import com.skillforge.core.model.Message;
import com.skillforge.server.entity.ActivityLogEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.ActivityLogService;
import com.skillforge.server.service.MemoryService;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SessionDigestExtractor {

    private static final Logger log = LoggerFactory.getLogger(SessionDigestExtractor.class);

    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final ActivityLogService activityLogService;
    private final MemoryService memoryService;
    private final MemoryConsolidator memoryConsolidator;

    public SessionDigestExtractor(SessionRepository sessionRepository,
                                  SessionService sessionService,
                                  ActivityLogService activityLogService,
                                  MemoryService memoryService,
                                  MemoryConsolidator memoryConsolidator) {
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.activityLogService = activityLogService;
        this.memoryService = memoryService;
        this.memoryConsolidator = memoryConsolidator;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void extractDailyMemories() {
        List<SessionEntity> unprocessed = sessionRepository
                .findByCompletedAtIsNotNullAndDigestExtractedAtIsNull();

        log.info("SessionDigestExtractor: found {} unprocessed sessions", unprocessed.size());

        for (SessionEntity session : unprocessed) {
            try {
                extractSessionMemories(session);
                session.setDigestExtractedAt(Instant.now());
                sessionRepository.save(session);
            } catch (Exception e) {
                log.error("Failed to extract memories for session {}", session.getId(), e);
            }
        }

        // Consolidate memories for all affected users
        Set<Long> userIds = unprocessed.stream()
                .map(SessionEntity::getUserId)
                .collect(Collectors.toSet());
        for (Long uid : userIds) {
            try {
                memoryConsolidator.consolidate(uid);
            } catch (Exception e) {
                log.error("Failed to consolidate memories for user {}", uid, e);
            }
        }
    }

    private void extractSessionMemories(SessionEntity session) {
        // 1. Get activity log for this session
        List<ActivityLogEntity> activities = activityLogService
                .getSessionActivities(session.getId());

        // Content gate: skip sessions with < 3 activities
        if (activities.size() < 3) {
            log.debug("Skipping session {} — only {} activities", session.getId(), activities.size());
            return;
        }

        // 2. Build activity summary
        StringBuilder summary = new StringBuilder();
        summary.append("Session ID: ").append(session.getId()).append("\n");
        summary.append("Title: ").append(session.getTitle()).append("\n");
        summary.append("Duration: ").append(formatDuration(session)).append("\n\n");
        summary.append("## Activity Log\n\n");
        for (ActivityLogEntity a : activities) {
            summary.append("- [").append(a.getToolName()).append("] ");
            if (a.getInputSummary() != null) summary.append(a.getInputSummary());
            summary.append(" → ").append(a.isSuccess() ? "OK" : "FAIL");
            if (a.getOutputSummary() != null && !a.getOutputSummary().isEmpty()) {
                summary.append(" | ").append(a.getOutputSummary(), 0,
                        Math.min(100, a.getOutputSummary().length()));
            }
            summary.append("\n");
        }

        // 3. Get conversation highlights (last 10 messages with text)
        List<Message> messages = sessionService.getSessionMessages(session.getId());
        summary.append("\n## Conversation Highlights\n\n");
        int msgCount = 0;
        for (int i = messages.size() - 1; i >= 0 && msgCount < 10; i--) {
            Message m = messages.get(i);
            String text = m.getTextContent();
            if (text != null && !text.isBlank()) {
                String preview = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                summary.append("- [").append(m.getRole()).append("] ").append(preview).append("\n");
                msgCount++;
            }
        }

        // 4. Store as a knowledge memory (Phase 2 simplified — Phase 3 will use LLM extraction)
        String title = "Session digest: " + (session.getTitle() != null
                ? session.getTitle()
                : session.getId().substring(0, Math.min(8, session.getId().length())));
        String content = summary.toString();
        if (content.length() > 2000) {
            content = content.substring(0, 2000) + "...[truncated]";
        }

        memoryService.createMemoryIfNotDuplicate(
                session.getUserId(), "knowledge", title, content, "auto-extract"
        );
    }

    private String formatDuration(SessionEntity session) {
        if (session.getCompletedAt() == null || session.getCreatedAt() == null) {
            return "unknown";
        }
        Instant start = session.getCreatedAt()
                .atZone(java.time.ZoneId.systemDefault()).toInstant();
        Duration duration = Duration.between(start, session.getCompletedAt());
        long minutes = duration.toMinutes();
        if (minutes < 60) return minutes + "m";
        return duration.toHours() + "h " + (minutes % 60) + "m";
    }
}
