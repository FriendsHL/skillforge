package com.skillforge.server.repository;

import com.skillforge.server.entity.OptReportBatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * OPT-REPORT-V1 (V97): JPA access for {@link OptReportBatchEntity}.
 */
public interface OptReportBatchRepository extends JpaRepository<OptReportBatchEntity, String> {

    List<OptReportBatchEntity> findByReportId(String reportId);
}
