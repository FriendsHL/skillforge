package com.skillforge.core.llm;

public class EmbeddingNotSupportedException extends RuntimeException {
    public EmbeddingNotSupportedException(String providerName) {
        super("Provider '" + providerName + "' does not support embeddings");
    }
}
