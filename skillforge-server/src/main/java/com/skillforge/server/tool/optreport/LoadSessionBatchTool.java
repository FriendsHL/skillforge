package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OPT-REPORT-V1 — STEP 1 / STEP 5 of the {@code report-generator} agent
 * pipeline.
 *
 * <p>Given an {@code agentId} + {@code windowDays}, returns the list of the
 * target agent's recent {@code origin='production'} sessions paginated by
 * {@code offset} / {@code limit}. Each item bundles minimal session metadata
 * plus any existing annotation rows ({@code source ∈ {signal, llm}}) so the
 * report-generator can decide which batches to fan out and, in STEP 5,
 * read back the post-annotation state.
 *
 * <p>Filters mirror the existing {@code session-annotator} pipeline so the
 * "reports route" and the "auto attribution route" see the same universe of
 * sessions:
 * <ul>
 *   <li>{@code parent_session_id IS NULL} — top-level only, skip sub-agent
 *       child sessions which are typically internal book-keeping.</li>
 *   <li>{@code origin = 'production'} — exclude eval-trial sessions.</li>
 *   <li>{@code created_at >= now() - windowDays days} — recent only.</li>
 * </ul>
 *
 * <p>Volumes at dogfood scale: even a hot agent rarely produces &gt; 100
 * sessions / 7d. The default {@code limit=50} comfortably covers the typical
 * case; {@code limit=200} hard cap protects against unbounded scans if a
 * future high-traffic agent gets wired up.
 *
 * <p>Read-only by design — no annotation writes happen here (those go via
 * {@code AnnotateSessionTool} from the worker SubAgent).
 */
