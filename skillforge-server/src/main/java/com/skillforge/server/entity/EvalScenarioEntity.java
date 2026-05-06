package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "t_eval_scenario")
@EntityListeners(AuditingEntityListener.class)
public class EvalScenarioEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 36)
    private String agentId;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 64)
    private String category = "session_derived";

    @Column(length = 16)
    private String split = "held_out";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String task;

    @Column(length = 32)
    private String oracleType = "llm_judge";

    @Column(columnDefinition = "TEXT")
    private String oracleExpected;

    @Column(nullable = false, length = 32)
    private String status = "draft";

    @Column(length = 36)
    private String sourceSessionId;

    @Column(columnDefinition = "TEXT")
    private String extractionRationale;

    /**
     * EVAL-V2 M2: JSON-encoded array of {@code {role, content}} for multi-turn cases.
     * NULL means single-turn (use {@link #task} / {@link #oracleExpected} via the legacy path).
     * Stored as TEXT (not JSONB) so we keep parser ownership in the service layer; the
     * Spring-managed {@code ObjectMapper} handles encode/decode with all modules registered.
     */
    @Column(columnDefinition = "TEXT")
    private String conversationTurns;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(length = 64)
    private String parentScenarioId;

    @CreatedDate
    private Instant createdAt;

    private Instant reviewedAt;

    public EvalScenarioEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSplit() {
        return split;
    }

    public void setSplit(String split) {
        this.split = split;
    }

    public String getTask() {
        return task;
    }

    public void setTask(String task) {
        this.task = task;
    }

    public String getOracleType() {
        return oracleType;
    }

    public void setOracleType(String oracleType) {
        this.oracleType = oracleType;
    }

    public String getOracleExpected() {
        return oracleExpected;
    }

    public void setOracleExpected(String oracleExpected) {
        this.oracleExpected = oracleExpected;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public void setSourceSessionId(String sourceSessionId) {
        this.sourceSessionId = sourceSessionId;
    }

    public String getExtractionRationale() {
        return extractionRationale;
    }

    public void setExtractionRationale(String extractionRationale) {
        this.extractionRationale = extractionRationale;
    }

    public String getConversationTurns() {
        return conversationTurns;
    }

    public void setConversationTurns(String conversationTurns) {
        this.conversationTurns = conversationTurns;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getParentScenarioId() {
        return parentScenarioId;
    }

    public void setParentScenarioId(String parentScenarioId) {
        this.parentScenarioId = parentScenarioId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }
}
