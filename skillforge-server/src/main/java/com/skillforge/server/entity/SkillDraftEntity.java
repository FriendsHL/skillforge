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
@Table(name = "t_skill_draft")
@EntityListeners(AuditingEntityListener.class)
public class SkillDraftEntity {

    @Id
    private String id;

    @Column(length = 36)
    private String sourceSessionId;

    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String triggers;

    @Column(columnDefinition = "TEXT")
    private String requiredTools;

    @Column(columnDefinition = "TEXT")
    private String promptHint;

    @Column(columnDefinition = "TEXT")
    private String extractionRationale;

    @Column(nullable = false, length = 32)
    private String status = "draft";

    private Long skillId;

    /**
     * Plan r2 §9 + Code Judge r1 B-FE-3 — pre-save dedupe similarity score (0..1).
     * Populated by {@code SkillDraftService.extractFromRecentSessions} when the
     * candidate has a non-trivial similarity to an existing skill or sibling draft.
     * <p>FE uses this to colour the row (orange ≥ 0.60 = "merge?", red ≥ 0.85 = "duplicate")
     * and gate the Modal.confirm + forceCreate flow.
     */
    @Column(name = "similarity")
    private Double similarity;

    /** Plan r2 §9 — id of the matched skill or draft (informational; no FK). */
    @Column(name = "merge_candidate_id")
    private Long mergeCandidateId;

    /** Plan r2 §9 — name of the matched skill / draft (used for FE display). */
    @Column(name = "merge_candidate_name", length = 256)
    private String mergeCandidateName;

    /**
     * SKILL-CREATOR-WITH-EVAL V91 (2026-05-18): evaluation target agent. For
     * the extract-from-sessions path this is back-filled from
     * {@code sourceSession.agent_id}; for the upload / marketplace / natural-
     * language paths the dispatch caller sets it explicitly. NULL until the
     * dispatch path resolves it (legacy rows pre-V91 stay null forever).
     */
    @Column(name = "target_agent_id")
    private Long targetAgentId;

    /**
     * SKILL-CREATOR-WITH-EVAL V91 (2026-05-18): id of the transient SkillEntity
     * rendered for the evaluation pair (with_skill / without_skill SubAgent
     * runs). Written by {@code SkillCreatorService.dispatchEvaluation} after
     * the V6 R3 {@code promoteDraftToTransientSkill}-style render; cleaned up
     * (or promoted to the real candidate) by the approve-flow once the operator
     * acts on the eval verdict.
     */
    @Column(name = "candidate_skill_id")
    private Long candidateSkillId;

    /**
     * SKILL-CREATOR-WITH-EVAL V91 (2026-05-18): provenance of the draft.
     * Free-form VARCHAR(64), no CHECK constraint, so we don't have to migrate
     * each time a new entry-point lands. Known values:
     * {@code upload} / {@code marketplace} / {@code natural-language} /
     * {@code extract-from-sessions} / {@code attribution} / {@code manual}.
     * NULL for pre-V91 rows (legacy extract / attribution path).
     */
    @Column(name = "source", length = 64)
    private String source;

    /**
     * SKILL-CREATOR-WITH-EVAL V91 (2026-05-18): Jackson-serialised
     * {@code EvaluationResult} record written by
     * {@code SkillCreatorEvalCoordinator} after all 2N SubAgent runs land.
     * Shape doc in V91__skill_draft_evaluation.sql header. NULL while the
     * eval is still in flight (status='draft') or for entry-points that
     * skip evaluation (no eval scenarios in the zip).
     */
    @Column(name = "evaluation_result_json", columnDefinition = "TEXT")
    private String evaluationResultJson;

    @CreatedDate
    private Instant createdAt;

    private Instant reviewedAt;

    private Long reviewedBy;

    public SkillDraftEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public void setSourceSessionId(String sourceSessionId) {
        this.sourceSessionId = sourceSessionId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
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

    public String getPromptHint() {
        return promptHint;
    }

    public void setPromptHint(String promptHint) {
        this.promptHint = promptHint;
    }

    public String getExtractionRationale() {
        return extractionRationale;
    }

    public void setExtractionRationale(String extractionRationale) {
        this.extractionRationale = extractionRationale;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getSkillId() {
        return skillId;
    }

    public void setSkillId(Long skillId) {
        this.skillId = skillId;
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

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Double getSimilarity() {
        return similarity;
    }

    public void setSimilarity(Double similarity) {
        this.similarity = similarity;
    }

    public Long getMergeCandidateId() {
        return mergeCandidateId;
    }

    public void setMergeCandidateId(Long mergeCandidateId) {
        this.mergeCandidateId = mergeCandidateId;
    }

    public String getMergeCandidateName() {
        return mergeCandidateName;
    }

    public void setMergeCandidateName(String mergeCandidateName) {
        this.mergeCandidateName = mergeCandidateName;
    }

    public Long getTargetAgentId() {
        return targetAgentId;
    }

    public void setTargetAgentId(Long targetAgentId) {
        this.targetAgentId = targetAgentId;
    }

    public Long getCandidateSkillId() {
        return candidateSkillId;
    }

    public void setCandidateSkillId(Long candidateSkillId) {
        this.candidateSkillId = candidateSkillId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getEvaluationResultJson() {
        return evaluationResultJson;
    }

    public void setEvaluationResultJson(String evaluationResultJson) {
        this.evaluationResultJson = evaluationResultJson;
    }
}
