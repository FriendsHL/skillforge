package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionTaskDependencyEntity;
import com.skillforge.server.entity.SessionTaskDependencyId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionTaskDependencyRepository extends JpaRepository<SessionTaskDependencyEntity, SessionTaskDependencyId> {
    List<SessionTaskDependencyEntity> findBySessionId(String sessionId);
}
