package com.skillforge.server.dto;

import java.time.Instant;

public record SessionCompactionCheckpointDto(
        String id,
        String sessionId,
        long boundarySeqNo,
        Long summarySeqNo,
        String reason,
        Long preRangeStartSeqNo,
        Long preRangeEndSeqNo,
        Long postRangeStartSeqNo,
        Long postRangeEndSeqNo,
        String snapshotRef,
        Instant createdAt
) {
}
