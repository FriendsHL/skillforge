package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "t_memory_snapshot")
public class MemorySnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 36, nullable = false)
    private String extractionBatchId;

    private Long memoryId;

    private Long userId;

    private String type;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String tags;

    @Column(length = 36)
    private String sourceExtractionBatchId;

    private int recallCount;

    private Instant lastRecalledAt;

    private LocalDateTime memoryCreatedAt;

    private LocalDateTime memoryUpdatedAt;

    private Instant snapshotAt;

    public MemorySnapshotEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getExtractionBatchId() {
        return extractionBatchId;
    }

    public void setExtractionBatchId(String extractionBatchId) {
        this.extractionBatchId = extractionBatchId;
    }

    public Long getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(Long memoryId) {
        this.memoryId = memoryId;
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

    public String getSourceExtractionBatchId() {
        return sourceExtractionBatchId;
    }

    public void setSourceExtractionBatchId(String sourceExtractionBatchId) {
        this.sourceExtractionBatchId = sourceExtractionBatchId;
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

    public LocalDateTime getMemoryCreatedAt() {
        return memoryCreatedAt;
    }

    public void setMemoryCreatedAt(LocalDateTime memoryCreatedAt) {
        this.memoryCreatedAt = memoryCreatedAt;
    }

    public LocalDateTime getMemoryUpdatedAt() {
        return memoryUpdatedAt;
    }

    public void setMemoryUpdatedAt(LocalDateTime memoryUpdatedAt) {
        this.memoryUpdatedAt = memoryUpdatedAt;
    }

    public Instant getSnapshotAt() {
        return snapshotAt;
    }

    public void setSnapshotAt(Instant snapshotAt) {
        this.snapshotAt = snapshotAt;
    }
}
