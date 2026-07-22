package com.skillforge.server.media;

import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.entity.MediaGenerationJobEntity;
import com.skillforge.server.service.ChatAttachmentService;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class MediaJobProcessor {
    private static final Logger log = LoggerFactory.getLogger(MediaJobProcessor.class);
    private final MediaJobStore store;
    private final Map<String, VideoGenerationProvider> providers;
    private final ChatAttachmentService attachments;
    private final UserWebSocketHandler websocket;
    private final Path stagingRoot;
    private final String workerId = UUID.randomUUID().toString();

    public MediaJobProcessor(MediaJobStore store, List<VideoGenerationProvider> providers,
                             ChatAttachmentService attachments, UserWebSocketHandler websocket,
                             @Value("${skillforge.media.staging-root:./data/media-staging}") String stagingRoot) {
        this.store = store; this.attachments = attachments; this.websocket = websocket;
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(VideoGenerationProvider::name, p -> p));
        this.stagingRoot = Path.of(stagingRoot).toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelayString = "${skillforge.media.worker.poll-ms:5000}", initialDelayString = "${skillforge.media.worker.initial-delay-ms:5000}")
    public void poll() {
        Instant now = Instant.now();
        int abandoned = store.failStaleSubmitting(now.minusSeconds(120));
        if (abandoned > 0) log.warn("Marked [{}] ambiguous media submissions as SUBMIT_UNKNOWN", abandoned);
        for (MediaGenerationJobEntity candidate : store.due(now)) {
            try { if (store.acquire(candidate.getId(), workerId, now)) process(store.require(candidate.getId())); }
            catch (RuntimeException e) { log.warn("Media job processing failed for job [{}]: {}", candidate.getId(), e.getMessage()); }
        }
    }

    private void process(MediaGenerationJobEntity job) {
        VideoGenerationProvider provider = providers.get(job.getProvider());
        if (provider == null) { store.postpone(job.getId(), Instant.now().plusSeconds(30)); return; }
        VideoGenerationProvider.VideoStatus remote = provider.get(job.getProviderJobId());
        MediaGenerationJobEntity updated = store.recordPoll(job.getId(), remote);
        if ("DOWNLOADING".equals(updated.getStatus())) download(provider, updated, remote.resultUrl());
        else broadcast(updated);
    }

    private void download(VideoGenerationProvider provider, MediaGenerationJobEntity job, String url) {
        if (url == null || url.isBlank()) { store.markDownloadFailed(job.getId(), "Provider result URL is missing"); return; }
        Path workspace = stagingRoot.resolve(job.getId()).normalize();
        Path target = workspace.resolve("result.mp4").normalize();
        if (!target.startsWith(stagingRoot)) throw new SecurityException("Media staging path is invalid");
        try {
            provider.download(url, target);
            ChatAttachmentEntity attachment = attachments.importGeneratedFile(job.getSessionId(), job.getUserId(),
                    job.getSourceToolUseId(), target, "Generated video", workspace);
            broadcast(store.markReady(job.getId(), attachment.getId()));
        } catch (RuntimeException e) {
            broadcast(store.markDownloadFailed(job.getId(), e.getMessage()));
        } finally {
            try { Files.deleteIfExists(target); Files.deleteIfExists(workspace); }
            catch (Exception e) { log.warn("Failed to clean media staging directory for job [{}]", job.getId()); }
        }
    }

    private void broadcast(MediaGenerationJobEntity job) {
        websocket.broadcast(job.getUserId(), Map.of("type", "media_job_updated", "sessionId", job.getSessionId(),
                "jobId", job.getId(), "status", job.getStatus(), "updatedAt", job.getUpdatedAt().toString()));
    }
}
