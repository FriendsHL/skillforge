package com.skillforge.server.repository;

import com.skillforge.server.entity.ModelUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelUsageRepository extends JpaRepository<ModelUsageEntity, Long> {

    List<ModelUsageEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<ModelUsageEntity> findBySessionId(String sessionId);
}
