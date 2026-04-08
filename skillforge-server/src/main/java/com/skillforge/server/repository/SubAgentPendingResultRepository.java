package com.skillforge.server.repository;

import com.skillforge.server.entity.SubAgentPendingResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SubAgentPendingResultRepository extends JpaRepository<SubAgentPendingResultEntity, Long> {

    List<SubAgentPendingResultEntity> findByParentSessionIdOrderByIdAsc(String parentSessionId);

    @Modifying
    @Transactional
    void deleteByParentSessionId(String parentSessionId);
}
