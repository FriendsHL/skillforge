package com.skillforge.server.repository;

import com.skillforge.server.entity.AgentAuthoredHookEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentAuthoredHookRepository extends JpaRepository<AgentAuthoredHookEntity, Long> {

    List<AgentAuthoredHookEntity> findByTargetAgentIdOrderByIdAsc(Long targetAgentId);

    List<AgentAuthoredHookEntity> findByTargetAgentIdAndReviewStateOrderByIdAsc(Long targetAgentId,
                                                                                String reviewState);

    List<AgentAuthoredHookEntity> findByTargetAgentIdAndEventAndReviewStateAndEnabledTrueOrderByIdAsc(
            Long targetAgentId,
            String event,
            String reviewState);

    boolean existsByTargetAgentIdAndEventAndMethodKindAndMethodIdAndMethodRefAndReviewStateIn(
            Long targetAgentId,
            String event,
            String methodKind,
            Long methodId,
            String methodRef,
            List<String> reviewStates);
}
