package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-D2 / moved into C per review) —
 * agent-callable ledger tool. The orchestrator calls this once per loop turn to
 * record what changed + the A/B score delta + whether the candidate was kept.
 *
 * <p><b>No new table.</b> Each iteration is a {@code t_flywheel_run_step} row with
 * {@code step_kind='evolve_iteration'} on the parent {@code evolve} run (architect
 * REUSE TABLE verdict). The iteration fields (iteration / surface / changeDesc /
 * candidateId / baselineScore / candidateScore / delta / kept / abRunId) go into
 * the step's free-schema {@code step_output_json} via
 * {@link FlywheelRunService#appendEvolveIterationStep(String, int, com.fasterxml.jackson.databind.JsonNode)}.
 * These same rows are also the FR-C7 per-evolve-run A/B budget counter.
 *
 * <p><b>Recursion guard (invariant).</b> Registered ONLY in the main
 * {@code SkillRegistry} (see {@code SkillForgeConfig}); deliberately ABSENT from
 * {@code WorkflowSkillRegistryFactory} (the workflow sub-agent registry) — same
 * isolation invariant as the Module A/B tools.
 */
public class RecordIterationTool implements Tool {

    public static final String NAME = "RecordIteration";

    private static final Logger log = LoggerFactory.getLogger(RecordIterationTool.class);

    private final FlywheelRunService flywheelRunService;
    private final ObjectMapper objectMapper;

    public RecordIterationTool(FlywheelRunService flywheelRunService, ObjectMapper objectMapper) {
        this.flywheelRunService = flywheelRunService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Record one iteration of the auto-evolving loop to the evolve-run ledger. "
                + "Call this each loop turn after you have the A/B result and your keep decision. "
                + "Inputs:\n"
                + "- \"evolveRunId\": the evolve run id.\n"
                + "- \"iteration\": 1-based iteration index (integer).\n"
                + "- \"surface\": \"prompt\" / \"skill\" / \"behavior_rule\".\n"
                + "- \"changeDesc\": short description of what this candidate changed.\n"
                + "- \"candidateId\": the candidate id evaluated this turn.\n"
                + "- \"baselineScore\" / \"candidateScore\" / \"delta\": numbers from GetAbResult.\n"
                + "- \"kept\": boolean — whether you keep this candidate (records it; does NOT promote).\n"
                + "- \"abRunId\" (optional): the A/B run id for traceability.\n"
                + "Returns the recorded stepId.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("evolveRunId", Map.of("type", "string",
                "description", "The evolve run id."));
        properties.put("iteration", Map.of("type", "integer",
                "description", "1-based iteration index."));
        properties.put("surface", Map.of("type", "string",
                // No "agent" here (§7 B2): V1 records per-surface iterations only.
                "enum", EvolveSurface.v1NonAgentWireValues(),
                "description", "Optimisation surface."));
        properties.put("changeDesc", Map.of("type", "string",
                "description", "Short description of what this candidate changed."));
        properties.put("candidateId", Map.of("type", "string",
                "description", "The candidate id evaluated this turn."));
        properties.put("baselineScore", Map.of("type", "number",
                "description", "Baseline score from GetAbResult."));
        properties.put("candidateScore", Map.of("type", "number",
                "description", "Candidate score from GetAbResult."));
        properties.put("delta", Map.of("type", "number",
                "description", "candidateScore - baselineScore."));
        properties.put("kept", Map.of("type", "boolean",
                "description", "Whether the candidate is kept (recorded, not promoted)."));
        properties.put("abRunId", Map.of("type", "string",
                "description", "Optional A/B run id for traceability."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("evolveRunId", "iteration", "surface", "changeDesc",
                "candidateId", "kept"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError(
                        "input is required (evolveRunId, iteration, surface, changeDesc, "
                                + "candidateId, kept)");
            }
            String evolveRunId = trimToNull(input.get("evolveRunId"));
            if (evolveRunId == null) {
                return SkillResult.validationError("evolveRunId is required");
            }
            // Validate the run exists AND is an evolve run before appending a step
            // (the step FK would catch a missing run, but a clear early error is
            // friendlier to the LLM and prevents recording onto a wrong loop kind).
            Optional<FlywheelRunEntity> runOpt = flywheelRunService.findById(evolveRunId);
            if (runOpt.isEmpty()) {
                return SkillResult.validationError("evolve run not found: " + evolveRunId);
            }
            if (!FlywheelRunEntity.LOOP_KIND_EVOLVE.equals(runOpt.get().getLoopKind())) {
                return SkillResult.validationError(
                        "run " + evolveRunId + " is not an evolve run (loopKind="
                                + runOpt.get().getLoopKind() + ")");
            }

            Integer iteration = parseInt(input.get("iteration"));
            if (iteration == null || iteration < 1) {
                return SkillResult.validationError("iteration is required and must be an integer >= 1");
            }
            EvolveSurface surface = EvolveSurface.fromWire(trimToNull(input.get("surface")));
            if (surface == null) {
                // §7 B2 / W-WARN-1: this tool doesn't accept agent — don't list it.
                return SkillResult.validationError(
                        "surface is required and must be one of: "
                                + EvolveSurface.v1NonAgentAcceptedValues());
            }
            // §7 B2: V1 records per-surface iterations only — reject surface=agent
            // cleanly (the orchestrator records the per-surface change it made).
            if (surface == EvolveSurface.AGENT) {
                return SkillResult.validationError(
                        "surface=agent is not supported by RecordIteration in V1: record the "
                                + "per-surface change (prompt / skill / behavior_rule) you made this turn");
            }
            String changeDesc = trimToNull(input.get("changeDesc"));
            if (changeDesc == null) {
                return SkillResult.validationError("changeDesc is required");
            }
            String candidateId = trimToNull(input.get("candidateId"));
            if (candidateId == null) {
                return SkillResult.validationError("candidateId is required");
            }
            Boolean kept = parseBoolean(input.get("kept"));
            if (kept == null) {
                return SkillResult.validationError("kept is required and must be a boolean");
            }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("iteration", iteration);
            payload.put("surface", surface.wire());
            payload.put("changeDesc", changeDesc);
            payload.put("candidateId", candidateId);
            putNumber(payload, "baselineScore", input.get("baselineScore"));
            putNumber(payload, "candidateScore", input.get("candidateScore"));
            putNumber(payload, "delta", input.get("delta"));
            payload.put("kept", kept);
            String abRunId = trimToNull(input.get("abRunId"));
            if (abRunId != null) {
                payload.put("abRunId", abRunId);
            }

            String stepId = flywheelRunService.appendEvolveIterationStep(evolveRunId, iteration, payload);

            log.info("[RecordIteration] evolveRunId={} iteration={} surface={} candidateId={} "
                            + "kept={} -> stepId={}",
                    evolveRunId, iteration, surface.wire(), candidateId, kept, stepId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("stepId", stepId);
            response.put("evolveRunId", evolveRunId);
            response.put("iteration", iteration);
            response.put("status", "recorded");
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (IllegalArgumentException e) {
            return SkillResult.validationError(e.getMessage());
        } catch (Exception e) {
            log.error("RecordIteration execute failed", e);
            return SkillResult.error("RecordIteration error: " + e.getMessage());
        }
    }

    private void putNumber(ObjectNode node, String field, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Number n) {
            node.put(field, n.doubleValue());
            return;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return;
        }
        try {
            node.put(field, Double.parseDouble(s));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a number (got: " + s + ")");
        }
    }

    private static Integer parseInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        String s = trimToNull(value);
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("iteration must be an integer (got: " + s + ")");
        }
    }

    private static Boolean parseBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String s = trimToNull(value);
        if (s == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("kept must be a boolean (got: " + s + ")");
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
