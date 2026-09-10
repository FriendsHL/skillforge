package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionToolAttemptEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionToolAttemptRepository extends JpaRepository<SessionToolAttemptEntity, Long> {

    Optional<SessionToolAttemptEntity> findBySessionIdAndStepId(String sessionId, UUID stepId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM SessionToolAttemptEntity a "
            + "WHERE a.sessionId = :sessionId AND a.stepId = :stepId")
    Optional<SessionToolAttemptEntity> findBySessionIdAndStepIdForUpdate(
            @Param("sessionId") String sessionId,
            @Param("stepId") UUID stepId);

    Optional<SessionToolAttemptEntity> findByAssistantMessageId(Long assistantMessageId);

    List<SessionToolAttemptEntity> findBySessionIdAndStateIn(
            String sessionId, Collection<String> states);

    List<SessionToolAttemptEntity> findBySessionIdAndStateOrderByIdAsc(
            String sessionId, String state, Pageable pageable);

    List<SessionToolAttemptEntity> findByPostActionStateInOrderByIdAsc(
            Collection<String> states, Pageable pageable);

    Optional<SessionToolAttemptEntity> findBySessionIdAndId(String sessionId, Long id);

    long countBySessionIdAndPostActionState(String sessionId, String postActionState);

    /** All prior-generation attempts become obsolete after an in-place restore epoch bump. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SessionToolAttemptEntity a WHERE a.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM SessionToolAttemptEntity a WHERE a.id = :id AND a.sessionId = :sessionId")
    Optional<SessionToolAttemptEntity> findBySessionIdAndIdForUpdate(
            @Param("sessionId") String sessionId,
            @Param("id") Long id);
}
