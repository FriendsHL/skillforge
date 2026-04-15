package com.skillforge.server.repository;

import com.skillforge.server.entity.CollabRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface CollabRunRepository extends JpaRepository<CollabRunEntity, String> {

    List<CollabRunEntity> findByStatus(String status);

    List<CollabRunEntity> findAllByOrderByCreatedAtDesc();

    /**
     * Atomically set status=COMPLETED only if currently RUNNING.
     * Returns 1 if updated, 0 if already COMPLETED/CANCELLED (TOCTOU guard).
     */
    @Modifying
    @Query("UPDATE CollabRunEntity c SET c.status = 'COMPLETED', c.completedAt = :completedAt WHERE c.collabRunId = :id AND c.status = 'RUNNING'")
    int completeIfRunning(@Param("id") String collabRunId, @Param("completedAt") Instant completedAt);
}
