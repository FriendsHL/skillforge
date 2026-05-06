package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalScenarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalScenarioDraftRepository extends JpaRepository<EvalScenarioEntity, String> {

    List<EvalScenarioEntity> findByAgentIdOrderByCreatedAtDesc(String agentId);

    List<EvalScenarioEntity> findByAgentId(String agentId);

    List<EvalScenarioEntity> findByStatus(String status);

    List<EvalScenarioEntity> findByAgentIdAndStatus(String agentId, String status);
}
