package com.skillforge.core.engine.durability;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable archive gate acknowledgement; both states allow continuation without Tool replay. */
public record ArchivePreparationAck(
        long attemptId,
        UUID stepId,
        UUID resultBatchId,
        LoopDurabilityScope executionScope,
        long executionGeneration,
        ArchivePreparationState state,
        int preparedCount,
        int totalCount,
        List<PersistedBlockOccurrence> resultBlocks) {

    public ArchivePreparationAck {
        if (attemptId <= 0L) throw new IllegalArgumentException("attemptId must be positive");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(resultBatchId, "resultBatchId");
        Objects.requireNonNull(executionScope, "executionScope");
        if (executionGeneration <= 0L) {
            throw new IllegalArgumentException("executionGeneration must be positive");
        }
        Objects.requireNonNull(state, "state");
        if (preparedCount < 0 || totalCount <= 0 || preparedCount > totalCount) {
            throw new IllegalArgumentException("archive preparation counts are invalid");
        }
        if (state == ArchivePreparationState.PREPARED && preparedCount != totalCount) {
            throw new IllegalArgumentException("PREPARED must cover the complete vector");
        }
        if (state == ArchivePreparationState.RAW_FALLBACK && preparedCount != 0) {
            throw new IllegalArgumentException("RAW_FALLBACK must retain the complete raw vector");
        }
        resultBlocks = resultBlocks == null ? List.of() : List.copyOf(resultBlocks);
        if (resultBlocks.size() != totalCount || resultBlocks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("resultBlocks must match totalCount");
        }
    }
}
