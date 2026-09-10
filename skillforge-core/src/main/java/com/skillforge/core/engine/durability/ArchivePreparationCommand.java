package com.skillforge.core.engine.durability;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Winning result identity submitted to occurrence archive preparation before continuation. */
public record ArchivePreparationCommand(
        LoopDurabilityScope executionScope,
        long attemptId,
        UUID stepId,
        UUID resultBatchId,
        long executionGeneration,
        String assistantPayloadHash,
        String manifestHash,
        List<PersistedBlockOccurrence> resultBlocks,
        LoopDurabilityScope coordinatorScope) {

    public ArchivePreparationCommand {
        Objects.requireNonNull(executionScope, "executionScope");
        if (attemptId <= 0L) throw new IllegalArgumentException("attemptId must be positive");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(resultBatchId, "resultBatchId");
        if (executionGeneration <= 0L) {
            throw new IllegalArgumentException("executionGeneration must be positive");
        }
        requireHash(assistantPayloadHash, "assistantPayloadHash");
        requireHash(manifestHash, "manifestHash");
        resultBlocks = resultBlocks == null ? List.of() : List.copyOf(resultBlocks);
        if (resultBlocks.isEmpty() || resultBlocks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("resultBlocks must not be empty");
        }
        Objects.requireNonNull(coordinatorScope, "coordinatorScope");
        if (!executionScope.sessionId().equals(coordinatorScope.sessionId())
                || executionScope.userId() != coordinatorScope.userId()
                || executionScope.historyEpoch() != coordinatorScope.historyEpoch()) {
            throw new IllegalArgumentException(
                    "execution and coordinator scopes must identify the same Session epoch");
        }
    }

    public static ArchivePreparationCommand from(ToolResultCommitAck resultAck) {
        Objects.requireNonNull(resultAck, "resultAck");
        return new ArchivePreparationCommand(
                resultAck.executionScope(),
                resultAck.attemptId(),
                resultAck.stepId(),
                resultAck.resultBatchId(),
                resultAck.executionGeneration(),
                resultAck.assistantPayloadHash(),
                resultAck.manifestHash(),
                resultAck.resultBlocks(),
                resultAck.executionScope());
    }

    /** Rebinds continuation authority after a fenced restart without changing result identity. */
    public ArchivePreparationCommand withCoordinatorScope(LoopDurabilityScope newCoordinatorScope) {
        return new ArchivePreparationCommand(
                executionScope, attemptId, stepId, resultBatchId, executionGeneration,
                assistantPayloadHash, manifestHash, resultBlocks, newCoordinatorScope);
    }

    private static void requireHash(String hash, String field) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 hex value");
        }
    }
}
