package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionTaskEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionTaskEventRepository extends JpaRepository<SessionTaskEventEntity, Long> {
    List<SessionTaskEventEntity> findByTaskIdOrderByCreatedAtAscIdAsc(String taskId);
}
