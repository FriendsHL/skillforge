package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "t_skill")
@EntityListeners(AuditingEntityListener.class)
public class SkillEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String skillPath;

    @Column(columnDefinition = "TEXT")
    private String triggers;

    @Column(columnDefinition = "TEXT")
    private String requiredTools;

    private Long ownerId;

    private boolean isPublic = false;

    @Column(columnDefinition = "boolean default true")
    private boolean enabled = true;

    /** Skill 来源: builtin / upload / clawhub。null 视为旧数据(upload) */
    @Column(length = 32)
    private String source;

    /** ClawHub 安装时记录的版本号(其他来源为 null) */
    @Column(length = 64)
    private String version;

    /** 风险等级: low / medium / high / blocked */
    @Column(length = 16)
    private String riskLevel;

    /** 安全扫描报告(JSON 字符串,审计用) */
    @Column(columnDefinition = "TEXT")
    private String scanReport;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    private Long parentSkillId;

    @Column(length = 32)
    private String semver;

    @Column(nullable = false)
    private long usageCount = 0;

    @Column(nullable = false)
    private long successCount = 0;

    /** Failure tally — added by V31 migration alongside success_count. */
    @Column(name = "failure_count", nullable = false)
    private long failureCount = 0;

    /**
     * System skill flag — true for built-in skills loaded by SystemSkillLoader from
     * {@code system-skills/} directory. System rows have {@code owner_id = NULL} and
     * cannot be deleted through the user-facing DELETE endpoint (returns 403).
     * Added by V31 migration.
     */
    @Column(name = "is_system", nullable = false)
    private boolean isSystem = false;

    /**
     * SHA-256 (or equivalent) hash of the canonical SKILL.md content; used by
     * {@code SkillCatalogReconciler} to detect on-disk artifact changes between
     * startups. Added by V33 migration. Nullable for legacy rows; populated on
     * first reconcile pass.
     */
    @Column(length = 128)
    private String contentHash;

    /**
     * Last time the artifact was scanned by SystemSkillLoader / UserSkillLoader
     * / SkillCatalogReconciler. Added by V33 migration. Persisted as TIMESTAMP,
     * mapped to {@link Instant} per java.md footgun #2 (new fields use Instant).
     */
    @Column(name = "last_scanned_at")
    private Instant lastScannedAt;

    /**
     * Artifact governance status. One of {@code active / missing / invalid /
     * shadowed}. Default {@code active}. Added by V33 migration.
     */
    @Column(name = "artifact_status", length = 32, nullable = false)
    private String artifactStatus = "active";

    /**
     * When {@code artifact_status='shadowed'}, identifies the winner shadowing
     * this row. Format: {@code system:<name>} for system-vs-runtime conflicts,
     * {@code runtime:<id>} for runtime-vs-runtime. Null otherwise. Added by V33
     * migration.
     */
    @Column(name = "shadowed_by", length = 255)
    private String shadowedBy;

    /**
     * SKILL-CANARY-ROLLOUT V2 (V78 migration): rollout stage for this skill row.
     * One of {@code production / canary / rolled_back / disabled}. Default
     * {@code production} preserves the existing one-shot promote behaviour for
     * every skill row at migration time — only operators that explicitly
     * "start canary" via the dashboard transition rows out of {@code production}.
     */
    @Column(name = "rollout_stage", length = 32, nullable = false)
    private String rolloutStage = "production";

    /**
     * SKILL-CANARY-ROLLOUT V2 (V78 migration): percentage of sessions allocated
     * to this skill row when it is a canary candidate, 0..100. Default
     * {@code 100} means "everyone gets this version" — i.e. the existing
     * "一刀切" semantics. The CanaryAllocator (Phase 1.2) only sub-samples
     * when an associated {@code t_canary_rollout} row is active.
     */
    @Column(name = "rollout_percentage", nullable = false)
    private Integer rolloutPercentage = 100;

    /**
     * SKILL-CURATOR V1 (V163 migration): when non-null, the time the curator
     * archived this skill (the row is also {@code enabled=false} at that point).
     * NULL = not archived. Persisted as TIMESTAMPTZ (V70+ convention for Instant
     * columns), mapped to {@link Instant} per java.md footgun #2.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /**
     * SKILL-CURATOR V1 (V163 migration): short machine tag for why this skill was
     * archived (e.g. {@code low_usage_curator}). NULL when not archived.
     */
    @Column(name = "archive_reason", length = 64)
    private String archiveReason;

    public SkillEntity() {
    }

    /**
     * 2026-05-26 — fork-row invariants enforced at insert time. Catches the
     * id=452 bug class where a row was persisted with {@code source='evolution-fork'}
     * (a path-allocation hint, not a valid {@link com.skillforge.server.skill.SkillSource}
     * row value) or a {@code skill_path} under {@code /evolution-fork/} but no
     * {@code parent_skill_id}. Both signal a buggy fork creation path that
     * bypassed {@link com.skillforge.server.service.SkillService#forkSkill}
     * (the canonical path which sets parent + semver via {@code nextSemver}).
     *
     * <p>{@code @PrePersist} only — does NOT fire on update, so existing
     * orphan-fork rows (parent deleted later, leaving dangling
     * {@code parent_skill_id}) continue to save normally.
     *
     * <p>Package-private so {@link com.skillforge.server.entity.SkillEntityForkInvariantsTest}
     * can drive it without a full JPA persistence context.
     */
    @PrePersist
    void validateForkRowInvariantsOnInsert() {
        if ("evolution-fork".equals(source)) {
            throw new IllegalStateException(
                    "SkillEntity insert rejected: source='evolution-fork' is a path-allocation "
                            + "hint, not a valid SkillEntity.source value. Forks must inherit "
                            + "parent.source (clawhub/draft-approve/skillhub/etc). "
                            + "Use SkillService.forkSkill which sets source + parent + semver "
                            + "correctly. (name=" + name + " skillPath=" + skillPath + ")");
        }
        boolean pathUnderEvolutionFork = skillPath != null
                && skillPath.contains("/evolution-fork/");
        if (pathUnderEvolutionFork && parentSkillId == null) {
            throw new IllegalStateException(
                    "SkillEntity insert rejected: skill_path under /evolution-fork/ but "
                            + "parent_skill_id is null. Fork rows must have parent_skill_id set "
                            + "at insert time. Use SkillService.forkSkill which sets parent + "
                            + "semver via nextSemver(). (name=" + name + " skillPath=" + skillPath + ")");
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSkillPath() {
        return skillPath;
    }

    public void setSkillPath(String skillPath) {
        this.skillPath = skillPath;
    }

    public String getTriggers() {
        return triggers;
    }

    public void setTriggers(String triggers) {
        this.triggers = triggers;
    }

    public String getRequiredTools() {
        return requiredTools;
    }

    public void setRequiredTools(String requiredTools) {
        this.requiredTools = requiredTools;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getScanReport() {
        return scanReport;
    }

    public void setScanReport(String scanReport) {
        this.scanReport = scanReport;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getParentSkillId() {
        return parentSkillId;
    }

    public void setParentSkillId(Long parentSkillId) {
        this.parentSkillId = parentSkillId;
    }

    public String getSemver() {
        return semver;
    }

    public void setSemver(String semver) {
        this.semver = semver;
    }

    public long getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(long usageCount) {
        this.usageCount = usageCount;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(long successCount) {
        this.successCount = successCount;
    }

    public long getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(long failureCount) {
        this.failureCount = failureCount;
    }

    public boolean isSystem() {
        return isSystem;
    }

    public void setSystem(boolean isSystem) {
        this.isSystem = isSystem;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Instant getLastScannedAt() {
        return lastScannedAt;
    }

    public void setLastScannedAt(Instant lastScannedAt) {
        this.lastScannedAt = lastScannedAt;
    }

    public String getArtifactStatus() {
        return artifactStatus;
    }

    public void setArtifactStatus(String artifactStatus) {
        this.artifactStatus = artifactStatus;
    }

    public String getShadowedBy() {
        return shadowedBy;
    }

    public void setShadowedBy(String shadowedBy) {
        this.shadowedBy = shadowedBy;
    }

    public String getRolloutStage() {
        return rolloutStage;
    }

    public void setRolloutStage(String rolloutStage) {
        this.rolloutStage = rolloutStage;
    }

    public Integer getRolloutPercentage() {
        return rolloutPercentage;
    }

    public void setRolloutPercentage(Integer rolloutPercentage) {
        this.rolloutPercentage = rolloutPercentage;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public String getArchiveReason() {
        return archiveReason;
    }

    public void setArchiveReason(String archiveReason) {
        this.archiveReason = archiveReason;
    }
}
