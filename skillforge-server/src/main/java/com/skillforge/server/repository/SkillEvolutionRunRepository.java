package com.skillforge.server.repository;

import com.skillforge.server.entity.SkillEvolutionRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillEvolutionRunRepository extends JpaRepository<SkillEvolutionRunEntity, String> {

    List<SkillEvolutionRunEntity> findBySkillIdOrderByCreatedAtDesc(Long skillId);

    List<SkillEvolutionRunEntity> findByStatus(String status);

    List<SkillEvolutionRunEntity> findBySkillIdAndStatusIn(Long skillId, List<String> statuses);
}
