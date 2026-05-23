package com.skillforge.server.repository;

import com.skillforge.server.entity.OptReportEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * OPT-REPORT-V1 (V97): JPA access for {@link OptReportEntity}.
 *
 * <p>Listing is per-agent newest-first (matches the dashboard
 * "Reports" tab UX). Single-row lookups go via {@code findById} from
 * {@link JpaRepository}.
 */
public interface OptReportRepository extends JpaRepository<OptReportEntity, String> {

    @Query("""
            SELECT r FROM OptReportEntity r
            WHERE r.agentId = :agentId
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    List<OptReportEntity> findByAgentIdOrderByCreatedAtDesc(
            @Param("agentId") Long agentId, Pageable pageable);
}
