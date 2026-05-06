package com.skillforge.server.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "t_eval_annotation")
@EntityListeners(AuditingEntityListener.class)
public class EvalAnnotationEntity {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPLIED = "applied";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_item_id", nullable = false)
    private Long taskItemId;

    @Column(name = "annotator_id", nullable = false)
    private Long annotatorId;

    @Column(name = "original_score", precision = 5, scale = 2)
    private BigDecimal originalScore;

    @Column(name = "corrected_score", precision = 5, scale = 2)
    private BigDecimal correctedScore;

    @Column(name = "corrected_expected", columnDefinition = "TEXT")
    private String correctedExpected;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskItemId() { return taskItemId; }
    public void setTaskItemId(Long taskItemId) { this.taskItemId = taskItemId; }
    public Long getAnnotatorId() { return annotatorId; }
    public void setAnnotatorId(Long annotatorId) { this.annotatorId = annotatorId; }
    public BigDecimal getOriginalScore() { return originalScore; }
    public void setOriginalScore(BigDecimal originalScore) { this.originalScore = originalScore; }
    public BigDecimal getCorrectedScore() { return correctedScore; }
    public void setCorrectedScore(BigDecimal correctedScore) { this.correctedScore = correctedScore; }
    public String getCorrectedExpected() { return correctedExpected; }
    public void setCorrectedExpected(String correctedExpected) { this.correctedExpected = correctedExpected; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
}
