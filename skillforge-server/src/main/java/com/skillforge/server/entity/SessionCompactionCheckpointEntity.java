package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.EntityListeners;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "t_session_compaction_checkpoint", indexes = {
        @Index(name = "idx_scc_session_created", columnList = "session_id, created_at"),
        @Index(name = "idx_scc_session_boundary", columnList = "session_id, boundary_seq_no")
})
public class SessionCompactionCheckpointEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    /** 对应 COMPACT_BOUNDARY 在 t_session_message 的 seq_no。 */
    @Column(name = "boundary_seq_no", nullable = false)
    private long boundarySeqNo;

    /** 对应 SUMMARY 在 t_session_message 的 seq_no。 */
    @Column(name = "summary_seq_no")
    private Long summarySeqNo;

    /** manual / auto-threshold / overflow-retry / timeout-retry。 */
    @Column(name = "reason", length = 32, nullable = false)
    private String reason;

    @Column(name = "pre_range_start_seq_no")
    private Long preRangeStartSeqNo;

    @Column(name = "pre_range_end_seq_no")
    private Long preRangeEndSeqNo;

    @Column(name = "post_range_start_seq_no")
    private Long postRangeStartSeqNo;

    @Column(name = "post_range_end_seq_no")
    private Long postRangeEndSeqNo;

    /** 预留外部快照引用，如对象存储路径。 */
    @Column(name = "snapshot_ref", columnDefinition = "TEXT")
    private String snapshotRef;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SessionCompactionCheckpointEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getBoundarySeqNo() {
        return boundarySeqNo;
    }

    public void setBoundarySeqNo(long boundarySeqNo) {
        this.boundarySeqNo = boundarySeqNo;
    }

    public Long getSummarySeqNo() {
        return summarySeqNo;
    }

    public void setSummarySeqNo(Long summarySeqNo) {
        this.summarySeqNo = summarySeqNo;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getPreRangeStartSeqNo() {
        return preRangeStartSeqNo;
    }

    public void setPreRangeStartSeqNo(Long preRangeStartSeqNo) {
        this.preRangeStartSeqNo = preRangeStartSeqNo;
    }

    public Long getPreRangeEndSeqNo() {
        return preRangeEndSeqNo;
    }

    public void setPreRangeEndSeqNo(Long preRangeEndSeqNo) {
        this.preRangeEndSeqNo = preRangeEndSeqNo;
    }

    public Long getPostRangeStartSeqNo() {
        return postRangeStartSeqNo;
    }

    public void setPostRangeStartSeqNo(Long postRangeStartSeqNo) {
        this.postRangeStartSeqNo = postRangeStartSeqNo;
    }

    public Long getPostRangeEndSeqNo() {
        return postRangeEndSeqNo;
    }

    public void setPostRangeEndSeqNo(Long postRangeEndSeqNo) {
        this.postRangeEndSeqNo = postRangeEndSeqNo;
    }

    public String getSnapshotRef() {
        return snapshotRef;
    }

    public void setSnapshotRef(String snapshotRef) {
        this.snapshotRef = snapshotRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
