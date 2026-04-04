package com.skillforge.server.repository;

import com.skillforge.server.entity.SubAgentTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubAgentTaskRepository extends JpaRepository<SubAgentTaskEntity, String> {

    List<SubAgentTaskEntity> findByParentSessionId(String parentSessionId);

    List<SubAgentTaskEntity> findByStatus(String status);
}
