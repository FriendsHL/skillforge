package com.skillforge.server.repository;

import com.skillforge.server.entity.MemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MemoryRepository extends JpaRepository<MemoryEntity, Long> {

    List<MemoryEntity> findByUserId(Long userId);

    List<MemoryEntity> findByUserIdAndType(Long userId, String type);

    List<MemoryEntity> findByUserIdAndContentContaining(Long userId, String keyword);

    List<MemoryEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<MemoryEntity> findByUserIdAndTitle(Long userId, String title);

    @Modifying
    @Query("UPDATE MemoryEntity m SET m.recallCount = m.recallCount + 1, m.lastRecalledAt = :now WHERE m.id = :id")
    void incrementRecallCount(@Param("id") Long id, @Param("now") Instant now);
}
