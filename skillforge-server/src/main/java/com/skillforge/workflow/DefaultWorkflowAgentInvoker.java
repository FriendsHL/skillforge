package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.SessionService;
import com.skillforge.workflow.engine.WorkflowSubAgentEngineFactory;
import com.skillforge.workflow.exception.SchemaViolationException;
import com.skillforge.workflow.exception.WorkflowAgentNotFoundException;
import com.skillforge.workflow.schema.SchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Production {@link WorkflowAgentInvoker} (plan §5.1 — the {@code agent()} →
 * {@code AgentLoopEngine.run} synchronous path). One instance is created
 * per-run by {@link WorkflowAgentInvokerFactory}, capturing the run id, anchor
 * session, and user id; the shared service dependencies come from the factory.
 *
 * <p>Per {@link WorkflowAgentInvoker}, {@link #invoke} runs <strong>pure
 * Java</strong> and never touches Rhino — which is what makes the offload
 * concurrency model safe ({@code HostParallel} submits this to a worker pool).
 *
 * <p>Flow per invocation:
 * <ol>
 *   <li>resolve {@code agentSlug → AgentEntity → AgentDefinition} (optional
 *       {@code model} override);</li>
 *   <li>create a worker sub-session under the anchor;</li>
 *   <li>{@code appendStep} (pending) carrying {@code phase/agentSlug/stepIndex/
 *       prompt} + attach the sub-session id;</li>
 *   <li>{@code engine.run(...)} synchronously (no compactor / no pendingAsk —
 *       see {@link WorkflowSubAgentEngineFactory});</li>
 *   <li>{@code transitionStepStatus} → completed (output json) or error.</li>
 * </ol>
 */
public final class DefaultWorkflowAgentInvoker implements WorkflowAgentInvoker {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowAgentInvoker.class);
    private static final int DEFAULT_MAX_LOOPS = 10;

    /**
     * Total {@code engine.run} attempts for a schema-bound {@code agent()} call
     * (W2 / FR-1.4): the first attempt plus up to 2 retries = 3 total. Each
     * retry re-runs the engine on the same sub-session with a correction suffix
     * appended to the prompt. A call without a {@code schema} opt runs exactly
     * once.
     */
    private static final int MAX_SCHEMA_ATTEMPTS = 3;

    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final SessionService sessionService;
    private final FlywheelRunService flywheelRunService;
    private final WorkflowSubAgentEngineFactory engineFactory;
    private final ObjectMapper objectMapper;
    private final SchemaValidator schemaValidator;

    // Per-run state.
    private final String runId;
    private final SessionEntity anchorSession;
    private final Long userId;

    public DefaultWorkflowAgentInvoker(AgentRepository agentRepository,
                                       AgentService agentService,
                                       SessionService sessionService,
                                       FlywheelRunService flywheelRunService,
                                       WorkflowSubAgentEngineFactory engineFactory,
                                       ObjectMapper objectMapper,
                                       SchemaValidator schemaValidator,
                                       String runId,
                                       SessionEntity anchorSession,
                                       Long userId) {
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.flywheelRunService = flywheelRunService;
        this.engineFactory = engineFactory;
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
        this.runId = runId;
        this.anchorSession = anchorSession;
        this.userId = userId;
    }

    @Override
    public Object invoke(String prompt, Map<String, Object> opts, int stepIndex) {
        String agentSlug = stringOpt(opts, "agentSlug");
        if (agentSlug == null || agentSlug.isBlank()) {
            throw new WorkflowAgentNotFoundException("<missing agentSlug>");
        }
        AgentEntity entity = agentRepository.findFirstByName(agentSlug)
                .orElseThrow(() -> new WorkflowAgentNotFoundException(agentSlug));

        AgentDefinition def = agentService.toAgentDefinition(entity);
        String modelOverride = stringOpt(opts, "model");
        if (modelOverride != null && !modelOverride.isBlank()) {
            def.setModelId(modelOverride);
        }

        // Worker sub-session under the workflow anchor.
        SessionEntity sub = sessionService.createSubSession(anchorSession, entity.getId(), runId);

        // Register the step (pending) before running, carrying the deterministic
        // step_index (V127) + step_kind for journal-replay (Task A/F).
        String stepInputJson = buildStepInput(opts, agentSlug, stepIndex, prompt);
        String stepRunId = flywheelRunService.appendStep(
                runId, stepInputJson, FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH, stepIndex);
        flywheelRunService.attachStepSubAgentSession(stepRunId, sub.getId());

        LoopContext lc = new LoopContext();
        lc.setExecutionMode("auto");
        lc.setMaxLoops(intOpt(opts, "maxLoops", DEFAULT_MAX_LOOPS));

        AgentLoopEngine engine = engineFactory.buildWorkflowEngine(new SkillRegistry());

        // Schema enforcement (Task E). When opts carries a `schema`, validate the
        // agent's JSON output and retry (correction-suffixed prompt) up to
        // MAX_SCHEMA_ATTEMPTS total. No schema → exactly one run, String result.
        JsonNode schemaNode = extractSchemaNode(opts);

        LoopResult result = null;
        Object parsedResult = null;
        List<String> lastViolations = null;
        int attempts = 0;

        for (int attempt = 0; attempt < MAX_SCHEMA_ATTEMPTS; attempt++) {
            attempts = attempt + 1;
            String effectivePrompt = (attempt == 0)
                    ? prompt
                    : prompt + retrySuffix(lastViolations);
            try {
                // ★ synchronous, pure Java, never touches Rhino (plan §5.1 step 10).
                result = engine.run(def, effectivePrompt, null, sub.getId(), userId, lc);
            } catch (RuntimeException ex) {
                // Engine hard failure (e.g. network) — not a schema retry case.
                flywheelRunService.transitionStepStatus(
                        stepRunId, FlywheelRunStepEntity.STATUS_ERROR, null, ex.getMessage());
                log.warn("Workflow agent() step {} (slug={}) failed: {}", stepIndex, agentSlug, ex.getMessage());
                throw ex;
            }

            if (schemaNode == null) {
                break; // no schema → first response is the answer
            }

            String resp = result.getFinalResponse();
            JsonNode parsed = tryParseJson(resp);
            if (parsed == null) {
                lastViolations = List.of("output is not valid JSON");
            } else {
                List<String> violations = schemaValidator.validate(parsed, schemaNode);
                if (violations.isEmpty()) {
                    parsedResult = objectMapper.convertValue(parsed, Object.class);
                    lastViolations = null;
                    break;
                }
                lastViolations = violations;
            }
            log.info("Workflow agent() step {} (slug={}) schema attempt {}/{} failed: {}",
                    stepIndex, agentSlug, attempts, MAX_SCHEMA_ATTEMPTS, lastViolations);
        }

        // Schema present but never satisfied across all attempts → error + throw.
        if (schemaNode != null && lastViolations != null) {
            flywheelRunService.transitionStepStatus(
                    stepRunId, FlywheelRunStepEntity.STATUS_ERROR, null,
                    "schema violation after " + attempts + " attempts: " + lastViolations);
            throw new SchemaViolationException(stepIndex, lastViolations);
        }

        ObjectNode output = objectMapper.createObjectNode();
        output.put("finalResponse", result.getFinalResponse());
        output.put("loopCount", result.getLoopCount());
        output.put("subSessionId", sub.getId());
        output.put("schemaAttempts", attempts);
        flywheelRunService.transitionStepStatus(
                stepRunId, FlywheelRunStepEntity.STATUS_COMPLETED, output, null);

        // schema → parsed Map/List (HostAgent converts via JsConversions.toJs);
        // no schema → raw String.
        return schemaNode != null ? parsedResult : result.getFinalResponse();
    }

    /**
     * Builds a JSON Schema {@link JsonNode} from the {@code schema} opt (a plain
     * Java {@code Map} produced by {@code JsConversions.jsToJava}), or {@code null}
     * when no schema was supplied.
     */
    private JsonNode extractSchemaNode(Map<String, Object> opts) {
        Object schema = opts == null ? null : opts.get("schema");
        if (schema == null) {
            return null;
        }
        return objectMapper.valueToTree(schema);
    }

    private JsonNode tryParseJson(String resp) {
        if (resp == null || resp.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(resp);
        } catch (Exception e) {
            return null;
        }
    }

    private static String retrySuffix(List<String> violations) {
        return "\n\n[SCHEMA RETRY] Your previous output violated the required JSON schema: "
                + violations + ". Output STRICT JSON matching the schema, with no prose or code fences.";
    }

    private String buildStepInput(Map<String, Object> opts, String agentSlug, int stepIndex, String prompt) {
        ObjectNode node = objectMapper.createObjectNode();
        String phase = stringOpt(opts, "phase");
        if (phase != null) {
            node.put("phase", phase);
        }
        node.put("agentSlug", agentSlug);
        node.put("stepIndex", stepIndex);
        node.put("prompt", prompt);
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            // Never block the run on step-input serialization; fall back to a minimal payload.
            return "{\"agentSlug\":\"" + agentSlug + "\",\"stepIndex\":" + stepIndex + "}";
        }
    }

    private static String stringOpt(Map<String, Object> opts, String key) {
        Object v = opts == null ? null : opts.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intOpt(Map<String, Object> opts, String key, int dflt) {
        Object v = opts == null ? null : opts.get(key);
        if (v instanceof Number num) {
            return num.intValue();
        }
        if (v instanceof String s) {
            try {
                return (int) Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return dflt;
            }
        }
        return dflt;
    }
}
