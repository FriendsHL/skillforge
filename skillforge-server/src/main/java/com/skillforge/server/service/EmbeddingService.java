package com.skillforge.server.service;

import com.skillforge.core.llm.EmbeddingNotSupportedException;
import com.skillforge.core.llm.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Embedding service.
 *
 * <p>Reuses the configured OpenAI-compatible provider; when the provider does not
 * support embeddings, returns {@code Optional.empty()} so callers fall back to FTS-only
 * without errors.
 *
 * <p>Does not introduce Ollama or other new external dependencies.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingProvider provider;

    public EmbeddingService(EmbeddingProvider provider) {
        this.provider = provider;
    }

    /**
     * Generate a text embedding vector.
     *
     * @return the vector, or empty (provider unavailable / call failed)
     */
    public Optional<float[]> embed(String text) {
        try {
            return Optional.of(provider.embed(text));
        } catch (EmbeddingNotSupportedException e) {
            log.debug("Embedding not supported: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Embedding failed, falling back to FTS-only: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
