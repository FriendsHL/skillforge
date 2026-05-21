package com.skillforge.server.tool.canary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.canary.CanaryMetricsService;
import com.skillforge.server.canary.CanaryMetricsService.RecomputeResult;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.4: thin agent-facing wrapper around
 * {@link CanaryMetricsService#recompute(Duration)}.
 *
 * <p>Sole step of the {@code metrics-collector} agent's hourly pipeline
 * (system_prompt 在 t_agent 表内，V95 inline seed —— KILL-BOOTSTRAP-PROMPT-TO-DB
 * 2026-05-22). The V79-seeded {@code ScheduledTask metrics-collector-hourly}
 * fires this agent once per hour; the agent calls this tool with default
 * {@code window_hours=1}.
 *
 * <p>Wire shape:
 * <ul>
 *   <li>input: {@code { "window_hours": int (optional, default 1) }}</li>
 *   <li>output: {@code { "ok": true, "window_hours": int, "active_canaries": int,
 *       "snapshots_written": int, "auto_rollbacks_triggered": int }}</li>
 * </ul>
 *
 * <p>Tool name {@code RecomputeMetrics} matches the V79 seed
 * {@code tool_ids = ["RecomputeMetrics"]} on the metrics-collector agent.
 *
 * <p>All business logic lives in {@link CanaryMetricsService} — this tool is
 * the JSON adapter + LLM-facing description. Mirrors {@code RecomputeClustersTool}
 * structurally (the V1 sibling tool one phase ahead).
 */
public class RecomputeMetricsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RecomputeMetricsTool.class);

    static final int DEFAULT_WINDOW_HOURS = 1;
    static final int MIN_WINDOW_HOURS = 1;
    static final int MAX_WINDOW_HOURS = 168; // 7 days — defensive ceiling, mirrors the metrics REST endpoint cap.

    private final CanaryMetricsService metricsService;
    private final ObjectMapper objectMapper;

    public RecomputeMetricsTool(CanaryMetricsService metricsService, ObjectMapper objectMapper) {
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "RecomputeMetrics";
    }

    @Override
    public String getDescription() {
        return "Hourly canary metrics aggregator. Scans annotation_type='outcome' rows "
                + "written in the last N hours (default " + DEFAULT_WINDOW_HOURS
                + ", clamped to [" + MIN_WINDOW_HOURS + ", " + MAX_WINDOW_HOURS + "]), "
                + "joins each session to its canary_group annotation to classify control "
                + "vs candidate, and upserts one t_canary_metric_snapshot row per "
                + "(active canary × hour bucket). Then triggers auto-rollback check "
                + "for every active canary so a fresh snapshot can immediately roll back "
                + "the candidate when its failure rate exceeds 1.5× control over ≥ 50 "
                + "candidate samples. Idempotent on re-run within the same hour.";
    }

    @Override
    public boolean isReadOnly() {
        // Writes t_canary_metric_snapshot + may transition t_canary_rollout via
        // auto-rollback. NOT read-only.
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("window_hours", Map.of(
                "type", "integer",
                "description", "Look-back window in hours (default " + DEFAULT_WINDOW_HOURS
                        + ", clamped to [" + MIN_WINDOW_HOURS + ", " + MAX_WINDOW_HOURS + "])."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            int windowHours = DEFAULT_WINDOW_HOURS;
            if (input != null) {
                windowHours = SkillInputUtils.toInt(input.get("window_hours"), DEFAULT_WINDOW_HOURS);
            }
            if (windowHours < MIN_WINDOW_HOURS) windowHours = MIN_WINDOW_HOURS;
            if (windowHours > MAX_WINDOW_HOURS) windowHours = MAX_WINDOW_HOURS;

            RecomputeResult result = metricsService.recompute(Duration.ofHours(windowHours));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("window_hours", windowHours);
            payload.put("active_canaries", result.activeCanaries());
            payload.put("snapshots_written", result.snapshotsWritten());
            payload.put("auto_rollbacks_triggered", result.autoRollbacksTriggered());
            log.info("RecomputeMetricsTool: windowHours={} active={} snapshotsWritten={} autoRollbacks={}",
                    windowHours, result.activeCanaries(),
                    result.snapshotsWritten(), result.autoRollbacksTriggered());
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("RecomputeMetricsTool execute failed", e);
            return SkillResult.error("RecomputeMetrics error: " + e.getMessage());
        }
    }
}
