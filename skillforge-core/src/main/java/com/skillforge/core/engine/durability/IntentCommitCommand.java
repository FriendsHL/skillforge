package com.skillforge.core.engine.durability;

import java.util.Objects;
import java.util.UUID;

/** Stable, retryable request to persist an assistant Tool intent before execution. */
public record IntentCommitCommand(
        LoopDurabilityScope origin,
        UUID stepId,
        String writeBatchId,
        MessageSnapshot assistant,
        ToolCallManifest manifest,
        DurableFrontier expectedPreIntentFrontier,
        String traceId) {

    public IntentCommitCommand {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(stepId, "stepId");
        if (writeBatchId == null || writeBatchId.isBlank()) {
            throw new IllegalArgumentException("writeBatchId must not be blank");
        }
        Objects.requireNonNull(assistant, "assistant");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(expectedPreIntentFrontier, "expectedPreIntentFrontier");
    }
}