public class LoadSessionBatchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(LoadSessionBatchTool.class);

    static final int DEFAULT_WINDOW_DAYS = 7;
    static final int MIN_WINDOW_DAYS = 1;
    static final int MAX_WINDOW_DAYS = 30;
    static final int DEFAULT_OFFSET = 0;
    static final int DEFAULT_LIMIT = 50;
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 200;

    private final SessionRepository sessionRepository;
    private final SessionAnnotationRepository annotationRepository;
    private final com.skillforge.server.repository.AgentRepository agentRepository;
    private final ObjectMapper objectMapper;
    private final java.time.Clock clock;

    public LoadSessionBatchTool(SessionRepository sessionRepository,
                                SessionAnnotationRepository annotationRepository,
                                com.skillforge.server.repository.AgentRepository agentRepository,
                                ObjectMapper objectMapper,
                                java.time.Clock clock) {
        this.sessionRepository = sessionRepository;
        this.annotationRepository = annotationRepository;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public String getName() {
        return "LoadSessionBatch";
    }

    @Override
    public String getDescription() {
        return "OPT-REPORT-V1 STEP 1 / STEP 5: load the target agent's recent "
                + "production sessions (origin='production', parent_session_id IS NULL, "
                + "created_at >= now() - windowDays days) paginated by offset/limit. "
                + "Each item bundles {sessionId, createdAt, runtimeStatus, messageCount, "
                + "annotations:[{type,value,source,confidence,reasoning?}]}. Also returns "
                + "total count (unpaged) so the agent can compute batches. "
                + "Defaults: windowDays=" + DEFAULT_WINDOW_DAYS
                + " (clamped [" + MIN_WINDOW_DAYS + ", " + MAX_WINDOW_DAYS + "]), "
                + "offset=" + DEFAULT_OFFSET + ", limit=" + DEFAULT_LIMIT
                + " (clamped [" + MIN_LIMIT + ", " + MAX_LIMIT + "]).";
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
        properties.put("offset", Map.of(
                "type", "integer",
                "description", "Pagination offset; default " + DEFAULT_OFFSET + "."));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "Page size; default " + DEFAULT_LIMIT
                        + ", clamped [" + MIN_LIMIT + ", " + MAX_LIMIT + "]."));

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
            int offset = Math.max(asInt(input.get("offset"), DEFAULT_OFFSET), 0);
            int limit = clamp(asInt(input.get("limit"), DEFAULT_LIMIT), MIN_LIMIT, MAX_LIMIT);

            Instant now = clock.instant();
            Instant windowStart = now.minus(windowDays, ChronoUnit.DAYS);

            // Pull all candidate sessions for the agent in the window, then apply
            // offset/limit + filter in-memory. SessionRepository has multiple
            // findBy variants but none combine (agentId, origin, createdAt) with
            // pagination in one call — at V1 dogfood scale (typically < 200
            // sessions / 7d / agent) the in-memory filter is fine and avoids
            // adding a custom JPQL just for this one tool.
            List<SessionEntity> all = sessionRepository.findByAgentId(agentId);
            List<SessionEntity> filtered = new ArrayList<>();
            for (SessionEntity s : all) {
                if (s.getParentSessionId() != null) continue;
                if (!SessionEntity.ORIGIN_PRODUCTION.equals(s.getOrigin())) continue;
                Instant created = toInstant(s.getCreatedAt());
                if (created == null || created.isBefore(windowStart)) continue;
                filtered.add(s);
            }
            // Sort by createdAt DESC; tie-break by id desc for determinism.
            filtered.sort((a, b) -> {
                Instant ai = toInstant(a.getCreatedAt());
                Instant bi = toInstant(b.getCreatedAt());
                if (ai == null && bi == null) return b.getId().compareTo(a.getId());
                if (ai == null) return 1;
                if (bi == null) return -1;
                int byTime = bi.compareTo(ai);
                if (byTime != 0) return byTime;
                return b.getId().compareTo(a.getId());
            });

            int total = filtered.size();
            int from = Math.min(offset, total);
            int to = Math.min(from + limit, total);
            List<SessionEntity> page = filtered.subList(from, to);

            List<Map<String, Object>> items = new ArrayList<>(page.size());
            for (SessionEntity s : page) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sessionId", s.getId());
                Instant created = toInstant(s.getCreatedAt());
                item.put("createdAt", created == null ? null : created.toString());
                item.put("runtimeStatus", s.getRuntimeStatus());
                item.put("messageCount", s.getMessageCount());

                List<SessionAnnotationEntity> rows = annotationRepository.findBySessionId(s.getId());
                List<Map<String, Object>> annotations = new ArrayList<>(rows.size());
                for (SessionAnnotationEntity a : rows) {
                    Map<String, Object> ann = new LinkedHashMap<>();
                    ann.put("type", a.getAnnotationType());
                    ann.put("value", a.getAnnotationValue());
                    ann.put("source", a.getSource());
                    ann.put("confidence", a.getConfidence());
                    if (a.getReasoning() != null) {
                        ann.put("reasoning", a.getReasoning());
                    }
                    annotations.add(ann);
                }
                item.put("annotations", annotations);
                items.add(item);
            }

            // V1.1 fix: include the real agent name so the report-generator
            // can render "Design Agent" in the report header instead of
            // "Agent #N" placeholder.
            String agentName = agentRepository.findById(agentId)
                    .map(com.skillforge.server.entity.AgentEntity::getName)
                    .orElse("agent#" + agentId);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("agentId", agentId);
            payload.put("agentName", agentName);
            payload.put("windowDays", windowDays);
            payload.put("windowStart", windowStart.toString());
            payload.put("windowEnd", now.toString());
            payload.put("total", total);
            payload.put("offset", offset);
            payload.put("items", items);

            log.info("LoadSessionBatchTool: agentId={} windowDays={} total={} offset={} limit={} returned={}",
                    agentId, windowDays, total, offset, limit, items.size());
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("LoadSessionBatchTool execute failed", e);
            return SkillResult.error("LoadSessionBatch error: " + e.getMessage());
        }
    }

    /**
     * {@code SessionEntity.createdAt} is {@code LocalDateTime} (java.md
     * footgun #2 — historical); convert via the deployment's default zone.
     * Server is assumed UTC; mismatched zones would only shift the lookback
     * window by ±1 day for a sliver of sessions near the boundary, which is
     * acceptable for the V1 reports use case.
     */
    private static Instant toInstant(java.time.LocalDateTime ldt) {
        return ldt == null ? null : ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
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
