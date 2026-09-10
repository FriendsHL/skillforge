package com.skillforge.core.engine.durability;

import java.util.Objects;
import java.util.UUID;

/** Server-authoritative immutable result of an intent transaction. */
public record IntentCommitAck(
        long attemptId,
        UUID stepId,
        PersistedMessageOccurrence assistant,
        DurableFrontier preIntentFrontier,
        ToolCallManifest manifest,
        String assistantPayloadHash,
        String manifestHash,
        ReplaySafety replaySafety) {

    public IntentCommitAck {
        if (attemptId <= 0) throw new IllegalArgumentException("attemptId must be positive");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(assistant, "assistant");
        Objects.requireNonNull(preIntentFrontier, "preIntentFrontier");
        Objects.requireNonNull(manifest, "manifest");
        requireHash(assistantPayloadHash, "assistantPayloadHash");
        requireHash(manifestHash, "manifestHash");
        Objects.requireNonNull(replaySafety, "replaySafety");
        if (replaySafety != manifest.replaySafety()) {
            throw new IllegalArgumentException("ACK replaySafety must match manifest");
        }
    }

    private static void requireHash(String hash, String field) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 hex value");
        }
    }
}
