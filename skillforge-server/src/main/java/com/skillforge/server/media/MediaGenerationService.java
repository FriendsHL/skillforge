package com.skillforge.server.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.MediaGenerationJobEntity;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MediaGenerationService {
    private final MediaJobStore store;
    private final Map<String, VideoGenerationProvider> providers;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Object> submissionLocks = new ConcurrentHashMap<>();

    public MediaGenerationService(MediaJobStore store, java.util.List<VideoGenerationProvider> providers,
                                  ObjectMapper objectMapper) {
        this.store = store; this.objectMapper = objectMapper;
        this.providers = providers.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(VideoGenerationProvider::name, p -> p));
    }

    public MediaGenerationJobEntity submitVideo(Long userId, String sessionId, String toolUseId,
                                                 VideoGenerationProvider.VideoRequest request) {
        String key = sessionId + ":" + toolUseId;
        Object lock = submissionLocks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                var replay = store.replay(sessionId, toolUseId);
                if (replay.isPresent()) return replay.get();
                VideoGenerationProvider provider = providers.get("ark");
                if (provider == null) throw new IllegalStateException("Video generation provider is not enabled");
                MediaGenerationJobEntity row = store.createSubmitting(userId, sessionId, toolUseId, "ark", model(provider), json(request));
                if (!"SUBMITTING".equals(row.getStatus())) return row;
                try {
                    return store.markQueued(row.getId(), provider.submit(request));
                } catch (RuntimeException e) {
                    store.markSubmitFailed(row.getId(), "PROVIDER_SUBMIT_FAILED", e.getMessage());
                    throw e;
                }
            }
        } finally {
            submissionLocks.remove(key, lock);
        }
    }

    public MediaGenerationJobEntity cancel(String jobId) {
        MediaGenerationJobEntity row = store.require(jobId);
        if (java.util.Set.of("READY", "CANCELLED", "SUBMIT_FAILED", "SUBMIT_UNKNOWN",
                "GENERATION_FAILED", "DOWNLOAD_FAILED", "PROCESSING_FAILED", "EXPIRED")
                .contains(row.getStatus())) {
            throw new IllegalStateException("Media job is already terminal");
        }
        VideoGenerationProvider provider = providers.get(row.getProvider());
        if (provider == null || row.getProviderJobId() == null) {
            throw new IllegalStateException("Media job cannot be cancelled before provider submission completes");
        }
        provider.cancel(row.getProviderJobId());
        return store.markCancelled(jobId);
    }

    private String model(VideoGenerationProvider provider) {
        return provider instanceof ArkVideoGenerationClient ? "doubao-seedance-1-5-pro-251215" : provider.name();
    }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
}
