package com.skillforge.observability.retention;

import com.skillforge.observability.api.BlobStore;
import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Plan §5.4 R2-W1 — 30 天清理任务。
 *
 * <p>失败仅 log + warn，不抛（决策 Q6）。
 */
@Component
public class BlobRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(BlobRetentionJob.class);
    private static final Duration RETENTION = Duration.ofDays(30);

    private final BlobStore blobStore;
    private final LlmSpanRepository spanRepo;
    private final LlmTraceRepository traceRepo;

    public BlobRetentionJob(BlobStore blobStore,
                            LlmSpanRepository spanRepo,
                            LlmTraceRepository traceRepo) {
        this.blobStore = blobStore;
        this.spanRepo = spanRepo;
        this.traceRepo = traceRepo;
    }

    @Scheduled(cron = "${skillforge.observability.retention.cron:0 30 3 * * *}")
    @Transactional
    public void run() {
        try {
            Instant cutoff = Instant.now().minus(RETENTION);
            // 1. Find expired spans + delete blobs.
            List<LlmSpanEntity> expired = spanRepo.findByStartedAtBefore(cutoff);
            for (LlmSpanEntity e : expired) {
                if (e.getInputBlobRef() != null) blobStore.delete(e.getInputBlobRef());
                if (e.getOutputBlobRef() != null) blobStore.delete(e.getOutputBlobRef());
                if (e.getRawSseBlobRef() != null) blobStore.delete(e.getRawSseBlobRef());
            }
            spanRepo.deleteAllInBatch(expired);
            // 2. Drop trace rows beyond cutoff.
            var expiredTraces = traceRepo.findByStartedAtBefore(cutoff);
            traceRepo.deleteAllInBatch(expiredTraces);
            log.info("BlobRetentionJob: removed {} spans, {} traces older than {}",
                    expired.size(), expiredTraces.size(), cutoff);
        } catch (Exception e) {
            log.warn("obs.retention.failed", e);
        }
    }
}
