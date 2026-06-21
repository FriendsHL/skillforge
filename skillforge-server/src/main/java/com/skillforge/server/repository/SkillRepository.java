package com.skillforge.server.repository;

import com.skillforge.server.entity.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SkillRepository extends JpaRepository<SkillEntity, Long> {

    List<SkillEntity> findByOwnerId(Long ownerId);

    List<SkillEntity> findByIsPublicTrue();

    Optional<SkillEntity> findByName(String name);

    /**
     * SKILL-DASHBOARD-POLISH-V2 §H — pre-flight exact-name lookup for approveDraft.
     * Returns the currently enabled skill row whose (ownerId, name) matches; aligns
     * with the V64 partial unique index {@code uq_t_skill_owner_name_enabled}.
     * <p>{@code findFirst} avoids implying a unique constraint at the JPA layer
     * (ownerId can be null on system rows; partial index only fires when enabled).
     */
    Optional<SkillEntity> findFirstByOwnerIdAndNameAndEnabledTrue(Long ownerId, String name);

    /**
     * Bug A — sibling-aware delete needs to enumerate every (owner_id, name) row
     * (regardless of enabled state) so deleteSkill can decide whether to
     * unregister the SkillRegistry name or hand it off to a still-enabled row.
     */
    List<SkillEntity> findByOwnerIdAndName(Long ownerId, String name);

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
     * <p>The {@code ON CONFLICT (COALESCE(owner_id, -1), name) WHERE enabled}
     * inference clause MUST stay in sync with the partial
     * {@code uq_t_skill_owner_name_enabled} index defined in
     * {@code V64__skill_owner_name_unique_when_enabled.sql}; PostgreSQL
     * resolves the conflict target by exact expression + predicate match.
     * The {@code WHERE enabled} predicate is required since V64 because the
     * unique index is partial; without it Postgres reports "no unique or
     * exclusion constraint matching the ON CONFLICT specification".
     *
     * <p>Initial semver is hardcoded to literal {@code 'v1'} in the native
     * SQL VALUES clause. Forks bump via
     * {@link com.skillforge.server.service.SkillService#cloneToFork}
     * (v1 → v2 → v3 ...). Without this default the FE renders
     * {@code 'v0.0.0'} for imported skills; see V118 backfill migration.
     *
     * @return rows actually inserted: {@code 1} if this caller won the race,
     *     {@code 0} if a concurrent caller's row already satisfies the unique
     *     constraint.
     */
    @Modifying
    @Query(value = """
            INSERT INTO t_skill
                (owner_id, is_system, name, description, triggers, required_tools,
                 skill_path, is_public, enabled, source, version, semver,
                 usage_count, success_count, failure_count,
                 content_hash, artifact_status, last_scanned_at, created_at)
            VALUES (:ownerId, false, :name, :description, :triggers, :requiredTools,
                    :skillPath, false, true, :source, :version, 'v1',
                    0, 0, 0,
                    :contentHash, 'active', :lastScannedAt, :createdAt)
            ON CONFLICT (COALESCE(owner_id, -1), name) WHERE enabled DO NOTHING
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

    /**
     * SKILL-CURATOR V1 — candidates for archival: non-system, enabled, not yet
     * archived, not curator-exempt, rarely-used, and old enough.
     *
     * <p>System skills ({@code isSystem=true}) are exempt by the query itself.
     *
     * <p><b>Restore-protection (curator_exempt, V164):</b> the
     * {@code curatorExempt = false} clause excludes skills a human deliberately
     * restored via the dashboard ({@code POST /api/skills/{id}/restore}); the
     * restore path sets {@code curatorExempt=true} so the curator never
     * re-archives them.
     *
     * <p><b>No {@code updatedAt} guard (SKILL-CURATOR bug A):</b> an earlier version
     * also excluded recently-{@code updatedAt} rows to "respect manual edits". But
     * {@code updatedAt} is {@code @LastModifiedDate} and is bumped by SYSTEM saves
     * (reconcile/evolve/canary), not just user edits — so it is the wrong "user
     * intent" signal and effectively excluded everything. Restore-protection (don't
     * re-archive what a user manually restored) is deferred to a proper exempt
     * marker, to be added when real (non-dry-run) archival is enabled.
     *
     * <p><b>Type note:</b> {@code createdAt} is a {@link java.time.LocalDateTime}
     * (legacy column) — {@code createdBefore} must be {@code LocalDateTime}.
     */
    @Query("SELECT s FROM SkillEntity s "
            + "WHERE s.isSystem = false "
            + "  AND s.enabled = true "
            + "  AND s.archivedAt IS NULL "
            + "  AND s.curatorExempt = false "
            + "  AND s.usageCount < :minUsage "
            + "  AND s.createdAt < :createdBefore")
    List<SkillEntity> findArchivalCandidates(@Param("minUsage") long minUsage,
                                             @Param("createdBefore") java.time.LocalDateTime createdBefore);

    /**
     * SKILL-CURATOR bug B fix: refresh {@code last_scanned_at} for the given rows via
     * a direct bulk UPDATE that does NOT trigger {@code @LastModifiedDate} — so
     * scan bookkeeping never spuriously bumps {@code updated_at} (which must stay
     * "last real content change", not "last boot"). Used by SkillCatalogReconciler.
     */
    @Transactional
    @Modifying
    @Query("UPDATE SkillEntity s SET s.lastScannedAt = :ts WHERE s.id IN :ids")
    void touchLastScannedAt(@Param("ids") java.util.Collection<Long> ids, @Param("ts") Instant ts);
}
