package com.skillforge.server.repository;

import com.skillforge.server.entity.MemorySnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemorySnapshotRepository extends JpaRepository<MemorySnapshotEntity, Long> {

    List<MemorySnapshotEntity> findByExtractionBatchIdAndUserId(String extractionBatchId, Long userId);
}
