package com.skillforge.server.tool.sessionannotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.sessionannotation.SessionPatternClusterService;
import com.skillforge.server.sessionannotation.SessionPatternClusterService.RecomputeResult;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PROD-LABEL-CLUSTER V1 (Phase 1.4): thin agent-facing wrapper around
 * {@link SessionPatternClusterService#recompute(Duration)}.
 *
 * <p>STEP 3 of the {@code session-annotator} agent pipeline (§4.1
 * "Clustering — deterministic"). The agent invokes this once per run with
 * default {@code window_days=7} (matches tech-design §5.3); the tool re-folds
 * annotations into cluster signatures, upserts {@code t_session_pattern} rows,
 * and appends any new {@code t_pattern_session_member} entries.
 *
 * <p>All business logic lives in the service — this tool is the JSON adapter
 * (parse window_days, format output) and an LLM-facing description. The
 * service unit tests lock the main behavior; this tool's tests verify wiring.
 */
public class RecomputeClustersTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RecomputeClustersTool.class);

    private static final int DEFAULT_WINDOW_DAYS = 7;
    private static final int MIN_WINDOW_DAYS = 1;
    private static final int MAX_WINDOW_DAYS = 30;  // defensive ceiling; V1 monthly is the practical limit.

    private final SessionPatternClusterService clusterService;
    private final ObjectMapper objectMapper;

    public RecomputeClustersTool(SessionPatternClusterService clusterService,
                                 ObjectMapper objectMapper) {
        this.clusterService = clusterService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "RecomputeClusters";
    }

    @Override
    public String getDescription() {
        return "STEP 3 of the session-annotator pipeline. Scan source='llm' annotations "
                + "created in the last N days (default " + DEFAULT_WINDOW_DAYS
                + ", clamped to [" + MIN_WINDOW_DAYS + ", " + MAX_WINDOW_DAYS + "]), bucket "
                + "sessions by (outcome × suspect_surface × top_failing_tool × agent_id), "
                + "and upsert each bucket with ≥ 3 members into t_session_pattern. "
                + "Excludes outcome=success and confidence < 0.5 per PRD admission rules. "
                + "Idempotent on re-run — duplicate cluster rows and duplicate member "
                + "rows are avoided via signature UNIQUE + composite PK respectively. "
                + "No LLM judgment performed here.";
    }

    @Override
    public boolean isReadOnly() {
        // Writes t_session_pattern + t_pattern_session_member. Keep default false.
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("window_days", Map.of(
                "type", "integer",
                "description", "Look-back window in days (default " + DEFAULT_WINDOW_DAYS
                        + ", clamped to [" + MIN_WINDOW_DAYS + ", " + MAX_WINDOW_DAYS + "])."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        // window_days intentionally not required — sensible default keeps the agent prompt simple.
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            int windowDays = DEFAULT_WINDOW_DAYS;
            if (input != null) {
                windowDays = SkillInputUtils.toInt(input.get("window_days"), DEFAULT_WINDOW_DAYS);
            }
            if (windowDays < MIN_WINDOW_DAYS) windowDays = MIN_WINDOW_DAYS;
            if (windowDays > MAX_WINDOW_DAYS) windowDays = MAX_WINDOW_DAYS;

            RecomputeResult result = clusterService.recompute(Duration.ofDays(windowDays));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("window_days", windowDays);
            payload.put("patterns_upserted", result.patternsUpserted());
            payload.put("members_added", result.membersAdded());
            log.info("RecomputeClustersTool: windowDays={} patternsUpserted={} membersAdded={}",
                    windowDays, result.patternsUpserted(), result.membersAdded());
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("RecomputeClustersTool execute failed", e);
            return SkillResult.error("RecomputeClusters error: " + e.getMessage());
        }
    }
}
