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

    /** Plan r2 §6: UserSkillLoader startup scan source. */
    List<SkillEntity> findByIsSystemFalseAndEnabledTrue();

    /** Plan r2 §3 case A: orphan dir scan needs all non-system rows (regardless of enabled). */
    List<SkillEntity> findByIsSystemFalse();

    /** Plan r2 §8 W-1: list filter by is_system. */
    List<SkillEntity> findByIsSystem(boolean isSystem);

    /** P1-D §T5: compat tuple for runtime path-not-matched fallback (4-tuple, see plan). */
    Optional<SkillEntity> findFirstByIsSystemFalseAndOwnerIdAndNameAndSource(
            Long ownerId, String name, String source);

    /** P1-D §T5: locate row by exact skill_path string (after normalize, callers supply both raw and normalized variants). */
    Optional<SkillEntity> findFirstBySkillPath(String skillPath);

    /** P1-D §T5: enumerate runtime rows with a given name (used for runtime-vs-runtime conflict resolution). */
    List<SkillEntity> findByIsSystemFalseAndName(String name);

    /** P1-D §T5: enumerate runtime rows by source (used for compat fallback when path mismatched). */
    List<SkillEntity> findByIsSystemFalseAndSource(String source);

    /**
     * Plan r2 §7 — atomic counter update by skill name. Avoids JPA dirty-check
     * lost-update under concurrent tool execution. Returns rows updated (0 if no
     * matching skill row, e.g. internal Java Tool name).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SkillEntity s "
            + "SET s.usageCount = s.usageCount + 1, "
            + "    s.successCount = s.successCount + :successInc, "
            + "    s.failureCount = s.failureCount + :failureInc "
            + "WHERE s.name = :name")
    int incrementUsageByName(@Param("name") String name,
                             @Param("successInc") int successInc,
                             @Param("failureInc") int failureInc);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE SkillEntity s SET s.usageCount = s.usageCount + 1, s.successCount = s.successCount + :successIncrement WHERE s.id = :id")
    void incrementUsage(@Param("id") Long id, @Param("successIncrement") int successIncrement);
}
