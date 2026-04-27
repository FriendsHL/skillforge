package com.skillforge.server.memory;

import com.skillforge.core.model.Message;
import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.entity.ActivityLogEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.ActivityLogService;
import com.skillforge.server.service.MemoryService;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SessionDigestExtractor {

    private static final Logger log = LoggerFactory.getLogger(SessionDigestExtractor.class);
    private static final int MIN_EXTRACTABLE_USER_TURNS = 3;

    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final ActivityLogService activityLogService;
    private final MemoryService memoryService;
    private final MemoryConsolidator memoryConsolidator;
    private final MemoryProperties memoryProperties;
    private final LlmMemoryExtractor llmMemoryExtractor;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentMap<String, Instant> emptyCooldownUntilBySession = new ConcurrentHashMap<>();
    private final Set<String> extractionInFlight = ConcurrentHashMap.newKeySet();

    public SessionDigestExtractor(SessionRepository sessionRepository,
                                  SessionService sessionService,
                                  ActivityLogService activityLogService,
                                  MemoryService memoryService,
                                  MemoryConsolidator memoryConsolidator,
                                  MemoryProperties memoryProperties,
                                  LlmMemoryExtractor llmMemoryExtractor,
                                  PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.activityLogService = activityLogService;
        this.memoryService = memoryService;
        this.memoryConsolidator = memoryConsolidator;
        this.memoryProperties = memoryProperties;
        this.llmMemoryExtractor = llmMemoryExtractor;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    /**
     * Asynchronously extract memories for a single top-level session.
     *
     * <p>Memory v2 PR-3 replaces the old {@code digestExtractedAt != null}
     * terminal lock with an incremental cursor. {@code digestExtractedAt} is now
     * only a cooldown / observability timestamp.
     */
    @Async
    public void triggerExtractionAsync(String sessionId) {
        try {
            ExtractionResult result = triggerExtractionNow(sessionId);
            if (result.extracted()) {
                log.info("SessionDigestExtractor: extracted session={} fromSeq={} toSeq={} count={} batch={}",
                        sessionId, result.fromSeq(), result.toSeq(), result.memoryCount(), result.batchId());
            } else {
                log.debug("SessionDigestExtractor: skipped session={} reason={}", sessionId, result.reason());
            }
        } catch (Exception e) {
            log.error("SessionDigestExtractor: async extraction failed for session={}", sessionId, e);
        }
    }

    ExtractionResult triggerExtractionNow(String sessionId) {
        if (!extractionInFlight.add(sessionId)) {
            return ExtractionResult.skipped("in-flight");
        }
        try {
            ExtractionPlan plan = prepareExtractionPlan(sessionId);
            if (!plan.ready()) {
                return ExtractionResult.skipped(plan.reason());
            }
            ExtractionOutcome outcome = extractPrepared(plan);
            if (!outcome.success()) {
                return ExtractionResult.skipped(outcome.reason());
            }
            ExtractionResult result = finalizeExtraction(plan, outcome);
            if (result.extracted() && result.memoryCount() > 0) {
                try {
                    memoryConsolidator.consolidate(plan.userId());
                } catch (Exception e) {
                    log.error("Failed to consolidate memories for user {}", plan.userId(), e);
                }
            }
            return result;
        } finally {
            extractionInFlight.remove(sessionId);
        }
    }

    private ExtractionPlan prepareExtractionPlan(String sessionId) {
        if (transactionTemplate == null) {
            return prepareExtractionPlanLocked(sessionId);
        }
        ExtractionPlan plan = transactionTemplate.execute(status -> prepareExtractionPlanLocked(sessionId));
        return plan != null ? plan : ExtractionPlan.skipped("transaction-returned-null");
    }

    private ExtractionPlan prepareExtractionPlanLocked(String sessionId) {
        SessionEntity session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            return ExtractionPlan.skipped("session-not-found");
        }
        if (session.getParentSessionId() != null) {
            return ExtractionPlan.skipped("child-session");
        }

        long cursor = effectiveCursor(session);
        long latestSeq = sessionService.getLatestNormalSeqNo(session.getId());
        if (latestSeq <= cursor) {
            return ExtractionPlan.skipped("no-new-normal-messages");
        }

        long newUserTurns = sessionService.countUserNormalMessagesAfterSeq(session.getId(), cursor);
        if (newUserTurns < MIN_EXTRACTABLE_USER_TURNS) {
            return ExtractionPlan.skipped("not-enough-user-turns");
        }

        Instant now = Instant.now();
        boolean forceByTurnCount = newUserTurns >= maxUnextractedTurns();
        if (!forceByTurnCount && isCoolingDown(session, now)) {
            return ExtractionPlan.skipped("cooldown");
        }

        List<SessionService.StoredMessage> incrementalRecords =
                sessionService.getNormalHistoryRecordsAfterSeq(session.getId(), cursor);
        if (incrementalRecords.isEmpty()) {
            return ExtractionPlan.skipped("no-incremental-records");
        }

        List<Message> messages = new ArrayList<>(incrementalRecords.size());
        for (SessionService.StoredMessage stored : incrementalRecords) {
            messages.add(stored.message());
        }
        long fromSeq = incrementalRecords.get(0).seqNo();
        long toSeq = incrementalRecords.get(incrementalRecords.size() - 1).seqNo();
        List<ActivityLogEntity> activities = activityLogService.getSessionActivities(session.getId());
        return ExtractionPlan.ready(session, cursor, fromSeq, toSeq, messages, activities);
    }

    private ExtractionOutcome extractPrepared(ExtractionPlan plan) {
        String extractionBatchId = memoryService.beginExtractionBatch(plan.userId());

        int memoryCount;
        if (memoryProperties.isLlmMode()) {
            try {
                memoryCount = llmMemoryExtractor.extract(
                        plan.session(), plan.activities(), plan.messages(), extractionBatchId, plan.fromSeq(), plan.toSeq());
                log.info("LLM extraction produced {} memories for session={} batch={}",
                        memoryCount, plan.sessionId(), extractionBatchId);
            } catch (Exception e) {
                log.warn("LLM extraction failed for session={}, leaving cursor unchanged: {}",
                        plan.sessionId(), e.getMessage());
                return ExtractionOutcome.failed("llm-failed");
            }
        } else {
            memoryCount = extractRuleBased(
                    plan.session(), plan.activities(), plan.messages(), extractionBatchId, plan.fromSeq(), plan.toSeq());
        }

        return ExtractionOutcome.success(memoryCount, extractionBatchId);
    }

    private ExtractionResult finalizeExtraction(ExtractionPlan plan, ExtractionOutcome outcome) {
        if (transactionTemplate == null) {
            return finalizeExtractionLocked(plan, outcome);
        }
        ExtractionResult result = transactionTemplate.execute(status -> finalizeExtractionLocked(plan, outcome));
        return result != null ? result : ExtractionResult.skipped("transaction-returned-null");
    }

    private ExtractionResult finalizeExtractionLocked(ExtractionPlan plan, ExtractionOutcome outcome) {
        SessionEntity session = sessionRepository.findByIdForUpdate(plan.sessionId()).orElse(null);
        if (session == null) {
            return ExtractionResult.skipped("session-not-found");
        }
        long currentCursor = effectiveCursor(session);
        if (currentCursor > plan.cursor() && currentCursor >= plan.toSeq()) {
            return ExtractionResult.skipped("cursor-already-advanced");
        }

        Instant now = Instant.now();
        session.setLastExtractedMessageSeq(plan.toSeq());
        session.setDigestExtractedAt(now);
        sessionRepository.save(session);

        if (outcome.memoryCount() == 0) {
            emptyCooldownUntilBySession.put(session.getId(), now.plus(emptyResultCooldown()));
        } else {
            emptyCooldownUntilBySession.remove(session.getId());
        }

        return ExtractionResult.extracted(plan.fromSeq(), plan.toSeq(), outcome.memoryCount(), outcome.batchId());
    }

    private long effectiveCursor(SessionEntity session) {
        long cursor = session.getLastExtractedMessageSeq();
        if (cursor == 0L && session.getDigestExtractedAt() == null) {
            return -1L;
        }
        return cursor;
    }

    private boolean isCoolingDown(SessionEntity session, Instant now) {
        Instant emptyCooldownUntil = emptyCooldownUntilBySession.get(session.getId());
        if (emptyCooldownUntil != null) {
            if (emptyCooldownUntil.isAfter(now)) {
                return true;
            }
            emptyCooldownUntilBySession.remove(session.getId(), emptyCooldownUntil);
        }

        Instant lastExtractedAt = session.getDigestExtractedAt();
        if (lastExtractedAt == null) {
            return false;
        }
        return lastExtractedAt.plus(standardCooldown()).isAfter(now);
    }

    private int maxUnextractedTurns() {
        return Math.max(1, memoryProperties.getExtraction().getMaxUnextractedTurns());
    }

    private Duration standardCooldown() {
        return Duration.ofMinutes(Math.max(0, memoryProperties.getExtraction().getCooldownMinutes()));
    }

    private Duration emptyResultCooldown() {
        return Duration.ofMinutes(Math.max(0, memoryProperties.getExtraction().getEmptyResultCooldownMinutes()));
    }

    private int extractRuleBased(SessionEntity session,
                                 List<ActivityLogEntity> activities,
                                 List<Message> messages,
                                 String extractionBatchId,
                                 long fromSeq,
                                 long toSeq) {
        StringBuilder summary = new StringBuilder();
        summary.append("Session ID: ").append(session.getId()).append("\n");
        summary.append("Title: ").append(session.getTitle()).append("\n");
        summary.append("Duration: ").append(formatDuration(session)).append("\n\n");
        summary.append("Extraction seq range: ").append(fromSeq).append("..").append(toSeq).append("\n\n");
        summary.append("## Activity Log\n\n");
        for (ActivityLogEntity a : activities) {
            summary.append("- [").append(a.getToolName()).append("] ");
            if (a.getInputSummary() != null) summary.append(a.getInputSummary());
            summary.append(" -> ").append(a.isSuccess() ? "OK" : "FAIL");
            if (a.getOutputSummary() != null && !a.getOutputSummary().isEmpty()) {
                summary.append(" | ").append(a.getOutputSummary(), 0,
                        Math.min(100, a.getOutputSummary().length()));
            }
            summary.append("\n");
        }

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

        String title = "Session digest: " + (session.getTitle() != null
                ? session.getTitle()
                : session.getId().substring(0, Math.min(8, session.getId().length())));
        String content = summary.toString();
        if (content.length() > 2000) {
            content = content.substring(0, 2000) + "...[truncated]";
        }

        memoryService.createMemoryIfNotDuplicate(
                session.getUserId(), "knowledge", title, content, "auto-extract", extractionBatchId
        );
        return 1;
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

    record ExtractionResult(boolean extracted,
                            String reason,
                            long fromSeq,
                            long toSeq,
                            int memoryCount,
                            String batchId) {
        static ExtractionResult skipped(String reason) {
            return new ExtractionResult(false, reason, -1L, -1L, 0, null);
        }

        static ExtractionResult extracted(long fromSeq, long toSeq, int memoryCount, String batchId) {
            return new ExtractionResult(true, null, fromSeq, toSeq, memoryCount, batchId);
        }
    }

    record ExtractionPlan(boolean ready,
                          String reason,
                          SessionEntity session,
                          long cursor,
                          long fromSeq,
                          long toSeq,
                          List<Message> messages,
                          List<ActivityLogEntity> activities) {
        static ExtractionPlan skipped(String reason) {
            return new ExtractionPlan(false, reason, null, -1L, -1L, -1L, List.of(), List.of());
        }

        static ExtractionPlan ready(SessionEntity session,
                                    long cursor,
                                    long fromSeq,
                                    long toSeq,
                                    List<Message> messages,
                                    List<ActivityLogEntity> activities) {
            return new ExtractionPlan(true, null, session, cursor, fromSeq, toSeq,
                    List.copyOf(messages), List.copyOf(activities));
        }

        String sessionId() {
            return session.getId();
        }

        Long userId() {
            return session.getUserId();
        }
    }

    record ExtractionOutcome(boolean success, String reason, int memoryCount, String batchId) {
        static ExtractionOutcome failed(String reason) {
            return new ExtractionOutcome(false, reason, 0, null);
        }

        static ExtractionOutcome success(int memoryCount, String batchId) {
            return new ExtractionOutcome(true, null, memoryCount, batchId);
        }
    }
}
