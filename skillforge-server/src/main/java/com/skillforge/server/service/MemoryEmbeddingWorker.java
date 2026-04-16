package com.skillforge.server.service;

import com.skillforge.server.repository.MemoryRepository;
import com.skillforge.server.util.VectorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async worker for computing and persisting memory embeddings.
 * Separated from MemoryService so that Spring's @Async proxy works correctly
 * (self-invocation within the same class bypasses the proxy).
 */
@Component
public class MemoryEmbeddingWorker {

    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingWorker.class);

    private final EmbeddingService embeddingService;
    private final MemoryRepository memoryRepository;

    public MemoryEmbeddingWorker(EmbeddingService embeddingService, MemoryRepository memoryRepository) {
        this.embeddingService = embeddingService;
        this.memoryRepository = memoryRepository;
    }

    /**
     * Asynchronously compute and store the embedding for a memory entry.
     * Failures are logged and silently ignored; the embedding column stays null
     * and search degrades to FTS-only for that entry.
     */
    @Async
    public void triggerEmbeddingAsync(Long memoryId, String text) {
        if (text == null || text.isBlank()) {
            log.debug("Skipping embedding for memoryId={}: empty text", memoryId);
            return;
        }
        try {
            embeddingService.embed(text).ifPresent(vec ->
                    memoryRepository.updateEmbedding(memoryId, VectorUtils.toVectorString(vec)));
        } catch (Exception e) {
            log.warn("Failed to compute embedding for memoryId={}: {}", memoryId, e.getMessage());
        }
    }
}
