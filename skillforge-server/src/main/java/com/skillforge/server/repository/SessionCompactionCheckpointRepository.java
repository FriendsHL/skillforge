package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionCompactionCheckpointEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionCompactionCheckpointRepository
        extends JpaRepository<SessionCompactionCheckpointEntity, String> {

    Page<SessionCompactionCheckpointEntity> findBySessionIdOrderByCreatedAtDesc(
            String sessionId, Pageable pageable);

    @Modifying
    @Query("""
            DELETE FROM SessionCompactionCheckpointEntity c
            WHERE c.sessionId = :sessionId
              AND (c.postRangeEndSeqNo IS NULL OR c.postRangeEndSeqNo > :seqNo)
            """)
    void deleteBySessionIdAfterSeqNo(@Param("sessionId") String sessionId,
                                     @Param("seqNo") Long seqNo);
}
