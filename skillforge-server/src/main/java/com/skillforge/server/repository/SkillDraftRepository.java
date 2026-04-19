package com.skillforge.server.repository;

import com.skillforge.server.entity.SkillDraftEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SkillDraftRepository extends JpaRepository<SkillDraftEntity, String> {

    List<SkillDraftEntity> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    List<SkillDraftEntity> findByOwnerIdAndStatus(Long ownerId, String status);

    long countByOwnerIdAndStatus(Long ownerId, String status);

    /** Pessimistic write lock — prevents concurrent approve/discard on the same draft. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM SkillDraftEntity d WHERE d.id = :id")
    Optional<SkillDraftEntity> findByIdForUpdate(@Param("id") String id);
}
