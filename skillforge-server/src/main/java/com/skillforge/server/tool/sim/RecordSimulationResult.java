package com.skillforge.server.tool.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.SimulatorTrialEntity;
import com.skillforge.server.eval.usersim.UserSimAgentConstants;
import com.skillforge.server.repository.SimulatorTrialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2 termination-recording tool.
 *
 * <p>Called by the user-simulator agent's loop when it decides the trial is
 * over (task_completed / failure_signal / max_turns). Writes structured
 * termination metadata into {@code t_simulator_trial}. The outer
 * {@link com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator} reads
 * the same row idempotently after the loop ends — UserSim's call wins, but
 * orchestrator fills in any field UserSim left blank.
 */
public class RecordSimulationResult implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RecordSimulationResult.class);

    private final SimulatorTrialRepository trialRepository;
    private final ObjectMapper objectMapper;

    public RecordSimulationResult(SimulatorTrialRepository trialRepository, ObjectMapper objectMapper) {
        this.trialRepository = trialRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return UserSimAgentConstants.TOOL_RECORD_SIMULATION_RESULT;
    }

    @Override
    public String getDescription() {
        return "Record the termination outcome of a V5 user-simulator trial. Call this when the "
                + "business goal is met, a failure signal triggers, or max_turns is hit. Updates "
                + "t_simulator_trial (terminationReason / observedFailureSignals / turnsUsed) for "
                + "dashboard surfacing. Output [TERMINATE] immediately after this call so the outer "
                + "orchestrator stops the ping-pong loop.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("trialId", Map.of(
                "type", "string",
                "description", "Trial id (UUID) — passed in the UserSim kickoff message; required."));
        properties.put("terminationReason", Map.of(
                "type", "string",
                "description", "One of 'task_completed' / 'failure_signal' / 'max_turns'.",
                "enum", List.of("task_completed", "failure_signal", "max_turns")));
        properties.put("observedFailureSignals", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Concrete failure-signal phrases observed (only when "
                        + "terminationReason='failure_signal'; empty otherwise)."));
        properties.put("turnsUsed", Map.of(
                "type", "integer",
                "description", "How many turns elapsed before termination (current turn index + 1)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("trialId", "terminationReason", "turnsUsed"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @SuppressWarnings("unchecked")
    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String trialId = asString(input.get("trialId"));
            if (trialId == null || trialId.isBlank()) {
                return SkillResult.validationError("trialId is required");
            }
            String terminationReason = asString(input.get("terminationReason"));
            if (terminationReason == null || terminationReason.isBlank()) {
                return SkillResult.validationError("terminationReason is required");
            }
            Integer turnsUsed;
            Object t = input.get("turnsUsed");
            if (t instanceof Number n) {
                turnsUsed = n.intValue();
            } else if (t instanceof String s && !s.isBlank()) {
                try { turnsUsed = Integer.parseInt(s); }
                catch (NumberFormatException ne) {
                    return SkillResult.validationError("turnsUsed must be an integer");
                }
            } else {
                return SkillResult.validationError("turnsUsed is required");
            }

            String observed;
            Object raw = input.get("observedFailureSignals");
            if (raw instanceof List<?> list) {
                StringBuilder sb = new StringBuilder();
                for (Object o : list) {
                    if (o == null) continue;
                    if (sb.length() > 0) sb.append(",");
                    sb.append(o);
                }
                observed = sb.length() == 0 ? null : sb.toString();
            } else if (raw instanceof String s) {
                observed = s.isBlank() ? null : s;
            } else {
                observed = null;
            }

            Optional<SimulatorTrialEntity> opt = trialRepository.findById(trialId);
            if (opt.isEmpty()) {
                return SkillResult.error("Trial not found: " + trialId);
            }
            SimulatorTrialEntity trial = opt.get();
            trial.setTerminationReason(terminationReason);
            trial.setTurnsUsed(turnsUsed);
            if (observed != null) {
                trial.setObservedFailureSignals(observed);
            }
            trialRepository.save(trial);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("trialId", trialId);
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.error("RecordSimulationResult execute failed", e);
            return SkillResult.error("RecordSimulationResult error: " + e.getMessage());
        }
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }
}
