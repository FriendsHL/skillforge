package com.skillforge.server.repository;

import com.skillforge.server.entity.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SkillRepository extends JpaRepository<SkillEntity, Long> {

    List<SkillEntity> findByOwnerId(Long ownerId);

    List<SkillEntity> findByIsPublicTrue();

    Optional<SkillEntity> findByName(String name);

    List<SkillEntity> findBySource(String source);

    List<SkillEntity> findByParentSkillId(Long parentSkillId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE SkillEntity s SET s.usageCount = s.usageCount + 1, s.successCount = s.successCount + :successIncrement WHERE s.id = :id")
    void incrementUsage(@Param("id") Long id, @Param("successIncrement") int successIncrement);
}
