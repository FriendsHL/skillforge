package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalDatasetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * EVAL-DATASET-LAYER V1: spring-data repository for {@link EvalDatasetEntity}.
 * Used by {@link com.skillforge.server.service.EvalDatasetService}.
 */
public interface EvalDatasetRepository extends JpaRepository<EvalDatasetEntity, String> {

    List<EvalDatasetEntity> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    List<EvalDatasetEntity> findByOwnerIdAndAgentIdOrderByCreatedAtDesc(Long ownerId, String agentId);

    Optional<EvalDatasetEntity> findByOwnerIdAndName(Long ownerId, String name);

    /**
     * AUTOEVOLVE-CLOSE-LOOP Phase BC-M2b: all datasets owned by (scoped to) a
     * specific agent. Used by {@code HarvestedScenarioService} to locate the
     * agent's {@code *mixed*} dataset without a full-table scan.
     */
    List<EvalDatasetEntity> findByAgentId(String agentId);
}
