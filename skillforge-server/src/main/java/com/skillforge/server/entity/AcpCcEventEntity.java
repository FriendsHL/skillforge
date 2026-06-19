package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A normalized OTLP log/event emitted by a spawned Claude Code (cc) child process
 * (ACP-EXTERNAL-AGENT P2-1), bound to a SkillForge cc sub-session.
 *
 * <p>Spike-verified (2026-06-19): cc emits OTLP metrics + logs(events), NOT
 * traces/spans. Each cc event carries the injected {@code sf.session_id} resource
 * attribute (see {@code AcpAgentRunner} telemetry env), which links it to the
 * SkillForge sub-session in {@link #sessionId}.
 *
 * <p><b>PRIVACY:</b> {@link #attrsJson} holds ONLY structural attributes — the
 * ingest layer strips PII ({@code user.email} / {@code user.account_uuid} /
 * {@code user.account_id} / {@code user.id} / {@code organization.id}) and the
 * {@code prompt} full text before persisting (only {@code prompt_length} is kept).
 *
 * <p>P2-1 only LANDS these rows; translating them into {@code LlmSpan} (P2-2) and
 * the trace-tree UI (P2-3) are out of scope here.
 */
@Entity
@Table(name = "t_acp_cc_event")
public class AcpCcEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SkillForge cc sub-session id (the injected {@code sf.session_id}). */
    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    /** cc's own {@code session.id} (structural, nullable). */
    @Column(name = "cc_session_id", length = 128)
    private String ccSessionId;

    /** Event name, e.g. {@code claude_code.api_request}. */
    @Column(name = "event_name", nullable = false, length = 64)
    private String eventName;

    /** {@code event.sequence} (nullable). */
    @Column(name = "event_seq")
    private Long eventSeq;

    /** {@code event.timestamp} / logRecord time (nullable). */
    @Column(name = "ts")
    private Instant ts;

    /** {@code agent.name} on subagent api_request events (nullable). */
    @Column(name = "agent_name", length = 128)
    private String agentName;

    /** {@code tool_name} on tool_* events (nullable). */
    @Column(name = "tool_name", length = 128)
    private String toolName;

    /** {@code tool_use_id} on tool_* events (nullable). */
    @Column(name = "tool_use_id", length = 128)
    private String toolUseId;

    /** PII-filtered structural attributes serialized as a JSON object. */
    @Column(name = "attrs_json", nullable = false, columnDefinition = "TEXT")
    private String attrsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public AcpCcEventEntity() {
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

    public String getCcSessionId() {
        return ccSessionId;
    }

    public void setCcSessionId(String ccSessionId) {
        this.ccSessionId = ccSessionId;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public Long getEventSeq() {
        return eventSeq;
    }

    public void setEventSeq(Long eventSeq) {
        this.eventSeq = eventSeq;
    }

    public Instant getTs() {
        return ts;
    }

    public void setTs(Instant ts) {
        this.ts = ts;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }

    public String getAttrsJson() {
        return attrsJson;
    }

    public void setAttrsJson(String attrsJson) {
        this.attrsJson = attrsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
