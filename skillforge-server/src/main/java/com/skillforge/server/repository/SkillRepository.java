package com.skillforge.server.repository;

import com.skillforge.server.entity.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /** SKILL-IMPORT: locate an existing user-owned import row by (ownerId, name, source) tuple. */
    Optional<SkillEntity> findByOwnerIdAndNameAndSourceAndIsSystem(
            Long ownerId, String name, String source, boolean isSystem);

    /**
     * SKILL-IMPORT: PostgreSQL-safe idempotent insert for an externally-installed skill.
     *
     * <p>Mirrors the pattern documented in
     * {@link ToolResultArchiveRepository#insertIgnoreConflict} (Judge FIX-2 from
     * a prior pipeline run): JPA {@code save()} cannot serve as a conflict-detection
     * point because PostgreSQL marks the transaction as aborted on a unique
     * violation, killing the catch+retry path. Native {@code ON CONFLICT DO
     * NOTHING} silently no-ops on conflict and does not abort the transaction
     * — caller does a re-lookup to fetch the winner row.
     *
     * <p>The {@code ON CONFLICT (COALESCE(owner_id, -1), name)} expression list
     * MUST stay in sync with the {@code uq_t_skill_owner_name} expression
     * index defined in {@code V31__skill_control_plane.sql}; PostgreSQL
     * resolves the conflict target by exact expression match.
     *
     * @return rows actually inserted: {@code 1} if this caller won the race,
     *     {@code 0} if a concurrent caller's row already satisfies the unique
     *     constraint.
     */
    @Modifying
    @Query(value = """
            INSERT INTO t_skill
                (owner_id, is_system, name, description, triggers, required_tools,
                 skill_path, is_public, enabled, source, version,
                 usage_count, success_count, failure_count,
                 content_hash, artifact_status, last_scanned_at, created_at)
            VALUES (:ownerId, false, :name, :description, :triggers, :requiredTools,
                    :skillPath, false, true, :source, :version,
                    0, 0, 0,
                    :contentHash, 'active', :lastScannedAt, :createdAt)
            ON CONFLICT (COALESCE(owner_id, -1), name) DO NOTHING
            """, nativeQuery = true)
    int insertImportedSkillIgnoreConflict(@Param("ownerId") Long ownerId,
                                          @Param("name") String name,
                                          @Param("description") String description,
                                          @Param("triggers") String triggers,
                                          @Param("requiredTools") String requiredTools,
                                          @Param("skillPath") String skillPath,
                                          @Param("source") String source,
                                          @Param("version") String version,
                                          @Param("contentHash") String contentHash,
                                          @Param("lastScannedAt") Instant lastScannedAt,
                                          @Param("createdAt") Instant createdAt);

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
