package com.skillforge.server.repository;

import com.skillforge.server.entity.SubAgentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubAgentRunRepository extends JpaRepository<SubAgentRunEntity, String> {

    List<SubAgentRunEntity> findByParentSessionId(String parentSessionId);

    List<SubAgentRunEntity> findByStatus(String status);
}
