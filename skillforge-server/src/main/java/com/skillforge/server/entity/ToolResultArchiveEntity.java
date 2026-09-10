package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * P9-2: 持久化归档单条 tool_result 原文。V198 后新归档通过持久化的
 * message/block occurrence 和 exact payload hash 定位；toolUseId 只是查询属性，不再是唯一身份。
 *
 * <p>归档触发条件：同一 user message 内 tool_result 聚合 chars 超过 per-message 预算
 *（默认 200K）；按大小降序归档直到回到预算内。
 *
 * <p>Legacy rows may have no occurrence fields and remain unclaimed. Partial occurrence
 * uniqueness is DDL-owned because JPA annotations cannot express its predicate.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "t_tool_result_archive",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_tool_result_archive_archive_id",
                        columnNames = {"archive_id"})
        },
        indexes = {
                @Index(name = "idx_tool_result_archive_session_created",
                        columnList = "session_id, created_at"),
                @Index(name = "idx_tool_result_archive_session_tool_hash",
                        columnList = "session_id, tool_use_id, canonical_payload_hash")
        })
public class ToolResultArchiveEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外稳定 UUID（写入 preview 文本，便于追溯）。 */
    @Column(name = "archive_id", length = 36, nullable = false)
    private String archiveId;

    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    /** 关联的 t_session_message.id；若未知则为 null（重放/兼容路径）。 */
    @Column(name = "session_message_id")
    private Long sessionMessageId;

    /** Zero-based block ordinal within the immutable persisted message occurrence. */
    @Column(name = "block_index")
    private Integer blockIndex;

    @Column(name = "tool_use_id", length = 255, nullable = false)
    private String toolUseId;

    /** 可从相邻 assistant tool_use 反查；反查失败时为 null。 */
    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(name = "original_chars", nullable = false)
    private int originalChars;

    /** 2KB head preview（写入 ContentBlock 替换文本）。 */
    @Column(name = "preview", columnDefinition = "TEXT")
    private String preview;

    /** 原始正文。TEXT 列允许较大；上层视输出大小控制。 */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /** Lowercase SHA-256 over the versioned exact scalar payload framing. */
    @Column(name = "canonical_payload_hash", columnDefinition = "CHAR(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String canonicalPayloadHash;

    /** Exact scalar framing version; null only for legacy unclaimed rows. */
    @Column(name = "payload_hash_version")
    private Short payloadHashVersion;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ToolResultArchiveEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getArchiveId() {
        return archiveId;
    }

    public void setArchiveId(String archiveId) {
        this.archiveId = archiveId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getSessionMessageId() {
        return sessionMessageId;
    }

    public void setSessionMessageId(Long sessionMessageId) {
        this.sessionMessageId = sessionMessageId;
    }

    public Integer getBlockIndex() {
        return blockIndex;
    }

    public void setBlockIndex(Integer blockIndex) {
        this.blockIndex = blockIndex;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public int getOriginalChars() {
        return originalChars;
    }

    public void setOriginalChars(int originalChars) {
        this.originalChars = originalChars;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCanonicalPayloadHash() {
        return canonicalPayloadHash;
    }

    public void setCanonicalPayloadHash(String canonicalPayloadHash) {
        this.canonicalPayloadHash = canonicalPayloadHash;
    }

    public Short getPayloadHashVersion() {
        return payloadHashVersion;
    }

    public void setPayloadHashVersion(Short payloadHashVersion) {
        this.payloadHashVersion = payloadHashVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
