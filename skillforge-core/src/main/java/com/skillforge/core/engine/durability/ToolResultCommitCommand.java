package com.skillforge.core.engine.durability;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable request to close one winning Tool execution with its complete ordered result vector. */
public record ToolResultCommitCommand(
        LoopDurabilityScope executionScope,
        long attemptId,
        UUID stepId,
        UUID claimRequestId,
        long executionGeneration,
        UUID resultBatchId,
        List<MessageSnapshot> results,
        DurableFrontier expectedPreResultFrontier,
        String assistantPayloadHash,
        String manifestHash,
        String traceId) {

    public ToolResultCommitCommand {
        Objects.requireNonNull(executionScope, "executionScope");
        if (attemptId <= 0L) throw new IllegalArgumentException("attemptId must be positive");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(claimRequestId, "claimRequestId");
        if (executionGeneration <= 0L) {
            throw new IllegalArgumentException("executionGeneration must be positive");
        }
        Objects.requireNonNull(resultBatchId, "resultBatchId");
        results = results == null ? List.of() : List.copyOf(results);
        if (results.isEmpty() || results.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("results must contain the complete non-null vector");
        }
        Objects.requireNonNull(expectedPreResultFrontier, "expectedPreResultFrontier");
        requireHash(assistantPayloadHash, "assistantPayloadHash");
        requireHash(manifestHash, "manifestHash");
    }

    private static void requireHash(String hash, String field) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 hex value");
        }
    }
}
