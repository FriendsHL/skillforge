package com.skillforge.server.repository;

import com.skillforge.server.entity.SubAgentPendingResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SubAgentPendingResultRepository extends JpaRepository<SubAgentPendingResultEntity, Long> {

    List<SubAgentPendingResultEntity> findByParentSessionIdAndStatusIsNullOrderByIdAsc(String parentSessionId);

    /** @deprecated Use findByParentSessionIdAndStatusIsNullOrderByIdAsc instead */
    List<SubAgentPendingResultEntity> findByParentSessionIdOrderByIdAsc(String parentSessionId);

    @Modifying
    @Transactional
    void deleteByParentSessionId(String parentSessionId);

    List<SubAgentPendingResultEntity> findByTargetSessionIdAndStatusIsNullOrderBySeqNoAsc(String targetSessionId);

    /** @deprecated Use findByTargetSessionIdAndStatusIsNullOrderBySeqNoAsc instead */
    List<SubAgentPendingResultEntity> findByTargetSessionIdOrderBySeqNoAsc(String targetSessionId);

    boolean existsByMessageId(String messageId);

    @Modifying
    @Transactional
    void deleteByTargetSessionId(String targetSessionId);
}
