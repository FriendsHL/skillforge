package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * MEMORY-LLM-SYNTHESIS (V68): LLM-generated memory edit proposal awaiting human review.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@code proposed} (default after LLM emit)</li>
 *   <li>{@code approved} — applied to {@code t_memory} via {@code MemoryProposalService.approve}</li>
 *   <li>{@code rejected} — user rejected, kept for audit</li>
 *   <li>{@code stale} — source memory state changed before approve (race)</li>
 *   <li>{@code auto_archived} — older than 7d without review</li>
 * </ul>
 *
 * <p>All LLM output flows through this table — no direct {@code t_memory} writes from LLM.
 */
@Entity
@Table(name = "t_memory_proposal")
@EntityListeners(AuditingEntityListener.class)
public class MemoryProposalEntity {

    public static final String STATUS_PROPOSED = "proposed";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_AUTO_ARCHIVED = "auto_archived";
    public static final String STATUS_STALE = "stale";

    public static final String TYPE_DEDUP = "dedup";
    public static final String TYPE_REFLECTION = "reflection";
    public static final String TYPE_OPTIMIZE = "optimize";
    public static final String TYPE_CONTRADICTION = "contradiction";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "synthesis_run_id", nullable = false, length = 64)
    private String synthesisRunId;

    @Column(name = "proposal_type", nullable = false, length = 16)
    private String proposalType;

    /**
     * JSON array of bigint memory ids the LLM cited as input.
     * {@link JdbcTypeCode}(JSON) tells Hibernate to bind the String value as jsonb
     * so PostgreSQL accepts the write into a jsonb column (otherwise Hibernate would
     * try to send varchar and PG errors out with type mismatch).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_memory_ids", nullable = false, columnDefinition = "jsonb")
    private String sourceMemoryIds;

    @Column(name = "winner_memory_id")
    private Long winnerMemoryId;

    @Column(name = "suggested_title", length = 256)
    private String suggestedTitle;

    @Column(name = "suggested_content", columnDefinition = "TEXT")
    private String suggestedContent;

    @Column(name = "suggested_importance", length = 16)
    private String suggestedImportance;

    /** Hard-truncated to 200 chars before persist (W-5 / F-N4). */
    @Column(name = "reasoning", length = 256)
    private String reasoning;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_json", columnDefinition = "jsonb")
    private String evidenceJson;

    @Column(name = "llm_prompt_hash", length = 64)
    private String llmPromptHash;

    @Column(name = "llm_response_excerpt", columnDefinition = "TEXT")
    private String llmResponseExcerpt;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PROPOSED;

    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Generated column: {@code created_at + INTERVAL '7 days'}. Read-only from JPA's POV. */
    @Column(name = "auto_archive_after", insertable = false, updatable = false)
    private Instant autoArchiveAfter;

    public MemoryProposalEntity() {
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

    public String getSynthesisRunId() {
        return synthesisRunId;
    }

    public void setSynthesisRunId(String synthesisRunId) {
        this.synthesisRunId = synthesisRunId;
    }

    public String getProposalType() {
        return proposalType;
    }

    public void setProposalType(String proposalType) {
        this.proposalType = proposalType;
    }

    public String getSourceMemoryIds() {
        return sourceMemoryIds;
    }

    public void setSourceMemoryIds(String sourceMemoryIds) {
        this.sourceMemoryIds = sourceMemoryIds;
    }

    public Long getWinnerMemoryId() {
        return winnerMemoryId;
    }

    public void setWinnerMemoryId(Long winnerMemoryId) {
        this.winnerMemoryId = winnerMemoryId;
    }

    public String getSuggestedTitle() {
        return suggestedTitle;
    }

    public void setSuggestedTitle(String suggestedTitle) {
        this.suggestedTitle = suggestedTitle;
    }

    public String getSuggestedContent() {
        return suggestedContent;
    }

    public void setSuggestedContent(String suggestedContent) {
        this.suggestedContent = suggestedContent;
    }

    public String getSuggestedImportance() {
        return suggestedImportance;
    }

    public void setSuggestedImportance(String suggestedImportance) {
        this.suggestedImportance = suggestedImportance;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(String evidenceJson) {
        this.evidenceJson = evidenceJson;
    }

    public String getLlmPromptHash() {
        return llmPromptHash;
    }

    public void setLlmPromptHash(String llmPromptHash) {
        this.llmPromptHash = llmPromptHash;
    }

    public String getLlmResponseExcerpt() {
        return llmResponseExcerpt;
    }

    public void setLlmResponseExcerpt(String llmResponseExcerpt) {
        this.llmResponseExcerpt = llmResponseExcerpt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getReviewedByUserId() {
        return reviewedByUserId;
    }

    public void setReviewedByUserId(Long reviewedByUserId) {
        this.reviewedByUserId = reviewedByUserId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getAutoArchiveAfter() {
        return autoArchiveAfter;
    }

    public void setAutoArchiveAfter(Instant autoArchiveAfter) {
        this.autoArchiveAfter = autoArchiveAfter;
    }
}
