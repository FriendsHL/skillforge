package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVE-CLOSE-LOOP P2-a G5 — 段 1 (症状归类) of the
 * {@code holistic-error-span-analyzer} workflow sub-agent.
 *
 * <p>Given an {@code agentId} + {@code windowDays}, returns the target agent's
 * failed tool-call spans ({@code kind='tool' AND error IS NOT NULL}) across its
 * recent production sessions, already grouped by <em>(toolName + error
 * signature)</em> so the analyzer sees fine-grained, cross-session symptom
 * clusters instead of a flat span dump.
 *
 * <p><b>Session universe.</b> Error spans are scoped to the SAME universe of
 * sessions {@link LoadSessionBatchTool} reports on — top-level
 * ({@code parent_session_id IS NULL}), {@code origin='production'}, within
 * {@code windowDays}. This deliberately mirrors {@code LoadSessionBatchTool}'s
 * inline predicate (must stay in sync); filtering {@code t_llm_span} by
 * {@code agent_id} alone would also pull in eval-trial / sub-agent spans, which
 * the opt-report never annotates.
 *
 * <p><b>Grouping.</b> The error signature masks the volatile parts of an error
 * message (quoted substrings, file paths, numbers) and truncates to a stable
 * prefix, so e.g. {@code "Path is not a directory: /a/b/src"} and
 * {@code "Path is not a directory: /c/d/src"} collapse into one group while
 * {@code "old_string not found in file"} stays distinct. Each group bundles
 * {@code count}, distinct {@code sessionCount}, capped {@code exampleSessionIds}
 * (for the analyzer's 段 2 representative-session pull), and one raw
 * {@code exampleError} for the analyzer to read.
 *
 * <p>Read-only by design — no writes. Volumes at dogfood scale are small (a hot
 * agent ~100 error spans / 30d); the {@link #MAX_ERROR_SPANS} cap is a backstop
 * against a future high-traffic agent, and truncation is logged (never silent).
 */
public class LoadErrorSpanBatchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(LoadErrorSpanBatchTool.class);

    static final int DEFAULT_WINDOW_DAYS = 30;
    static final int MIN_WINDOW_DAYS = 1;
    static final int MAX_WINDOW_DAYS = 30;

    /** Hard cap on error spans scanned, mirrors LoadSessionBatch's MAX_LIMIT scale. */
    static final int MAX_ERROR_SPANS = 2000;
    /** Hard cap on distinct groups returned (sorted by count DESC). */
    static final int MAX_GROUPS = 50;
    /** Example session ids retained per group (for 段 2 representative pull). */
    static final int MAX_EXAMPLE_SESSIONS = 5;
    /** Error-signature prefix length after masking volatile parts. */
    static final int SIGNATURE_PREFIX_LEN = 80;
    /** Truncation cap for the raw example error surfaced per group. */
    static final int EXAMPLE_ERROR_MAX = 400;

    private static final String KIND_TOOL = "tool";

    // Error-signature normalization patterns (precompiled). Order matters: quoted
    // substrings are masked first (they may contain slashes/digits), then any
    // whitespace-free token containing a slash (paths/URLs), then standalone numbers.
    private static final java.util.regex.Pattern DQUOTE = java.util.regex.Pattern.compile("\"[^\"]*\"");
    private static final java.util.regex.Pattern SQUOTE = java.util.regex.Pattern.compile("'[^']*'");
    private static final java.util.regex.Pattern PATH_TOKEN = java.util.regex.Pattern.compile("\\S*/\\S*");
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("\\b\\d+\\b");
    private static final java.util.regex.Pattern WS = java.util.regex.Pattern.compile("\\s+");

    private final SessionRepository sessionRepository;
    private final LlmSpanRepository spanRepository;
    private final ObjectMapper objectMapper;
    private final java.time.Clock clock;

    public LoadErrorSpanBatchTool(SessionRepository sessionRepository,
                                  LlmSpanRepository spanRepository,
                                  ObjectMapper objectMapper,
                                  java.time.Clock clock) {
        this.sessionRepository = sessionRepository;
        this.spanRepository = spanRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public String getName() {
        return "LoadErrorSpanBatch";
    }

    @Override
    public String getDescription() {
        return "AUTOEVOLVE G5 段1: load the target agent's failed tool-call spans "
                + "(kind='tool' AND error IS NOT NULL) across its recent production "
                + "sessions (origin='production', parent_session_id IS NULL, within "
                + "windowDays), grouped by (toolName + error signature) across sessions. "
                + "Returns {agentId, windowDays, sessionCount, errorSpanCount, truncated, "
                + "groups:[{toolName, errorType, errorSignature, count, sessionCount, "
                + "exampleSessionIds:[...], exampleError}]} sorted by count DESC. "
                + "groups is [] (never null) when the agent has no failures in the window. "
                + "Defaults: windowDays=" + DEFAULT_WINDOW_DAYS
                + " (clamped [" + MIN_WINDOW_DAYS + ", " + MAX_WINDOW_DAYS + "]).";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agentId", Map.of(
                "type", "integer",
                "description", "Target agent id (t_agent.id, long)."));
        properties.put("windowDays", Map.of(
                "type", "integer",
                "description", "Lookback window in days; default " + DEFAULT_WINDOW_DAYS
                        + ", clamped [" + MIN_WINDOW_DAYS + ", " + MAX_WINDOW_DAYS + "]."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("agentId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                return SkillResult.validationError("input is required");
            }
            Long agentId = asLong(input.get("agentId"));
            if (agentId == null || agentId <= 0L) {
                return SkillResult.validationError("agentId must be a positive long");
            }
            int windowDays = clamp(asInt(input.get("windowDays"), DEFAULT_WINDOW_DAYS),
                    MIN_WINDOW_DAYS, MAX_WINDOW_DAYS);

            Instant now = clock.instant();
            Instant windowStart = now.minus(windowDays, ChronoUnit.DAYS);

            // Session universe — MUST match LoadSessionBatchTool's predicate so the
            // analyzer sees error spans from the same sessions the report annotates.
            List<String> sessionIds = productionSessionIds(agentId, windowStart);
            if (sessionIds.isEmpty()) {
                return SkillResult.success(objectMapper.writeValueAsString(
                        emptyPayload(agentId, windowDays, windowStart, now)));
            }

            // Pull all spans for those sessions in time order, then keep the failed
            // tool calls. findBySessionIdInOrderByStartedAtAsc batches the IN-list.
            List<LlmSpanEntity> spans = spanRepository.findBySessionIdInOrderByStartedAtAsc(sessionIds);
            List<LlmSpanEntity> errorSpans = new ArrayList<>();
            for (LlmSpanEntity s : spans) {
                if (!KIND_TOOL.equals(s.getKind())) continue;
                if (s.getError() == null || s.getError().isBlank()) continue;
                errorSpans.add(s);
            }

            boolean truncated = errorSpans.size() > MAX_ERROR_SPANS;
            if (truncated) {
                log.warn("LoadErrorSpanBatchTool: agentId={} error spans {} exceed cap {} — truncating",
                        agentId, errorSpans.size(), MAX_ERROR_SPANS);
                errorSpans = errorSpans.subList(0, MAX_ERROR_SPANS);
            }

            List<Map<String, Object>> groups = groupBySignature(errorSpans);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("agentId", agentId);
            payload.put("windowDays", windowDays);
            payload.put("windowStart", windowStart.toString());
            payload.put("windowEnd", now.toString());
            payload.put("sessionCount", sessionIds.size());
            payload.put("errorSpanCount", errorSpans.size());
            payload.put("truncated", truncated);
            payload.put("groups", groups);

            log.info("LoadErrorSpanBatchTool: agentId={} windowDays={} sessions={} errorSpans={} groups={} truncated={}",
                    agentId, windowDays, sessionIds.size(), errorSpans.size(), groups.size(), truncated);
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("LoadErrorSpanBatchTool execute failed", e);
            return SkillResult.error("LoadErrorSpanBatch error: " + e.getMessage());
        }
    }

    /**
     * Top-level production sessionIds for the agent within the window. Mirrors
     * {@link LoadSessionBatchTool}'s inline filter (parent_session_id IS NULL,
     * origin='production', createdAt >= windowStart) — keep in sync.
     */
    private List<String> productionSessionIds(Long agentId, Instant windowStart) {
        List<SessionEntity> all = sessionRepository.findByAgentId(agentId);
        List<String> ids = new ArrayList<>();
        for (SessionEntity s : all) {
            if (s.getParentSessionId() != null) continue;
            if (!SessionEntity.ORIGIN_PRODUCTION.equals(s.getOrigin())) continue;
            Instant created = toInstant(s.getCreatedAt());
            if (created == null || created.isBefore(windowStart)) continue;
            ids.add(s.getId());
        }
        return ids;
    }

    /**
     * Group error spans by (toolName + errorType + masked error signature). Within
     * a group, accumulate count, distinct sessions, capped example sessions, and
     * keep the first raw error as the example. Returns groups sorted by count DESC
     * then distinct-session count DESC, capped at {@link #MAX_GROUPS}.
     */
    private List<Map<String, Object>> groupBySignature(List<LlmSpanEntity> errorSpans) {
        Map<String, Group> byKey = new LinkedHashMap<>();
        for (LlmSpanEntity s : errorSpans) {
            String name = s.getName();
            String errorType = s.getErrorType();
            String signature = errorSignature(name, errorType, s.getError());
            String key = (name == null ? "?" : name) + " "
                    + (errorType == null ? "" : errorType) + " " + signature;
            Group g = byKey.computeIfAbsent(key, k -> new Group(name, errorType, signature, s.getError()));
            g.count++;
            if (s.getSessionId() != null) {
                g.sessions.add(s.getSessionId());
            }
        }

        List<Group> sorted = new ArrayList<>(byKey.values());
        sorted.sort((a, b) -> {
            if (b.count != a.count) return Integer.compare(b.count, a.count);
            return Integer.compare(b.sessions.size(), a.sessions.size());
        });

        List<Map<String, Object>> out = new ArrayList<>();
        for (Group g : sorted) {
            if (out.size() >= MAX_GROUPS) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("toolName", g.toolName);
            m.put("errorType", g.errorType);
            m.put("errorSignature", g.signature);
            m.put("count", g.count);
            m.put("sessionCount", g.sessions.size());
            List<String> examples = new ArrayList<>();
            for (String sid : g.sessions) {
                if (examples.size() >= MAX_EXAMPLE_SESSIONS) break;
                examples.add(sid);
            }
            m.put("exampleSessionIds", examples);
            m.put("exampleError", truncate(g.exampleError, EXAMPLE_ERROR_MAX));
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> emptyPayload(Long agentId, int windowDays, Instant windowStart, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", agentId);
        payload.put("windowDays", windowDays);
        payload.put("windowStart", windowStart.toString());
        payload.put("windowEnd", now.toString());
        payload.put("sessionCount", 0);
        payload.put("errorSpanCount", 0);
        payload.put("truncated", false);
        payload.put("groups", new ArrayList<>());
        return payload;
    }

    /** Mutable accumulator for one (toolName + errorType + signature) group. */
    private static final class Group {
        final String toolName;
        final String errorType;
        final String signature;
        final String exampleError;
        final LinkedHashSet<String> sessions = new LinkedHashSet<>();
        int count;

        Group(String toolName, String errorType, String signature, String exampleError) {
            this.toolName = toolName;
            this.errorType = errorType;
            this.signature = signature;
            this.exampleError = exampleError;
        }
    }

    /**
     * Deterministic error signature: mask the volatile parts (quoted substrings,
     * file paths, numbers) then truncate, so messages differing only in specifics
     * collapse into one group.
     */
    static String errorSignature(String name, String errorType, String error) {
        StringBuilder sig = new StringBuilder();
        sig.append(name == null ? "?" : name);
        if (errorType != null && !errorType.isBlank()) {
            sig.append(" | ").append(errorType.trim());
        }
        sig.append(" | ").append(normalizeError(error));
        return sig.toString();
    }

    static String normalizeError(String error) {
        if (error == null) {
            return "";
        }
        String s = error.trim();
        s = DQUOTE.matcher(s).replaceAll("\"_\"");
        s = SQUOTE.matcher(s).replaceAll("'_'");
        s = PATH_TOKEN.matcher(s).replaceAll("<path>");
        s = NUMBER.matcher(s).replaceAll("#");
        s = WS.matcher(s).replaceAll(" ").trim();
        if (s.length() > SIGNATURE_PREFIX_LEN) {
            s = s.substring(0, SIGNATURE_PREFIX_LEN);
        }
        return s;
    }

    /**
     * {@code SessionEntity.createdAt} is {@code LocalDateTime} (legacy); convert
     * via the deployment's default zone — same approach as {@link LoadSessionBatchTool}.
     */
    private static Instant toInstant(java.time.LocalDateTime ldt) {
        return ldt == null ? null : ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
    }

    private static String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static int asInt(Object o, int defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        return Math.min(v, hi);
    }
}
