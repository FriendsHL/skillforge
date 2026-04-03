package com.skillforge.server.repository;

import com.skillforge.server.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    List<SessionEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<SessionEntity> findByAgentId(Long agentId);

    long countByAgentId(Long agentId);
}
