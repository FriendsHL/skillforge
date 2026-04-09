package com.skillforge.server.repository;

import com.skillforge.server.entity.CompactionEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompactionEventRepository extends JpaRepository<CompactionEventEntity, Long> {

    List<CompactionEventEntity> findBySessionIdOrderByIdDesc(String sessionId);
}
