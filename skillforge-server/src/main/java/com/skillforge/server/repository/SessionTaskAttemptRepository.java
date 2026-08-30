package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionTaskAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SessionTaskAttemptRepository extends JpaRepository<SessionTaskAttemptEntity, String> {
    Optional<SessionTaskAttemptEntity> findByTaskIdAndStatus(String taskId, String status);
    Optional<SessionTaskAttemptEntity> findByIdAndStatus(String id, String status);
    List<SessionTaskAttemptEntity> findByWorkerSessionIdAndStatus(String workerSessionId, String status);
    List<SessionTaskAttemptEntity> findByGraphSessionIdAndStatus(String graphSessionId, String status);
    List<SessionTaskAttemptEntity> findByStatusOrderByExpiresAtAsc(String status);
    List<SessionTaskAttemptEntity> findByTaskIdOrderByAttemptNoDesc(String taskId);

    @Query("SELECT COALESCE(MAX(a.attemptNo), 0) FROM SessionTaskAttemptEntity a WHERE a.taskId = :taskId")
    int findMaxAttemptNo(@Param("taskId") String taskId);

    @Query("SELECT a.id FROM SessionTaskAttemptEntity a WHERE a.status = :status ORDER BY a.expiresAt ASC")
    List<String> findIdsByStatusOrderByExpiresAtAsc(@Param("status") String status);
}
