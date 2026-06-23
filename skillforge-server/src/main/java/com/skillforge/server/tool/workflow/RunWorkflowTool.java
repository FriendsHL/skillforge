package com.skillforge.server.tool.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.workflow.WorkflowDefinition;
import com.skillforge.workflow.WorkflowDefinitionRegistry;
import com.skillforge.workflow.WorkflowRunnerService;
import com.skillforge.workflow.exception.WorkflowAlreadyRunningException;
import com.skillforge.workflow.exception.WorkflowDefinitionChangedException;
import com.skillforge.workflow.exception.WorkflowMetaException;
import com.skillforge.workflow.exception.WorkflowNotFoundException;
import com.skillforge.workflow.exception.WorkflowNotPausedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVING — agent-facing entry point that lets an agent trigger the DSL
 * workflow engine ({@link WorkflowRunnerService}). Mirrors Claude Code's
 * {@code Workflow} tool with three execution modes:
 *
 * <ol>
 *   <li><b>name</b> — run a registered {@code *.workflow.js} definition by its
 *       {@code meta.name} ({@link WorkflowRunnerService#startRun(String, Map, Long)}).</li>
 *   <li><b>inline</b> — parse an inline JS source (with its
 *       {@code export const meta = {...}} block) on the fly via
 *       {@link WorkflowDefinitionRegistry#parseInline(String)} and run it
 *       directly ({@link WorkflowRunnerService#startRun(WorkflowDefinition, Map, Long)}),
 *       without registering it. Inline source runs in the SAME L1 sandbox as a
 *       named run.</li>
 *   <li><b>resume</b> — resume a run parked on a {@code humanApprove()} gate with
 *       an approve / reject decision
 *       ({@link WorkflowRunnerService#resume(String, boolean, String, String)}).</li>
 * </ol>
 *
 * <p>name / inline runs are <b>async fire-and-trigger</b>: the call returns a
 * {@code runId} immediately; the run proceeds on the workflow executor and
 * surfaces progress / completion over WebSocket. resume is likewise async.
 *
 * <p><b>Recursion guard (invariant).</b> This tool is registered ONLY in the
 * main {@code SkillRegistry} (see {@code SkillForgeConfig}). It is deliberately
 * absent from {@code WorkflowSkillRegistryFactory} (the workflow sub-agent
 * registry) so a workflow's own sub-agent can never trigger another workflow —
 * preventing unbounded recursion / runaway runs.
 */
public class RunWorkflowTool implements Tool {

    public static final String NAME = "RunWorkflow";

    private static final Logger log = LoggerFactory.getLogger(RunWorkflowTool.class);

    private static final String MODE_NAME = "name";
    private static final String MODE_INLINE = "inline";
    private static final String MODE_RESUME = "resume";
    private static final String DECISION_APPROVED = "approved";
    private static final String DECISION_REJECTED = "rejected";

    private final WorkflowRunnerService runnerService;
    private final WorkflowDefinitionRegistry registry;
    private final ObjectMapper objectMapper;

    public RunWorkflowTool(WorkflowRunnerService runnerService,
                           WorkflowDefinitionRegistry registry,
                           ObjectMapper objectMapper) {
        this.runnerService = runnerService;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return """
                Kick off a deterministic multi-agent WORKFLOW on the DSL engine, then return \
                immediately with a runId. Workflows orchestrate many sub-agents / tools as a \
                repeatable, journaled program written in a small sandboxed JavaScript DSL.

                Three modes (set "mode"):
                - "name": run a workflow already registered on the server by its meta.name. \
                Provide "name" and optional "args" (object passed to the script as `args`).
                - "inline": run a workflow you supply on the fly. Provide "script" — the full \
                .workflow.js source INCLUDING its `export const meta = { name, description, \
                phases }` block (meta.name is required) — plus optional "args". The script is \
                parsed and run in the SAME sandbox as a registered workflow; it is NOT saved/\
                registered (one-shot).
                - "resume": unblock a run that is paused on a humanApprove() gate. Provide \
                "resumeRunId", "decision" ("approved" or "rejected"), and optional "reason".

                ASYNC FIRE-AND-TRIGGER: name / inline return a runId and the run proceeds in \
                the BACKGROUND on the workflow executor — you do NOT get the result inline. Do \
                not call RunWorkflow again for the same workflow (a duplicate is rejected as \
                "already running" and wastes turns). To get the outcome, poll the matching read \
                tool by that runId (e.g. GetOptReport for an opt-report run) until it is \
                completed, or wait for the WebSocket completion event. resume is likewise async.

                WHEN TO USE: a determinate, repeatable orchestration — fan out one task over a \
                batch of work items, run a fixed pipeline of stages, or a loop that needs a \
                human-approval gate. Prefer a workflow when the structure is known up front and \
                you want it journaled / resumable.
                WHEN NOT TO USE: a single step you can just do yourself (call the tool / one \
                SubAgent directly — a workflow is overhead). "I just want some parallelism" is \
                NOT sufficient: only reach for a workflow when the orchestration is genuinely \
                multi-step and worth the journal + sandbox cost.

                INLINE DSL (what the script body may call — these are the ONLY host primitives; \
                there is no other API):
                - phase("title")            mark a phase boundary (progress / journaling).
                - log("msg")                emit a workflow log line.
                - agent(prompt, opts)       dispatch one LLM sub-agent and BLOCK for its result \
                (string). opts selects the agent, e.g. { agentSlug: "session-annotator" }.
                - tool(name, input)         invoke a host (Java) tool synchronously on the \
                workflow thread and get its result (deterministic node, not an LLM).
                - parallel([t1, t2, ...])   run sub-agent thunks concurrently; each thunk MUST \
                tail-call agent(...) (e.g. () => agent(...)). Returns the array of results.
                - pipeline(items, s1, s2)   V1 SERIAL fan-over: apply each stage callback across \
                items in order.
                - humanApprove(payload)     park the run on an approval gate; it pauses until a \
                resume decision arrives (see mode=resume).
                - ctx                        per-run context object (run metadata / scratch).
                - args                       the object you passed in as "args".
                The body returns a final value (the run result). Use plain JS for control flow.

                SANDBOX HARD LIMITS (do not design around exceeding these): no Java bridge / \
                Packages / java.*, no eval, no Function constructor, no file or network access \
                from JS — side effects only happen through agent()/tool(). Per-run budgets: \
                ~1,000,000 interpreter instructions, at most 1000 agent() calls, and a 30-minute \
                wall-clock cap; exceeding any aborts the run. Keep workflows deterministic — do \
                NOT rely on wall-clock time or randomness for control flow (parallel() ordering \
                and journal replay assume determinism).""";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("mode", Map.of(
                "type", "string",
                "enum", List.of(MODE_NAME, MODE_INLINE, MODE_RESUME),
                "description", "Execution mode: \"name\" (run registered workflow), "
                        + "\"inline\" (run inline JS source), or \"resume\" (resume a "
                        + "paused human-approval gate)."
        ));
        properties.put("name", Map.of(
                "type", "string",
                "description", "Workflow name to run (required for mode=name)."
        ));
        properties.put("script", Map.of(
                "type", "string",
                "description", "Inline workflow JS source including the "
                        + "`export const meta = {...}` block (required for mode=inline)."
        ));
        properties.put("args", Map.of(
                "type", "object",
                "description", "Optional argument object passed to the workflow "
                        + "(name / inline modes). Omit if the workflow takes no args."
        ));
        properties.put("resumeRunId", Map.of(
                "type", "string",
                "description", "The runId of the paused run to resume (required for mode=resume)."
        ));
        properties.put("decision", Map.of(
                "type", "string",
                "enum", List.of(DECISION_APPROVED, DECISION_REJECTED),
                "description", "The approval decision (required for mode=resume): "
                        + "\"approved\" to continue, \"rejected\" to decline."
        ));
        properties.put("reason", Map.of(
                "type", "string",
                "description", "Optional free-text reason for the resume decision (mode=resume)."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("mode"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError(
                        "input is required (mode plus the fields for that mode)");
            }
            String mode = trimToNull(input.get("mode"));
            if (mode == null) {
                return SkillResult.validationError(
                        "mode is required: one of \"name\", \"inline\", \"resume\"");
            }

            Long userId = context != null ? context.getUserId() : null;

            switch (mode) {
                case MODE_NAME:
                    return runByName(input, userId);
                case MODE_INLINE:
                    return runInline(input, userId);
                case MODE_RESUME:
                    return resume(input, userId);
                default:
                    return SkillResult.validationError(
                            "unknown mode \"" + mode + "\": must be one of \"name\", "
                                    + "\"inline\", \"resume\"");
            }
        } catch (WorkflowAlreadyRunningException e) {
            // 409 — a run for this name is already in flight.
            return SkillResult.error(e.getMessage());
        } catch (WorkflowNotFoundException | WorkflowNotPausedException
                 | WorkflowDefinitionChangedException e) {
            return SkillResult.error(e.getMessage());
        } catch (WorkflowMetaException | IllegalArgumentException e) {
            // Bad inline source / missing-or-malformed required field → LLM should retry.
            return SkillResult.validationError(e.getMessage());
        } catch (Exception e) {
            log.error("RunWorkflow execute failed", e);
            return SkillResult.error("RunWorkflow error: " + e.getMessage());
        }
    }

    private SkillResult runByName(Map<String, Object> input, Long userId) throws Exception {
        String name = trimToNull(input.get("name"));
        if (name == null) {
            return SkillResult.validationError("name is required for mode=name");
        }
        Map<String, Object> args = extractArgs(input);
        String runId = runnerService.startRun(name, args, userId);
        return started(runId, MODE_NAME);
    }

    private SkillResult runInline(Map<String, Object> input, Long userId) throws Exception {
        String script = trimToNull(input.get("script"));
        if (script == null) {
            return SkillResult.validationError("script is required for mode=inline");
        }
        Map<String, Object> args = extractArgs(input);
        WorkflowDefinition def = registry.parseInline(script);
        String runId = runnerService.startRun(def, args, userId);
        return started(runId, MODE_INLINE);
    }

    private SkillResult resume(Map<String, Object> input, Long userId) throws Exception {
        String resumeRunId = trimToNull(input.get("resumeRunId"));
        if (resumeRunId == null) {
            return SkillResult.validationError("resumeRunId is required for mode=resume");
        }
        String decision = trimToNull(input.get("decision"));
        if (decision == null) {
            return SkillResult.validationError(
                    "decision is required for mode=resume: \"approved\" or \"rejected\"");
        }
        if (!DECISION_APPROVED.equals(decision) && !DECISION_REJECTED.equals(decision)) {
            return SkillResult.validationError(
                    "decision must be \"approved\" or \"rejected\" (got: " + decision + ")");
        }
        boolean approved = DECISION_APPROVED.equals(decision);
        String reason = trimToNull(input.get("reason"));
        // reviewerId attributes the decision to the calling agent's user. Single-tenant
        // dogfood — no role gating; the marker just records "an agent approved this".
        String reviewerId = "agent:" + (userId == null ? "unknown" : userId);

        runnerService.resume(resumeRunId, approved, reason, reviewerId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runId", resumeRunId);
        response.put("mode", MODE_RESUME);
        response.put("status", "resumed");
        response.put("decision", decision);
        return SkillResult.success(objectMapper.writeValueAsString(response));
    }

    private SkillResult started(String runId, String mode) throws Exception {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runId", runId);
        response.put("mode", mode);
        response.put("status", "started");
        // Self-documenting next-step guidance: the previous message ("subscribe to
        // WebSocket events") is not actionable for an LLM caller and led the evolve
        // orchestrator to re-call RunWorkflow repeatedly (each rejected as "already
        // running"), burning its loop budget. Tell the caller exactly what to do next.
        response.put("message", "Workflow run started asynchronously (runId=" + runId
                + "). It is now RUNNING — do NOT call RunWorkflow again for the same "
                + "workflow (a duplicate is rejected as 'already running' and wastes turns). "
                + "To obtain the result, poll the run by this runId with the matching read "
                + "tool (e.g. GetOptReport(reportId=" + runId + ") when this is an opt-report), "
                + "retrying that read tool until the run is completed.");
        response.put("nextAction", "poll_run_by_runId");
        return SkillResult.success(objectMapper.writeValueAsString(response));
    }

    /**
     * Extracts the optional {@code args} object. Accepts a Map (preferred) or a
     * JSON object string; anything else is rejected so a malformed args value
     * surfaces as a clean validation error rather than a confusing downstream
     * failure.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractArgs(Map<String, Object> input) throws Exception {
        Object raw = input.get("args");
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (raw instanceof String s) {
            if (s.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(s, Map.class);
        }
        throw new IllegalArgumentException(
                "args must be an object (got " + raw.getClass().getSimpleName() + ")");
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
