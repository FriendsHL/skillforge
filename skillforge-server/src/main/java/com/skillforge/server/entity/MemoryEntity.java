package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "t_memory")
@EntityListeners(AuditingEntityListener.class)
public class MemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private String type;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String tags;

    @Column(length = 36)
    private String extractionBatchId;

    @Column(columnDefinition = "INT DEFAULT 0")
    private int recallCount = 0;

    private Instant lastRecalledAt;

    /**
     * Lifecycle status: ACTIVE (default) → STALE → ARCHIVED → physical delete.
     * Migration V29 introduces this column with default 'ACTIVE' so existing
     * memories keep current visibility. PR-2 will start filtering on it.
     */
    @Column(length = 16, nullable = false)
    private String status = "ACTIVE";

    /** Set when status transitions to ARCHIVED; used to age out after 90 days (PR-5). */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /**
     * MEMORY-DREAM-CONSOLIDATION: tracking why this memory was archived
     * ({@code expired_ttl} / {@code capacity_demote} / {@code dedup_merge_with_<id>}).
     * Nullable — legacy rows archived before V66 keep NULL.
     */
    @Column(name = "archived_reason", length = 128)
    private String archivedReason;

    /**
     * Importance promoted from the legacy {@code tags = "importance:*"} CSV token
     * to a dedicated column. Default 'medium' matches the legacy fallback.
     */
    @Column(length = 8, nullable = false)
    private String importance = "medium";

    /** Cached score from the most recent eviction sweep (PR-5); null until first scoring. */
    @Column(name = "last_score")
    private Double lastScore;

    @Column(name = "last_scored_at")
    private Instant lastScoredAt;

    /**
     * MEMORY-LLM-SYNTHESIS (V68): provenance / form of this memory row.
     * <ul>
     *   <li>{@code observation} (default) — originated from a user session via the existing rule/LLM extractor</li>
     *   <li>{@code reflection} — synthesized by LlmMemorySynthesizer, see {@link #derivedFromMemoryIds}</li>
     *   <li>{@code optimized} — content rewritten by LlmMemorySynthesizer, original preserved in {@link #originalContent}</li>
     * </ul>
     * Orthogonal to {@link #type} (business taxonomy: preference/feedback/knowledge/project/reference).
     */
    @Column(name = "memory_kind", length = 16)
    private String memoryKind;

    /** MEMORY-LLM-SYNTHESIS (V68): JSON array of source memory ids; only set when {@code memoryKind=reflection}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "derived_from_memory_ids", columnDefinition = "jsonb")
    private String derivedFromMemoryIds;

    /** MEMORY-LLM-SYNTHESIS (V68): pre-optimize content preserved for revert; only when {@code memoryKind=optimized}. */
    @Column(name = "original_content", columnDefinition = "TEXT")
    private String originalContent;

    /** MEMORY-LLM-SYNTHESIS (V68): links back to the synthesis run that produced or modified this row. */
    @Column(name = "synthesis_run_id", length = 64)
    private String synthesisRunId;

    /**
     * Where the current memory content originated. Kept separate from
     * memoryKind, which describes synthesis form rather than evidence authority.
     */
    @Column(name = "provenance_source", nullable = false, length = 32)
    private String provenanceSource = "LEGACY_UNKNOWN";

    /** UNVERIFIED / CONFIRMED / REJECTED. */
    @Column(name = "confirmation_status", nullable = false, length = 16)
    private String confirmationStatus = "UNVERIFIED";

    /** Optional normalized confidence in [0,1]. */
    @Column(name = "confidence")
    private Double confidence;

    /** Optimistic concurrency token; prevents silent last-writer-wins updates. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public MemoryEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getExtractionBatchId() {
        return extractionBatchId;
    }

    public void setExtractionBatchId(String extractionBatchId) {
        this.extractionBatchId = extractionBatchId;
    }

    public int getRecallCount() {
        return recallCount;
    }

    public void setRecallCount(int recallCount) {
        this.recallCount = recallCount;
    }

    public Instant getLastRecalledAt() {
        return lastRecalledAt;
    }

    public void setLastRecalledAt(Instant lastRecalledAt) {
        this.lastRecalledAt = lastRecalledAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public String getArchivedReason() {
        return archivedReason;
    }

    public void setArchivedReason(String archivedReason) {
        this.archivedReason = archivedReason;
    }

    public String getImportance() {
        return importance;
    }

    public void setImportance(String importance) {
        this.importance = importance;
    }

    public Double getLastScore() {
        return lastScore;
    }

    public void setLastScore(Double lastScore) {
        this.lastScore = lastScore;
    }

    public Instant getLastScoredAt() {
        return lastScoredAt;
    }

    public void setLastScoredAt(Instant lastScoredAt) {
        this.lastScoredAt = lastScoredAt;
    }

    public String getMemoryKind() {
        return memoryKind;
    }

    public void setMemoryKind(String memoryKind) {
        this.memoryKind = memoryKind;
    }

    public String getDerivedFromMemoryIds() {
        return derivedFromMemoryIds;
    }

    public void setDerivedFromMemoryIds(String derivedFromMemoryIds) {
        this.derivedFromMemoryIds = derivedFromMemoryIds;
    }

    public String getOriginalContent() {
        return originalContent;
    }

    public void setOriginalContent(String originalContent) {
        this.originalContent = originalContent;
    }

    public String getSynthesisRunId() {
        return synthesisRunId;
    }

    public void setSynthesisRunId(String synthesisRunId) {
        this.synthesisRunId = synthesisRunId;
    }

    public String getProvenanceSource() {
        return provenanceSource;
    }

    public void setProvenanceSource(String provenanceSource) {
        this.provenanceSource = provenanceSource;
    }

    public String getConfirmationStatus() {
        return confirmationStatus;
    }

    public void setConfirmationStatus(String confirmationStatus) {
        this.confirmationStatus = confirmationStatus;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
