package com.skillforge.server.service.observability;

import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.TraceSpanEntity;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan §7.4 R3-WN2 — resolve the child sessionId for a SubAgent TOOL_CALL span.
 *
 * <p>双源解析：
 * <ol>
 *   <li>Primary: parse {@code TraceSpan.output} for {@code "  childSessionId: <uuid>\n"}
 *   (字面格式与 {@code SubAgentTool.java:174} 严格一致)</li>
 *   <li>Fallback: find a child session whose {@code parentSessionId == span.sessionId} AND
 *   {@code createdAt >= span.startTime} (use earliest match)</li>
 * </ol>
 *
 * <p>解析失败时回退到 NULL，前端不渲染跳转链接（正向行为是"显示链接 → 跳转"；
 * 负向行为是"不显示链接"——不会出现错误指向）。
 */
@Service
public class SubagentSessionResolver {

    private static final Logger log = LoggerFactory.getLogger(SubagentSessionResolver.class);

    /** Pattern strictly matches {@code SubAgentTool.java:174} format. */
    private static final Pattern CHILD_ID_PATTERN =
            Pattern.compile("(?m)^\\s*childSessionId:\\s*([a-f0-9-]{36})\\s*$");

    private final SessionRepository sessionRepository;

    public SubagentSessionResolver(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Resolve the child sessionId for a SubAgent TOOL_CALL span. Never throws — exceptions
     * are swallowed and {@code null} is returned (UI gracefully omits the jump link).
     */
    public String resolve(TraceSpanEntity span) {
        if (span == null) return null;
        if (!"TOOL_CALL".equals(span.getSpanType())) return null;
        if (!"SubAgent".equals(span.getName())) return null;
        return resolve(span.getId(), span.getName(), span.getSessionId(),
                span.getOutput(), span.getStartTime());
    }

    /**
     * OBS-2 M3 — resolve SubAgent child sessionId for a {@link LlmSpan} (kind='tool')
     * after the read-path cut-over to {@code t_llm_span}.
     */
    public String resolve(LlmSpan span) {
        if (span == null) return null;
        if (!"tool".equals(span.kind())) return null;
        if (!"SubAgent".equals(span.name())) return null;
        return resolve(span.spanId(), span.name(), span.sessionId(),
                span.outputSummary(), span.startedAt());
    }

    private String resolve(String spanId, String name, String sessionId,
                           String outputText, Instant startTime) {
        try {
            // 1. Primary: regex on output text.
            if (outputText != null) {
                Matcher m = CHILD_ID_PATTERN.matcher(outputText);
                if (m.find()) return m.group(1);
            }
            // 2. Fallback: look up child session by parent_session_id + createdAt.
            // SessionEntity.createdAt is LocalDateTime (java.md footgun #2 historical);
            // span.startTime is Instant. Use systemDefault zone (server deployment assumes UTC).
            if (startTime == null || sessionId == null) return null;
            LocalDateTime startedAtLdt = LocalDateTime.ofInstant(startTime, ZoneId.systemDefault());
            return sessionRepository
                    .findFirstByParentSessionIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                            sessionId, startedAtLdt)
                    .map(SessionEntity::getId)
                    .orElse(null);
        } catch (Throwable t) {
            log.debug("SubagentSessionResolver swallowed exception for span={}", spanId, t);
            return null;
        }
    }
}
