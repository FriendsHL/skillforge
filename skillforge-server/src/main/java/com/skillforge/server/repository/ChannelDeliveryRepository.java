package com.skillforge.server.repository;

import com.skillforge.server.entity.ChannelDeliveryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ChannelDeliveryRepository extends JpaRepository<ChannelDeliveryEntity, String> {

    /**
     * Atomic CTE: pick batch FOR UPDATE SKIP LOCKED, mark IN_FLIGHT, return ids.
     * Split from the HTTP call so the DB connection is released during delivery.
     * Wrap UPDATE ... RETURNING in a CTE so Hibernate treats this as a SELECT.
     * {@code @Modifying(clearAutomatically = true)} tells Hibernate to flush the
     * persistence context so stale entity copies don't override the status we
     * just set, and re-read is forced on subsequent findById calls within the
     * same transaction.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            WITH claimed AS (
                SELECT id FROM t_channel_delivery
                WHERE status IN ('PENDING','RETRY') AND scheduled_at <= :now
                ORDER BY scheduled_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            ), updated AS (
                UPDATE t_channel_delivery d
                SET status = 'IN_FLIGHT', scheduled_at = :now
                FROM claimed c
                WHERE d.id = c.id
                RETURNING d.id
            )
            SELECT id FROM updated
            """, nativeQuery = true)
    List<String> claimBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /** Reset orphaned IN_FLIGHT rows older than cutoff to PENDING (restart recovery). */
    @Modifying
    @Query(value = """
            UPDATE t_channel_delivery
            SET status = 'PENDING'
            WHERE status = 'IN_FLIGHT' AND scheduled_at <= :cutoff
            """, nativeQuery = true)
    int resetOrphanedInFlight(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("UPDATE ChannelDeliveryEntity d SET d.status = 'DELIVERED', " +
           "d.deliveredAt = :now WHERE d.id = :id")
    int markDelivered(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE ChannelDeliveryEntity d SET d.status = 'FAILED', d.lastError = :reason " +
           "WHERE d.id = :id")
    int markFailed(@Param("id") String id, @Param("reason") String reason);

    @Modifying
    @Query("UPDATE ChannelDeliveryEntity d SET d.status = 'RETRY', " +
           "d.retryCount = :retryCount, d.scheduledAt = :scheduledAt, d.lastError = :reason " +
           "WHERE d.id = :id")
    int scheduleRetry(@Param("id") String id,
                      @Param("retryCount") int retryCount,
                      @Param("scheduledAt") Instant scheduledAt,
                      @Param("reason") String reason);

    /**
     * Operator-initiated retry: reset to PENDING with scheduledAt=NOW() so the next
     * poller tick claims it. Retry count is preserved. Status must not be
     * DELIVERED or IN_FLIGHT — caller guards that.
     */
    @Modifying
    @Query("UPDATE ChannelDeliveryEntity d SET d.status = 'PENDING', " +
           "d.scheduledAt = :now WHERE d.id = :id")
    int requeue(@Param("id") String id, @Param("now") Instant now);

    Page<ChannelDeliveryEntity> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<ChannelDeliveryEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
