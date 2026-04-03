package com.skillforge.server.repository;

import com.skillforge.server.entity.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkillRepository extends JpaRepository<SkillEntity, Long> {

    List<SkillEntity> findByOwnerId(Long ownerId);

    List<SkillEntity> findByIsPublicTrue();

    Optional<SkillEntity> findByName(String name);
}
