package com.skillforge.server.repository;

import com.skillforge.server.entity.MemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoryRepository extends JpaRepository<MemoryEntity, Long> {

    List<MemoryEntity> findByUserId(Long userId);

    List<MemoryEntity> findByUserIdAndType(Long userId, String type);

    List<MemoryEntity> findByUserIdAndContentContaining(Long userId, String keyword);

    List<MemoryEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
