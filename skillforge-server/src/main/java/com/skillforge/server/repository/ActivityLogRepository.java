package com.skillforge.server.repository;

import com.skillforge.server.entity.ActivityLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ActivityLogRepository extends JpaRepository<ActivityLogEntity, Long> {

    List<ActivityLogEntity> findByUserIdAndSessionIdOrderByCreatedAtAsc(Long userId, String sessionId);

    List<ActivityLogEntity> findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(Long userId, LocalDateTime since);
}
