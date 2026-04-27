package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
