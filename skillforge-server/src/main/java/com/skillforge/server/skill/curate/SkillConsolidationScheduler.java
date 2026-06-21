package com.skillforge.server.skill.curate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SKILL-CURATOR V1 — nightly cron driving {@link SkillConsolidator#consolidate()}.
 *
 * <p>Mirrors {@code MemoryConsolidationScheduler}:
 * <ul>
 *   <li>Schedule {@code 0 45 3 * * *} (03:45 daily) — staggered after the memory
 *       consolidation cron at 03:30 so nightly CPU does not spike on overlap.</li>
 *   <li>yaml gate {@code skillforge.skill.consolidation.scheduled-enabled} (default true):
 *       set false to disable the cron at runtime without restart.</li>
 *   <li>try/catch around the call — a cron method must never throw.</li>
 * </ul>
 */
@Component
public class SkillConsolidationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SkillConsolidationScheduler.class);

    private final SkillConsolidator consolidator;
    private final boolean enabled;

    public SkillConsolidationScheduler(SkillConsolidator consolidator,
                                       @Value("${skillforge.skill.consolidation.scheduled-enabled:true}")
                                       boolean enabled) {
        this.consolidator = consolidator;
        this.enabled = enabled;
    }

    /** Cron: 0 45 3 * * * — daily 03:45 (after memory consolidation's 03:30). */
    @Scheduled(cron = "0 45 3 * * *")
    public void scheduledRun() {
        if (!enabled) {
            log.info("SkillConsolidationScheduler disabled via "
                    + "skillforge.skill.consolidation.scheduled-enabled=false");
            return;
        }
        try {
            SkillConsolidator.ConsolidationResult result = consolidator.consolidate();
            log.info("SkillConsolidationScheduler: done dryRun={} candidatesFound={} archived={}",
                    result.dryRun(), result.candidatesFound(), result.archived());
        } catch (Exception e) {
            // A cron must never throw — log and swallow so the scheduler keeps running.
            log.error("SkillConsolidationScheduler: consolidate failed: {}", e.getMessage(), e);
        }
    }
}
