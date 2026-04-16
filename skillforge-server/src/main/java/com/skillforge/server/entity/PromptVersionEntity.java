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
@Table(name = "t_prompt_version")
@EntityListeners(AuditingEntityListener.class)
public class PromptVersionEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 36, nullable = false)
    private String agentId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private int versionNumber;

    @Column(length = 32, nullable = false)
    private String status = "candidate";

    @Column(length = 32, nullable = false)
    private String source = "auto_improve";

    @Column(length = 36)
    private String sourceEvalRunId;

    @Column(length = 36)
    private String abRunId;

    private Double deltaPassRate;

    private Double baselinePassRate;

    @Column(columnDefinition = "TEXT")
    private String improvementRationale;

    @CreatedDate
    @Column(nullable = false)
    private Instant createdAt;

    private Instant promotedAt;

    private Instant deprecatedAt;

    public PromptVersionEntity() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourceEvalRunId() { return sourceEvalRunId; }
    public void setSourceEvalRunId(String sourceEvalRunId) { this.sourceEvalRunId = sourceEvalRunId; }

    public String getAbRunId() { return abRunId; }
    public void setAbRunId(String abRunId) { this.abRunId = abRunId; }

    public Double getDeltaPassRate() { return deltaPassRate; }
    public void setDeltaPassRate(Double deltaPassRate) { this.deltaPassRate = deltaPassRate; }

    public Double getBaselinePassRate() { return baselinePassRate; }
    public void setBaselinePassRate(Double baselinePassRate) { this.baselinePassRate = baselinePassRate; }

    public String getImprovementRationale() { return improvementRationale; }
    public void setImprovementRationale(String improvementRationale) { this.improvementRationale = improvementRationale; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getPromotedAt() { return promotedAt; }
    public void setPromotedAt(Instant promotedAt) { this.promotedAt = promotedAt; }

    public Instant getDeprecatedAt() { return deprecatedAt; }
    public void setDeprecatedAt(Instant deprecatedAt) { this.deprecatedAt = deprecatedAt; }
}
