package com.skillforge.server.memory;

import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * MEMORY-DREAM-CONSOLIDATION — nightly cron driving {@link MemoryConsolidator#consolidate(Long)}
 * for every recently-active user.
 *
 * <p>Strategy mirrors {@code SkillDraftScheduledExtractor}:
 * <ol>
 *   <li>Find distinct userIds whose top-level sessions saw a real user message in the
 *       last {@code activeUserLookbackDays} (default 7d). INV: the only correct
 *       activity proxy is {@code lastUserMessageAt} (java.md footgun #2).</li>
 *   <li>Per user, call {@link MemoryConsolidator#consolidate(Long)} inside a try/catch so
 *       a single user's failure (broken embedding row, JPA glitch, etc.) is logged
 *       and the cron continues with the next user (INV-2).</li>
 *   <li>Emit summary at INFO so operators can diff success/failure across nights.</li>
 * </ol>
 *
 * <p>Schedule: {@code 0 30 3 * * *} (03:30 daily) — staggered after
 * {@code IdleSessionMemoryScanner}'s 03:00 daily cron and ahead of the
 * {@code SkillScheduledEvaluator} 04:00 Monday cron / {@code SkillSelfImproveLoop}
 * 05:00 Tuesday cron, so cluster CPU does not spike on overlapping nightly work.
 *
 * <p>yaml gate: {@code skillforge.memory.consolidation.scheduled-enabled} (default true).
 * Set to {@code false} to disable the cron at runtime without restart.
 */
@Component
public class MemoryConsolidationScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationScheduler.class);

    private final SessionRepository sessionRepository;
    private final MemoryConsolidator memoryConsolidator;
    private final MemoryProperties memoryProperties;
    private final boolean enabled;

    public MemoryConsolidationScheduler(SessionRepository sessionRepository,
                                        MemoryConsolidator memoryConsolidator,
                                        MemoryProperties memoryProperties,
                                        @Value("${skillforge.memory.consolidation.scheduled-enabled:true}")
                                        boolean enabled) {
        this.sessionRepository = sessionRepository;
        this.memoryConsolidator = memoryConsolidator;
        this.memoryProperties = memoryProperties != null ? memoryProperties : new MemoryProperties();
        this.enabled = enabled;
    }

    /** Cron: 0 30 3 * * * — daily 03:30. */
    @Scheduled(cron = "0 30 3 * * *")
    public void scheduledRun() {
        runOnce();
    }

    /**
     * Public entry point — used by both the {@link #scheduledRun} cron and the
     * {@code AdminMemoryConsolidationController} manual-trigger endpoint.
     *
     * @param userIdFilter optional restriction; when non-null, only that user is
     *                     consolidated (admin path); when null, scans every active user.
     */
    public ConsolidationSummary runOnce(Long userIdFilter) {
        if (!enabled) {
            log.info("MemoryConsolidationScheduler disabled via "
                    + "skillforge.memory.consolidation.scheduled-enabled=false");
            return new ConsolidationSummary(0, 0, 0);
        }

        List<Long> userIds;
        if (userIdFilter != null) {
            userIds = List.of(userIdFilter);
        } else {
            int lookbackDays = memoryProperties.getConsolidation().getActiveUserLookbackDays();
            Instant since = Instant.now().minus(Duration.ofDays(Math.max(lookbackDays, 1)));
            try {
                userIds = sessionRepository.findDistinctUserIdsWithRecentUserMessage(since);
            } catch (Exception e) {
                log.error("MemoryConsolidationScheduler: failed to query active userIds since={}: {}",
                        since, e.getMessage(), e);
                return new ConsolidationSummary(0, 0, 0);
            }
        }

        if (userIds == null || userIds.isEmpty()) {
            log.info("MemoryConsolidationScheduler: 0 eligible users{}",
                    userIdFilter != null ? " (filter=" + userIdFilter + ")" : "");
            return new ConsolidationSummary(0, 0, 0);
        }

        int succeeded = 0;
        int failed = 0;
        for (Long userId : userIds) {
            try {
                memoryConsolidator.consolidate(userId);
                succeeded++;
            } catch (Exception e) {
                // INV-2: per-user failure logs WARN and continues — never abort the whole cron.
                failed++;
                log.warn("MemoryConsolidationScheduler: userId={} consolidate failed: {}",
                        userId, e.getMessage(), e);
            }
        }
        log.info("MemoryConsolidationScheduler: done eligible={} succeeded={} failed={}",
                userIds.size(), succeeded, failed);
        return new ConsolidationSummary(userIds.size(), succeeded, failed);
    }

    /** Convenience overload for the cron path. */
    public ConsolidationSummary runOnce() {
        return runOnce(null);
    }

    /** Result row for the admin endpoint and for tests. */
    public record ConsolidationSummary(int eligible, int succeeded, int failed) {}
}
