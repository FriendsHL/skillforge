package com.skillforge.server.dto;

import java.time.Instant;
import java.util.Map;

/**
 * REST DTO for one persisted session message row.
 *
 * <p>{@code createdAt} carries {@code t_session_message.created_at} (audit category — see
 * {@code .claude/rules/identity-column-on-rewrite.md}) so FE can render the message bubble's
 * hover tooltip. It serializes as ISO-8601 via the Spring-managed {@code ObjectMapper}
 * (JavaTimeModule auto-registered; see {@code .claude/rules/java.md} footgun #1).
 * Nullable: legacy CLOB fallback path has no row-level timestamp.
 */
public record SessionMessageDto(
        long seqNo,
        String role,
        Object content,
        String msgType,
        String messageType,
        String controlId,
        Instant answeredAt,
        Map<String, Object> metadata,
        String traceId,
        Instant createdAt,
        String reasoningContent
) {
    /** Backward-compat — defaults reasoningContent to null. */
    public SessionMessageDto(long seqNo, String role, Object content, String msgType,
                             String messageType, String controlId, Instant answeredAt,
                             Map<String, Object> metadata, String traceId, Instant createdAt) {
        this(seqNo, role, content, msgType, messageType, controlId, answeredAt, metadata, traceId, createdAt, null);
    }

    /** Backward-compat — defaults createdAt + reasoningContent to null. */
    public SessionMessageDto(long seqNo, String role, Object content, String msgType,
                             String messageType, String controlId, Instant answeredAt,
                             Map<String, Object> metadata, String traceId) {
        this(seqNo, role, content, msgType, messageType, controlId, answeredAt, metadata, traceId, null, null);
    }

    /** Backward-compat — defaults traceId + createdAt + reasoningContent to null. */
    public SessionMessageDto(long seqNo, String role, Object content, String msgType,
                             String messageType, String controlId, Instant answeredAt,
                             Map<String, Object> metadata) {
        this(seqNo, role, content, msgType, messageType, controlId, answeredAt, metadata, null, null, null);
    }

    public SessionMessageDto(long seqNo, String role, Object content, String msgType,
                             Map<String, Object> metadata) {
        this(seqNo, role, content, msgType, "normal", null, null, metadata, null, null, null);
    }
}
