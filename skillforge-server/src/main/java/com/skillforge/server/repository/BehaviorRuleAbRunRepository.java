package com.skillforge.server.repository;

import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BehaviorRuleAbRunRepository extends JpaRepository<BehaviorRuleAbRunEntity, String> {

    List<BehaviorRuleAbRunEntity> findByAgentIdAndStatus(String agentId, String status);

    List<BehaviorRuleAbRunEntity> findByCandidateVersionIdOrderByStartedAtDesc(String candidateVersionId);
}
