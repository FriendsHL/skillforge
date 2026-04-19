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
@Table(name = "t_skill_evolution_run")
@EntityListeners(AuditingEntityListener.class)
public class SkillEvolutionRunEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private Long skillId;

    private Long forkedSkillId;

    @Column(length = 36)
    private String abRunId;

    @Column(length = 36)
    private String agentId;

    @Column(length = 32, nullable = false)
    private String status = "PENDING";

    private Double successRateBefore;

    private Long usageCountBefore;

    @Column(columnDefinition = "TEXT")
    private String improvedSkillMd;

    @Column(columnDefinition = "TEXT")
    private String evolutionReasoning;

    @Column(columnDefinition = "TEXT")
    private String failureReason;

    private Long triggeredByUserId;

    @CreatedDate
    private Instant createdAt;

    private Instant startedAt;

    private Instant completedAt;

    public SkillEvolutionRunEntity() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getSkillId() { return skillId; }
    public void setSkillId(Long skillId) { this.skillId = skillId; }

    public Long getForkedSkillId() { return forkedSkillId; }
    public void setForkedSkillId(Long forkedSkillId) { this.forkedSkillId = forkedSkillId; }

    public String getAbRunId() { return abRunId; }
    public void setAbRunId(String abRunId) { this.abRunId = abRunId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Double getSuccessRateBefore() { return successRateBefore; }
    public void setSuccessRateBefore(Double successRateBefore) { this.successRateBefore = successRateBefore; }

    public Long getUsageCountBefore() { return usageCountBefore; }
    public void setUsageCountBefore(Long usageCountBefore) { this.usageCountBefore = usageCountBefore; }

    public String getImprovedSkillMd() { return improvedSkillMd; }
    public void setImprovedSkillMd(String improvedSkillMd) { this.improvedSkillMd = improvedSkillMd; }

    public String getEvolutionReasoning() { return evolutionReasoning; }
    public void setEvolutionReasoning(String evolutionReasoning) { this.evolutionReasoning = evolutionReasoning; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public Long getTriggeredByUserId() { return triggeredByUserId; }
    public void setTriggeredByUserId(Long triggeredByUserId) { this.triggeredByUserId = triggeredByUserId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
