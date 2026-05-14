package com.skillforge.server.scheduling;

import com.skillforge.server.service.ChatAttachmentService;
import com.skillforge.server.service.ChatAttachmentService.CleanupResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ATTACHMENT-CLEANUP (Wave1-B): nightly cron that calls
 * {@link ChatAttachmentService#cleanupOrphans(int, boolean)} to drop:
 *
 * <ol>
 *   <li>{@code t_chat_attachment} rows uploaded but never bound to a message,
 *       older than {@code skillforge.chat.attachments.cleanup.threshold-hours}
 *       (default 24h). These typically come from users uploading then closing
 *       the page without ever sending.</li>
 *   <li>Physical files under {@code skillforge.chat.attachments.root} that have
 *       no corresponding DB row — orphaned by {@code ON DELETE CASCADE} when
 *       the owning session was deleted.</li>
 * </ol>
 *
 * <p><b>Schedule:</b> default {@code 0 0 4 * * *} (04:00 daily) — placed after
 * the 03:00 / 03:30 memory-related crons in {@code application.yml} so cluster
 * CPU does not spike on overlapping nightly work.</p>
 *
 * <p><b>Disable:</b> set
 * {@code skillforge.chat.attachments.cleanup.enabled=false} to drop the bean
 * entirely ({@link ConditionalOnProperty}); the admin manual-trigger endpoint
 * still calls {@code ChatAttachmentService.cleanupOrphans} directly, so the
 * service path remains testable in isolation.</p>
 *
 * <p><b>Multi-JVM caveat:</b> if SkillForge is ever clustered with 2+ replicas,
 * each replica will fire its own cron at the same wall-clock time. Both racing
 * the same set of rows / files is benign (delete-if-exists is idempotent), but
 * results in 2× the log noise. Revisit with ShedLock / leader election when
 * clustering becomes a real deployment.</p>
 */
@Component
@ConditionalOnProperty(
        name = "skillforge.chat.attachments.cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ChatAttachmentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentCleanupScheduler.class);

    private final ChatAttachmentService attachmentService;
    private final int thresholdHours;

    public ChatAttachmentCleanupScheduler(ChatAttachmentService attachmentService,
                                          @Value("${skillforge.chat.attachments.cleanup.threshold-hours:24}")
                                          int thresholdHours) {
        this.attachmentService = attachmentService;
        this.thresholdHours = thresholdHours;
    }

    /**
     * Cron entrypoint. Default {@code 0 0 4 * * *} (daily 04:00); the cron
     * expression is configurable so ops can stagger if needed without a code
     * change.
     *
     * <p>Wrapped in try/catch so a single failure (e.g. DB outage) does not
     * leak past the scheduler — the next firing will retry on its own
     * schedule.</p>
     */
    @Scheduled(cron = "${skillforge.chat.attachments.cleanup.cron:0 0 4 * * *}")
    public void runDaily() {
        try {
            CleanupResult result = attachmentService.cleanupOrphans(thresholdHours, false);
            log.info("ChatAttachmentCleanupScheduler done: orphanRowsDeleted={} filesDeleted={} errors={} root={}",
                    result.orphanRowsDeleted(), result.filesDeleted(), result.errors().size(),
                    attachmentService.getStorageRoot());
        } catch (RuntimeException e) {
            // cleanupOrphans is contract-bound not to throw, but defense in depth so an
            // unexpected throw never bubbles into Spring's TaskScheduler (which would
            // suppress future firings on some scheduler configurations).
            log.error("ChatAttachmentCleanupScheduler failed unexpectedly: {}", e.getMessage(), e);
        }
    }
}
