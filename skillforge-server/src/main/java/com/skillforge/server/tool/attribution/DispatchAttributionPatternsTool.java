package com.skillforge.server.tool.attribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.attribution.AttributionDispatcherService;
import com.skillforge.server.attribution.AttributionDispatcherService.DispatchResult;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ATTRIBUTION-DISPATCHER-AGENT Phase 2 — Tool wrapper around
 * {@link AttributionDispatcherService#dispatchPendingPatterns(int)} so the
 * dedicated {@code attribution-dispatcher} system agent can invoke the
 * dispatcher logic via the standard LLM tool-call path. Single tool, single
 * optional parameter, single JSON summary output.
 *
 * <p>Background: pre-V93 the {@code attribution-dispatcher-hourly} cron fired
 * the {@code attribution-curator} agent directly with a generic prompt — the
 * curator's STEP 1 ({@code PatternRead}) requires a {@code patternId} that the
 * generic prompt does not carry, so the curator immediately stopped (verified
 * via trace 7c1aa5cc-ae9f-4de2-b53c-b7188a81aa3e, 2026-05-21 03:06 UTC). V93
 * seeds a dedicated dispatcher agent + rewires cron #6 to point at it; this
 * tool is what the dispatcher agent actually calls per run.
 *
 * <p>Wire shape:
 * <ul>
 *   <li>input: {@code { "max_dispatch": int (optional, default 5, range [1,
 *       20]) }} — service default {@link
 *       AttributionDispatcherService#DEFAULT_MAX_DISPATCH_PER_RUN} = 5</li>
 *   <li>output: {@code { "ok": true, "candidates_scanned": N, "dispatched": N,
 *       "skippedSurface": N, "skippedCooldown": N, "skippedActive": N }}</li>
 *   <li>error path: any {@link Exception} thrown by the service is mapped to
 *       {@link SkillResult#error(String)} with prefix
 *       {@code "DispatchAttributionPatterns: "} so the curator-style LLM
 *       summary step still has something to say (instead of dying mid-loop)</li>
 * </ul>
 */
public class DispatchAttributionPatternsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DispatchAttributionPatternsTool.class);

    static final int DEFAULT_MAX_DISPATCH =
            AttributionDispatcherService.DEFAULT_MAX_DISPATCH_PER_RUN;
    static final int MIN_MAX_DISPATCH = 1;
    static final int MAX_MAX_DISPATCH = 20;

    private final AttributionDispatcherService dispatcherService;
    private final ObjectMapper objectMapper;

    public DispatchAttributionPatternsTool(AttributionDispatcherService dispatcherService,
                                           ObjectMapper objectMapper) {
        this.dispatcherService = dispatcherService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "DispatchAttributionPatterns";
    }

    @Override
    public String getDescription() {
        return "Entry point for the attribution-dispatcher system agent. Calls "
                + "AttributionDispatcherService.dispatchPendingPatterns(maxDispatch) "
                + "once per run to scan t_session_pattern, apply the 4 ratify-locked "
                + "filters (surface allowlist / member-count threshold / 24h cooldown "
                + "/ no in-flight event), and async-dispatch attribution-curator for "
                + "each eligible pattern. Returns a JSON summary of the scan "
                + "(candidates_scanned / dispatched / skippedSurface / "
                + "skippedCooldown / skippedActive). max_dispatch is optional "
                + "(default " + DEFAULT_MAX_DISPATCH + ", clamped to ["
                + MIN_MAX_DISPATCH + ", " + MAX_MAX_DISPATCH + "]).";
    }

    @Override
    public boolean isReadOnly() {
        // The service writes a dispatch_initiated sentinel row + spawns
        // attribution-curator sessions per eligible pattern → mutates state,
        // not read-only.
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("max_dispatch", Map.of(
                "type", "integer",
                "description", "Optional cap on patterns to dispatch this run "
                        + "(default " + DEFAULT_MAX_DISPATCH + ", clamped to ["
                        + MIN_MAX_DISPATCH + ", " + MAX_MAX_DISPATCH + "]). "
                        + "Service default DEFAULT_MAX_DISPATCH_PER_RUN="
                        + DEFAULT_MAX_DISPATCH + "."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of());
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        int maxDispatch = DEFAULT_MAX_DISPATCH;
        if (input != null) {
            maxDispatch = SkillInputUtils.toInt(input.get("max_dispatch"), DEFAULT_MAX_DISPATCH);
        }
        // Defensive clamp: tool-call LLM input is untrusted; service itself
        // also handles maxDispatchPerRun<=0 → default fallback, but applying
        // the [1, 20] clamp here gives a predictable upper bound regardless
        // of future service-side changes.
        maxDispatch = Math.max(MIN_MAX_DISPATCH, Math.min(MAX_MAX_DISPATCH, maxDispatch));

        try {
            DispatchResult r = dispatcherService.dispatchPendingPatterns(maxDispatch);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("candidates_scanned", r.scanned());
            payload.put("dispatched", r.dispatched());
            payload.put("skippedSurface", r.skippedSurface());
            payload.put("skippedCooldown", r.skippedCooldown());
            payload.put("skippedActive", r.skippedActive());
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            // W4 fix: AttributionDispatcherService.dispatchPendingPatterns is
            // mostly try/caught internally per-pattern, but the outer scan
            // (findWithFilters / agent lookup) can still throw on DB outage.
            // Surfacing a SkillResult.error keeps the cron-driven LLM loop
            // alive so its final summary step still emits structured output
            // (LLM-driven retry / operator visibility), rather than the loop
            // engine treating an exception as a hard abort.
            log.warn("[DispatchAttributionPatternsTool] dispatch failed: {}", e.getMessage(), e);
            return SkillResult.error("DispatchAttributionPatterns: " + e.getMessage());
        }
    }
}
