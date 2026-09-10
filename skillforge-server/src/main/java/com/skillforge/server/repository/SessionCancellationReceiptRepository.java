package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionCancellationReceiptEntity;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/** Deliberately exposes no update/delete API for append-only cancellation receipts. */
public interface SessionCancellationReceiptRepository
        extends Repository<SessionCancellationReceiptEntity, UUID> {

    Optional<SessionCancellationReceiptEntity> findById(UUID requestId);

    Optional<SessionCancellationReceiptEntity>
            findBySessionIdAndHistoryEpochAndTargetLoopIdAndTargetLoopFenceAndTargetOwnerInstanceId(
                    String sessionId,
                    long historyEpoch,
                    String targetLoopId,
                    long targetLoopFence,
                    String targetOwnerInstanceId);

    <S extends SessionCancellationReceiptEntity> S saveAndFlush(S receipt);
}
