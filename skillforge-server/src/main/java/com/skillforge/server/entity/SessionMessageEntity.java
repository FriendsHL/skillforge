package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import jakarta.persistence.EntityListeners;

import java.time.Instant;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "t_session_message",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_session_message_session_seq",
                        columnNames = {"session_id", "seq_no"})
        },
        indexes = {
        @Index(name = "idx_session_message_session_created", columnList = "session_id, created_at"),
        @Index(name = "idx_session_message_session_type_seq", columnList = "session_id, msg_type, seq_no"),
        // partial index predicate (pruned_at IS NOT NULL) is defined in Flyway V18 SQL
        @Index(name = "idx_session_message_session_pruned", columnList = "session_id, pruned_at")
})
public class SessionMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    @Column(name = "seq_no", nullable = false)
    private long seqNo;

    @Column(name = "role", length = 16, nullable = false)
    private String role;

    /** 消息类型：NORMAL / COMPACT_BOUNDARY / SUMMARY / SYSTEM_EVENT。 */
    @Column(name = "msg_type", length = 32, nullable = false)
    private String msgType;

    /** Message.content 的 JSON 序列化结果。 */
    @Column(name = "content_json", columnDefinition = "TEXT")
    private String contentJson;

    /** 扩展元数据 JSON（boundary token、checkpoint id 等）。 */
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    /** UI / 控制流语义：normal / team_result / subagent_result / ask_user / confirmation。 */
    @Column(name = "message_type", length = 32, nullable = false)
    private String messageType = "normal";

    /** 控制消息稳定 ID，例如 askId / confirmationId。 */
    @Column(name = "control_id", length = 64)
    private String controlId;

    /** OpenAI 兼容 provider thinking 模式下的推理内容；带 tool_use 的下一轮必须原样回传，否则 API 400。 */
    @Column(name = "reasoning_content", columnDefinition = "TEXT")
    private String reasoningContent;

    /** 预留给后续工具输出裁剪：非空表示该消息已被裁剪。 */
    @Column(name = "pruned_at")
    private Instant prunedAt;

    /** 一次性控制卡片完成时间；非空表示前端折叠为历史摘要。 */
    @Column(name = "answered_at")
    private Instant answeredAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SessionMessageEntity() {
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

    public long getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(long seqNo) {
        this.seqNo = seqNo;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }

    public String getContentJson() {
        return contentJson;
    }

    public void setContentJson(String contentJson) {
        this.contentJson = contentJson;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getControlId() {
        return controlId;
    }

    public void setControlId(String controlId) {
        this.controlId = controlId;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public Instant getPrunedAt() {
        return prunedAt;
    }

    public void setPrunedAt(Instant prunedAt) {
        this.prunedAt = prunedAt;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(Instant answeredAt) {
        this.answeredAt = answeredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
