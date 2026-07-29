package com.skillforge.core.engine;

/** Content-free provenance metadata for one memory rendered into the prompt. */
public record MemoryInjectionRef(
        Long memoryId,
        String provenanceSource,
        String confirmationStatus,
        Double confidence,
        Long version) {
}
