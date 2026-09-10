package com.skillforge.core.engine.durability;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Server-authoritative immutable acknowledgement of a complete ordered Tool result batch. */
public record ToolResultCommitAck(
        long attemptId,
        UUID stepId,
        UUID resultBatchId,
        LoopDurabilityScope executionScope,
        long executionGeneration,
        List<PersistedMessageOccurrence> results,
        List<PersistedBlockOccurrence> resultBlocks,
        DurableFrontier preResultFrontier,
        DurableFrontier postResultFrontier,
        String assistantPayloadHash,
        String manifestHash) {

    public ToolResultCommitAck {
        if (attemptId <= 0L) throw new IllegalArgumentException("attemptId must be positive");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(resultBatchId, "resultBatchId");
        Objects.requireNonNull(executionScope, "executionScope");
        if (executionGeneration <= 0L) {
            throw new IllegalArgumentException("executionGeneration must be positive");
        }
        results = results == null ? List.of() : List.copyOf(results);
        if (results.isEmpty() || results.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("results must contain the complete non-null vector");
        }
        resultBlocks = resultBlocks == null ? List.of() : List.copyOf(resultBlocks);
        if (resultBlocks.isEmpty() || resultBlocks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "resultBlocks must contain the complete non-null vector");
        }
        if (resultBlocks.size() != results.size()) {
            throw new IllegalArgumentException("resultBlocks must align with results");
        }
        Objects.requireNonNull(preResultFrontier, "preResultFrontier");
        Objects.requireNonNull(postResultFrontier, "postResultFrontier");
        requireHash(assistantPayloadHash, "assistantPayloadHash");
        requireHash(manifestHash, "manifestHash");
    }

    private static void requireHash(String hash, String field) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 hex value");
        }
    }
}
