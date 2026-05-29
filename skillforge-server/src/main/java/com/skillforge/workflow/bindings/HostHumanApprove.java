package com.skillforge.workflow.bindings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.workflow.WorkflowContext;
import com.skillforge.workflow.exception.WorkflowPausedException;
import com.skillforge.workflow.journal.JournalCache;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

/**
 * The {@code humanApprove(payload)} host binding (AUTOEVOLVING V1 Sprint 2,
 * dsl-syntax §3.6). Parks the workflow run on an approval gate.
 *
 * <p><b>Chunk 1 (this class): the first-pause path only.</b> When the run first
 * reaches the gate it:
 * <ol>
 *   <li>allocates a deterministic {@code stepIndex} (consumes the same
 *       {@code nextStepIndex} counter as {@code agent()} so journal-replay
 *       indices stay aligned — plan §2.2.4);</li>
 *   <li>persists a {@code pending} {@code human_approve} step carrying the
 *       payload + stepIndex;</li>
 *   <li>transitions the run {@code running → paused};</li>
 *   <li>broadcasts {@code workflow_human_approve_required};</li>
 *   <li>throws {@link WorkflowPausedException} to unwind the Rhino thread (all
 *       state is in the DB).</li>
 * </ol>
 *
 * <p><b>Chunk 2 seam:</b> the resume short-circuit — {@code if (ctx.isResuming()
 * && stepIndex <= ctx.getResumeFrontierIndex()) return cachedDecision(...)} —
 * lands in chunk 2 via {@code JournalCache.getApproveDecision} (marked below).
 */
public final class HostHumanApprove extends BaseFunction {

    private final transient WorkflowContext ctx;

    public HostHumanApprove(WorkflowContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String getFunctionName() {
        return "humanApprove";
    }

    @Override
    public int getArity() {
        return 1;
    }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        FlywheelRunService runService = ctx.getFlywheelRunService();
        if (runService == null) {
            throw Context.reportRuntimeError(
                    "humanApprove() is not available in this run context (no FlywheelRunService bound)");
        }
        Object payload = (args.length == 0) ? null : JsConversions.jsToJava(args[0]);

        int stepIndex = ctx.nextStepIndex();

        // ── Journal-replay resume short-circuit (Task F, plan §2.6) ──
        // On resume, every gate at/before the frontier returns its recorded
        // decision instead of pausing again. The frontier gate itself
        // (stepIndex == frontier) is the one the approve REST call just decided —
        // reaching it flips replayComplete=true so live phase()/log() resume
        // broadcasting. Earlier gates (stepIndex < frontier) were decided on a
        // prior resume; they hit the cache without flipping the flag.
        if (ctx.isResuming() && stepIndex <= ctx.getResumeFrontierIndex()) {
            JournalCache cache = ctx.getJournalCache();
            if (cache == null) {
                throw new IllegalStateException(
                        "journal-replay requires a JournalCache, but none is bound (runId="
                                + ctx.getRunId() + ")");
            }
            JsonNode decision = cache.getApproveDecision(ctx.getRunId(), stepIndex)
                    .orElseThrow(() -> new IllegalStateException(
                            "journal-replay: no recorded decision for human_approve stepIndex="
                                    + stepIndex + " (runId=" + ctx.getRunId() + ")"));
            if (stepIndex == ctx.getResumeFrontierIndex()) {
                ctx.setReplayComplete(true);
            }
            ObjectMapper om = ctx.getObjectMapper();
            if (om == null) {
                // r1 code-W1: never passthrough the raw JsonNode to the JS scope.
                // A real resume always binds an ObjectMapper (WorkflowRunnerService);
                // a null one means a misconfigured context. Returning the JsonNode
                // would expose Jackson internals to the L1 sandbox's ClassShutter.
                throw new IllegalStateException(
                        "journal-replay humanApprove requires an ObjectMapper to convert the "
                                + "recorded decision, but none is bound (runId=" + ctx.getRunId() + ")");
            }
            Object decisionJava = om.convertValue(decision, Object.class);
            return JsConversions.toJs(cx, scope, decisionJava);
        }

        // First-pause path.
        String runId = ctx.getRunId();
        String stepInputJson = buildStepInput(payload, stepIndex);
        String stepRunId = runService.appendStep(
                runId, stepInputJson, FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE, stepIndex);
        runService.pauseRun(runId, "human_approve_required");

        if (ctx.getBroadcaster() != null) {
            ctx.getBroadcaster().humanApproveRequired(runId, stepRunId, stepIndex, payload);
        }

        throw new WorkflowPausedException(runId, stepRunId, stepIndex);
    }

    private String buildStepInput(Object payload, int stepIndex) {
        ObjectMapper om = ctx.getObjectMapper();
        if (om == null) {
            // Defensive: still persist a minimal payload-free input.
            return "{\"stepKind\":\"" + FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE
                    + "\",\"stepIndex\":" + stepIndex + "}";
        }
        try {
            ObjectNode node = om.createObjectNode();
            node.put("stepKind", FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE);
            node.put("stepIndex", stepIndex);
            node.set("payload", om.valueToTree(payload));
            return om.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"stepKind\":\"" + FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE
                    + "\",\"stepIndex\":" + stepIndex + "}";
        }
    }
}
