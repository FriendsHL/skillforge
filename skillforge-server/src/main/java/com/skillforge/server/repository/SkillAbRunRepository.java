package com.skillforge.server.repository;

import com.skillforge.server.entity.SkillAbRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SkillAbRunRepository extends JpaRepository<SkillAbRunEntity, String> {

    List<SkillAbRunEntity> findByParentSkillIdOrderByStartedAtDesc(Long parentSkillId);

    List<SkillAbRunEntity> findByCandidateSkillIdOrderByStartedAtDesc(Long candidateSkillId);

    List<SkillAbRunEntity> findByStatusIn(List<String> statuses);
}
