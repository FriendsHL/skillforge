package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionCompactionCheckpointEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SessionCompactionCheckpointRepository
        extends JpaRepository<SessionCompactionCheckpointEntity, String> {

    @Query("""
            SELECT c FROM SessionCompactionCheckpointEntity c
            WHERE c.sessionId = :sessionId
            ORDER BY CASE WHEN c.sidecarWatermark IS NULL THEN 1 ELSE 0 END,
                     c.sidecarWatermark DESC
            """)
    Page<SessionCompactionCheckpointEntity> findTimelineBySessionId(
            @Param("sessionId") String sessionId, Pageable pageable);

    @Query("SELECT c.sidecarWatermark FROM SessionCompactionCheckpointEntity c WHERE c.id = :id")
    Optional<Long> findSidecarWatermarkById(@Param("id") String id);

    @Modifying
    @Query("""
            DELETE FROM SessionCompactionCheckpointEntity c
            WHERE c.sessionId = :sessionId
              AND c.id <> :retainedCheckpointId
              AND c.sidecarWatermark > :sidecarWatermark
            """)
    int deleteBySessionIdAfterSidecarWatermark(
            @Param("sessionId") String sessionId,
            @Param("retainedCheckpointId") String retainedCheckpointId,
            @Param("sidecarWatermark") long sidecarWatermark);
}
