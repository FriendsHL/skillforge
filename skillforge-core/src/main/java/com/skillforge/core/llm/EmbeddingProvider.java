package com.skillforge.core.llm;

/**
 * Text embedding interface, corresponding to OpenAI-compatible POST /v1/embeddings.
 * Implementations are not required to be thread-safe: callers serialize access
 * through EmbeddingService.
 */
public interface EmbeddingProvider {

    /**
     * Convert text to a vector.
     *
     * @param text input text
     * @return float vector, length determined by the model
     * @throws EmbeddingNotSupportedException when the provider does not support embeddings
     */
    float[] embed(String text);

    /**
     * Vector dimension, used for DDL when creating the vector column.
     * Default 1536.
     */
    default int dimension() {
        return 1536;
    }
}
