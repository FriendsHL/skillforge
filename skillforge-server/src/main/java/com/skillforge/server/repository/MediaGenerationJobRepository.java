package com.skillforge.server.repository;

import com.skillforge.server.entity.MediaGenerationJobEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MediaGenerationJobRepository extends JpaRepository<MediaGenerationJobEntity, String> {
    Optional<MediaGenerationJobEntity> findBySessionIdAndSourceToolUseId(String sessionId, String sourceToolUseId);
    Page<MediaGenerationJobEntity> findByUserId(Long userId, Pageable pageable);
    List<MediaGenerationJobEntity> findBySessionIdAndUserIdOrderByCreatedAtAsc(String sessionId, Long userId);
    List<MediaGenerationJobEntity> findTop20ByStatusInAndNextPollAtLessThanEqualOrderByNextPollAtAsc(
            Collection<String> statuses, Instant now);
    List<MediaGenerationJobEntity> findTop20ByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
            String status, Instant before);

    @Modifying
    @Query("UPDATE MediaGenerationJobEntity j SET j.leaseOwner=:owner, j.leaseExpiresAt=:expires "
            + "WHERE j.id=:id AND (j.leaseExpiresAt IS NULL OR j.leaseExpiresAt < :now)")
    int acquireLease(@Param("id") String id, @Param("owner") String owner,
                     @Param("now") Instant now, @Param("expires") Instant expires);
}
