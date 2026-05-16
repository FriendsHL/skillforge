package com.skillforge.server.tool.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator.SimulationOutcome;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator.TrialRequest;
import com.skillforge.server.eval.usersim.UserSimAgentConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2 entry-point tool.
 *
 * <p>Programmatic kickoff hook for {@link SimulatorTrialOrchestrator}. Invoked
 * by:
 * <ul>
 *   <li>{@code DynamicSimController} REST endpoint (Phase 1.3)</li>
 *   <li>future agent dispatch paths (Phase 2 attribution → dynamic-sim
 *       attribution loop)</li>
 *   <li>integration tests</li>
 * </ul>
 *
 * <p><b>Not</b> a tool consumed by the user-simulator agent's own loop
 * (Phase 1.2.0 4-ratify decision — including it in UserSim's tool_ids would be
 * dead-tool / circular). UserSim's actual tool list contains
 * {@link UserSimAgentConstants#TOOL_RECORD_SIMULATION_RESULT} only.
 */
public class RunSimulatorTrial implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RunSimulatorTrial.class);

    private final SimulatorTrialOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public RunSimulatorTrial(SimulatorTrialOrchestrator orchestrator, ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return UserSimAgentConstants.TOOL_RUN_SIMULATOR_TRIAL;
    }

    @Override
    public String getDescription() {
        return "Run one V5 dynamic user-simulator trial. Drives a UserSimulator agent against a "
                + "candidate agent for the given scenario + persona until business goal met, "
                + "failure signal triggered, or max_turns exceeded. Returns trial metadata; the "
                + "candidate transcript is persisted into a t_session (origin='user_sim') row.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("scenarioId", Map.of(
                "type", "string",
                "description", "t_eval_scenario.id of the scenario carrying business fields "
                        + "(businessGoal / successCriteria / userConstraints / failureSignals)"));
        properties.put("candidateAgentVersionId", Map.of(
                "type", "string",
                "description", "Optional. Candidate version id on the candidate surface (skill id "
                        + "as numeric string / prompt version UUID / behavior_rule version UUID). "
                        + "When null, the trial runs against the baseline."));
        properties.put("candidateSurfaceType", Map.of(
                "type", "string",
                "description", "Optional. One of 'skill' / 'prompt' / 'behavior_rule'. Required if "
                        + "candidateAgentVersionId is set.",
                "enum", List.of("skill", "prompt", "behavior_rule")));
        properties.put("persona", Map.of(
                "type", "string",
                "description", "Fixed persona string (one of the 5 personas from "
                        + "skillforge.eval.user-simulator.personas yaml — or a custom one for "
                        + "ad-hoc testing)."));
        properties.put("maxTurns", Map.of(
                "type", "integer",
                "description", "Optional. Defaults to skillforge.eval.user-simulator.max-turns "
                        + "(typically 10). Cap on outer ping-pong loop iterations."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("scenarioId", "persona"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String scenarioId = asString(input.get("scenarioId"));
            String persona = asString(input.get("persona"));
            if (scenarioId == null || scenarioId.isBlank()) {
                return SkillResult.validationError("scenarioId is required");
            }
            if (persona == null || persona.isBlank()) {
                return SkillResult.validationError("persona is required");
            }
            String candidateVersionId = asString(input.get("candidateAgentVersionId"));
            String candidateSurfaceType = asString(input.get("candidateSurfaceType"));
            if ((candidateVersionId != null && !candidateVersionId.isBlank())
                    && (candidateSurfaceType == null || candidateSurfaceType.isBlank())) {
                return SkillResult.validationError(
                        "candidateSurfaceType is required when candidateAgentVersionId is set");
            }
            // V5 known limitation: behavior_rule candidate inject can't take effect without
            // changing AgentLoopEngine (core 7+1 Iron Law file). Reject early so operator
            // never sees a misleading trial that actually ran baseline. V5.1 backlog.
            if (UserSimAgentConstants.SURFACE_BEHAVIOR_RULE.equals(candidateSurfaceType)) {
                return SkillResult.validationError(
                        "behavior_rule dynamic sim 暂不支持 — V4 结构 limitation，V5.1 backlog；"
                                + "仅 prompt + skill surface 当前可用");
            }
            Integer maxTurns = null;
            Object maxTurnsObj = input.get("maxTurns");
            if (maxTurnsObj instanceof Number n) {
                maxTurns = n.intValue();
            } else if (maxTurnsObj instanceof String s && !s.isBlank()) {
                try { maxTurns = Integer.parseInt(s); } catch (NumberFormatException ignore) { }
            }

            TrialRequest req = new TrialRequest(scenarioId,
                    blankToNull(candidateVersionId),
                    blankToNull(candidateSurfaceType),
                    persona,
                    maxTurns);

            SimulationOutcome outcome = orchestrator.runTrial(req);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("trialId", outcome.trialId());
            response.put("sessionId", outcome.sessionId());
            response.put("turnsUsed", outcome.turnsUsed());
            response.put("terminationReason", outcome.terminationReason());
            response.put("observedFailureSignals", outcome.observedFailureSignals());
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (IllegalArgumentException ie) {
            return SkillResult.validationError(ie.getMessage());
        } catch (Exception e) {
            log.error("RunSimulatorTrial execute failed", e);
            return SkillResult.error("RunSimulatorTrial error: " + e.getMessage());
        }
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }
}
