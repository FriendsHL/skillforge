package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "t_compaction_event", indexes = {
        @Index(name = "idx_compact_session", columnList = "sessionId"),
        @Index(name = "idx_compact_triggered_at", columnList = "triggeredAt")
})
public class CompactionEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 36, nullable = false)
    private String sessionId;

    /** light / full */
    @Column(length = 8, nullable = false)
    private String level;

    /** agent-tool / engine-soft / engine-hard / engine-gap / user-manual */
    @Column(length = 16, nullable = false)
    private String source;

    @Column(columnDefinition = "CLOB")
    private String reason;

    @Column(nullable = false)
    private Instant triggeredAt;

    @Column(nullable = false)
    private int beforeTokens;

    @Column(nullable = false)
    private int afterTokens;

    @Column(nullable = false)
    private int tokensReclaimed;

    @Column(nullable = false)
    private int beforeMessageCount;

    @Column(nullable = false)
    private int afterMessageCount;

    /** 逗号分隔的 rule 名; full 压缩固定为 "llm-summary" */
    @Column(length = 256)
    private String strategiesApplied;

    /** full 压缩的 LLM trace id (可选) */
    @Column(length = 36)
    private String llmCallId;

    public CompactionEventEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Instant getTriggeredAt() { return triggeredAt; }
    public void setTriggeredAt(Instant triggeredAt) { this.triggeredAt = triggeredAt; }

    public int getBeforeTokens() { return beforeTokens; }
    public void setBeforeTokens(int beforeTokens) { this.beforeTokens = beforeTokens; }

    public int getAfterTokens() { return afterTokens; }
    public void setAfterTokens(int afterTokens) { this.afterTokens = afterTokens; }

    public int getTokensReclaimed() { return tokensReclaimed; }
    public void setTokensReclaimed(int tokensReclaimed) { this.tokensReclaimed = tokensReclaimed; }

    public int getBeforeMessageCount() { return beforeMessageCount; }
    public void setBeforeMessageCount(int beforeMessageCount) { this.beforeMessageCount = beforeMessageCount; }

    public int getAfterMessageCount() { return afterMessageCount; }
    public void setAfterMessageCount(int afterMessageCount) { this.afterMessageCount = afterMessageCount; }

    public String getStrategiesApplied() { return strategiesApplied; }
    public void setStrategiesApplied(String strategiesApplied) { this.strategiesApplied = strategiesApplied; }

    public String getLlmCallId() { return llmCallId; }
    public void setLlmCallId(String llmCallId) { this.llmCallId = llmCallId; }
}
