package com.skillforge.server.repository;

import com.skillforge.server.entity.AgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRepository extends JpaRepository<AgentEntity, Long> {

    List<AgentEntity> findByOwnerId(Long ownerId);

    List<AgentEntity> findByStatus(String status);

    List<AgentEntity> findByIsPublicTrue();
}
