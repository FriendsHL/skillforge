package com.skillforge.server.media;

import com.skillforge.server.entity.MediaGenerationJobEntity;
import com.skillforge.server.repository.MediaGenerationJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.Set;

@Service
public class MediaJobStore {
    private final MediaGenerationJobRepository repository;
    public MediaJobStore(MediaGenerationJobRepository repository) { this.repository = repository; }

    @Transactional
    public MediaGenerationJobEntity createSubmitting(Long userId, String sessionId, String toolUseId,
                                                      String provider, String model, String requestJson) {
        Optional<MediaGenerationJobEntity> existing = repository.findBySessionIdAndSourceToolUseId(sessionId, toolUseId);
        if (existing.isPresent()) return existing.get();
        Instant now = Instant.now();
        MediaGenerationJobEntity row = new MediaGenerationJobEntity();
        row.setId(UUID.randomUUID().toString()); row.setUserId(userId); row.setSessionId(sessionId);
        row.setSourceToolUseId(toolUseId); row.setIdempotencyKey(sessionId + ":" + toolUseId);
        row.setMediaType("video"); row.setOperation("generate"); row.setProvider(provider); row.setModel(model);
        row.setStatus("SUBMITTING"); row.setRequestJson(requestJson); row.setCreatedAt(now); row.setUpdatedAt(now);
        return repository.saveAndFlush(row);
    }

    @Transactional
    public MediaGenerationJobEntity markQueued(String id, VideoGenerationProvider.SubmittedVideo submitted) {
        MediaGenerationJobEntity row = require(id); Instant now = Instant.now();
        row.setProviderJobId(submitted.providerJobId()); row.setProviderMetadataJson(
                submitted.requestId() == null ? null : "{\"requestId\":\"" + safe(submitted.requestId()) + "\"}");
        row.setStatus("QUEUED"); row.setSubmittedAt(now); row.setNextPollAt(now.plusSeconds(5)); row.setUpdatedAt(now);
        return repository.save(row);
    }

    @Transactional
    public MediaGenerationJobEntity markSubmitFailed(String id, String code, String message) {
        MediaGenerationJobEntity row = require(id); row.setStatus("SUBMIT_FAILED"); row.setErrorCode(code);
        row.setErrorMessage(limit(message)); row.setCompletedAt(Instant.now()); row.setUpdatedAt(Instant.now());
        return repository.save(row);
    }

    @Transactional(readOnly = true) public MediaGenerationJobEntity require(String id) { return repository.findById(id).orElseThrow(); }
    @Transactional(readOnly = true) public Optional<MediaGenerationJobEntity> replay(String sessionId, String toolUseId) { return repository.findBySessionIdAndSourceToolUseId(sessionId, toolUseId); }
    @Transactional(readOnly = true)
    public List<MediaGenerationJobEntity> due(Instant now) {
        return repository.findTop20ByStatusInAndNextPollAtLessThanEqualOrderByNextPollAtAsc(
                Set.of("QUEUED", "RUNNING", "DOWNLOADING"), now);
    }
    @Transactional
    public int failStaleSubmitting(Instant before) {
        List<MediaGenerationJobEntity> stale = repository
                .findTop20ByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc("SUBMITTING", before);
        stale.forEach(row -> {
            row.setStatus("SUBMIT_UNKNOWN");
            row.setErrorCode("SUBMIT_OUTCOME_UNKNOWN");
            row.setErrorMessage("Service stopped while provider submission was in progress; automatic resubmission was suppressed to avoid duplicate billing.");
            row.setCompletedAt(Instant.now());
            row.setUpdatedAt(Instant.now());
        });
        repository.saveAll(stale);
        return stale.size();
    }
    @Transactional public boolean acquire(String id, String owner, Instant now) {
        return repository.acquireLease(id, owner, now, now.plusSeconds(60)) == 1;
    }
    @Transactional
    public void postpone(String id, Instant next) {
        MediaGenerationJobEntity row = require(id); row.setNextPollAt(next); row.setLeaseOwner(null); row.setLeaseExpiresAt(null); row.setUpdatedAt(Instant.now()); repository.save(row);
    }
    @Transactional(readOnly = true)
    public List<MediaGenerationJobEntity> listSession(String sessionId, Long userId) {
        return repository.findBySessionIdAndUserIdOrderByCreatedAtAsc(sessionId, userId);
    }
    @Transactional
    public MediaGenerationJobEntity recordPoll(String id, VideoGenerationProvider.VideoStatus status) {
        MediaGenerationJobEntity row = require(id); Instant now = Instant.now();
        row.setAttemptCount(row.getAttemptCount() + 1); row.setLeaseOwner(null); row.setLeaseExpiresAt(null); row.setUpdatedAt(now);
        switch (status.state().toLowerCase()) {
            case "queued" -> { row.setStatus("QUEUED"); row.setNextPollAt(now.plusSeconds(5)); }
            case "running" -> { row.setStatus("RUNNING"); if (row.getStartedAt() == null) row.setStartedAt(now); row.setNextPollAt(now.plusSeconds(5)); }
            case "succeeded" -> { row.setStatus("DOWNLOADING"); row.setNextPollAt(now); }
            case "failed" -> { row.setStatus("GENERATION_FAILED"); row.setErrorCode(status.errorCode() == null ? "PROVIDER_FAILED" : status.errorCode()); row.setErrorMessage(limit(status.errorMessage())); row.setCompletedAt(now); row.setNextPollAt(null); }
            case "cancelled" -> { row.setStatus("CANCELLED"); row.setCompletedAt(now); row.setNextPollAt(null); }
            default -> { row.setNextPollAt(now.plusSeconds(10)); }
        }
        return repository.save(row);
    }
    @Transactional
    public MediaGenerationJobEntity markReady(String id, String attachmentId) {
        MediaGenerationJobEntity row = require(id); Instant now = Instant.now(); row.setResultAttachmentId(attachmentId);
        row.setStatus("READY"); row.setCompletedAt(now); row.setUpdatedAt(now); row.setNextPollAt(null); row.setLeaseOwner(null); row.setLeaseExpiresAt(null);
        return repository.save(row);
    }
    @Transactional
    public MediaGenerationJobEntity markDownloadFailed(String id, String message) {
        MediaGenerationJobEntity row = require(id); row.setStatus("DOWNLOAD_FAILED"); row.setErrorCode("DOWNLOAD_FAILED");
        row.setErrorMessage(limit(message)); row.setCompletedAt(Instant.now()); row.setUpdatedAt(Instant.now()); row.setNextPollAt(null); row.setLeaseOwner(null); row.setLeaseExpiresAt(null);
        return repository.save(row);
    }
    @Transactional
    public MediaGenerationJobEntity markCancelled(String id) {
        MediaGenerationJobEntity row = require(id); row.setStatus("CANCELLED"); row.setCompletedAt(Instant.now()); row.setUpdatedAt(Instant.now()); row.setNextPollAt(null);
        return repository.save(row);
    }
    private static String limit(String v) { if (v == null) return null; return v.length() <= 1000 ? v : v.substring(0, 1000); }
    private static String safe(String v) { return v.replace("\\", "").replace("\"", ""); }
}
