package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Range summary for the compaction storage redesign (storage-redesign.md §2.2, P1).
 *
 * <p>A row records that the real message rows in {@code [startSeq, endSeq]} of a session were
 * compacted into {@code summaryText}. This table is model-view-only: the model view injects the
 * summary in place of the covered span; the user view never reads it (users see the full real
 * history). {@code recoveryPayload} (Q4) carries restart-recovery info without a message row;
 * {@code supersededBy} (Q3) implements the rolling-summary merge — a superseded summary points at
 * the new summary that subsumes it, and {@code superseded_by IS NULL} identifies the active one.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "t_session_summary", indexes = {
        // JPA @Index cannot express a partial-index predicate. The real DDL index in V157 is
        // PARTIAL — `WHERE superseded_by IS NULL` — so it only covers active summaries; this
        // annotation only documents the (session_id, start_seq) column list for schema tools.
        @Index(name = "idx_ss_session_active", columnList = "session_id, start_seq")
})
public class SessionSummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    /** Inclusive start of the covered real seq_no range. */
    @Column(name = "start_seq", nullable = false)
    private long startSeq;

    /** Inclusive end of the covered real seq_no range. */
    @Column(name = "end_seq", nullable = false)
    private long endSeq;

    @Column(name = "summary_text", columnDefinition = "TEXT", nullable = false)
    private String summaryText;

    /** Compaction level: {@code light} / {@code full}. */
    @Column(name = "level", length = 16, nullable = false)
    private String level;

    /** Trigger source (e.g. {@code engine-hard}, {@code user-manual}). */
    @Column(name = "source", length = 32)
    private String source;

    @Column(name = "tokens_before")
    private Integer tokensBefore;

    @Column(name = "tokens_after")
    private Integer tokensAfter;

    @Column(name = "compacted_message_count")
    private Integer compactedMessageCount;

    /** Q4: restart-recovery payload (e.g. recently-read files snapshot); injected into model view. */
    @Column(name = "recovery_payload", columnDefinition = "TEXT")
    private String recoveryPayload;

    /** Q3: when this summary has been merged into a newer one, points at the newer summary's id; NULL = active. */
    @Column(name = "superseded_by")
    private Long supersededBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SessionSummaryEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getStartSeq() {
        return startSeq;
    }

    public void setStartSeq(long startSeq) {
        this.startSeq = startSeq;
    }

    public long getEndSeq() {
        return endSeq;
    }

    public void setEndSeq(long endSeq) {
        this.endSeq = endSeq;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public void setSummaryText(String summaryText) {
        this.summaryText = summaryText;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Integer getTokensBefore() {
        return tokensBefore;
    }

    public void setTokensBefore(Integer tokensBefore) {
        this.tokensBefore = tokensBefore;
    }

    public Integer getTokensAfter() {
        return tokensAfter;
    }

    public void setTokensAfter(Integer tokensAfter) {
        this.tokensAfter = tokensAfter;
    }

    public Integer getCompactedMessageCount() {
        return compactedMessageCount;
    }

    public void setCompactedMessageCount(Integer compactedMessageCount) {
        this.compactedMessageCount = compactedMessageCount;
    }

    public String getRecoveryPayload() {
        return recoveryPayload;
    }

    public void setRecoveryPayload(String recoveryPayload) {
        this.recoveryPayload = recoveryPayload;
    }

    public Long getSupersededBy() {
        return supersededBy;
    }

    public void setSupersededBy(Long supersededBy) {
        this.supersededBy = supersededBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
